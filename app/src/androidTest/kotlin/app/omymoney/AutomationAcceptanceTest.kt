package app.omymoney

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.data.*
import app.omymoney.platform.accessibility.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AutomationAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as OMyMoneyApplication).container.store
    private val prefs get() = compose.activity.getSharedPreferences("qa-stage-b", Context.MODE_PRIVATE)

    @Test fun manuallyQueuedDraftsSurviveProcessRestartWithoutDuplicatePosting() {
        if (InstrumentationRegistry.getArguments().getString("stageBPhase") == "verify") {
            val book = requireNotNull(prefs.getString("book", null))
            val account = requireNotNull(prefs.getString("account", null))
            val pending = requireNotNull(prefs.getString("pending", null))
            verify(book, account)
            openReview(pending)
            compose.onNodeWithTag("bill_amount").assertTextContains("14.")
            compose.waitForIdle()
            captureUiEvidence("b14-recovered.png")
            compose.onNodeWithTag("screen_back").performClick()
            return
        }
        val book = UUID.randomUUID().toString()
        val account = UUID.randomUUID().toString()
        val accountName = "自动验收现金 ${account.take(6)}"
        runBlocking {
            store.catalog.ensureLocalDefaults()
            assertTrue(store.catalog.saveBook(Book(book, "自动验收 ${book.take(6)}", zoneId = ZoneId.of("Asia/Shanghai"))) is CatalogResult.Saved)
            assertTrue(store.catalog.saveAccount(AssetAccount(account, accountName, openingBalance = Money(10_000, CurrencySpec.CNY))) is CatalogResult.Saved)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reads = AtomicInteger()
        val allowed = AtomicBoolean(false)
        val capture = CaptureCoordinator(scope, permitted = { allowed.get() }, context = { store.catalog.recognitionContext() },
            snapshot = { event ->
                reads.incrementAndGet()
                NodeSnapshotReader().read(Node(children = paidPage().map { Node(value = it) }), event.packageName)
            }, enqueue = { result, _ -> store.recognitionQueue.enqueue(result) }, normalDelayMillis = 10)
        try {
            capture.connect()
            capture.submit(CaptureEvent(PACKAGE, 1, null, SystemClock.uptimeMillis()))
            runBlocking { delay(100) }
            assertEquals(0, reads.get())
            assertEquals(0, entries(book).size)
            allowed.set(true)
            repeat(20) { capture.submit(CaptureEvent(PACKAGE, 1, null, SystemClock.uptimeMillis())) }
            compose.waitUntil(5000) { entries(book).size == 1 }
            val first = entries(book).single()
            assertEquals(1230L, first.draft.amount!!.minor)
            assertNull(first.draft.fromAccountId)
            assertTrue(first.events.any { it.state == QueueState.RECOGNIZED })
            assertEquals(QueueState.PENDING, first.state)
            assertEquals(0, bills(book).size)
            runBlocking {
                assertEquals(first.id, store.recognitionQueue.enqueue(first.result).id)
                val invalid = store.recognitionQueue.resolve(first.id, QueueOutcome.Save(first.draft.copy(amount = null)))
                assertTrue(invalid.saveResult is SaveResult.Invalid)
                assertEquals(QueueState.FAILED, invalid.entry.state)
            }
            assertEquals(0, bills(book).size)
            openReview(first.id)
            compose.onNodeWithTag("bill_amount").performTextReplacement("13.50")
            compose.onNodeWithTag("bill_from_account").performScrollTo().performClick()
            compose.onNodeWithTag("selection_search").performTextInput(accountName)
            compose.onNode(hasText(accountName) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
            compose.onNodeWithTag("bill_save").performClick()
            compose.waitUntil(5000) { entries(book).single().state == QueueState.SAVED }
            val saved = entries(book).single()
            runBlocking { store.recognitionQueue.resolve(saved.id, QueueOutcome.Save(saved.draft)) }
            verify(book, account)
            val similar = runBlocking {
                val draft = saved.draft.copy(submissionId = UUID.randomUUID().toString(), occurredAt = saved.draft.occurredAt!!.plusSeconds(30), note = "另一商户")
                store.recognitionQueue.enqueue(RecognitionResult.Matched(draft, listOf(FieldEvidence("merchant", listOf(1), "另一商户")), "acceptance-similar"))
            }
            assertEquals(DedupKind.SIMILAR, similar.decision.kind)
            runBlocking { store.recognitionQueue.resolve(similar.id, QueueOutcome.Ignore) }
            val pending = runBlocking {
                store.recognitionQueue.enqueue(RecognitionResult.NeedsReview(saved.draft.copy(submissionId = UUID.randomUUID().toString(),
                    occurredAt = null, timeSource = TimeSource.UNKNOWN, note = "缺少时间，保留核对"),
                    listOf(FieldError("occurredAt", "missing", "missing")), emptyList(), "acceptance-no-time"))
            }
            assertEquals(QueueState.PENDING, pending.state)
            val unsupported = RuleEngine(RuleRegistry.enabledRules(setOf(PACKAGE), RecognitionContext(book))).recognize(
                UiSnapshot(PACKAGE, 1, Instant.now(), paidPage().map { UiNode(text = it.replace("支付成功", "支付失败")) }))
            assertTrue(unsupported is RecognitionResult.Unsupported)
            assertTrue(ScreenshotRules.recognize(OcrDocument(100, 100, emptyList()), RecognitionContext(book)) is RecognitionResult.Unsupported)
            compose.onNodeWithTag("screen_back").performClick()
            compose.onNodeWithTag("nav_bills").performClick()
            waitFor("bill_row_${saved.billId}")
            compose.onNodeWithTag("bill_row_${saved.billId}").assertIsDisplayed()
            captureUiEvidence("b14-ledger.png")
            openReview(pending.id)
            compose.onNodeWithTag("bill_amount").performTextReplacement("14.")
            compose.onNodeWithTag("screen_back").performClick()
            waitFor("recognition_queue")
            compose.waitUntil(5000) {
                var hidden = false
                compose.runOnIdle {
                    hidden = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
                }
                hidden
            }
            compose.waitUntil(5000) { entries(book).single { it.id == pending.id }.state == QueueState.PENDING }
            assertTrue(prefs.edit().putString("book", book).putString("account", account).putString("pending", pending.id).commit())
            val entries = entries(book)
            assertEquals(3, entries.size)
            assertEquals(1, entries.count { it.state == QueueState.SAVED })
            assertEquals(1, entries.count { it.state == QueueState.IGNORED })
            assertEquals(1, entries.count { it.state == QueueState.PENDING })
            assertTrue(entries.single { it.id == saved.id }.events.any { it.state == QueueState.FAILED })
            verify(book, account)
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 3000)
            captureUiEvidence("b14-queue.png")
        } finally { capture.disconnect(); scope.cancel() }
    }

    private fun openReview(id: String) {
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_queue").performClick()
        waitFor("queue_$id")
        compose.onNodeWithTag("queue_$id").performClick()
        waitFor("bill_amount")
    }
    private fun verify(book: String, account: String) {
        assertEquals(1, bills(book).size)
        assertEquals(1350L, bills(book).single().amount.minor)
        assertEquals(8650L, runBlocking { store.catalog.observeAccounts().first().single { it.id == account }.balance.minor })
    }
    private fun bills(book: String) = runBlocking { store.ledger.observeBills(BillQuery(bookId = book)).first() }
    private fun entries(book: String) = runBlocking { store.recognitionQueue.entries().filter { it.draft.bookId == book } }
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun paidPage() = listOf("账单详情", "当前状态", "支付成功", "商品详情", "午餐", "实付款", "12.30", "支付时间",
        "2026-10-11 12:00:00", "支付方式", "多多钱包", "交易单号", "TEST-B14", "商户单号", "TEST-SHOP-B14")
    private class Node(private val value: String? = null, private val children: List<Node> = emptyList()) : SnapshotNode {
        override val packageName = PACKAGE
        override val windowId = 1
        override val password = false
        override val editable = false
        override val sensitive = false
        override val visible = true
        override val text get() = value
        override val description: String? = null
        override val viewId: String? = null
        override val className: String? = null
        override val childCount get() = children.size
        override fun child(index: Int) = children[index]
        override fun release() = Unit
    }
    companion object { private const val PACKAGE = "com.xunmeng.pinduoduo" }
}
