package app.omymoney.platform.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import app.omymoney.core.diagnostics.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CaptureRuntime(val connected: Boolean = false, val unlocked: Boolean = true, val error: String? = null,
    val systemEnabled: Boolean? = null, val systemBound: Boolean = connected) {
    val available: Boolean get() = connected && systemBound && systemEnabled != false
}

object AccessibilityStatus {
    internal val mutable = MutableStateFlow(CaptureRuntime())
    val runtime = mutable.asStateFlow()
    private var observing = false
    fun refresh(context: Context): Boolean = unlocked(context).also { current ->
        mutable.update { it.copy(unlocked = current) }
    }
    fun settingEnabled(context: Context): Boolean? = try {
        val expected = ComponentName(context, LedgerAccessibilityService::class.java)
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == expected }
    } catch (_: RuntimeException) { null }

    fun granted(context: Context): Boolean = settingEnabled(context) ?: bound(context)

    fun bound(context: Context): Boolean = try {
        val expected = ComponentName(context, LedgerAccessibilityService::class.java)
        context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == expected
            }
    } catch (_: RuntimeException) { false }

    fun ready(context: Context): Boolean = runtime.value.available && bound(context)

    fun refreshAccess(context: Context): CaptureRuntime {
        val enabled = settingEnabled(context)
        val bound = bound(context)
        mutable.update { it.copy(systemEnabled = enabled, systemBound = bound) }
        return runtime.value
    }

    @Synchronized fun observe(context: Context, diagnostics: DiagnosticSink) {
        if (observing) return
        observing = true
        val application = context.applicationContext
        var previous: Pair<Boolean?, Boolean>? = null
        fun changed() {
            val state = refreshAccess(application)
            val value = state.systemEnabled to state.systemBound
            if (value == previous) return
            previous = value
            diagnostics.recordSafely("accessibility_status") { buildJsonObject {
                state.systemEnabled?.let { put("settingEnabled", it) }
                put("settingReadable", state.systemEnabled != null); put("systemBound", state.systemBound)
                put("callbackConnected", state.connected); put("available", state.available)
            } }
        }
        try {
            application.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false, object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) = changed() })
            val manager = application.getSystemService(AccessibilityManager::class.java)
            manager.addAccessibilityStateChangeListener { changed() }
            if (Build.VERSION.SDK_INT >= 33) manager.addAccessibilityServicesStateChangeListener(
                ContextCompat.getMainExecutor(application)) { changed() }
        } catch (error: RuntimeException) { diagnostics.recordSafely("accessibility_observer_error", DiagnosticRedactor.failure(error)) }
        changed()
    }
    fun unlocked(context: Context): Boolean = !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
        context.getSystemService(PowerManager::class.java).isInteractive
}
