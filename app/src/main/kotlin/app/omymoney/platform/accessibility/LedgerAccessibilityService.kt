package app.omymoney.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import app.omymoney.OMyMoneyApplication
import app.omymoney.data.AutomationSettings
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import app.omymoney.platform.overlay.DraftOverlayHost
import app.omymoney.platform.overlay.OverlayDismissReason
import app.omymoney.core.diagnostics.*
import android.os.SystemClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.*
import java.util.UUID

class LedgerAccessibilityService : AccessibilityService() {
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val store get() = (application as OMyMoneyApplication).container.store
    private val diagnostics get() = (application as OMyMoneyApplication).container.diagnostics
    private val snapshotDiagnostics = object : DiagnosticSink {
        override val enabled get() = diagnostics.enabled && AccessibilityStatus.unlocked(this@LedgerAccessibilityService)
        override fun record(kind: String, fields: JsonObject) {
            if (enabled) diagnostics.recordSafely(kind, fields)
        }
    }
    private val diagnosticWindows = DiagnosticWindowPolicy()
    private fun reader(event: CaptureEvent, source: String = "recognition", maxReadMillis: Long = 250): NodeSnapshotReader {
        val revision = diagnosticWindows.revision()
        return NodeSnapshotReader(snapshotDiagnostics, source, maxReadMillis) {
            event.diagnosticMetadataOnly || revision != diagnosticWindows.revision() ||
                diagnosticWindows.restricted(event.packageName, event.windowId)
        }
    }
    private data class DiagnosticCapture(val event: CaptureEvent, val id: String)
    private val diagnosticRequests = Channel<DiagnosticCapture>(4)
    private val diagnosticWorker = Executors.newSingleThreadExecutor { task -> Thread({
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run()
    }, "diagnostic-nodes") }.asCoroutineDispatcher()
    private val diagnosticScope = CoroutineScope(SupervisorJob() + diagnosticWorker)
    private var settingsJob: Job? = null
    private var overlayJob: Job? = null
    private var unlockRecheck: Job? = null
    private val overlay by lazy { DraftOverlayHost(this, (application as OMyMoneyApplication).container) }
    @Volatile private var policy = AutomationSettings()
    private val capture by lazy {
        CaptureCoordinator(scope, permitted = { name ->
            store.automation.read().settings.permits(name) && AccessibilityStatus.ready(this) && AccessibilityStatus.unlocked(this)
        }, context = { store.catalog.recognitionContext() }, snapshot = { event ->
            if (event.windowClassName?.endsWith("WalletPwdDialog") == true) {
                diagnostics.recordSafely("recognition_snapshot_skipped", buildJsonObject { put("reason", "password_window") }); null
            } else {
                val root = rootInActiveWindow
                if (root == null) null else try {
                    if (root.windowId != event.windowId) null else reader(event).read(root, event.packageName,
                        event.diagnosticMetadataOnly || diagnosticWindows.restricted(event.packageName, event.windowId))
                } finally { @Suppress("DEPRECATION") root.recycle() }
            }
        }, enqueue = { result, event ->
            val boot = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)
            val entry = store.recognitionQueue.recordCapture(result, "boot/$boot/${event.windowId}")
            diagnostics.recordSafely("queue_returned", buildJsonObject {
                put("entryId", entry.id); put("state", entry.state.name); put("dedup", entry.decision.kind.name)
                entry.billId?.let { id -> put("billId", id) }
            })
        }, onError = { error ->
            AccessibilityStatus.mutable.update { it.copy(error = error) }
        }, diagnostics = diagnostics, onNonPaymentWindow = { event ->
            store.recognitionQueue.closeCaptureWindow(event.packageName)
        })
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            diagnostics.recordSafely("screen_state", buildJsonObject {
                put("action", intent?.action); put("unlocked", AccessibilityStatus.unlocked(this@LedgerAccessibilityService))
            })
            capture.interrupt()
            if (!AccessibilityStatus.refresh(this@LedgerAccessibilityService)) overlay.dismiss(OverlayDismissReason.LOCKED)
            unlockRecheck?.cancel()
            if (intent?.action != Intent.ACTION_SCREEN_OFF) unlockRecheck = scope.launch(Dispatchers.Main) {
                repeat(8) {
                    delay(250)
                    if (AccessibilityStatus.refresh(this@LedgerAccessibilityService)) return@launch
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        diagnostics.recordSafely("accessibility_created")
        ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        diagnosticScope.launch {
            for (request in diagnosticRequests) {
                val event = request.event
                val settings = diagnostics.state.value
                if (!settings.enabled || event.packageName !in settings.packages) continue
                try {
                    val reason = when {
                        !AccessibilityStatus.unlocked(this@LedgerAccessibilityService) -> "locked"
                        event.diagnosticMetadataOnly || diagnosticWindows.restricted(event.packageName, event.windowId) -> "password_or_login_window"
                        SystemClock.uptimeMillis() - event.uptimeMillis !in 0..1000 -> "stale_event"
                        else -> null
                    }
                    if (reason != null) { diagnosticSkipped(request.id, reason); continue }
                    val root = rootInActiveWindow
                    if (root == null) { diagnosticSkipped(request.id, "null_root"); continue }
                    try {
                        if (root.windowId != event.windowId || root.packageName?.toString() != event.packageName) diagnosticSkipped(request.id, "stale_window")
                        else reader(event, "diagnostic/${request.id}", 75).read(root, event.packageName)
                    } finally { @Suppress("DEPRECATION") root.recycle() }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { diagnostics.recordSafely("diagnostic_snapshot_error", DiagnosticRedactor.failure(error)) }
            }
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        diagnosticWindows.clear()
        capture.connect()
        diagnostics.recordSafely("accessibility_connected", buildJsonObject { put("unlocked", AccessibilityStatus.unlocked(this@LedgerAccessibilityService)) })
        AccessibilityStatus.mutable.value = CaptureRuntime(connected = true, unlocked = AccessibilityStatus.unlocked(this),
            systemEnabled = AccessibilityStatus.settingEnabled(this))
        settingsJob?.cancel()
        settingsJob = scope.launch {
            combine(store.automation.observe(), diagnostics.state.map { it.enabled to it.packages }.distinctUntilChanged()) { automation, diagnostic ->
                automation to diagnostic
            }.distinctUntilChanged().collect { (state, diagnostic) ->
                if (policy != state.settings) capture.interrupt()
                policy = state.settings
                withContext(Dispatchers.Main) {
                    serviceInfo = serviceInfo.apply {
                        val recognitionPackages = if (policy.enabled && !policy.paused) policy.packages else emptySet()
                        val enabledPackages = recognitionPackages + if (diagnostic.first) diagnostic.second else emptySet()
                        packageNames = enabledPackages.takeIf { it.isNotEmpty() }?.toTypedArray() ?: arrayOf("app.omymoney.disabled")
                        eventTypes = if (diagnostic.first) AccessibilityEvent.TYPES_ALL_MASK else
                            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    }
                }
                diagnostics.recordSafely("capture_configuration", buildJsonObject {
                    put("recognitionEnabled", policy.enabled); put("recognitionPaused", policy.paused)
                    put("recognitionPackages", JsonArray(policy.packages.sorted().map(::JsonPrimitive)))
                    put("diagnosticPackages", JsonArray(diagnostic.second.sorted().map(::JsonPrimitive)))
                    put("allEventTypes", diagnostic.first); put("androidCoalescingMillis", 100)
                    put("accessibilityGranted", AccessibilityStatus.granted(this@LedgerAccessibilityService))
                })
                AccessibilityStatus.mutable.update { it.copy(error = if (state.corrupt) "settings_corrupt" else null) }
            }
        }
        overlayJob?.cancel()
        overlayJob = scope.launch(Dispatchers.Main.immediate) {
            val container = (application as OMyMoneyApplication).container
            combine(store.recognitionQueue.observe(), store.automation.observe(), AccessibilityStatus.runtime, container.appVisible, container.privacyLock.state) {
                entries, settings, runtime, visible, privacy ->
                overlay.update(entries, settings.settings, runtime.unlocked && runtime.available && !privacy.locked, visible)
            }.collect { }
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!AccessibilityStatus.refresh(this)) overlay.dismiss(OverlayDismissReason.LOCKED)
        val name = event.packageName?.toString()
        val metadataOnly = name != null && diagnosticWindows.observe(name, event.windowId, event.eventType,
            event.className?.toString(), event.isPassword)
        val diagnosticState = diagnostics.state.value
        if (diagnosticState.enabled && name in diagnosticState.packages) {
            val id = UUID.randomUUID().toString()
            diagnostics.recordSafely("accessibility_event", buildJsonObject {
                put("eventId", id); put("package", name); put("windowId", event.windowId); put("eventType", event.eventType)
                put("eventTypeName", AccessibilityEvent.eventTypeToString(event.eventType)); put("eventUptime", event.eventTime)
                put("class", event.className?.toString()?.take(512)); put("contentChanges", event.contentChangeTypes)
                put("action", event.action); put("fromIndex", event.fromIndex); put("toIndex", event.toIndex)
                put("addedCount", event.addedCount); put("removedCount", event.removedCount); put("textCount", event.text.size)
                put("passwordEvent", event.isPassword); put("recordCount", event.recordCount)
                put("unlocked", AccessibilityStatus.unlocked(this@LedgerAccessibilityService))
            })
            when {
                metadataOnly -> diagnosticSkipped(id, "password_or_login_window")
                !diagnosticRequests.trySend(DiagnosticCapture(CaptureEvent(requireNotNull(name), event.windowId,
                    event.className?.toString(), event.eventTime), id)).isSuccess -> diagnosticSkipped(id, "bounded_queue_full")
            }
        }
        if (!isRecognitionEvent(event.eventType)) return
        if (name == null || !policy.permits(name)) { capture.interrupt(); return }
        capture.submit(CaptureEvent(name, event.windowId, event.className?.toString(), event.eventTime, metadataOnly))
    }
    override fun onInterrupt() { capture.interrupt(); diagnostics.recordSafely("accessibility_interrupted") }
    override fun onUnbind(intent: Intent?): Boolean {
        diagnosticWindows.clear()
        capture.disconnect(); settingsJob?.cancel()
        diagnostics.recordSafely("accessibility_disconnected")
        overlayJob?.cancel(); overlay.dismiss(OverlayDismissReason.DISCONNECTED)
        AccessibilityStatus.mutable.value = CaptureRuntime(systemEnabled = AccessibilityStatus.settingEnabled(this))
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        diagnostics.recordSafely("accessibility_destroyed")
        capture.disconnect(); scope.cancel(); worker.close()
        diagnosticRequests.close(); diagnosticScope.cancel(); diagnosticWorker.close()
        overlay.dismiss(OverlayDismissReason.DISCONNECTED)
        unregisterReceiver(screenReceiver)
        AccessibilityStatus.mutable.value = CaptureRuntime(systemEnabled = AccessibilityStatus.settingEnabled(this))
        super.onDestroy()
    }
    private fun diagnosticSkipped(id: String, reason: String) = diagnostics.recordSafely("diagnostic_snapshot_skipped", buildJsonObject {
        put("eventId", id); put("reason", reason)
    })
}

internal fun isRecognitionEvent(type: Int) = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
