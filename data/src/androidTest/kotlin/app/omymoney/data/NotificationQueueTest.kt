package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.data.backup.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NotificationQueueTest {
    @Test fun notificationReplayPersistsOnceAndNeverAutomaticallyChangesBalances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val initial = CatalogRepository(db).initialDraft()
            val queue = RecognitionQueue(db)
            val sample = TransactionNotification("com.tencent.mm", "test-key", Instant.now(), mapOf("text" to "支付成功 CNY 12.30"))
            val result = NotificationRules.recognize(sample, RecognitionContext(initial.bookId))
            val first = queue.enqueue(result)
            assertEquals(first.id, queue.enqueue(result).id)
            assertEquals(QueueState.PENDING, first.state)
            assertEquals(BillSource.NOTIFICATION, first.draft.source)
            assertEquals(0, db.ledgerDao().billCount())
            val bytes = ByteArrayOutputStream().also { BackupArchive(db, context).exportTo(it) }.toByteArray()
            val archive = BackupArchive(db, context)
            val preview = archive.inspect(bytes.inputStream())
            archive.restore(preview.previewId, RestoreMode.REPLACE)
            assertEquals(first, queue.read(first.id))
        } finally { db.close() }
    }

    @Test fun aPageCaptureCannotAutomaticallyDuplicateAReviewedNotification() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val initial = CatalogRepository(db).initialDraft()
            val time = Instant.now()
            val queue = RecognitionQueue(db)
            val notification = NotificationRules.recognize(TransactionNotification("com.tencent.mm", "key", time,
                mapOf("text" to "支付成功 CNY 12.30")), RecognitionContext(initial.bookId))
            val entry = queue.enqueue(notification)
            val reviewed = initial.copy(source = BillSource.NOTIFICATION, sourcePackage = "com.tencent.mm", amount = Money(1230, CurrencySpec.CNY),
                recognizedAt = time, occurredAt = time, timeSource = TimeSource.USER)
            assertEquals(QueueState.SAVED, queue.resolve(entry.id, QueueOutcome.Save(reviewed)).entry.state)
            val page = reviewed.copy(submissionId = UUID.randomUUID().toString(), source = BillSource.ACCESSIBILITY, ruleId = "WeChatPaySuccess")
            val capture = queue.recordCapture(RecognitionResult.Matched(page, emptyList(), "WeChatPaySuccess"), "test-window")
            assertEquals(QueueState.PENDING, capture.state)
            assertNotNull(capture.automaticReviewReason)
            assertEquals(1, db.ledgerDao().billCount())
        } finally { db.close() }
    }
}
