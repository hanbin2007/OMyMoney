package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.ledger.DebtRules
import app.omymoney.core.model.*
import app.omymoney.data.backup.BackupArchive
import app.omymoney.data.backup.RestoreMode
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AssetTransactionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: LedgerDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var catalog: CatalogRepository
    private val book = UUID.randomUUID().toString()
    private val cash = UUID.randomUUID().toString()
    private val debt = UUID.randomUUID().toString()
    private val credit = UUID.randomUUID().toString()
    private val cny = CurrencySpec.CNY
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        ledger = RoomLedgerRepository(db); catalog = CatalogRepository(db)
        assertTrue(catalog.saveBook(Book(book, "Assets")) is CatalogResult.Saved)
        assertTrue(catalog.saveAccount(AssetAccount(cash, "Cash")) is CatalogResult.Saved)
        assertTrue(catalog.saveAccount(AssetAccount(debt, "Debt", kind = AccountKind.LIABILITY, group = "Loans")) is CatalogResult.Saved)
        assertTrue(catalog.saveAccount(AssetAccount(credit, "Card", kind = AccountKind.CREDIT,
            creditTerms = CreditTerms(31, CreditDueMode.DAYS_AFTER_STATEMENT, 20, Money(100000, cny)))) is CatalogResult.Saved)
    }
    @After fun cleanup() { db.close() }
    private fun draft(kind: BillKind, minor: Long, from: String?, to: String?, source: String? = null) = TransactionDraft(
        book, kind = kind, amount = Money(minor, cny), fromAccountId = from, toAccountId = to, linkedBillId = source,
        occurredAt = Instant.parse("2026-02-28T10:00:00Z"), timeSource = TimeSource.USER,
    )
    private suspend fun save(draft: TransactionDraft, revision: Long? = null): Bill {
        val result = ledger.save(draft, revision)
        assertTrue("Expected Saved: $result", result is SaveResult.Saved)
        return ledger.observeBill((result as SaveResult.Saved).billId).first()!!
    }

    @Test fun debtPrincipalInterestAndCreditPaymentSurviveEditArchiveUndoAndBackup() = runBlocking {
        val borrow = save(draft(BillKind.DEBT_BORROW, 10000, debt, cash))
        val repay = save(draft(BillKind.DEBT_REPAY, 4000, cash, debt, borrow.id))
        save(draft(BillKind.INTEREST_EXPENSE, 200, cash, null, borrow.id))
        assertEquals(5800L, db.ledgerDao().account(cash)!!.balanceMinor)
        assertEquals(-6000L, db.ledgerDao().account(debt)!!.balanceMinor)
        assertEquals(6000L, DebtRules.position(debt, ledger.observeBills(BillQuery()).first()).principal)
        assertTrue(ledger.save(draft(BillKind.DEBT_REPAY, 7000, cash, debt, borrow.id), null) is SaveResult.Invalid)
        assertTrue(catalog.archiveAccount(debt) is CatalogResult.Saved)
        val edited = save(repay.toDraft().copy(amount = Money(3000, cny)), repay.revision)
        assertEquals(-7000L, db.ledgerDao().account(debt)!!.balanceMinor)
        val token = ledger.delete(edited.id); assertTrue(ledger.undo(token))
        assertTrue(catalog.restoreAccount(debt) is CatalogResult.Saved)
        save(draft(BillKind.EXPENSE, 1000, credit, null))
        save(draft(BillKind.CREDIT_PAYMENT, 1000, cash, credit))
        assertEquals(0L, db.ledgerDao().account(credit)!!.balanceMinor)
        assertEquals(1, ledger.observeBills(BillQuery(kind = BillKind.EXPENSE)).first().size)
        assertTrue(catalog.reorderAccounts(listOf(debt, credit, cash)) is CatalogResult.Saved)
        val archive = BackupArchive(db, context)
        val bytes = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
        archive.restore(archive.inspect(bytes.inputStream()).previewId, RestoreMode.REPLACE)
        assertEquals(listOf(debt, credit, cash), catalog.observeAccounts().first().map { it.id })
        assertEquals("Loans", catalog.observeAccounts().first().first { it.id == debt }.group)
        assertEquals(CreditTerms(31, CreditDueMode.DAYS_AFTER_STATEMENT, 20, Money(100000, cny)),
            catalog.observeAccounts().first().first { it.id == credit }.creditTerms)
    }

    @Test fun wrongAccountRolesKindChangesAndFailedPostingAreRejectedAtomically() = runBlocking {
        assertTrue(ledger.save(draft(BillKind.DEBT_BORROW, 10000, cash, credit), null) is SaveResult.Invalid)
        assertTrue(ledger.save(draft(BillKind.CREDIT_PAYMENT, 10000, credit, cash), null) is SaveResult.Invalid)
        val borrow = save(draft(BillKind.DEBT_BORROW, 10000, debt, cash))
        val original = catalog.observeAccounts(true).first().first { it.id == debt }
        assertTrue(catalog.saveAccount(original.copy(kind = AccountKind.CASH)) is CatalogResult.Invalid)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_debt BEFORE INSERT ON postings BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertEquals(SaveResult.Failure("storage"), ledger.save(draft(BillKind.DEBT_REPAY, 1000, cash, debt, borrow.id), null))
        assertEquals(10000L, db.ledgerDao().account(cash)!!.balanceMinor)
        assertEquals(-10000L, db.ledgerDao().account(debt)!!.balanceMinor)
        assertEquals(1, db.ledgerDao().billCount())
    }

    @Test fun groupRenameAndOrderingAreAtomicAndPreservedByBackup() = runBlocking {
        assertTrue(catalog.reorderAccountGroups(listOf("Loans", null)) is CatalogResult.Saved)
        assertEquals(listOf(debt, cash, credit), catalog.observeAccounts().first().map { it.id })
        assertEquals(cash, catalog.initialDraft(book).fromAccountId)
        assertTrue(catalog.renameAccountGroup("Loans", "Long term") is CatalogResult.Saved)
        assertEquals("Long term", catalog.observeAccounts().first().first().group)
        assertTrue(catalog.reorderAccountGroups(listOf("missing", null)) is CatalogResult.Invalid)
        assertEquals(listOf(debt, cash, credit), catalog.observeAccounts().first().map { it.id })
        val archive = BackupArchive(db, context)
        val bytes = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
        archive.restore(archive.inspect(bytes.inputStream()).previewId, RestoreMode.REPLACE)
        assertEquals("Long term", catalog.observeAccounts().first().first().group)
        assertEquals(listOf(debt, cash, credit), catalog.observeAccounts().first().map { it.id })
    }

    @Test fun separateRepaymentsCannotMergeBeyondPrincipalAndCreditSettingsConflictsAreExplicit() = runBlocking {
        val borrow = save(draft(BillKind.DEBT_BORROW, 10000, debt, cash))
        val archive = BackupArchive(db, context)
        suspend fun export() = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
        val base = export()
        save(draft(BillKind.DEBT_REPAY, 6000, cash, debt, borrow.id))
        val incoming = export()
        archive.restore(archive.inspect(base.inputStream()).previewId, RestoreMode.REPLACE)
        save(draft(BillKind.DEBT_REPAY, 6000, cash, debt, borrow.id))
        val preview = archive.inspect(incoming.inputStream())
        assertTrue(preview.conflicts.any { it.kind == app.omymoney.data.backup.ConflictKind.LINKED_RULE })
        assertTrue(runCatching { archive.restore(preview.previewId, RestoreMode.MERGE) }.isFailure)
        archive.restore(preview.previewId, RestoreMode.REPLACE)
        assertEquals(-4000L, db.ledgerDao().account(debt)!!.balanceMinor)
        val card = catalog.observeAccounts().first().single { it.id == credit }
        assertTrue(catalog.saveAccount(card.copy(creditTerms = card.creditTerms!!.copy(statementDay = 25))) is CatalogResult.Saved)
        assertTrue(archive.inspect(incoming.inputStream()).conflicts.any { it.kind == app.omymoney.data.backup.ConflictKind.ACCOUNT })
    }
}
