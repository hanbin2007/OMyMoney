package app.omymoney.platform.security

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AuthenticationResult { SUCCESS, CANCELLED, FAILED }
enum class LockError { NO_CREDENTIAL, CANCELLED, FAILED, STORAGE }
data class LockState(val enabled: Boolean = false, val locked: Boolean = false, val authenticating: Boolean = false, val error: LockError? = null)
interface DeviceAuthentication {
    fun available(activity: ComponentActivity): Boolean
    fun authenticate(activity: ComponentActivity, result: (AuthenticationResult) -> Unit): () -> Unit
}

class PrivacyLock(private val preferences: PrivacyPreferences, private val authentication: DeviceAuthentication = SystemDeviceAuthentication()) {
    private val mutable = MutableStateFlow(LockState(preferences.state.value.lockEnabled, preferences.state.value.lockEnabled))
    val state = mutable.asStateFlow()
    val allowOverlay get() = !state.value.enabled
    private var generation = 0L
    private var cancel: (() -> Unit)? = null
    fun requestUnlock(activity: ComponentActivity) {
        if (state.value.enabled && state.value.locked) authenticate(activity, null)
    }
    fun setEnabled(activity: ComponentActivity, enabled: Boolean) {
        if (state.value.enabled != enabled) authenticate(activity, enabled)
    }
    fun lock(cancelAuthentication: Boolean = false) {
        if (cancelAuthentication) { generation++; cancel?.invoke(); cancel = null }
        mutable.value = state.value.copy(locked = state.value.enabled,
            authenticating = !cancelAuthentication && state.value.authenticating, error = null)
    }
    private fun authenticate(activity: ComponentActivity, enable: Boolean?) {
        if (state.value.authenticating) return
        if (!authentication.available(activity)) {
            mutable.value = state.value.copy(error = LockError.NO_CREDENTIAL)
            return
        }
        val attempt = ++generation
        mutable.value = state.value.copy(authenticating = true, error = null)
        lateinit var observer: LifecycleEventObserver
        var pendingSuccess = false
        fun complete(result: AuthenticationResult) {
            activity.lifecycle.removeObserver(observer)
            if (attempt != generation) return
            cancel = null
            generation++
            val success = result == AuthenticationResult.SUCCESS && !activity.isFinishing && !activity.isDestroyed &&
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                !activity.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
            when {
                success && enable != null && !preferences.setLockEnabled(enable) ->
                    mutable.value = state.value.copy(authenticating = false, error = LockError.STORAGE)
                success -> {
                    val enabled = enable ?: state.value.enabled
                    mutable.value = LockState(enabled = enabled, locked = false)
                }
                else -> mutable.value = state.value.copy(authenticating = false,
                    error = if (result == AuthenticationResult.CANCELLED) LockError.CANCELLED else LockError.FAILED)
            }
        }
        observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (pendingSuccess) complete(AuthenticationResult.SUCCESS)
                Lifecycle.Event.ON_DESTROY -> {
                    activity.lifecycle.removeObserver(observer)
                    if (generation == attempt) lock(cancelAuthentication = true)
                }
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        val cancellation = authentication.authenticate(activity) { result ->
            if (attempt == generation) {
                // Legacy device credentials deliver their Activity result before onStart.
                if (result == AuthenticationResult.SUCCESS && !activity.isFinishing && !activity.isDestroyed &&
                    activity.lifecycle.currentState == Lifecycle.State.CREATED) pendingSuccess = true
                else complete(result)
            }
        }
        if (generation == attempt) cancel = {
            pendingSuccess = false
            activity.lifecycle.removeObserver(observer)
            cancellation()
        }
    }
}
