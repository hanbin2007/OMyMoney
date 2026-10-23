package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.core.recognition.rules.AlipayRules
import app.omymoney.core.recognition.rules.McDonaldsRules
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class McDonaldsRecordingTest {
    private val time = Instant.parse("2026-10-23T04:30:00Z")

    @Test fun nativeOrdersStayPendingUntilManualSaveAndReplaysDoNotAddBills() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val book = CatalogRepository(db).recognitionContext().bookId
            val rules = RuleEngine(RuleRegistry.enabledRules(setOf(McDonaldsRules.PACKAGE), RecognitionContext(book, zoneId = ZoneId.of("UTC"))))
            val queue = RecognitionQueue(db)
            val result = rules.recognize(order(1))
            val first = queue.recordCapture(result, "mcd/1")
            assertEquals(QueueState.PENDING, first.state)
            assertEquals(Money(2850, CurrencySpec.CNY), first.draft.amount)
            assertTrue(RoomLedgerRepository(db).observeBills(BillQuery(bookId = book)).first().isEmpty())
            assertEquals(first.id, queue.recordCapture(rules.recognize(order(2)), "mcd/2").id)
            val saved = queue.resolve(first.id, QueueOutcome.Save(first.draft)).entry
            assertEquals(QueueState.SAVED, saved.state)
            assertEquals(saved.id, queue.recordCapture(rules.recognize(order(3)), "mcd/3").id)
            assertEquals(1, RoomLedgerRepository(db).observeBills(BillQuery(bookId = book)).first().size)
        } finally { db.close() }
    }

    @Test fun paymentAppReturnCannotAutomaticallyDuplicateAnAlreadyReviewedNativeOrder() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val book = CatalogRepository(db).recognitionContext().bookId
            val engine = RuleEngine(RuleRegistry.enabledRules(setOf(McDonaldsRules.PACKAGE, AlipayRules.PACKAGE), RecognitionContext(book, zoneId = ZoneId.of("UTC"))))
            val queue = RecognitionQueue(db)
            val native = queue.recordCapture(engine.recognize(order(1)), "mcd/1")
            queue.resolve(native.id, QueueOutcome.Save(native.draft))
            val payment = engine.recognize(UiSnapshot(AlipayRules.PACKAGE, 2, time.plusSeconds(2),
                listOf("支付成功28.50", "完成").map { UiNode(it) }))
            val returned = queue.recordCapture(payment, "alipay/2")
            assertEquals(QueueState.PENDING, returned.state)
            assertEquals(AutomaticReviewReason.CROSS_SOURCE_IDENTITY, returned.automaticReviewReason)
            assertEquals(1, RoomLedgerRepository(db).observeBills(BillQuery(bookId = book)).first().size)
        } finally { db.close() }
    }

    @Test fun nativePackageRequiresItsOwnBookkeepingSelection() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val settings = AutomationSettingsRepository(db)
            settings.setEnabled(true)
            settings.setPackage(AlipayRules.PACKAGE, true)
            assertFalse(settings.read().settings.permits(McDonaldsRules.PACKAGE))
            settings.setPackage(McDonaldsRules.PACKAGE, true)
            assertTrue(settings.read().settings.permits(McDonaldsRules.PACKAGE))
            settings.setPaused(true)
            assertFalse(settings.read().settings.permits(McDonaldsRules.PACKAGE))
            settings.setPaused(false)
            settings.setPackage(McDonaldsRules.PACKAGE, false)
            assertFalse(settings.read().settings.permits(McDonaldsRules.PACKAGE))
            assertTrue(settings.read().settings.permits(AlipayRules.PACKAGE))
        } finally { db.close() }
    }

    private fun order(window: Int) = UiSnapshot(McDonaldsRules.PACKAGE, window, time.plusSeconds(window.toLong()),
        listOf("订单详情", "订单号", "MCD-TEST-ORDER", "实付金额", "¥28.50", "支付成功", "支付时间", "2026-10-23 04:30:00").map { UiNode(it) })
}
