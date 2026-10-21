package app.omymoney

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.*
import app.omymoney.data.*
import app.omymoney.platform.notification.NotificationCaptureStatus
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly enabled on an isolated emulator with the synthetic com.unionpay QA fixture. */
class NotificationListenerSystemTest {
    @Test fun systemDeliveredSyntheticNotificationReachesPendingQueueWithoutAccessibility(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("notificationSystemQa") == "true")
        val container = ApplicationProvider.getApplicationContext<OMyMoneyApplication>().container
        assertTrue(NotificationCaptureStatus.granted(ApplicationProvider.getApplicationContext()))
        NotificationCaptureStatus.requestConnection(ApplicationProvider.getApplicationContext())
        // Instrumentation force-stops the target; Android can defer bound-service restart by ten seconds.
        withTimeout(30000) { NotificationCaptureStatus.runtime.first { it.connected } }
        val original = container.store.automation.read().settings
        val originalBook = container.store.catalog.recognitionContext().bookId
        val recording = container.diagnostics.enabled
        val book = Book(UUID.randomUUID().toString(), "Synthetic QA ${UUID.randomUUID().toString().take(8)}")
        assertTrue(container.store.catalog.saveBook(book) is CatalogResult.Saved)
        try {
            container.diagnostics.setEnabled(false)
            container.store.automation.setEnabled(true)
            container.store.automation.setPaused(false)
            container.store.automation.setPackage("com.unionpay", true)
            delay(200)
            val countsBefore = container.notificationCounts.state.value
            val command = "am start -W -n com.unionpay/qa.NotificationSource --ei id ${System.currentTimeMillis().rem(100000)}"
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
            val entry = withTimeout(10000) { container.store.recognitionQueue.observe().first { rows -> rows.any { it.draft.bookId == book.id } }.single { it.draft.bookId == book.id } }
            assertEquals(BillSource.NOTIFICATION, entry.draft.source)
            assertEquals("com.unionpay", entry.draft.sourcePackage)
            assertEquals(Money(789, CurrencySpec.CNY), entry.draft.amount)
            assertEquals(QueueState.PENDING, entry.state)
            assertTrue(container.store.ledger.observeBills(BillQuery(bookId = book.id)).first().isEmpty())
            withTimeout(10000) {
                container.notificationCounts.state.first {
                    it.received > countsBefore.received && it.candidates > countsBefore.candidates
                }
            }
        } finally {
            container.store.automation.setEnabled(original.enabled)
            container.store.automation.setPaused(original.paused)
            container.store.automation.setPackage("com.unionpay", "com.unionpay" in original.packages)
            container.store.catalog.selectBook(originalBook)
            container.diagnostics.setEnabled(recording)
        }
    }
}
