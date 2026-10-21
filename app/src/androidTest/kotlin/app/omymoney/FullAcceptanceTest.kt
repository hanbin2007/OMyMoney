package app.omymoney

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.budget.*
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.core.savings.*
import app.omymoney.core.schedule.*
import app.omymoney.core.statistics.*
import app.omymoney.data.*
import app.omymoney.data.backup.RestoreMode
import app.omymoney.data.exchange.*
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.time.*
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FullAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as OMyMoneyApplication).container
    private val store get() = container.store
    private val prefs get() = compose.activity.getSharedPreferences("qa-full-acceptance", Context.MODE_PRIVATE)
    private val date = LocalDate.parse("2024-01-15")
    private val time = Instant.parse("2024-01-15T12:00:00Z")
    private val zone = ZoneOffset.UTC
    private val usd = CurrencySpec("USD", 2)

    @Test fun everyLocalWorkflowSurvivesRestoreAndReopenWithExactSharedBalances() {
        if (InstrumentationRegistry.getArguments().getString("fullPhase") == "verify") {
            runBlocking { assertEquals(prefs.getString("snapshot", null), digest(snapshot(backup()))) }
            assertLedgerVisible(requireNotNull(prefs.getString("book", null)))
            captureUiEvidence("c10-reopened.png")
            return
        }
        val book = Book(id(), "整体验收 ${id().take(6)}", zoneId = zone)
        val cash = AssetAccount(id(), "验收现金 ${id().take(6)}", openingBalance = money(100_000))
        val bank = AssetAccount(id(), "验收银行 ${id().take(6)}")
        val foreign = AssetAccount(id(), "验收美元 ${id().take(6)}", currency = usd, openingBalance = Money(10_000, usd))
        val debt = AssetAccount(id(), "验收借款 ${id().take(6)}", kind = AccountKind.LIABILITY)
        runBlocking {
            store.catalog.ensureLocalDefaults()
            assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved)
            listOf(cash, bank, foreign, debt).forEach { assertTrue(store.catalog.saveAccount(it) is CatalogResult.Saved) }
            store.catalog.selectBook(book.id)
            assertTrue(store.catalog.saveExchangeRate(ManualExchangeRate(usd, CurrencySpec.CNY, "7.2", date)) is CatalogResult.Saved)
            fun draft(kind: BillKind, amount: Long, from: String? = null, to: String? = null, link: String? = null) =
                TransactionDraft(book.id, kind = kind, amount = money(amount), fromAccountId = from, toAccountId = to,
                    linkedBillId = link, occurredAt = time, zoneId = zone, timeSource = TimeSource.USER)
            suspend fun save(value: TransactionDraft): String {
                val result = store.ledger.save(value, null)
                assertTrue(result.toString(), result is SaveResult.Saved)
                return (result as SaveResult.Saved).billId
            }
            save(draft(BillKind.INCOME, 20_000, to = cash.id))
            val manual = EditorDraft("manual/${book.id}", draft(BillKind.EXPENSE, 10_000, from = cash.id), amountText = "100.00", dirty = true)
            store.drafts.write(manual)
            val submitted = store.drafts.submit(manual) as SaveResult.Saved
            assertNull(store.drafts.read(manual.key))
            save(draft(BillKind.REFUND, 2000, to = cash.id, link = submitted.billId))
            val advance = save(draft(BillKind.REIMBURSEMENT, 3000, from = cash.id))
            save(draft(BillKind.REIMBURSED, 3000, to = cash.id, link = advance))
            save(draft(BillKind.TRANSFER, 1000, foreign.id, bank.id).copy(amount = Money(1000, usd), fee = Money(10, usd),
                exchange = ExchangeSnapshot(bank.id, money(7199), "7.2", date)))
            val transfer = save(draft(BillKind.TRANSFER, 5000, cash.id, bank.id))
            val borrowed = save(draft(BillKind.DEBT_BORROW, 10_000, debt.id, bank.id))
            save(draft(BillKind.DEBT_REPAY, 4000, bank.id, debt.id, borrowed))

            val candidate = store.recognitionQueue.enqueue(RecognitionResult.Matched(
                draft(BillKind.EXPENSE, 1230, cash.id).copy(source = BillSource.ACCESSIBILITY), emptyList(), "full-acceptance"))
            assertEquals(9, bills(book.id).size)
            assertEquals(QueueState.PENDING, candidate.state)
            val saved = store.recognitionQueue.resolve(candidate.id, QueueOutcome.Save(candidate.draft))
            assertEquals(QueueState.SAVED, saved.entry.state)
            store.recognitionQueue.resolve(candidate.id, QueueOutcome.Save(candidate.draft))
            assertEquals(10, bills(book.id).size)
            val pending = store.recognitionQueue.enqueue(RecognitionResult.NeedsReview(
                draft(BillKind.EXPENSE, 1400, cash.id).copy(occurredAt = null, timeSource = TimeSource.UNKNOWN),
                listOf(FieldError("occurredAt", "missing", "missing")), emptyList(), "full-acceptance-pending"))
            assertEquals(QueueState.PENDING, pending.state)
            val unsaved = EditorDraft("manual/${book.id}", draft(BillKind.EXPENSE, 0, cash.id).copy(amount = null),
                amountText = "14.", dirty = true)
            store.drafts.write(unsaved)

            val rule = ScheduleRule(LocalDate.parse("2024-01-01"), zone = zone, frequency = ScheduleFrequency.DAILY, count = 3)
            val installment = ScheduledPlan(name = "三期精确分摊", template = draft(BillKind.EXPENSE, 1000, cash.id),
                rule = rule, automatic = true, installments = true)
            val reviewPlan = ScheduledPlan(name = "等待确认的周期账单", template = draft(BillKind.EXPENSE, 800, cash.id), rule = rule.copy(count = 1))
            assertTrue(store.schedules.save(installment) is ScheduleSaveResult.Saved)
            assertTrue(store.schedules.save(reviewPlan) is ScheduleSaveResult.Saved)
            store.schedules.executeDue(time)
            store.schedules.executeDue(time)
            val installments = store.schedules.observeExecutions(installment.id).first().sortedBy { it.index }
            assertEquals(listOf(333L, 333L, 334L), installments.map { it.template.amount!!.minor })
            assertTrue(installments.all { it.state == ScheduleExecutionState.POSTED })
            assertEquals(ScheduleExecutionState.PENDING, store.schedules.observeExecutions(reviewPlan.id).first().single().state)
            val savings = SavingsPlan(bookId = book.id, name = "验收储蓄", rule = SavingsRule(SavingsMode.CUSTOM, money(10_000), date), zone = zone)
            assertTrue(store.savings.savePlan(savings) is SavingsResult.Saved)
            assertTrue(store.savings.saveDeposit(SavingsDeposit(planId = savings.id, date = date, linkedBillId = transfer)) is SavingsResult.Saved)

            val csv = "kind,amount,currency,occurred_at,from_account,note\nEXPENSE,5.25,CNY,2024-01-15T12:00:00Z,${cash.name},原始导入\n".toByteArray()
            val preview = store.billImport.preview(csv.inputStream(), ExchangeFormat.CSV, book.id)
            assertTrue(preview.rows.single().selectable)
            assertEquals(1, store.billImport.commit(preview.previewId, ImportChoices(setOf(preview.rows.single().number))).imported)
            assertTrue(store.billImport.preview(csv.inputStream(), ExchangeFormat.CSV, book.id).rows.single().duplicate)
            for (format in ExchangeFormat.entries) {
                val output = ByteArrayOutputStream()
                assertEquals(14, store.billExport.export(BillQuery(bookId = book.id), format, output))
                val readback = store.billImport.preview(output.toByteArray().inputStream(), format, book.id)
                assertEquals(14, readback.rows.size)
                val amountColumn = readback.columns.indexOf("amount")
                assertTrue(readback.rows.any { it.raw[amountColumn] == "5.25" })
                store.billImport.cancel(readback.previewId)
            }
            val selected = bills(book.id).filter { it.kind == BillKind.INCOME }
            val action = store.batchActions.edit(selected.associate { it.id to it.revision }, BatchPatch(note = "批量后撤销"))
            assertTrue(store.batchActions.undo(action.id))
            assertFalse(store.batchActions.undo(action.id))

            val budget = BudgetPlan(book.id, BudgetPeriod(2024, 1), money(20_000))
            assertTrue(store.budgets.save(budget) is CatalogResult.Saved)
            val all = bills(book.id)
            val rates = store.catalog.observeExchangeRates().first()
            val summary = StatisticsCalculator.summarize(all, StatisticsRange(book.id, date.withDayOfMonth(1), LocalDate.parse("2024-02-01"), zone),
                CurrencySpec.CNY, rates = rates)
            assertEquals(BigInteger.valueOf(20_000), summary.totals.income.minor)
            assertEquals(BigInteger.valueOf(10_827), summary.totals.expense.minor)
            assertEquals(BigInteger.valueOf(12_199), summary.totals.transfer.minor)
            val usage = BudgetCalculator.calculate(budget.period, all, budget, zone = zone, today = date, rates = rates)
            assertEquals(summary.totals.expense.minor, usage.total.used)
            assertEquals(BigInteger.valueOf(9173), usage.total.remaining)
            val progress = SavingsCalculator.progress(savings, store.savings.observeDeposits(savings.id).first(), all.associateBy { it.id })
            assertEquals(BigInteger.valueOf(5000), progress.savedMinor)
            assertEquals(BigInteger.valueOf(5000), progress.remainingMinor)
            val balances = store.catalog.observeAccounts().first().associate { it.id to it.balance.minor }
            assertEquals(104_245L, balances.getValue(cash.id))
            assertEquals(18_199L, balances.getValue(bank.id))
            assertEquals(8990L, balances.getValue(foreign.id))
            assertEquals(-6000L, balances.getValue(debt.id))

            val before = backup()
            save(draft(BillKind.EXPENSE, 1, cash.id))
            assertEquals(15, bills(book.id).size)
            val restore = store.backup.inspect(before.inputStream())
            assertTrue(restore.summary.importReceipts > 0)
            assertTrue(restore.summary.batchActions > 0)
            // Privacy preferences are intentionally outside the portable business archive.
            assertTrue(container.preferences.setLockEnabled(true))
            try {
                store.backup.restore(restore.previewId, RestoreMode.REPLACE)
                assertTrue(container.preferences.state.value.lockEnabled)
                val reopenedLock = app.omymoney.platform.security.PrivacyLock(container.preferences)
                assertTrue(reopenedLock.state.value.enabled)
                assertTrue(reopenedLock.state.value.locked)
            } finally {
                assertTrue(container.preferences.setLockEnabled(false))
            }
            assertEquals(snapshot(before), snapshot(backup()))
            assertEquals("14.", store.drafts.read(unsaved.key)!!.amountText)
            assertEquals(QueueState.PENDING, store.recognitionQueue.read(pending.id)!!.state)
            assertTrue(prefs.edit().putString("book", book.id).putString("snapshot", digest(snapshot(before))).commit())
        }
        assertLedgerVisible(book.id)
        compose.activityRule.scenario.recreate()
        runBlocking { assertEquals(prefs.getString("snapshot", null), digest(snapshot(backup()))) }
        assertLedgerVisible(book.id)
        captureUiEvidence("c10-ledger.png")
    }

    private fun assertLedgerVisible(book: String) {
        waitFor("nav_bills")
        compose.onNodeWithTag("nav_bills").performClick()
        waitFor("book_switch")
        val bill = runBlocking { bills(book).first() }
        waitFor("bill_row_${bill.id}")
        compose.waitForIdle()
    }
    private suspend fun bills(book: String) = store.ledger.observeBills(BillQuery(bookId = book)).first()
    private suspend fun backup() = ByteArrayOutputStream().also { store.backup.exportTo(it) }.toByteArray()
    private fun snapshot(bytes: ByteArray): String = ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: error("Missing business snapshot")
            if (entry.name == "data.json") return@use zip.readBytes().decodeToString()
        }
        @Suppress("UNREACHABLE_CODE") error("Missing business snapshot")
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun money(minor: Long) = Money(minor, CurrencySpec.CNY)
    private fun id() = UUID.randomUUID().toString()
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}
