package app.omymoney.data.backup

import app.omymoney.core.ledger.PostingCalculator
import app.omymoney.core.ledger.LinkedBillRules
import app.omymoney.core.ledger.DebtRules
import app.omymoney.core.model.*
import app.omymoney.core.budget.BudgetCalculator
import app.omymoney.core.budget.BudgetPeriod
import app.omymoney.core.schedule.ScheduleCalculator
import app.omymoney.data.*
import app.omymoney.data.exchange.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
internal data class LedgerSnapshot(
    val books: List<BookEntity>, val accounts: List<AccountEntity>, val categories: List<CategoryEntity>,
    val bills: List<BillEntity>, val postings: List<PostingEntity>, val submissions: List<SubmissionEntity>,
    val deletions: List<DeletionEntity>, val preferences: List<PreferenceEntity>, val drafts: List<DraftEntity>,
    val queue: List<QueueEntity> = emptyList(),
    val exchangeRates: List<ExchangeRateEntity> = emptyList(),
    val budgets: List<BudgetEntity> = emptyList(),
    val schedules: List<ScheduleEntity> = emptyList(),
    val scheduleExecutions: List<ScheduleExecutionEntity> = emptyList(),
    val savingsPlans: List<SavingsPlanEntity> = emptyList(),
    val savingsDeposits: List<SavingsDepositEntity> = emptyList(),
    val importReceipts: List<ImportReceiptEntity> = emptyList(),
    val batchActions: List<BatchActionEntity> = emptyList(),
) {
    fun normalized() = copy(books = books.sortedBy { it.id }, accounts = accounts.sortedBy { it.id },
        categories = categories.sortedBy { it.id }, bills = bills.sortedBy { it.id },
        postings = postings.sortedWith(compareBy({ it.billId }, { it.position })), submissions = submissions.sortedBy { it.id },
        deletions = deletions.sortedBy { it.id }, preferences = preferences.sortedBy { it.key }, drafts = drafts.sortedBy { it.key }, queue = queue.sortedBy { it.id },
        exchangeRates = exchangeRates.sortedWith(compareBy({ it.sourceCode }, { it.sourceScale }, { it.targetCode }, { it.targetScale })),
        budgets = budgets.sortedWith(compareBy({ it.bookId }, { it.periodKey })),
        schedules = schedules.sortedBy { it.id }, scheduleExecutions = scheduleExecutions.sortedBy { it.id },
        savingsPlans = savingsPlans.sortedBy { it.id }, savingsDeposits = savingsDeposits.sortedBy { it.id },
        importReceipts = importReceipts.sortedBy { it.id }, batchActions = batchActions.sortedBy { it.id })
    fun refs(): List<AttachmentRef> = (bills.flatMap { LedgerJson.decodeBill(it).attachments } + drafts.flatMap {
        LedgerJson.format.decodeFromString<EditorDraft>(it.payloadJson).transaction.attachments
    } + queue.flatMap { it.model().let { entry -> entry.draft.attachments + entry.result.draft().attachments } } +
        schedules.flatMap { it.model().template.attachments } + scheduleExecutions.flatMap { it.model().template.attachments } +
        batchActions.flatMap { it.model().before.flatMap(Bill::attachments) }).distinct()
    fun summary() = BackupSummary(books.size, accounts.size, bills.count { it.deletedAt == null }, refs().size,
        schedules.size, schedules.count { it.model().automatic }, scheduleExecutions.count { it.state == ScheduleExecutionState.PENDING.name },
        savingsPlans.size, savingsDeposits.size, importReceipts.size, batchActions.size)
}

internal object RestoreValidator {
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val entityId = Regex(uuid)
    val attachmentPath = Regex("attachments/$uuid\\.(png|jpg|webp|gif)")
    fun check(valid: Boolean, code: String = "invalid_data") { if (!valid) throw BackupException(code) }
    private fun <T> unique(rows: List<T>, key: (T) -> Any) {
        val ids = rows.map(key)
        check(ids.size == ids.toSet().size, "duplicate_id")
        check(ids.all { it.toString().isNotBlank() && it.toString().length <= 512 })
    }
    fun validate(data: LedgerSnapshot) {
        unique(data.books) { it.id }; unique(data.accounts) { it.id }; unique(data.categories) { it.id }
        unique(data.bills) { it.id }; unique(data.postings) { it.billId to it.position }
        unique(data.submissions) { it.id }; unique(data.deletions) { it.id }
        unique(data.preferences) { it.key }; unique(data.drafts) { it.key }
        unique(data.queue) { it.id }
        unique(data.exchangeRates) { listOf(it.sourceCode, it.sourceScale, it.targetCode, it.targetScale) }
        unique(data.budgets) { it.bookId to it.periodKey }
        unique(data.schedules) { it.id }; unique(data.scheduleExecutions) { it.id }
        unique(data.scheduleExecutions) { it.planId to it.plannedAt }
        unique(data.savingsPlans) { it.id }; unique(data.savingsDeposits) { it.id }
        unique(data.importReceipts) { it.id }; unique(data.batchActions) { it.id }
        unique(data.savingsDeposits.filter { it.linkedBillId != null }) { requireNotNull(it.linkedBillId) }
        data.exchangeRates.forEach {
            val rate = it.model()
            check(rate.source.code != rate.target.code && runCatching { app.omymoney.core.ledger.ExchangeRules.parseRate(rate.rate) }.isSuccess, "invalid_rate")
        }
        (data.books.map { it.id } + data.accounts.map { it.id } + data.categories.map { it.id } +
            data.bills.map { it.id } + data.submissions.map { it.id } + data.deletions.map { it.id }).forEach { check(entityId.matches(it)) }
        val books = data.books.associateBy { it.id }; val accounts = data.accounts.associateBy { it.id }
        val categories = data.categories.associateBy { it.id }; val bills = data.bills.associateBy { it.id }
        check(books.isEmpty() || books.values.any { !it.archived }, "invalid_catalog")
        val preferences = data.preferences.associate { it.key to it.value }
        preferences["current_book"]?.let { check(books[it]?.archived == false, "invalid_reference") }
        preferences["default_book"]?.let { check(it in books, "invalid_reference") }
        data.books.forEach { it.model(); check(it.name.isNotBlank() && it.name.length <= 256) }
        data.accounts.forEach { row ->
            val account = row.model()
            check(row.name.isNotBlank() && row.name.length <= 256)
            account.group?.let { check(it.isNotBlank() && it.length <= 80 && !it.any(Char::isISOControl)) }
            account.creditTerms?.let { check(account.kind == AccountKind.CREDIT && CreditCycles.validate(it, account.currency), "invalid_credit_terms") }
        }
        data.categories.forEach { category ->
            category.model()
            check(category.bookId in books && category.name.isNotBlank())
            check(category.kind in listOf("EXPENSE", "INCOME"))
            category.parentId?.let { id ->
                val parent = categories[id] ?: throw BackupException("invalid_reference")
                check(parent.id != category.id && parent.parentId == null && parent.bookId == category.bookId && parent.kind == category.kind)
            }
        }
        data.budgets.forEach { row ->
            val book = books[row.bookId]?.model() ?: throw BackupException("invalid_reference")
            val period = BudgetPeriod.parse(row.periodKey)
            row.model()?.let { plan ->
                check(plan.bookId == book.id && plan.period == period && plan.total.currency == book.currency, "invalid_budget")
                check(BudgetCalculator.validate(plan, data.categories.map(CategoryEntity::model)).isEmpty(), "invalid_budget")
            }
        }
        val postingsByBill = data.postings.groupBy { it.billId }
        data.bills.forEach { row ->
            val bill = LedgerJson.decodeBill(row)
            check(row == bill.entity(row.deletedAt?.let(Instant::parse)))
            check(bill.revision > 0 && bill.bookId in books && entityId.matches(bill.submissionId))
            check(bill.fromAccountId == null || bill.fromAccountId in accounts)
            check(bill.toAccountId == null || bill.toAccountId in accounts)
            check(bill.kind != BillKind.EXPENSE || bill.toAccountId == null)
            check(bill.kind != BillKind.INCOME || bill.fromAccountId == null)
            val expected = PostingCalculator.calculate(bill.toDraft())
            check(DebtRules.validateAccounts(bill.toDraft(), accounts.values.map(AccountEntity::model)).isEmpty(), "invalid_account_role")
            check(postingsByBill[row.id].orEmpty().sortedBy { it.position } == expected.mapIndexed { index, p -> p.entity(row.id, index) }, "invalid_postings")
            expected.forEach { posting ->
                val account = accounts[posting.accountId] ?: throw BackupException("invalid_reference")
                check(account.model().currency == posting.amount.currency)
            }
            bill.categoryId?.let { id -> check(categories[id]?.let { it.bookId == bill.bookId && it.kind == PostingCalculator.categoryKind(bill.kind)?.name } == true) }
            bill.linkedBillId?.let { check(it in bills && it != bill.id) }
        }
        val activeBills = data.bills.filter { it.deletedAt == null }.map(LedgerJson::decodeBill)
        val savings = data.savingsPlans.associate { it.id to it.model() }
        savings.values.forEach { plan ->
            validateSavingsPlan(plan)
            check(plan.bookId in books && plan.revision > 0, "invalid_savings")
        }
        data.savingsDeposits.forEach { row ->
            val deposit = row.model()
            val plan = savings[deposit.planId] ?: throw BackupException("invalid_reference")
            validateSavingsDeposit(deposit, plan)
            check(deposit.revision > 0 && (deposit.linkedBillId == null || deposit.linkedBillId in bills), "invalid_savings")
        }
        activeBills.forEach { check(LinkedBillRules.validate(it.toDraft(), activeBills).isEmpty(), "invalid_link") }
        data.postings.forEach { check(it.billId in bills && it.accountId in accounts) }
        recomputeBalances(data).zip(data.accounts).forEach { (calculated, recorded) -> check(calculated == recorded, "invalid_balance") }
        data.submissions.forEach { check(it.billId in bills && it.revision in 1..bills.getValue(it.billId).revision && hashPattern.matches(it.requestHash)) }
        data.deletions.forEach { check(it.billId in bills && it.deletedRevision in 1..bills.getValue(it.billId).revision); Instant.parse(it.expiresAt) }
        data.importReceipts.forEach { row ->
            val receipt = row.model()
            check(row == receipt.entity() && entityId.matches(receipt.id) && hashPattern.matches(receipt.fileHash), "invalid_import_receipt")
            check(bills[receipt.billId]?.bookId == receipt.bookId && receipt.row in 2..TableCodec.MAX_ROWS + 1, "invalid_import_receipt")
            check(receipt.id == BillImportService.receiptId(receipt.bookId, receipt.fileHash, receipt.row), "invalid_import_receipt")
            check(receipt.columns.size in 1..TableCodec.MAX_COLUMNS && receipt.columns.distinct().size == receipt.columns.size &&
                receipt.columns.all { it.isNotBlank() && it.length <= 256 } && receipt.raw.size == receipt.columns.size &&
                receipt.raw.all { it.length <= TableCodec.MAX_CELL }, "invalid_import_receipt")
        }
        data.batchActions.forEach { row ->
            val action = row.model()
            check(row == action.entity() && entityId.matches(action.id) && action.bookId in books, "invalid_batch_action")
            check(action.before.size in 1..10_000 && action.before.map { it.id }.distinct().size == action.before.size &&
                action.afterRevisions.keys == action.before.map { it.id }.toSet(), "invalid_batch_action")
            action.before.forEach { before ->
                val current = bills[before.id] ?: throw BackupException("invalid_batch_action")
                val after = action.afterRevisions.getValue(before.id)
                check(before.bookId == action.bookId && current.bookId == action.bookId && before.revision > 0 &&
                    after == Math.addExact(before.revision, 1) && after <= current.revision, "invalid_batch_action")
                check(!action.used || current.revision > after, "invalid_batch_action")
                if (current.revision == after) {
                    val original = before.toDraft(before.submissionId)
                    val currentDraft = LedgerJson.decodeBill(current).toDraft(before.submissionId)
                    val comparable = if (action.deletions.isNotEmpty()) currentDraft else currentDraft.copy(
                        note = before.note, tags = before.tags, categoryId = before.categoryId,
                        excludeStats = before.excludeStats, excludeBudget = before.excludeBudget)
                    check(comparable == original, "invalid_batch_action")
                }
                check(entityId.matches(before.submissionId), "invalid_batch_action")
                PostingCalculator.calculate(before.toDraft()).forEach { posting ->
                    check(accounts[posting.accountId]?.model()?.currency == posting.amount.currency, "invalid_batch_action")
                }
                check(DebtRules.validateAccounts(before.toDraft(), accounts.values.map(AccountEntity::model)).isEmpty(), "invalid_batch_action")
                before.categoryId?.let { id -> check(categories[id]?.let { it.bookId == before.bookId && it.kind == PostingCalculator.categoryKind(before.kind)?.name } == true, "invalid_batch_action") }
                before.linkedBillId?.let { check(it in bills && it != before.id, "invalid_batch_action") }
            }
            if (action.deletions.isNotEmpty()) {
                check(action.deletions.size == action.before.size && action.deletions.map { it.billId }.toSet() == action.afterRevisions.keys, "invalid_batch_action")
                action.deletions.forEach { token ->
                    val deletion = data.deletions.firstOrNull { it.id == token.id }
                    check(deletion?.token() == token && deletion?.deletedRevision == action.afterRevisions[token.billId], "invalid_batch_action")
                }
            }
        }
        data.drafts.forEach {
            val draft = LedgerJson.format.decodeFromString<EditorDraft>(it.payloadJson)
            check(it.payloadJson.toByteArray().size <= 1_048_576 && draft.key == it.key && draft.transaction.bookId == it.bookId && it.bookId in books)
            with(draft.transaction) {
                listOfNotNull(id, submissionId, fromAccountId, toAccountId, categoryId, linkedBillId, exchange?.accountId).forEach { id -> check(entityId.matches(id)) }
            }
        }
        val schedules = data.schedules.associate { it.id to it.model() }
        val executions = data.scheduleExecutions.map(ScheduleExecutionEntity::model)
        val executionsByPlan = executions.groupBy { it.planId }
        val draftsByKey = data.drafts.associateBy { it.key }
        schedules.values.forEach { plan ->
            validateSchedule(plan)
            val draft = plan.template
            check(plan.revision > 0 && draft.bookId in books && draft.id == null && draft.submissionId == plan.id &&
                draft.source == BillSource.SCHEDULE && draft.zoneId == plan.rule.zone && draft.occurredAt == null && draft.timeSource == TimeSource.UNKNOWN, "invalid_schedule")
            val scheduled = scheduledDraft(plan, requireNotNull(ScheduleCalculator.occurrence(plan.rule, 0)))
            PostingCalculator.calculate(scheduled).forEach { posting ->
                check(accounts[posting.accountId]?.model()?.currency == posting.amount.currency, "invalid_reference")
            }
            draft.categoryId?.let { id -> check(categories[id]?.let { it.bookId == draft.bookId && it.kind == PostingCalculator.categoryKind(draft.kind)?.name } == true) }
            draft.linkedBillId?.let { check(it in bills, "invalid_reference") }
            val rows = executionsByPlan[plan.id].orEmpty().sortedBy { it.index }
            check(rows.size == plan.nextIndex && rows.withIndex().all { it.index == it.value.index }, "invalid_schedule_cursor")
        }
        executions.forEach { execution ->
            val plan = schedules[execution.planId] ?: throw BackupException("invalid_reference")
            val occurrence = ScheduleCalculator.occurrence(plan.rule, execution.index)
            check(occurrence?.plannedAt == execution.plannedAt && execution.id == scheduleExecutionId(plan.id, execution.plannedAt), "invalid_schedule_execution")
            val draft = execution.template
            check(draft.id == null && draft.submissionId == execution.id && draft.bookId == plan.template.bookId && draft.source == BillSource.SCHEDULE, "invalid_schedule_execution")
            listOfNotNull(draft.fromAccountId, draft.toAccountId, draft.categoryId, draft.linkedBillId, draft.exchange?.accountId).forEach { check(entityId.matches(it)) }
            val pending = draftsByKey["schedule/${execution.id}"]
            if (execution.state == ScheduleExecutionState.PENDING) {
                check(pending != null && execution.billId == null, "invalid_schedule_execution")
                val editor = LedgerJson.format.decodeFromString<EditorDraft>(requireNotNull(pending).payloadJson)
                check(editor.transaction.submissionId == execution.id && editor.transaction.bookId == draft.bookId && editor.transaction.source == BillSource.SCHEDULE, "invalid_schedule_execution")
            } else {
                check(pending == null, "invalid_schedule_execution")
                if (execution.state == ScheduleExecutionState.POSTED) {
                    val submission = data.submissions.firstOrNull { it.id == execution.id }
                    check(execution.billId in bills && submission?.billId == execution.billId &&
                        submission?.requestHash == LedgerJson.requestHash(draft, null), "invalid_schedule_execution")
                } else check(execution.billId == null, "invalid_schedule_execution")
            }
        }
        data.drafts.filter { it.key.startsWith("schedule/") }.forEach { row ->
            check(executions.any { it.id == row.key.removePrefix("schedule/") && it.state == ScheduleExecutionState.PENDING }, "invalid_schedule_execution")
        }
        check(data.queue.size <= 10000, "queue_limit")
        data.queue.forEach { row ->
            check(entityId.matches(row.id) && row.payloadJson.toByteArray().size <= RecognitionQueue.MAX_ENTRY_BYTES, "invalid_queue")
            val entry = row.model()
            RecognitionQueue.validateDraftIdentity(entry.draft)
            RecognitionQueue.validateDraftIdentity(entry.result.draft())
            check(entry.draft.bookId in books && entry.result.draft().bookId in books, "invalid_reference")
            check(entry.events.size in 2..512 && entry.events.first().state == QueueState.RECOGNIZED &&
                entry.events.first().at == entry.createdAt && entry.events.last().state == entry.state &&
                entry.events.last().at == entry.updatedAt, "invalid_queue")
            check(entry.draft.submissionId == entry.result.draft().submissionId, "invalid_queue")
            check(entry.automaticReviewReason == null || entry.automaticRevision == null, "invalid_queue")
            if (entry.state == QueueState.SAVED) {
                val submission = data.submissions.firstOrNull { it.id == entry.draft.submissionId }
                check(submission != null && entry.billId in bills && submission.billId == entry.billId &&
                    entry.savedRevision == submission.revision && submission.requestHash == LedgerJson.requestHash(entry.draft, null), "invalid_queue")
                entry.automaticRevision?.let { check(it in requireNotNull(entry.savedRevision)..bills.getValue(requireNotNull(entry.billId)).revision, "invalid_queue") }
            } else check(entry.billId == null && entry.savedRevision == null && entry.automaticRevision == null, "invalid_queue")
        }
        val refs = data.refs()
        unique(refs) { it.id }
        refs.forEach {
            val extension = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "image/gif" to "gif")[it.mimeType]
            check(extension != null && attachmentPath.matches(it.relativePath) && it.relativePath == "attachments/${it.id}.$extension" && hashPattern.matches(it.sha256), "unsafe_path")
        }
    }

    fun recomputeBalances(data: LedgerSnapshot): List<AccountEntity> {
        val balances = balanceTotals(data)
        return data.accounts.map { it.copy(balanceMinor = BigDecimal(balances.getValue(it.id)).longValueExact()) }
    }

    private fun balanceTotals(data: LedgerSnapshot): Map<String, BigInteger> {
        val active = data.bills.filter { it.deletedAt == null }.map { it.id }.toSet()
        val delta = mutableMapOf<String, BigInteger>()
        data.postings.filter { it.billId in active }.forEach { delta[it.accountId] = (delta[it.accountId] ?: BigInteger.ZERO) + BigInteger.valueOf(it.minor) }
        return data.accounts.associate { it.id to BigInteger.valueOf(it.openingMinor) + (delta[it.id] ?: BigInteger.ZERO) }
    }

    data class MergeResult(val snapshot: LedgerSnapshot?, val conflicts: List<RestoreConflict>)

    fun merge(current: LedgerSnapshot, incoming: LedgerSnapshot): MergeResult {
        val conflicts = mutableListOf<RestoreConflict>()
        fun label(row: Any?, source: LedgerSnapshot): String {
            fun billLabel(id: String): String = source.bills.firstOrNull { it.id == id }?.let { label(it, source) }.orEmpty()
            return when (row) {
                is BookEntity -> row.name
                is AccountEntity -> "${row.name} (${row.currencyCode})"
                is CategoryEntity -> "${source.books.firstOrNull { it.id == row.bookId }?.name.orEmpty()} / ${row.name}"
                is BillEntity -> LedgerJson.decodeBill(row).let { "${it.occurredAt.atZone(it.zoneId).toLocalDate()} ${it.amount.currency.code} ${it.amount.decimalText()} ${it.note.take(120)}".trim() }
                is PostingEntity -> billLabel(row.billId)
                is SubmissionEntity -> billLabel(row.billId)
                is DeletionEntity -> billLabel(row.billId)
                is DraftEntity -> LedgerJson.format.decodeFromString<EditorDraft>(row.payloadJson).let { "${it.currency.code} ${it.amountText.take(64)} ${it.transaction.note.take(120)}".trim() }
                is QueueEntity -> row.model().let { "${it.draft.amount?.decimalText().orEmpty()} ${it.draft.note.take(120)}".trim() }
                is ExchangeRateEntity -> "${row.sourceCode} / ${row.targetCode}: ${row.rate} (${row.asOf})"
                is BudgetEntity -> "${source.books.firstOrNull { it.id == row.bookId }?.name.orEmpty()} / ${row.periodKey}: " +
                    (row.model()?.total?.let { "${it.currency.code} ${it.decimalText()}" } ?: "(deleted)")
                is ScheduleEntity -> row.model().name
                is ScheduleExecutionEntity -> "${source.schedules.firstOrNull { it.id == row.planId }?.model()?.name.orEmpty()} / ${row.plannedAt}"
                is SavingsPlanEntity -> row.model().name
                is SavingsDepositEntity -> row.model().let { deposit ->
                    "${source.savingsPlans.firstOrNull { it.id == row.planId }?.model()?.name.orEmpty()} / ${deposit.date} " +
                        (deposit.amount?.let { "${it.currency.code} ${it.decimalText()}" } ?: billLabel(requireNotNull(deposit.linkedBillId)))
                }
                is ImportReceiptEntity -> billLabel(row.billId)
                is BatchActionEntity -> "${source.books.firstOrNull { it.id == row.bookId }?.name.orEmpty()} / ${row.model().before.size} / ${row.expiresAt}"
                else -> ""
            }
        }
        fun <T> combine(kind: ConflictKind, first: List<T>, second: List<T>, key: (T) -> Any): List<T> {
            val map = first.associateBy(key).toMutableMap()
            second.forEach { row ->
                val id = key(row); val previous = map[id]
                if (previous != null && previous != row) conflicts += RestoreConflict(kind, id.toString(), label(previous, current), label(row, incoming)) else map[id] = row
            }
            return map.values.toList()
        }
        val result = LedgerSnapshot(
            combine(ConflictKind.BOOK, current.books, incoming.books) { it.id },
            combine(ConflictKind.ACCOUNT, current.accounts.map { it.copy(balanceMinor = 0) }, incoming.accounts.map { it.copy(balanceMinor = 0) }) { it.id },
            combine(ConflictKind.CATEGORY, current.categories, incoming.categories) { it.id },
            combine(ConflictKind.BILL, current.bills, incoming.bills) { it.id },
            combine(ConflictKind.POSTING, current.postings, incoming.postings) { it.billId to it.position },
            combine(ConflictKind.SUBMISSION, current.submissions, incoming.submissions) { it.id },
            combine(ConflictKind.DELETION, current.deletions, incoming.deletions) { it.id },
            (incoming.preferences.associateBy { it.key } + current.preferences.associateBy { it.key }).values.toList(),
            combine(ConflictKind.DRAFT, current.drafts, incoming.drafts) { it.key },
            combine(ConflictKind.QUEUE, current.queue, incoming.queue) { it.id },
            combine(ConflictKind.EXCHANGE_RATE, current.exchangeRates, incoming.exchangeRates) { listOf(it.sourceCode, it.sourceScale, it.targetCode, it.targetScale) },
            combine(ConflictKind.BUDGET, current.budgets, incoming.budgets) { it.bookId to it.periodKey },
            combine(ConflictKind.SCHEDULE, current.schedules, incoming.schedules) { it.id },
            combine(ConflictKind.SCHEDULE_EXECUTION, current.scheduleExecutions, incoming.scheduleExecutions) { it.id },
            combine(ConflictKind.SAVINGS_PLAN, current.savingsPlans, incoming.savingsPlans) { it.id },
            combine(ConflictKind.SAVINGS_DEPOSIT, current.savingsDeposits, incoming.savingsDeposits) { it.id },
            combine(ConflictKind.IMPORT_RECEIPT, current.importReceipts, incoming.importReceipts) { it.id },
            combine(ConflictKind.BATCH_ACTION, current.batchActions, incoming.batchActions) { it.id },
        )
        if (conflicts.isNotEmpty()) return MergeResult(null, conflicts)
        result.savingsDeposits.filter { it.linkedBillId != null }.groupBy { requireNotNull(it.linkedBillId) }
            .filterValues { it.size > 1 }.forEach { (billId, _) ->
                conflicts += RestoreConflict(ConflictKind.SAVINGS_DEPOSIT, billId,
                    label(current.savingsDeposits.firstOrNull { it.linkedBillId == billId }, current),
                    label(incoming.savingsDeposits.firstOrNull { it.linkedBillId == billId }, incoming))
            }
        val activeBills = result.bills.filter { it.deletedAt == null }.map(LedgerJson::decodeBill)
        activeBills.filter { LinkedBillRules.validate(it.toDraft(), activeBills).isNotEmpty() }
            .map { it.linkedBillId ?: it.id }.distinct().forEach { id ->
                conflicts += RestoreConflict(ConflictKind.LINKED_RULE, id,
                    label(current.bills.firstOrNull { it.id == id }, current),
                    label(incoming.bills.firstOrNull { it.id == id }, incoming))
            }
        val totals = balanceTotals(result)
        result.accounts.forEach { account ->
            val amount = totals.getValue(account.id)
            if (amount < BigInteger.valueOf(Long.MIN_VALUE) || amount > BigInteger.valueOf(Long.MAX_VALUE)) {
                conflicts += RestoreConflict(ConflictKind.BALANCE_OVERFLOW, account.id,
                    label(current.accounts.firstOrNull { it.id == account.id }, current),
                    label(incoming.accounts.firstOrNull { it.id == account.id }, incoming))
            }
        }
        return MergeResult(if (conflicts.isEmpty()) result.copy(accounts = recomputeBalances(result)).normalized() else null, conflicts)
    }
}
