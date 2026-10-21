package app.omymoney

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.diagnostics.DiagnosticSink
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.data.CatalogResult
import app.omymoney.platform.accessibility.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class MeituanTakeoutCaptureTest {
    @Test fun standaloneEventsReachLedgerAndCheckoutAllowsAnotherEqualPayment() = runBlocking {
        val store = ApplicationProvider.getApplicationContext<OMyMoneyApplication>().container.store
        val book = UUID.randomUUID().toString()
        assertTrue(store.catalog.saveBook(Book(book, "Takeout ${book.take(8)}")) is CatalogResult.Saved)
        val packageName = "com.sankuai.meituan.takeoutnew"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val page = AtomicReference(listOf("支付成功 ¥28.40", "完成"))
        val captured = AtomicInteger()
        val boundaries = AtomicInteger()
        val trace = java.util.concurrent.CopyOnWriteArrayList<String>()
        val diagnostics = DiagnosticSink { kind, fields -> trace += "$kind:${fields.toString().take(256)}" }
        assertTrue(packageName in RuleRegistry.supportedPackages)
        val capture = CaptureCoordinator(scope, permitted = { it == packageName }, context = { RecognitionContext(book) },
            snapshot = { event -> UiSnapshot(event.packageName, event.windowId, Instant.now(), page.get().map { UiNode(it) }) },
            enqueue = { result, event ->
                store.recognitionQueue.recordCapture(result, "takeout-pipeline/${event.windowId}")
                captured.incrementAndGet()
            }, onNonPaymentWindow = { event ->
                store.recognitionQueue.closeCaptureWindow(event.packageName)
                boundaries.incrementAndGet()
            }, normalDelayMillis = 0, diagnostics = diagnostics)
        fun event() = CaptureEvent(packageName, 73, null, SystemClock.uptimeMillis())
        suspend fun bills() = store.ledger.observeBills(BillQuery(bookId = book)).first()
        suspend fun await(label: String, condition: suspend () -> Boolean) {
            val completed = withTimeoutOrNull(3000) { while (!condition()) delay(20); true }
            assertEquals("$label: ${trace.takeLast(8)}", true, completed)
        }
        try {
            capture.connect()
            capture.submit(event())
            await("Standalone receipt must reach the queue") { captured.get() >= 1 }
            assertEquals(1, bills().size)
            page.set(page.get() + "页面刷新")
            capture.submit(event())
            await("Receipt repaint must be processed") { captured.get() >= 2 }
            assertEquals(1, bills().size)
            listOf("确认支付", "立即支付", "去支付", "提交订单").forEachIndexed { index, checkout ->
                page.set(listOf(checkout, "¥28.40"))
                capture.submit(event())
                await("$checkout must end the preceding capture") { boundaries.get() >= index + 1 }
                page.set(listOf("支付成功 ¥28.40", "完成"))
                capture.submit(event())
                await("The next equal payment must not be lost after $checkout") { bills().size >= index + 2 }
                assertEquals(index + 2, bills().size)
            }
            assertTrue(bills().all { it.sourcePackage == packageName && it.amount == Money(2840, CurrencySpec.CNY) })
        } finally { capture.disconnect(); scope.cancel() }
    }
}
