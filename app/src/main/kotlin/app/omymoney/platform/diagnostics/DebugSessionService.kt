package app.omymoney.platform.diagnostics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.omymoney.MainActivity
import app.omymoney.OMyMoneyApplication
import app.omymoney.android.R
import app.omymoney.core.diagnostics.DiagnosticRedactor
import app.omymoney.core.diagnostics.recordSafely
import app.omymoney.platform.accessibility.AccessibilityStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class DebugSessionState(val starting: Boolean = false, val active: Boolean = false, val stopping: Boolean = false, val error: Boolean = false)

/** Intentionally process-local: neither process restart nor a worker can resume a session. */
class DebugSessionController(private val context: Context, private val diagnostics: DiagnosticManager,
    private val visible: () -> Boolean) {
    private val mutable = MutableStateFlow(DebugSessionState())
    val state = mutable.asStateFlow()
    internal var requested = false
        private set

    fun start() {
        check(!state.value.stopping) { "debug_stopping" }
        check(visible() && diagnostics.enabled && AccessibilityStatus.ready(context) &&
            DebugSessionService.notificationsAllowed(context)) { "debug_prerequisites" }
        if (requested) return
        requested = true
        mutable.value = DebugSessionState(starting = true)
        diagnostics.recordSafely("debug_session_requested")
        try { ContextCompat.startForegroundService(context, Intent(context, DebugSessionService::class.java)) }
        catch (error: Exception) {
            ended(failed = true)
            diagnostics.recordSafely("debug_session_start_failed", DiagnosticRedactor.failure(error))
            throw error
        }
    }

    suspend fun stop() {
        if (state.value.stopping) return
        val awaitingStart = state.value.starting
        diagnostics.recordSafely("debug_session_stop_requested", buildJsonObject { put("source", "app") })
        stopping()
        // Cancelling a foreground start before onStartCommand can still trigger
        // Android's foreground-start timeout. Let it publish then immediately stop.
        if (!awaitingStart && !context.stopService(Intent(context, DebugSessionService::class.java))) completeStop()
    }

    internal fun started() { mutable.value = DebugSessionState(active = true) }
    internal fun ended(failed: Boolean = false) { requested = false; mutable.value = DebugSessionState(error = failed) }
    internal fun stopping() {
        requested = false
        diagnostics.stopRecordingNow()
        mutable.value = DebugSessionState(stopping = true, error = state.value.error)
    }
    internal suspend fun completeStop() {
        var failed = state.value.error
        try {
            diagnostics.setEnabled(false)
            diagnostics.flush()
            if (diagnostics.state.value.paired) DiagnosticWork.request(context)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { ended(failed) }
    }
}

class DebugSessionService : Service() {
    private val container get() = (application as OMyMoneyApplication).container
    private val diagnostics get() = container.diagnostics
    private val controller get() = container.debugSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sampling: Job? = null
    private var ending = false
    private var stopReason = "service_destroyed"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Complete the Android foreground-start contract even if a stop raced the pending start.
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, notification)
        } catch (error: Exception) {
            diagnostics.recordSafely("debug_session_foreground_failed", DiagnosticRedactor.failure(error))
            controller.ended(failed = true)
            finish("foreground_failed")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) { finish("notification_stop"); return START_NOT_STICKY }
        if (!controller.requested || !diagnostics.enabled) { finish("not_requested"); return START_NOT_STICKY }
        if (sampling != null) return START_NOT_STICKY
        try { diagnostics.markDebugSessionStarted() }
        catch (error: Exception) {
            diagnostics.recordSafely("debug_session_start_failed", DiagnosticRedactor.failure(error))
            controller.ended(failed = true)
            finish("storage_failed"); return START_NOT_STICKY
        }
        controller.started()
        diagnostics.recordSafely("debug_session_started")
        scope.launch {
            combine(diagnostics.state.map { it.enabled }.distinctUntilChanged(), AccessibilityStatus.runtime) { enabled, runtime ->
                when { !enabled -> "recording_disabled"; !runtime.available -> "accessibility_disconnected"; else -> null }
            }.collect { reason -> if (reason != null) finish(reason) }
        }
        sampling = scope.launch {
            while (isActive && !ending) {
                if (!AccessibilityStatus.granted(this@DebugSessionService) || !notificationsAllowed(this@DebugSessionService)) {
                    finish("permission_revoked"); break
                }
                diagnostics.recordRuntime("debug_heartbeat", foregroundSession = true, appVisible = container.appVisible.value)
                try {
                    diagnostics.flush()
                    if (diagnostics.state.value.paired) DiagnosticWork.request(this@DebugSessionService)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    diagnostics.recordSafely("debug_session_storage_failed", DiagnosticRedactor.failure(error))
                    controller.ended(failed = true)
                    finish("storage_failed"); break
                }
                delay(60_000)
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(reason: String) {
        if (ending) return
        ending = true
        stopReason = reason
        diagnostics.recordSafely("debug_session_stopped", buildJsonObject { put("reason", reason) })
        controller.stopping()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        diagnostics.recordSafely("debug_task_removed")
        diagnostics.recordRuntime("task_removed", foregroundSession = true, appVisible = false)
        // No restart, alarm or new work is scheduled to defeat an explicit OS stop.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (!ending) diagnostics.recordSafely("debug_session_stopped", buildJsonObject { put("reason", stopReason) })
        controller.stopping()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        container.applicationScope.launch { controller.completeStop() }
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.debug_session_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DIAGNOSTICS)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, ID, Intent(this, DebugSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.debug_session_active)).setContentText(getString(R.string.debug_session_notification))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.debug_session_stop), stop).build()
    }

    companion object {
        const val ID = 1102
        const val CHANNEL = "debug-session"
        const val ACTION_STOP = "app.omymoney.STOP_DEBUG_SESSION"
        fun notificationsAllowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
}
