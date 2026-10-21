package app.omymoney.platform.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.omymoney.OMyMoneyApplication
import app.omymoney.core.diagnostics.*
import app.omymoney.core.recognition.*
import app.omymoney.data.AutomationSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.time.Instant

data class NotificationCaptureRuntime(val connected: Boolean = false, val error: String? = null)

object NotificationCaptureStatus {
    internal val mutable = MutableStateFlow(NotificationCaptureRuntime())
    val runtime = mutable.asStateFlow()
    fun granted(context: Context): Boolean {
        val component = ComponentName(context, TransactionNotificationListener::class.java)
        return if (Build.VERSION.SDK_INT >= 27) context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        else Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == component }
    }
    fun settingsIntent(context: Context): Intent = (if (Build.VERSION.SDK_INT >= 30)
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, TransactionNotificationListener::class.java).flattenToString())
        else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun openSettings(context: Context) {
        try { context.startActivity(settingsIntent(context)) }
        catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    fun requestConnection(context: Context) {
        if (granted(context) && !runtime.value.connected) {
            try { NotificationListenerService.requestRebind(ComponentName(context, TransactionNotificationListener::class.java)) }
            catch (_: IllegalStateException) { mutable.value = NotificationCaptureRuntime(error = "notification_binding_failed") }
            catch (_: SecurityException) { mutable.value = NotificationCaptureRuntime(error = "notification_binding_denied") }
        }
    }
}

class TransactionNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val container get() = (application as OMyMoneyApplication).container
    @Volatile private var policy = AutomationSettings()
    @Volatile private var connected = false
    private val coordinator by lazy {
        NotificationCaptureCoordinator(scope, permitted = { name ->
            connected && NotificationCaptureStatus.granted(this) && container.store.automation.read().settings.permits(name)
        }, context = { container.store.catalog.recognitionContext() }, enqueue = { result ->
            val entry = container.store.recognitionQueue.enqueue(result)
            container.diagnostics.recordSafely("notification_queue_returned", buildJsonObject {
                put("entryId", entry.id); put("state", entry.state.name); put("dedup", entry.decision.kind.name)
            })
        }, diagnostics = object : DiagnosticSink {
            override val enabled get() = connected && NotificationCaptureStatus.granted(this@TransactionNotificationListener) && container.diagnostics.enabled
            override fun record(kind: String, fields: JsonObject) { if (enabled) container.diagnostics.recordSafely(kind, fields) }
        }, onError = { error -> NotificationCaptureStatus.mutable.update { it.copy(error = error) } },
            initialCounts = container.notificationCounts.state.value, onCounts = container.notificationCounts::update)
    }
    override fun onCreate() {
        super.onCreate()
        scope.launch { container.store.automation.observe().collect { policy = it.settings } }
    }
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        NotificationCaptureStatus.mutable.value = NotificationCaptureRuntime(connected = true)
        container.refreshNotificationPermission()
        container.diagnostics.recordSafely("notification_listener_connected")
        // Do not replay active/history notifications when access is granted or the process reconnects.
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!connected || sbn == null || sbn.packageName !in RuleRegistry.supportedPackages || sbn.user != Process.myUserHandle()) return
        if (!policy.permits(sbn.packageName) && !container.diagnostics.enabled) return
        if (!NotificationCaptureStatus.granted(this)) return
        try {
            readTransactionNotification(sbn)?.let(coordinator::submit)
        } catch (error: Exception) {
            // Never log third-party exception messages, notification keys or arbitrary extras.
            container.diagnostics.recordSafely("notification_read_failed", buildJsonObject { put("type", error.javaClass.name) })
        }
    }
    override fun onListenerDisconnected() {
        connected = false
        NotificationCaptureStatus.mutable.value = NotificationCaptureRuntime()
        container.refreshNotificationPermission()
        container.diagnostics.recordSafely("notification_listener_disconnected")
        NotificationCaptureStatus.requestConnection(this)
        super.onListenerDisconnected()
    }
    override fun onDestroy() {
        connected = false
        coordinator.close(); scope.cancel()
        NotificationCaptureStatus.mutable.value = NotificationCaptureRuntime()
        super.onDestroy()
    }
}

internal fun readTransactionNotification(sbn: StatusBarNotification): TransactionNotification? {
    if (sbn.packageName !in RuleRegistry.supportedPackages) return null
    val notification = sbn.notification
    if (notification.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return null
    val extras = notification.extras ?: return null
    var truncated = false
    var remaining = NotificationRules.MAX_CHARS
    val fields = linkedMapOf<String, String>()
    fun add(key: String, value: CharSequence?) {
        if (value == null) return
        val limit = minOf(4096, remaining)
        if (value.length > limit) truncated = true
        val bounded = value.subSequence(0, minOf(value.length, limit)).toString().trim()
        remaining -= bounded.length
        if (bounded.isNotEmpty()) fields[key] = bounded
    }
    add("title", extras.getCharSequence(Notification.EXTRA_TITLE))
    add("text", extras.getCharSequence(Notification.EXTRA_TEXT))
    add("bigText", extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
    extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { lines ->
        if (lines.size > 16) truncated = true
        lines.take(16).forEachIndexed { index, line -> add("line$index", line) }
    }
    return TransactionNotification(sbn.packageName, sbn.key, Instant.ofEpochMilli(sbn.postTime), fields, truncated)
}
