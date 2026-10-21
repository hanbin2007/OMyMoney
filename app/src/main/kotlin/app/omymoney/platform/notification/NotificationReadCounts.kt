package app.omymoney.platform.notification

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Counts processing events, not unique notifications, uploaded batches or saved bills. */
data class NotificationReadCounts(val received: Long = 0, val candidates: Long = 0, val filtered: Long = 0,
    val dropped: Long = 0, val suppressed: Long = 0, val failed: Long = 0)

class NotificationCounterStore(context: Context) {
    private val preferences = context.getSharedPreferences(KEY, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read(context))
    val state = mutable.asStateFlow()

    @Synchronized fun update(counts: NotificationReadCounts) {
        mutable.value = counts
        preferences.edit().putLong("received", counts.received).putLong("candidates", counts.candidates)
            .putLong("filtered", counts.filtered).putLong("dropped", counts.dropped)
            .putLong("suppressed", counts.suppressed).putLong("failed", counts.failed).apply()
    }

    companion object {
        private const val KEY = "notification_read_counts"
        fun read(context: Context): NotificationReadCounts {
            val preferences = context.getSharedPreferences(KEY, Context.MODE_PRIVATE)
            fun value(key: String) = runCatching { preferences.getLong(key, 0).coerceAtLeast(0) }.getOrDefault(0)
            return NotificationReadCounts(value("received"), value("candidates"), value("filtered"),
                value("dropped"), value("suppressed"), value("failed"))
        }
    }
}
