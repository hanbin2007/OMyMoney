package app.omymoney

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.omymoney.platform.notification.NotificationCounterStore
import app.omymoney.platform.notification.NotificationReadCounts
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class NotificationCounterStoreTest {
    private class IsolatedContext : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        val prefix = "notification-counts-${UUID.randomUUID()}"
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("$prefix-$name", mode)
    }

    @Test fun eventCountsSurviveStoreRecreationWithoutStoringNotificationContent() {
        val context = IsolatedContext()
        try {
            NotificationCounterStore(context).update(NotificationReadCounts(received = 42, candidates = 7, filtered = 30, dropped = 5))
            val after = NotificationCounterStore(context).state.value
            assertEquals(42L, after.received)
            assertEquals(7L, after.candidates)
            assertEquals(30L, after.filtered)
            assertEquals(5L, after.dropped)
            assertTrue(context.getSharedPreferences("notification_read_counts", Context.MODE_PRIVATE).all.values.all { it is Long })
        } finally { context.getSharedPreferences("notification_read_counts", Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun corruptOrNegativeStatisticsDoNotPreventApplicationStartup() {
        val context = IsolatedContext()
        try {
            context.getSharedPreferences("notification_read_counts", Context.MODE_PRIVATE).edit()
                .putString("received", "bad").putLong("candidates", -1).commit()
            val after = NotificationCounterStore(context).state.value
            assertEquals(0L, after.received)
            assertEquals(0L, after.candidates)
        } finally { context.getSharedPreferences("notification_read_counts", Context.MODE_PRIVATE).edit().clear().commit() }
    }
}
