package app.omymoney.platform.accessibility

import app.omymoney.core.recognition.*
import app.omymoney.core.recognition.rules.*
import app.omymoney.data.QueueException
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import app.omymoney.core.diagnostics.*
import kotlinx.serialization.json.*

data class CaptureEvent(val packageName: String, val windowId: Int, val windowClassName: String?, val uptimeMillis: Long,
    val diagnosticMetadataOnly: Boolean = false)

class CaptureCoordinator(
    scope: CoroutineScope,
    private val permitted: suspend (String) -> Boolean,
    private val context: suspend () -> RecognitionContext,
    private val snapshot: suspend (CaptureEvent) -> UiSnapshot?,
    private val enqueue: suspend (RecognitionResult, CaptureEvent) -> Unit,
    private val onError: (String?) -> Unit = {},
    private val uptime: () -> Long = android.os.SystemClock::uptimeMillis,
    private val normalDelayMillis: Long = 450,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val onNonPaymentWindow: suspend (CaptureEvent) -> Unit = {},
) {
    private data class Request(val generation: Long, val event: CaptureEvent?)
    private val requests = Channel<Request>(Channel.CONFLATED)
    @Volatile private var generation = 0L
    @Volatile private var connectedSince: Long? = null
    private val recent = java.util.concurrent.ConcurrentHashMap<String, Long>()
    init {
        scope.launch {
            requests.receiveAsFlow().collectLatest { request ->
                val event = request.event ?: return@collectLatest
                trace("capture_started", request)
                try {
                    if (!eligible(request)) return@collectLatest
                    val context = context()
                    val contentDiagnostics = if (event.diagnosticMetadataOnly) DiagnosticSink.NONE else diagnostics
                    val session = if (event.packageName == AlipayRules.PACKAGE) AlipayCaptureSession(context, contentDiagnostics) else null
                    val rounds = if (session == null) listOf(normalDelayMillis) else listOf(160L, 450L)
                    var elapsed = 0L
                    for ((index, target) in rounds.withIndex()) {
                        delay((target - elapsed).coerceAtLeast(0)); elapsed = target
                        if (!eligible(request)) break
                        val page = snapshot(event) ?: run { trace("capture_snapshot_null", request); break }
                        currentCoroutineContext().ensureActive()
                        if (!eligible(request) || page.packageName != event.packageName || page.windowId != event.windowId) {
                            trace("capture_snapshot_stale", request); break
                        }
                        val window = UiSnapshot(page.packageName, page.windowId, page.capturedAt, page.nodes, page.truncated, event.windowClassName)
                        val result = session?.recognize(window, if (index == 0) AlipayCapturePass.FAST else AlipayCapturePass.FALLBACK)
                            ?: if (session == null) RuleEngine(RuleRegistry.enabledRules(setOf(event.packageName), context), contentDiagnostics).recognize(window) else null
                        diagnostics.recordSafely("capture_result") { buildJsonObject {
                            put("generation", request.generation); put("round", index); put("package", event.packageName)
                            // Page evidence is logged only by the privacy-gated snapshot reader.
                            put("resultKind", result?.javaClass?.simpleName)
                        } }
                        if (result == null || result is RecognitionResult.Unsupported) {
                            if (window.truncated) onError("snapshot_limit")
                            if (index == rounds.lastIndex && !window.truncated && isPaymentEntryWindow(window)) {
                                onNonPaymentWindow(event)
                                recent.clear()
                            }
                            continue
                        }
                        val digest = MessageDigest.getInstance("SHA-256").digest((context.toString() + window.packageName +
                            window.windowId + window.windowClassName + window.nodes.toString()).toByteArray()).joinToString("") { "%02x".format(it) }
                        val now = uptime()
                        recent.entries.removeAll { now - it.value > 5000 }
                        if (recent.containsKey(digest)) { trace("capture_recent_duplicate", request); break }
                        if (!eligible(request)) break
                        enqueue(result, event)
                        trace("capture_enqueued", request)
                        recent[digest] = now
                        while (recent.size > 32) recent.remove(recent.keys.first())
                        onError(null)
                        break
                    }
                } catch (error: CancellationException) { trace("capture_cancelled", request); throw error
                } catch (error: QueueException) { diagnostics.recordSafely("capture_queue_error", DiagnosticRedactor.failure(error)); onError(error.code)
                } catch (error: Exception) { diagnostics.recordSafely("capture_error", DiagnosticRedactor.failure(error)); onError("capture_failed") }
            }
        }
    }
    @Synchronized fun connect() { generation++; connectedSince = uptime(); recent.clear(); requests.trySend(Request(generation, null)) }
    @Synchronized fun disconnect() { connectedSince = null; interrupt() }
    @Synchronized fun interrupt() { generation++; requests.trySend(Request(generation, null)) }
    @Synchronized fun submit(event: CaptureEvent) {
        if (event.packageName !in RuleRegistry.supportedPackages) { interrupt(); return }
        // Invalidate immediately, even if node IPC is blocking the worker's collector.
        generation++
        trace("capture_submitted", Request(generation, event))
        requests.trySend(Request(generation, event))
    }
    private suspend fun eligible(request: Request): Boolean {
        val since = connectedSince
        val event = request.event
        val reason = when {
            since == null -> "disconnected"
            event == null -> "interrupt"
            request.generation != generation -> "superseded"
            event.uptimeMillis < since -> "before_connection"
            uptime() - event.uptimeMillis !in 0..5000 -> "event_age"
            event.packageName !in RuleRegistry.supportedPackages -> "unsupported_package"
            !permitted(event.packageName) -> "permission_or_policy"
            else -> null
        }
        if (reason != null) trace("capture_rejected", request, reason)
        return reason == null
    }
    private fun trace(kind: String, request: Request, reason: String? = null) = diagnostics.recordSafely(kind, buildJsonObject {
        put("generation", request.generation); put("currentGeneration", generation)
        request.event?.let { put("package", it.packageName); put("windowId", it.windowId); put("eventUptime", it.uptimeMillis) }
        reason?.let { put("reason", it) }
    })
}

private fun isPaymentEntryWindow(snapshot: UiSnapshot): Boolean {
    val labels = setOf("确认付款", "立即付款", "扫一扫", "扫码付款", "输入付款金额", "支付密码", "收付款", "付款码", "收款码")
    val takeoutLabels = if (snapshot.packageName == MeituanTakeoutRules.PACKAGE)
        setOf("确认支付", "立即支付", "去支付", "提交订单") else emptySet()
    return snapshot.nodes.any { node -> listOfNotNull(node.text, node.description).any { it.trim() in labels || it.trim() in takeoutLabels } }
}
