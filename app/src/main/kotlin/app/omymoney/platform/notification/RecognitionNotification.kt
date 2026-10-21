package app.omymoney.platform.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.omymoney.MainActivity
import app.omymoney.OMyMoneyApplication
import app.omymoney.android.R
import app.omymoney.data.AutomationSettings
import app.omymoney.platform.accessibility.CaptureRuntime
import app.omymoney.platform.accessibility.AccessibilityStatus
import kotlinx.coroutines.launch

class RecognitionNotification(private val context: Context) {
    fun permissionGranted() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun channelBlocked() = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
    fun allowed() = permissionGranted() && NotificationManagerCompat.from(context).areNotificationsEnabled() && !channelBlocked()

    fun refresh(pendingCount: Int, settings: AutomationSettings, runtime: CaptureRuntime,
        notificationRuntime: NotificationCaptureRuntime = NotificationCaptureRuntime()) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!allowed() || pendingCount == 0 && !settings.enabled) { manager.cancel(ID); return }
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.review_notification_channel), NotificationManager.IMPORTANCE_LOW)
            .apply { lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE })
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_QUEUE)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val status = when {
            !settings.enabled -> R.string.automation_disabled
            settings.paused -> R.string.automation_paused
            !runtime.available && notificationRuntime.connected -> when {
                notificationRuntime.error != null -> R.string.automation_capture_error
                settings.packages.isEmpty() -> R.string.automation_no_apps
                else -> R.string.notification_capture_active
            }
            !runtime.available -> if (AccessibilityStatus.granted(context)) R.string.automation_binding else R.string.automation_not_granted
            !runtime.unlocked -> R.string.automation_locked
            runtime.error != null -> R.string.automation_capture_error
            settings.packages.isEmpty() -> R.string.automation_no_apps
            else -> R.string.automation_active
        }
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.review_pending_count, pendingCount))
            .setContentText(context.getString(status)).setContentIntent(open).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(settings.enabled && (runtime.available || notificationRuntime.connected))
        if (settings.enabled) {
            val action = if (settings.paused) ACTION_RESUME else ACTION_PAUSE
            val intent = PendingIntent.getBroadcast(context, 1, Intent(context, RecognitionNotificationReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, context.getString(if (settings.paused) R.string.review_resume else R.string.automation_pause), intent)
        }
        try { manager.notify(ID, builder.build()) } catch (_: SecurityException) { manager.cancel(ID) }
    }
    companion object {
        const val CHANNEL = "recognition"
        const val ID = 1101
        const val ACTION_PAUSE = "app.omymoney.PAUSE_CAPTURE"
        const val ACTION_RESUME = "app.omymoney.RESUME_CAPTURE"
    }
}

class RecognitionNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val paused = when (intent?.action) {
            RecognitionNotification.ACTION_PAUSE -> true
            RecognitionNotification.ACTION_RESUME -> false
            else -> return
        }
        val pending = goAsync()
        val container = (context.applicationContext as OMyMoneyApplication).container
        container.applicationScope.launch {
            try { container.store.automation.setPaused(paused) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { AccessibilityStatus.mutable.value = AccessibilityStatus.mutable.value.copy(error = "storage") }
            finally { pending.finish() }
        }
    }
}
