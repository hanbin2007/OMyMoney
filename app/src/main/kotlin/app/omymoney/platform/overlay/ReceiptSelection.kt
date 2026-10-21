package app.omymoney.platform.overlay

import app.omymoney.data.QueueEntry
import app.omymoney.data.QueueState
import java.time.Duration
import java.time.Instant

internal fun selectReceipt(entries: List<QueueEntry>, currentId: String?, dismissed: Set<String>, now: Instant): QueueEntry? =
    entries.filter { entry ->
        entry.state == QueueState.SAVED && entry.automaticRevision != null && entry.billId != null && entry.id !in dismissed &&
            entry.events.firstOrNull { it.state == QueueState.SAVED }?.at?.let { Duration.between(it, now).seconds in 0..30 } == true
    }.maxByOrNull { it.createdAt } ?: entries.firstOrNull {
        it.id == currentId && it.id !in dismissed && it.state == QueueState.SAVED && it.automaticRevision != null && it.billId != null
    }
