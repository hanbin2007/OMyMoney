package app.omymoney.data

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import app.omymoney.core.ledger.LedgerRepository
import app.omymoney.core.ledger.LedgerMutationException
import app.omymoney.core.ledger.PostingCalculator
import app.omymoney.core.ledger.LinkedBillRules
import app.omymoney.core.ledger.DebtRules
import app.omymoney.core.model.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException

class RoomLedgerRepository(private val database: LedgerDatabase, private val clock: Clock = Clock.systemUTC()) : LedgerRepository {
    private val dao = database.ledgerDao()

    private data class PendingSave(val draft: TransactionDraft, val previous: Bill?, val bill: Bill,
        val postings: List<Posting>, val fingerprint: String)
    private data class PreparedSave(val pending: List<PendingSave>, val results: List<SaveResult.Saved>, val balances: Map<String, Long>)

    // Single edits and imports share one prospective-graph and net-posting transaction boundary.
    internal suspend fun saveAll(requests: List<Pair<TransactionDraft, Long?>>): List<SaveResult.Saved> = database.withTransaction {
        val prepared = prepareSaves(requests)
        applySaves(prepared)
        prepared.results
    }

    private suspend fun prepareSaves(requests: List<Pair<TransactionDraft, Long?>>): PreparedSave {
        require(requests.size in 1..10_000)
        require(requests.map { it.first.submissionId }.distinct().size == requests.size)
        val ids = requests.mapNotNull { it.first.id }
        require(ids.distinct().size == ids.size)
        val pending = mutableListOf<PendingSave>()
        val results = requests.map { (draft, expectedRevision) ->
            val postings = PostingCalculator.calculate(draft)
            val fingerprint = LedgerJson.requestHash(draft, expectedRevision)
            val submission = dao.submission(draft.submissionId)
            if (submission != null) {
                return@map if (submission.requestHash == fingerprint) {
                    SaveResult.Saved(submission.billId, submission.revision)
                } else throw SaveRejected(SaveResult.Invalid(listOf(FieldError("submissionId", "reused_submission", "Submission ID was already used for different content"))))
            }
            val previous = draft.id?.let { dao.bill(it) }
            if (previous?.deletedAt != null || previous?.revision != expectedRevision) {
                throw SaveRejected(SaveResult.Conflict(previous?.revision))
            }
            val previousBill = previous?.let(LedgerJson::decodeBill)
            val billId = draft.id ?: UUID.randomUUID().toString()
            val revision = Math.addExact(previous?.revision ?: 0, 1)
            val bill = draft.recorded(billId, revision, clock.instant())
            pending += PendingSave(draft.copy(id = billId), previousBill, bill, postings, fingerprint)
            SaveResult.Saved(billId, revision)
        }
        val changed = pending.map { it.bill.id }.toSet()
        val proposed = activeBills().filterNot { it.id in changed } + pending.map { it.bill }
        pending.forEach { validateReferences(it.draft, it.postings, it.previous, proposed) }
        val oldPostings = pending.flatMap { row -> row.previous?.let { dao.postings(it.id).map(PostingEntity::model) }.orEmpty() }
        return PreparedSave(pending, results, replacementBalances(oldPostings, pending.flatMap { it.postings }))
    }

    private suspend fun applySaves(prepared: PreparedSave) {
        prepared.balances.forEach { (id, balance) -> dao.updateBalance(id, balance) }
        prepared.pending.forEach { row ->
            dao.upsertBill(row.bill.entity())
            dao.removePostings(row.bill.id)
            dao.insertPostings(row.postings.mapIndexed { position, posting -> posting.entity(row.bill.id, position) })
            dao.insertSubmission(SubmissionEntity(row.draft.submissionId, row.bill.id, row.bill.revision, row.fingerprint))
        }
    }

    override suspend fun save(draft: TransactionDraft, expectedRevision: Long?): SaveResult = try {
        database.withTransaction {
            // Expected rejections happen before writes and must not roll back the caller's failure record.
            val prepared = try {
                prepareSaves(listOf(draft to expectedRevision))
            } catch (error: SaveRejected) {
                return@withTransaction error.result
            } catch (error: DraftValidationException) {
                return@withTransaction SaveResult.Invalid(error.errors)
            } catch (_: ArithmeticException) {
                return@withTransaction SaveResult.Invalid(listOf(FieldError("amount", "overflow", "Value exceeds supported range")))
            }
            applySaves(prepared)
            prepared.results.single()
        }
    } catch (error: SQLiteException) {
        // A failed write poisons an enclosing Room transaction; never return a fictitious committed state.
        if (database.inTransaction()) throw error
        SaveResult.Failure("storage")
    } catch (error: SerializationException) {
        if (database.inTransaction()) throw error
        SaveResult.Failure("corrupt_data")
    }

    override fun observeBills(query: BillQuery): Flow<List<Bill>> = dao.observeBills(
        query.bookId, query.accountId, query.kind?.name, query.categoryId, query.search.trim(),
    ).map { records ->
        records.map(LedgerJson::decodeBill).filter { bill ->
            (query.startInclusive == null || !bill.occurredAt.isBefore(query.startInclusive)) &&
                (query.endExclusive == null || bill.occurredAt.isBefore(query.endExclusive)) &&
                (query.tag == null || query.tag in bill.tags)
        }
    }

    override fun observeBill(id: String): Flow<Bill?> = dao.observeBill(id).map { it?.let(LedgerJson::decodeBill) }

    internal suspend fun validateNewDraft(draft: TransactionDraft, checkBalance: Boolean = false) {
        val postings = PostingCalculator.calculate(draft)
        validateReferences(draft, postings, null)
        if (checkBalance) replacementBalances(emptyList(), postings)
    }

    override suspend fun delete(billId: String): UndoToken = deleteAll(mapOf(billId to null)).single()

    internal suspend fun deleteAll(expected: Map<String, Long?>): List<UndoToken> = database.withTransaction {
        require(expected.size in 1..10_000)
        val records = expected.map { (id, revision) ->
            val entity = dao.bill(id) ?: throw LedgerMutationException("not_found")
            if (revision != null && (entity.revision != revision || entity.deletedAt != null)) throw LedgerMutationException("conflict")
            entity
        }
        if (activeBills().any { it.id !in expected && it.linkedBillId in expected }) throw LedgerMutationException("linked_records")
        val now = clock.instant()
        replaceBalanceEffect(records.filter { it.deletedAt == null }.flatMap { dao.postings(it.id).map(PostingEntity::model) }, emptyList())
        records.map { entity ->
            if (entity.deletedAt != null) return@map dao.unusedDeletionFor(entity.id)?.token() ?: throw LedgerMutationException("already_deleted")
            val bill = LedgerJson.decodeBill(entity)
            val revision = Math.addExact(bill.revision, 1)
            dao.upsertBill(bill.copy(revision = revision).entity(deletedAt = now))
            val deletion = DeletionEntity(UUID.randomUUID().toString(), bill.id, revision, now.plus(Duration.ofDays(1)).toString())
            dao.upsertDeletion(deletion)
            deletion.token()
        }
    }

    override suspend fun undo(token: UndoToken): Boolean = undoAll(listOf(token))

    internal suspend fun undoAll(tokens: List<UndoToken>): Boolean = database.withTransaction {
        if (tokens.isEmpty() || tokens.size > 10_000 || tokens.map { it.billId }.distinct().size != tokens.size) return@withTransaction false
        val records = tokens.map { token ->
            val deletion = dao.deletion(token.id) ?: return@withTransaction false
            if (deletion.used || deletion.token() != token || !clock.instant().isBefore(token.expiresAt)) return@withTransaction false
            val entity = dao.bill(deletion.billId) ?: return@withTransaction false
            if (entity.deletedAt == null || entity.revision != deletion.deletedRevision) return@withTransaction false
            LedgerJson.decodeBill(entity) to deletion
        }
        val proposed = activeBills() + records.map { it.first }
        if (records.any { LinkedBillRules.validate(it.first.toDraft(), proposed).isNotEmpty() }) return@withTransaction false
        replaceBalanceEffect(emptyList(), records.flatMap { dao.postings(it.first.id).map(PostingEntity::model) })
        records.forEach { (bill, deletion) ->
            dao.upsertBill(bill.copy(revision = Math.addExact(bill.revision, 1)).entity())
            dao.upsertDeletion(deletion.copy(used = true))
        }
        true
    }

    private suspend fun validateReferences(draft: TransactionDraft, postings: List<Posting>, previous: Bill?, related: List<Bill>? = null) {
        val errors = mutableListOf<FieldError>()
        val book = dao.book(draft.bookId)
        if (book == null || (book.archived && previous?.bookId != book.id)) {
            errors += FieldError("bookId", "unavailable_book", "Book is missing or archived")
        }
        val oldAccounts = setOfNotNull(previous?.fromAccountId, previous?.toAccountId)
        for (posting in postings) {
            val account = dao.account(posting.accountId)
            val field = if (posting.accountId == draft.fromAccountId) "fromAccountId" else "toAccountId"
            if (account == null || (account.archived && account.id !in oldAccounts)) {
                errors += FieldError(field, "unavailable_account", "Account is missing or archived")
            } else if (account.currencyCode != posting.amount.currency.code || account.currencyScale != posting.amount.currency.scale) {
                errors += FieldError(field, "currency_mismatch", "Amount and account currencies differ")
            }
        }
        draft.categoryId?.let { id ->
            val category = dao.category(id)
            if (category == null || category.bookId != draft.bookId || category.kind != PostingCalculator.categoryKind(draft.kind)?.name ||
                (category.archived && previous?.categoryId != id)) {
                errors += FieldError("categoryId", "unavailable_category", "Category does not belong to this book and transaction kind")
            }
        }
        if (draft.kind == BillKind.EXPENSE && draft.toAccountId != null) {
            errors += FieldError("toAccountId", "unexpected_account", "Expense cannot have a receiving account")
        }
        if (draft.kind == BillKind.INCOME && draft.fromAccountId != null) {
            errors += FieldError("fromAccountId", "unexpected_account", "Income cannot have a paying account")
        }
        errors += LinkedBillRules.validate(draft, related ?: activeBills())
        errors += DebtRules.validateAccounts(draft, dao.accounts().map(AccountEntity::model))
        if (errors.isNotEmpty()) throw DraftValidationException(errors)
    }

    private suspend fun activeBills() = dao.allBills().filter { it.deletedAt == null }.map(LedgerJson::decodeBill)

    // Apply the net delta, not an intermediate reversal that could overflow a valid final balance.
    private suspend fun replaceBalanceEffect(previous: List<Posting>, next: List<Posting>) {
        replacementBalances(previous, next).forEach { (id, balance) -> dao.updateBalance(id, balance) }
    }

    private suspend fun replacementBalances(previous: List<Posting>, next: List<Posting>): Map<String, Long> {
        val deltas = mutableMapOf<String, BigInteger>()
        previous.forEach { posting ->
            deltas[posting.accountId] = (deltas[posting.accountId] ?: BigInteger.ZERO) - BigInteger.valueOf(posting.amount.minor)
        }
        next.forEach { posting ->
            deltas[posting.accountId] = (deltas[posting.accountId] ?: BigInteger.ZERO) + BigInteger.valueOf(posting.amount.minor)
        }
        return deltas.mapValues { (accountId, delta) ->
            val account = dao.account(accountId) ?: throw LedgerMutationException("account_missing")
            BigDecimal(BigInteger.valueOf(account.balanceMinor) + delta).longValueExact()
        }
    }
}

internal class SaveRejected(val result: SaveResult) : Exception(result.toString())
