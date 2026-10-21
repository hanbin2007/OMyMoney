package app.omymoney

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.data.*
import app.omymoney.platform.diagnostics.DiagnosticManager
import app.omymoney.platform.notification.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class NotificationPipelineDeviceTest {
    private val application = ApplicationProvider.getApplicationContext<OMyMoneyApplication>()
    @Suppress("DEPRECATION")
    private fun notification(pkg: String, text: String, flags: Int = 0): StatusBarNotification {
        val notification = Notification.Builder(application, "test-notification-source")
            .setContentTitle("交易提醒").setContentText(text).build().apply {
                extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "$text\n商户：测试商户")
                this.flags = flags
            }
        return StatusBarNotification(pkg, pkg, 1, "test", Process.myUid(), 0, 0, notification, Process.myUserHandle(), System.currentTimeMillis())
    }

    @Test fun manifestAndBoundedReaderCoverEveryRegisteredSource() {
        val info = application.packageManager.getServiceInfo(ComponentName(application, TransactionNotificationListener::class.java), 0)
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", info.permission)
        assertFalse(info.exported)
        RuleRegistry.supportedPackages.forEach { pkg ->
            val sample = requireNotNull(readTransactionNotification(notification(pkg, "支付成功 CNY 12.30")))
            assertTrue(sample.fields.getValue("bigText").contains("测试商户"))
            assertEquals(pkg, sample.packageName)
            assertTrue(NotificationRules.isCandidate(sample))
        }
        assertNull(readTransactionNotification(notification("com.unrelated.app", "支付成功 CNY 12.30")))
        assertNull(readTransactionNotification(notification("com.tencent.mm", "支付成功 CNY 12.30", Notification.FLAG_GROUP_SUMMARY)))
        val huge = requireNotNull(readTransactionNotification(notification("com.tencent.mm", "支付成功 CNY 12.30" + "x".repeat(20000))))
        assertTrue(huge.truncated)
        assertFalse(NotificationRules.isCandidate(huge))
    }

    @Test fun consentControlsRealSpoolExportAndRecognitionUsesTheExistingDurableQueue() = runBlocking {
        val id = UUID.randomUUID().toString()
        val isolated = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = application.getSharedPreferences("notification-$id-$name", mode)
            override fun getNoBackupFilesDir() = File(application.cacheDir, "notification-$id").apply { mkdirs() }
        }
        val diagnostics = DiagnosticManager(isolated)
        val store = application.container.store
        val originalBook = store.catalog.recognitionContext().bookId
        val book = Book(UUID.randomUUID().toString(), "Notification QA ${id.take(8)}")
        assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var permitted = false
        val coordinator = NotificationCaptureCoordinator(scope, { permitted }, { RecognitionContext(book.id) },
            { store.recognitionQueue.enqueue(it); Unit }, diagnostics)
        try {
            diagnostics.setEnabled(true)
            val sample = requireNotNull(readTransactionNotification(notification("com.tencent.mm", "交易提醒：卡号 6222021234567890，金额稍后显示")))
            coordinator.submit(sample)
            suspend fun exportText(): String {
                val output = ByteArrayOutputStream().also { diagnostics.export(it) }
                return buildString {
                    ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.name.endsWith(".gz")) append(GZIPInputStream(zip.readBytes().inputStream()).bufferedReader().readText())
                        }
                    }
                }
            }
            val records = withTimeout(5000) {
                var text = exportText()
                while (!text.contains("notification_sample")) { delay(30); text = exportText() }
                text
            }
            assertTrue(records.contains("金额稍后显示"))
            assertFalse(records.contains("6222021234567890"))
            assertFalse(diagnostics.state.value.paired)
            assertTrue(store.ledger.observeBills(BillQuery(bookId = book.id)).first().isEmpty())
            diagnostics.setEnabled(false)
            coordinator.submit(sample.copy(key = "disabled", fields = mapOf("text" to "交易提醒 DISABLED_NOTIFICATION_SENTINEL")))
            delay(100)
            assertFalse(exportText().contains("DISABLED_NOTIFICATION_SENTINEL"))
            permitted = true
            val payment = requireNotNull(readTransactionNotification(notification("com.tencent.mm", "支付成功 CNY 12.30")))
            coordinator.submit(payment); coordinator.submit(payment)
            val entry = withTimeout(5000) { store.recognitionQueue.observe().first { rows -> rows.any { it.draft.bookId == book.id } }.single { it.draft.bookId == book.id } }
            assertEquals(QueueState.PENDING, entry.state)
            assertEquals(BillSource.NOTIFICATION, entry.draft.source)
            assertEquals(Money(1230, CurrencySpec.CNY), entry.draft.amount)
            assertNull(entry.draft.occurredAt)
            assertTrue(store.ledger.observeBills(BillQuery(bookId = book.id)).first().isEmpty())
        } finally {
            coordinator.close(); scope.cancel(); diagnostics.setEnabled(false)
            store.catalog.selectBook(originalBook)
        }
    }
}
