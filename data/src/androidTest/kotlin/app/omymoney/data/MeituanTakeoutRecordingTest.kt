package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.core.recognition.rules.AlipayRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MeituanTakeoutRecordingTest {
    private val time = Instant.parse("2026-10-18T11:30:00Z")
    private val packageName = "com.sankuai.meituan.takeoutnew"

    @Test fun receiptThenOrderAcrossWindowsEnrichesOneBillAndDifferentOrdersStaySeparate() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val book = CatalogRepository(db).recognitionContext().bookId
            val engine = RuleEngine(RuleRegistry.enabledRules(setOf(packageName), RecognitionContext(book)))
            val queue = RecognitionQueue(db)
            fun page(window: Int, seconds: Long, vararg text: String) = engine.recognize(
                UiSnapshot(packageName, window, time.plusSeconds(seconds), text.map { UiNode(it) }))
            fun receipt(seconds: Long) = page(41, seconds, "支付成功 ¥28.40", "完成")
            fun detail(order: String, seconds: Long) = page(42, seconds, "订单详情", "订单编号", order,
                "实付款", "¥28.40", "支付成功", "支付时间", "2026-10-18 11:30:00",
                "商家名称", "测试餐厅", "支付方式", "微信支付")
            suspend fun bills() = RoomLedgerRepository(db).observeBills(BillQuery(bookId = book)).first()

            val first = queue.recordCapture(receipt(0), "takeout/41")
            assertEquals(QueueState.SAVED, first.state)
            assertEquals(first.id, queue.recordCapture(detail("TEST-TAKEOUT-A", 2), "takeout/42").id)
            assertEquals(first.id, queue.recordCapture(receipt(3), "takeout/41").id)
            assertEquals(1, bills().size)
            assertEquals("测试餐厅", bills().single().payment?.counterparty)
            assertEquals("微信支付", bills().single().payment?.fromAccountHint)
            assertEquals(TimeSource.PAGE, bills().single().timeSource)
            assertEquals(packageName, bills().single().sourcePackage)
            queue.closeCaptureWindow(packageName)
            val next = queue.recordCapture(detail("TEST-TAKEOUT-B", 4), "takeout/42")
            assertEquals(QueueState.SAVED, next.state)
            assertNotEquals(first.id, next.id)
            assertEquals(2, bills().size)
        } finally { db.close() }
    }

    @Test fun standaloneCaptureRequiresItsOwnExplicitPackageConsent() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repository = AutomationSettingsRepository(db)
            repository.setEnabled(true)
            repository.setPackage("com.sankuai.meituan", true)
            assertFalse(repository.read().settings.permits(packageName))
            repository.setPackage(packageName, true)
            assertTrue(repository.read().settings.permits(packageName))
            repository.setPaused(true)
            assertFalse(repository.read().settings.permits(packageName))
            repository.setPaused(false)
            repository.setPackage(packageName, false)
            assertFalse(repository.read().settings.permits(packageName))
            assertTrue(repository.read().settings.permits("com.sankuai.meituan"))
        } finally { db.close() }
    }

    @Test fun externalPaymentAndTakeoutReturnCannotAutomaticallyPostThePurchaseTwice() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        for (takeoutFirst in listOf(false, true)) {
            val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
            try {
                val book = CatalogRepository(db).recognitionContext().bookId
                val engine = RuleEngine(RuleRegistry.enabledRules(setOf(packageName, AlipayRules.PACKAGE), RecognitionContext(book)))
                fun result(takeout: Boolean, seconds: Long) = engine.recognize(UiSnapshot(
                    if (takeout) packageName else AlipayRules.PACKAGE, 41, time.plusSeconds(seconds),
                    (if (takeout) listOf("支付成功 ¥28.40", "完成") else listOf("支付成功28.40", "完成")).map { UiNode(it) }))
                val queue = RecognitionQueue(db)
                assertEquals(QueueState.SAVED, queue.recordCapture(result(takeoutFirst, 0), "cross-app/41").state)
                val returned = queue.recordCapture(result(!takeoutFirst, 2), "cross-app/42")
                assertEquals(QueueState.PENDING, returned.state)
                assertEquals(AutomaticReviewReason.CROSS_SOURCE_IDENTITY, returned.automaticReviewReason)
                assertEquals(1, RoomLedgerRepository(db).observeBills(BillQuery(bookId = book)).first().size)
            } finally { db.close() }
        }
    }
}
