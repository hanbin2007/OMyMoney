package app.omymoney

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.android.R
import app.omymoney.core.model.*
import app.omymoney.core.recognition.RecognitionResult
import app.omymoney.core.savings.*
import app.omymoney.core.schedule.*
import app.omymoney.data.*
import app.omymoney.platform.security.LaunchRequest
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CrossFeatureNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store
    private data class Fixture(val book: Book, val cash: AssetAccount, val bank: AssetAccount)
    private fun fixture(): Fixture {
        val suffix = UUID.randomUUID().toString().take(6)
        val book = Book(UUID.randomUUID().toString(), "跨功能 $suffix", zoneId = ZoneOffset.UTC)
        val cash = AssetAccount(UUID.randomUUID().toString(), "现金 $suffix", openingBalance = Money(10_000, CurrencySpec.CNY))
        val bank = AssetAccount(UUID.randomUUID().toString(), "银行 $suffix")
        runBlocking {
            store.catalog.ensureLocalDefaults()
            assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(cash) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(bank) is CatalogResult.Saved)
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
        return Fixture(book, cash, bank)
    }
    private fun draft(f: Fixture, transfer: Boolean = false) = TransactionDraft(f.book.id,
        kind = if (transfer) BillKind.TRANSFER else BillKind.EXPENSE, amount = Money(1230, CurrencySpec.CNY),
        fromAccountId = f.cash.id, toAccountId = f.bank.id.takeIf { transfer },
        occurredAt = Instant.parse("2024-01-15T12:00:00Z"), zoneId = f.book.zoneId, timeSource = TimeSource.USER)
    private fun settings(tag: String) {
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("catalog_settings").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
    private fun deleteAndUndo(f: Fixture, billId: String, parent: String, evidence: String) {
        waitFor("bill_delete")
        val balances = runBlocking { store.catalog.observeAccounts().first().associate { it.id to it.balance } }
        compose.onNodeWithTag("bill_delete").performClick()
        waitFor(parent)
        compose.waitUntil(5000) { runBlocking { store.ledger.observeBill(billId).first() == null } }
        compose.onNodeWithText(compose.activity.getString(R.string.undo)).assertIsDisplayed()
        captureUiEvidence(evidence)
        compose.onNodeWithText(compose.activity.getString(R.string.undo)).performClick()
        compose.waitUntil(5000) { runBlocking { store.ledger.observeBill(billId).first() != null } }
        assertEquals(balances, runBlocking { store.catalog.observeAccounts().first().associate { it.id to it.balance } })
        assertEquals(1, runBlocking { store.ledger.observeBills(BillQuery(bookId = f.book.id)).first().size })
    }

    @Test fun scheduledBillDeletionOffersVisibleUndoBackInItsExecutionHistory() {
        val f = fixture()
        val plan = ScheduledPlan(name = "已入账期次", template = draft(f), automatic = true,
            rule = ScheduleRule(LocalDate.parse("2024-01-15"), zone = f.book.zoneId, count = 1))
        val execution = runBlocking {
            assertTrue(store.schedules.save(plan) is ScheduleSaveResult.Saved)
            store.schedules.executeDue(Instant.parse("2024-01-16T00:00:00Z"))
            store.schedules.observeExecutions(plan.id).first().single()
        }
        settings("settings_schedules")
        waitFor("sch_plan_${plan.id}")
        compose.onNodeWithTag("sch_plan_${plan.id}").performClick()
        waitFor("sch_detail")
        compose.onNodeWithTag("sch_detail").performScrollToNode(hasTestTag("sch_execution_${execution.id}"))
        compose.onNodeWithTag("sch_execution_${execution.id}").performClick()
        deleteAndUndo(f, execution.billId!!, "sch_detail", "c10-schedule-undo.png")
    }

    @Test fun linkedSavingsTransferDeletionOffersVisibleUndoBackInSavings() {
        val f = fixture()
        val plan = SavingsPlan(bookId = f.book.id, name = "关联转账", rule = SavingsRule(SavingsMode.CUSTOM,
            Money(10_000, CurrencySpec.CNY), LocalDate.parse("2024-01-15")), zone = f.book.zoneId)
        val result = runBlocking { store.ledger.save(draft(f, transfer = true), null) as SaveResult.Saved }
        val deposit = SavingsDeposit(planId = plan.id, date = plan.rule.start, linkedBillId = result.billId)
        runBlocking {
            assertTrue(store.savings.savePlan(plan) is SavingsResult.Saved)
            assertTrue(store.savings.saveDeposit(deposit) is SavingsResult.Saved)
        }
        settings("settings_savings")
        waitFor("sav_plan_${plan.id}")
        compose.onNodeWithTag("sav_plan_${plan.id}").performClick()
        waitFor("sav_detail")
        compose.onNodeWithTag("sav_detail").performScrollToNode(hasTestTag("sav_deposit_${deposit.id}"))
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.savings_open_transfer)).performClick()
        deleteAndUndo(f, result.billId, "sav_detail", "c10-savings-undo.png")
    }

    @Test fun savedRecognitionBillDeletionOffersVisibleUndoBackInHistory() {
        val f = fixture()
        val entry = runBlocking {
            val pending = store.recognitionQueue.enqueue(RecognitionResult.Matched(draft(f), emptyList(), "cross-navigation"))
            store.recognitionQueue.resolve(pending.id, QueueOutcome.Save(pending.draft)).entry
        }
        settings("settings_queue")
        compose.onNodeWithText(compose.activity.getString(R.string.review_history)).performClick()
        compose.onNodeWithTag("recognition_queue").performScrollToNode(hasTestTag("queue_${entry.id}"))
        compose.onNodeWithTag("queue_${entry.id}").performClick()
        deleteAndUndo(f, entry.billId!!, "recognition_queue", "c10-recognition-undo.png")
    }

    @Test fun externalBookOpenKeepsTheInterruptedScheduleEditorReachable() {
        val f = fixture()
        settings("settings_schedules")
        compose.onNodeWithTag("new_schedule").performClick()
        waitFor("sch_name")
        compose.onNodeWithTag("sch_name").performTextReplacement("尚未完成的计划")
        compose.onNodeWithTag("sch_amount").performScrollTo().performTextReplacement("23.")
        externalBookAndBack(f)
        waitFor("sch_name")
        compose.onNodeWithTag("sch_name").assertTextContains("尚未完成的计划")
        compose.onNodeWithTag("sch_amount").assertTextContains("23.")
        val beforeRecreate = compose.activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync { beforeRecreate.recreate() }
        compose.waitUntil(5000) { beforeRecreate.isDestroyed }
        waitFor("sch_name")
        compose.onNodeWithTag("sch_name").assertTextContains("尚未完成的计划")
        compose.onNodeWithTag("sch_amount").assertTextContains("23.")
        captureUiEvidence("c10-schedule-interrupted.png")
        assertTrue(runBlocking { store.schedules.observePlans(f.book.id).first().isEmpty() })
    }

    @Test fun externalBookOpenKeepsTheInterruptedSavingsDepositReachable() {
        val f = fixture()
        val plan = SavingsPlan(bookId = f.book.id, name = "保留存入表单", rule = SavingsRule(SavingsMode.CUSTOM,
            Money(10_000, CurrencySpec.CNY), LocalDate.now(f.book.zoneId)), zone = f.book.zoneId)
        runBlocking { assertTrue(store.savings.savePlan(plan) is SavingsResult.Saved) }
        settings("settings_savings")
        waitFor("sav_plan_${plan.id}")
        compose.onNodeWithTag("sav_plan_${plan.id}").performClick()
        waitFor("sav_add")
        compose.onNodeWithTag("sav_add").performClick()
        waitFor("dep_amount")
        compose.onNodeWithTag("dep_amount").performTextReplacement("14.")
        compose.onNodeWithTag("dep_note").performScrollTo().performTextReplacement("未提交的记录")
        externalBookAndBack(f)
        waitFor("dep_amount")
        compose.onNodeWithTag("dep_amount").assertTextContains("14.")
        compose.onNodeWithTag("dep_note").assertTextContains("未提交的记录")
        captureUiEvidence("c10-deposit-interrupted.png")
        assertTrue(runBlocking { store.savings.observeDeposits(plan.id).first().isEmpty() })
    }

    private fun externalBookAndBack(f: Fixture) {
        val activity = compose.activity
        val initial = activity.intent
        try {
            compose.runOnIdle { activity.startActivity(LaunchRequest.intent(activity, f.book.id, false)) }
            waitFor("add_bill")
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.intent = initial }
        }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }
    private fun waitFor(tag: String) { compose.waitUntil(5000) {
        runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
    } }
}
