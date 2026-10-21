package app.omymoney

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.core.recognition.rules.AlipayRules
import app.omymoney.data.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AutomaticPaymentUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<OMyMoneyApplication>()
    private val store get() = app.container.store
    private fun saved(): QueueEntry = runBlocking {
        val book = UUID.randomUUID().toString()
        store.catalog.saveBook(Book(book, "Payment ${book.take(6)}"))
        val result = RuleEngine(AlipayRules.create(RecognitionContext(book))).recognize(UiSnapshot(AlipayRules.PACKAGE, 5,
            Instant.now(), listOf("支付成功0.01", "zjy", "0.01", "完成").map { UiNode(it) }))
        store.recognitionQueue.recordCapture(result, "ui/$book")
    }
    @Test fun receiptOpensTheSavedBillAndCounterpartyCorrectionDoesNotCreateAnotherBill() {
        val entry = saved()
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_QUEUE)
            .putExtra(MainActivity.EXTRA_REVIEW_ID, entry.id)).use {
        compose.waitUntil(5000) { compose.onAllNodesWithTag("bill_amount").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bill_amount").assertTextContains("0.01")
        compose.onNodeWithTag("bill_counterparty").performScrollTo().assertTextContains("zjy")
        captureUiEvidence("automatic-v5-editor.png")
        compose.onNodeWithTag("bill_counterparty").performTextReplacement("Corrected payee")
        compose.onNodeWithTag("bill_save").performClick()
        compose.waitUntil(5000) {
            runBlocking { store.ledger.observeBills(BillQuery(bookId = entry.draft.bookId)).first().single().payment?.counterparty == "Corrected payee" }
        }
        val bills = runBlocking { store.ledger.observeBills(BillQuery(bookId = entry.draft.bookId)).first() }
        assertEquals(1, bills.size)
        assertEquals(entry.billId, bills.single().id)
        assertNull(bills.single().fromAccountId)
        }
    }

    @Test fun billDetailSeparatesCounterpartyChannelUnknownFundingAndEstimatedTime() {
        val entry = saved()
        ActivityScenario.launch(MainActivity::class.java).use {
        compose.waitUntil(5000) { compose.onAllNodesWithTag("bill_row_${entry.billId}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bill_row_${entry.billId}").performClick()
        compose.onNodeWithTag("detail_counterparty").performScrollTo().assertTextContains("zjy")
        compose.onNodeWithTag("detail_unknown_from").performScrollTo().assertTextContains("账户未识别", substring = true)
        compose.onNodeWithTag("detail_time").performScrollTo().assertTextContains("识别时间（估算）", substring = true)
        compose.onNodeWithTag("detail_source").performScrollTo().assertTextContains("支付宝")
        captureUiEvidence("automatic-v5-detail.png")
        }
    }

    @Test fun anAssignedRecognizedPaymentCanReturnToUnassignedWithoutASecondBill() {
        val entry = saved()
        val account = runBlocking {
            val account = AssetAccount(UUID.randomUUID().toString(), "Funding test", kind = AccountKind.WALLET)
            store.catalog.saveAccount(account)
            val bill = store.ledger.observeBill(entry.billId!!).first()!!
            store.ledger.save(bill.toDraft().copy(fromAccountId = account.id), bill.revision)
            account
        }
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_QUEUE)
            .putExtra(MainActivity.EXTRA_REVIEW_ID, entry.id)).use {
            compose.waitUntil(5000) { compose.onAllNodesWithTag("bill_from_account").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("bill_from_account").performScrollTo().performClick()
            compose.onNodeWithTag("option_unassigned").performClick()
            compose.onNodeWithTag("bill_save").performClick()
            compose.waitUntil(5000) { runBlocking { store.ledger.observeBill(entry.billId!!).first()!!.fromAccountId == null } }
            assertEquals(0L, runBlocking { store.catalog.observeAccounts().first().single { it.id == account.id }.balance.minor })
        }
    }
}
