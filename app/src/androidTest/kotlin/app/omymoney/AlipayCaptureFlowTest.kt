package app.omymoney

import android.os.SystemClock
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.core.recognition.rules.AlipayRules
import app.omymoney.data.*
import app.omymoney.platform.accessibility.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AlipayCaptureFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun capturedMergedSuccessQueuesOneCentAndRequiresExplicitAccountTimeAndSave() {
        val store = (compose.activity.application as OMyMoneyApplication).container.store
        val book = UUID.randomUUID().toString()
        val account = UUID.randomUUID().toString()
        val accountName = "Alipay fixture ${account.take(6)}"
        runBlocking {
            store.catalog.ensureLocalDefaults()
            assertTrue(store.catalog.saveBook(Book(book, "Alipay ${book.take(6)}")) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(AssetAccount(account, accountName,
                openingBalance = Money(100, CurrencySpec.CNY))) is CatalogResult.Saved)
        }
        val json = InstrumentationRegistry.getInstrumentation().context.assets.open(
            "recognition/alipay/captured-merged-success.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val nodes = json.getValue("nodes").jsonArray.map { element ->
            val node = element.jsonObject
            fun text(key: String) = node[key]?.jsonPrimitive?.contentOrNull
            UiNode(text("text"), text("description"), text("viewId"), text("className"), node["parentIndex"]?.jsonPrimitive?.intOrNull)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val capture = CaptureCoordinator(scope, permitted = { true }, context = { RecognitionContext(book) },
            snapshot = { event -> UiSnapshot(event.packageName, event.windowId, Instant.now(), nodes) },
            enqueue = { result, _ -> store.recognitionQueue.enqueue(result) })
        fun entries() = runBlocking { store.recognitionQueue.entries().filter { it.draft.bookId == book } }
        fun bills() = runBlocking { store.ledger.observeBills(BillQuery(bookId = book)).first() }
        try {
            capture.connect()
            repeat(20) { capture.submit(CaptureEvent(AlipayRules.PACKAGE, 1, null, SystemClock.uptimeMillis())) }
            compose.waitUntil(5000) { entries().size == 1 }
            val entry = entries().single()
            assertEquals(QueueState.PENDING, entry.state)
            assertEquals(Money(1, CurrencySpec.CNY), entry.draft.amount)
            assertEquals(BillKind.EXPENSE, entry.draft.kind)
            assertNull(entry.draft.fromAccountId)
            assertNull(entry.draft.occurredAt)
            assertEquals(0, bills().size)
            capture.submit(CaptureEvent(AlipayRules.PACKAGE, 1, null, SystemClock.uptimeMillis()))
            runBlocking { delay(600) }
            assertEquals(1, entries().size)
            compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithTag("settings_queue").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("queue_${entry.id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("queue_${entry.id}").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("bill_amount").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("bill_amount").assertTextContains("0.01")
            captureUiEvidence("alipay-v4-review-amount.png")
            compose.onNodeWithTag("bill_time").performScrollTo().assert(hasAnyDescendant(hasText(
                compose.activity.getString(app.omymoney.android.R.string.time_unconfirmed))))
            captureUiEvidence("alipay-v4-review-time.png")
            compose.onNodeWithTag("bill_from_account").performScrollTo().performClick()
            compose.onNodeWithTag("selection_search").performTextInput(accountName)
            compose.onNode(hasText(accountName) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
            compose.onNodeWithTag("bill_time").performScrollTo().performClick()
            compose.onNodeWithText("下一步").performClick()
            compose.onNodeWithText("确定").performClick()
            assertEquals(0, bills().size)
            compose.onNodeWithTag("bill_save").performClick()
            compose.waitUntil(5000) { entries().single().state == QueueState.SAVED }
            assertEquals(Money(1, CurrencySpec.CNY), bills().single().amount)
            assertEquals(account, bills().single().fromAccountId)
        } finally {
            capture.disconnect()
            scope.cancel()
        }
    }
}
