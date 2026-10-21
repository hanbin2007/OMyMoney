package app.omymoney.platform.notification

import app.omymoney.core.diagnostics.*
import app.omymoney.core.recognition.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

class NotificationCaptureCoordinator(scope: CoroutineScope,
    private val permitted: suspend (String) -> Boolean,
    private val context: suspend () -> RecognitionContext,
    private val enqueue: suspend (RecognitionResult) -> Unit,
    private val diagnostics: DiagnosticSink,
    private val onError: (String?) -> Unit = {},
    initialCounts: NotificationReadCounts = NotificationReadCounts(),
    private val onCounts: (NotificationReadCounts) -> Unit = {},
) {
    private val requests = Channel<TransactionNotification>(64)
    private val recorded = linkedSetOf<String>()
    private val countsLock = Any()
    private var counts = initialCounts
    init {
        scope.launch {
            for (sample in requests) {
                try {
                    val capture = permitted(sample.packageName)
                    if (!capture && !diagnostics.enabled) { changeCounts { it.copy(suppressed = it.suppressed.next()) }; continue }
                    if (!NotificationRules.isCandidate(sample)) {
                        changeCounts { it.copy(filtered = it.filtered.next()) }
                        diagnostics.recordSafely("notification_sample_skipped", buildJsonObject {
                            put("package", sample.packageName); put("reason", if (sample.truncated) "content_limit" else "not_transaction_or_sensitive")
                        })
                        reportCounts()
                        continue
                    }
                    changeCounts { it.copy(candidates = it.candidates.next()) }
                    val result = NotificationRules.recognize(sample, if (capture) context() else RecognitionContext())
                    NotificationRules.recordSample(sample, result, diagnostics)
                    reportCounts()
                    if (!capture || result is RecognitionResult.Unsupported || sample.identity in recorded) continue
                    // Re-read after suspension: stopping capture must win over queued notifications.
                    if (!permitted(sample.packageName)) continue
                    enqueue(result)
                    recorded += sample.identity
                    while (recorded.size > 256) recorded.remove(recorded.first())
                    onError(null)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    changeCounts { it.copy(failed = it.failed.next()) }
                    diagnostics.recordSafely("notification_capture_error", DiagnosticRedactor.failure(error))
                    reportCounts()
                    onError("notification_capture_failed")
                }
            }
        }
    }

    fun submit(sample: TransactionNotification): Boolean {
        changeCounts { it.copy(received = it.received.next()) }
        return requests.trySend(sample).isSuccess.also { accepted ->
            if (!accepted) {
                changeCounts { it.copy(dropped = it.dropped.next()) }
                diagnostics.recordSafely("notification_sample_dropped", buildJsonObject { put("reason", "bounded_queue_full") })
                reportCounts()
            }
        }
    }
    fun close() { requests.close() }

    private fun changeCounts(change: (NotificationReadCounts) -> NotificationReadCounts) = synchronized(countsLock) {
        counts = change(counts)
        try { onCounts(counts) } catch (_: Exception) { /* Statistics must not interrupt bookkeeping. */ }
    }
    private fun reportCounts() {
        val value = synchronized(countsLock) { counts }
        diagnostics.recordSafely("notification_capture_counts") { buildJsonObject {
            put("received", value.received); put("candidates", value.candidates); put("filtered", value.filtered)
            put("dropped", value.dropped); put("suppressed", value.suppressed); put("failed", value.failed)
        } }
    }
    private fun Long.next() = if (this == Long.MAX_VALUE) this else this + 1
}
