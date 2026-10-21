package app.omymoney

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.core.recognition.rules.AlipayRules
import app.omymoney.data.*
import app.omymoney.platform.accessibility.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class AutomaticCapturePipelineTest {
    @Test fun successRepaintsSaveOnceButARealCheckoutBoundaryAllowsTheNextEqualPayment() = runBlocking {
        val store = ApplicationProvider.getApplicationContext<OMyMoneyApplication>().container.store
        val book = UUID.randomUUID().toString()
        store.catalog.saveBook(Book(book, "Capture ${book.take(6)}"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val page = java.util.concurrent.atomic.AtomicReference(listOf("支付成功0.01", "zjy", "0.01", "完成"))
        val boundaries = java.util.concurrent.atomic.AtomicInteger()
        val captured = java.util.concurrent.atomic.AtomicInteger()
        val capture = CaptureCoordinator(scope, permitted = { true }, context = { RecognitionContext(book) },
            snapshot = { event -> UiSnapshot(event.packageName, event.windowId, Instant.now(), page.get().map { UiNode(it) }) },
            enqueue = { result, event ->
                store.recognitionQueue.recordCapture(result, "test/${event.windowId}")
                captured.incrementAndGet()
            }, onNonPaymentWindow = { event ->
                store.recognitionQueue.closeCaptureWindow(event.packageName)
                boundaries.incrementAndGet()
            })
        fun event(window: Int = 24) = CaptureEvent(AlipayRules.PACKAGE, window, null, SystemClock.uptimeMillis())
        suspend fun bills() = store.ledger.observeBills(BillQuery(bookId = book)).first()
        try {
            capture.connect()
            capture.submit(event())
            withTimeout(3000) { while (captured.get() < 1) delay(20) }
            assertEquals(1, bills().size)
            page.set(page.get() + "新广告")
            capture.submit(event())
            withTimeout(3000) { while (captured.get() < 2) delay(20) }
            assertEquals(1, bills().size)
            page.set(listOf("确认付款", "0.01"))
            capture.submit(event())
            withTimeout(3000) { while (boundaries.get() < 1) delay(20) }
            page.set(listOf("支付成功0.01", "zjy", "0.01", "完成"))
            capture.submit(event())
            withTimeout(3000) { while (bills().size < 2) delay(20) }
            assertEquals(2, bills().size)
            page.set(listOf("付款码"))
            capture.submit(event(99))
            withTimeout(3000) { while (boundaries.get() < 2) delay(20) }
            page.set(listOf("支付成功0.01", "zjy", "0.01", "完成"))
            capture.submit(event())
            withTimeout(3000) { while (bills().size < 3) delay(20) }
            assertEquals(3, bills().size)
            assertTrue(bills().all { it.fromAccountId == null && it.timeSource == TimeSource.CAPTURED })
        } finally { capture.disconnect(); scope.cancel() }
    }
}
