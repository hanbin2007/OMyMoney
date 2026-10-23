package app.omymoney

import android.app.Application
import android.content.Context
import android.app.Activity
import android.os.Bundle
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.content.ContextCompat
import app.omymoney.data.LedgerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import app.omymoney.platform.accessibility.AccessibilityStatus
import app.omymoney.platform.notification.RecognitionNotification
import app.omymoney.core.diagnostics.recordSafely
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OMyMoneyApplication : Application() {
    val container by lazy { AppContainer(this) }
    override fun onCreate() {
        super.onCreate()
        // Register privacy/visibility callbacks before the first Activity starts,
        // not lazily from its first Compose frame after onStart/onResume.
        container
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            container.diagnostics.recordCrash(error)
            previous?.uncaughtException(thread, error)
        }
        // A killed import may leave its bounded temporary image, never an attachment.
        java.io.File(cacheDir, "ocr").listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".image") }.forEach { it.delete() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        container.refreshLanguageSurfaces()
    }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val notificationCounts = app.omymoney.platform.notification.NotificationCounterStore(appContext)
    val diagnostics = app.omymoney.platform.diagnostics.DiagnosticManager(appContext)
    val updates = app.omymoney.platform.updates.UpdateManager(appContext)
    val store = LedgerStore(context, diagnostics)
    val preferences = app.omymoney.platform.security.PrivacyPreferences(appContext)
    val privacyLock = app.omymoney.platform.security.PrivacyLock(preferences)
    val widgets = app.omymoney.platform.widget.LedgerWidgets(appContext, this)
    val appVisible = MutableStateFlow(false)
    val debugSession = app.omymoney.platform.diagnostics.DebugSessionController(appContext, diagnostics) { appVisible.value }
    private val notificationRefresh = MutableStateFlow(0)
    private val languageTags = MutableStateFlow("")
    val languageChanges = languageTags.asStateFlow()
    private var languageRefresh: Job? = null
    private var scheduleCheck: kotlinx.coroutines.Job? = null
    fun checkSchedules() {
        if (scheduleCheck?.isActive == true) return
        scheduleCheck = applicationScope.launch(Dispatchers.IO) {
            try {
                if (store.schedules.executeDue(java.time.Instant.now()).moreDue) {
                    app.omymoney.platform.schedule.ScheduledBillWork.requestCheck(appContext)
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { app.omymoney.platform.schedule.ScheduledBillWork.requestCheck(appContext) }
        }
    }
    fun refreshNotificationPermission() { notificationRefresh.value++ }
    fun refreshLanguageSurfaces() {
        val requested = if (android.os.Build.VERSION.SDK_INT >= 33) LocaleManagerCompat.getApplicationLocales(appContext).toLanguageTags()
            else AppCompatDelegate.getApplicationLocales().toLanguageTags()
        languageRefresh?.cancel()
        languageRefresh = applicationScope.launch {
            // AppCompat persists pre-33 choices asynchronously; services must not read the previous choice.
            repeat(50) {
                val stored = withContext(Dispatchers.IO) { LocaleManagerCompat.getApplicationLocales(appContext).toLanguageTags() }
                if (stored == requested) {
                    val tags = withContext(Dispatchers.IO) { ContextCompat.getContextForLanguage(appContext).resources.configuration.locales.toLanguageTags() }
                    if (languageTags.value != tags) {
                        languageTags.value = tags
                        refreshNotificationPermission()
                        widgets.refresh()
                    }
                    return@launch
                }
                delay(100)
            }
        }
    }
    init {
        AccessibilityStatus.observe(appContext, diagnostics)
        widgets.observeInstalled()
        applicationScope.launch { preferences.state.collect { if (it.lockEnabled) widgets.redact() } }
        app.omymoney.platform.schedule.ScheduledBillWork.enqueue(appContext)
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private val resumed = mutableSetOf<Activity>()
            private val started = mutableSetOf<Activity>()
            init {
                applicationScope.launch { preferences.state.collect { options ->
                    started.forEach { applySecureWindow(it, options) }
                } }
            }
            override fun onActivityResumed(activity: Activity) {
                AccessibilityStatus.refresh(activity)
                AccessibilityStatus.refreshAccess(activity)
                app.omymoney.platform.notification.NotificationCaptureStatus.requestConnection(activity)
                resumed += activity; appVisible.value = true; refreshNotificationPermission(); checkSchedules()
                refreshLanguageSurfaces()
                diagnostics.recordRuntime("activity_resumed", debugSession.state.value.active, true)
            }
            override fun onActivityPaused(activity: Activity) {
                resumed -= activity; appVisible.value = resumed.isNotEmpty()
                diagnostics.recordSafely("activity_paused", buildJsonObject { put("appVisible", appVisible.value) })
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) { applySecureWindow(activity, preferences.state.value) }
            override fun onActivityStarted(activity: Activity) {
                started += activity
                applySecureWindow(activity, preferences.state.value)
                if (appContext.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) privacyLock.lock(true)
            }
            override fun onActivityStopped(activity: Activity) {
                started -= activity
                if (started.isEmpty() && !activity.isChangingConfigurations) privacyLock.lock()
                diagnostics.recordSafely("activity_stopped", buildJsonObject { put("configurationChange", activity.isChangingConfigurations) })
            }
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) { resumed -= activity; appVisible.value = resumed.isNotEmpty() }
        })
        androidx.core.content.ContextCompat.registerReceiver(appContext, object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) { privacyLock.lock(cancelAuthentication = true); widgets.redact() }
                else applicationScope.launch { widgets.refresh() }
            }
        }, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF); addAction(android.content.Intent.ACTION_USER_PRESENT)
            addAction(android.content.Intent.ACTION_TIME_CHANGED); addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED); addAction(android.content.Intent.ACTION_DATE_CHANGED)
        }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        applicationScope.launch {
            val notifications = RecognitionNotification(context.applicationContext)
            try {
                combine(store.recognitionQueue.observePendingCount(), store.automation.observe(), AccessibilityStatus.runtime,
                    app.omymoney.platform.notification.NotificationCaptureStatus.runtime, notificationRefresh) {
                    count, settings, runtime, notificationRuntime, _ ->
                    notifications.refresh(count, settings.settings, runtime, notificationRuntime)
                }.collect { }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { AccessibilityStatus.mutable.value = AccessibilityStatus.mutable.value.copy(error = "storage") }
        }
    }
    private fun applySecureWindow(activity: Activity, options: app.omymoney.platform.security.PrivacyOptions) {
        if (options.secureScreens || options.lockEnabled) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
}
