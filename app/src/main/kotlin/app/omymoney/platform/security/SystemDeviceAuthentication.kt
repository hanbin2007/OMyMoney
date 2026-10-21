package app.omymoney.platform.security

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.omymoney.android.R
import java.util.UUID

class SystemDeviceAuthentication : DeviceAuthentication {
    override fun available(activity: ComponentActivity) = activity.getSystemService(KeyguardManager::class.java).isDeviceSecure

    override fun authenticate(activity: ComponentActivity, result: (AuthenticationResult) -> Unit): () -> Unit {
        var finished = false
        fun finish(value: AuthenticationResult) { if (!finished) { finished = true; result(value) } }
        return if (Build.VERSION.SDK_INT >= 29) {
            val signal = CancellationSignal()
            val builder = BiometricPrompt.Builder(activity).setTitle(activity.getString(R.string.privacy_auth_title))
                .setDescription(activity.getString(R.string.privacy_auth_description))
            if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            else { @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true) }
            try {
                builder.build().authenticate(signal, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(value: BiometricPrompt.AuthenticationResult) = finish(AuthenticationResult.SUCCESS)
                    override fun onAuthenticationError(code: Int, message: CharSequence?) = finish(
                        if (code in setOf(BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED))
                            AuthenticationResult.CANCELLED else AuthenticationResult.FAILED)
                })
            } catch (_: Exception) { finish(AuthenticationResult.FAILED) }
            { finished = true; signal.cancel() }
        } else {
            @Suppress("DEPRECATION")
            val intent = activity.getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent(
                activity.getString(R.string.privacy_auth_title), activity.getString(R.string.privacy_auth_description))
            lateinit var launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>
            launcher = activity.activityResultRegistry.register("privacy/${UUID.randomUUID()}", ActivityResultContracts.StartActivityForResult()) {
                launcher.unregister()
                finish(if (it.resultCode == Activity.RESULT_OK) AuthenticationResult.SUCCESS else AuthenticationResult.CANCELLED)
            }
            try { if (intent == null) { launcher.unregister(); finish(AuthenticationResult.FAILED) } else launcher.launch(intent) }
            catch (_: Exception) { launcher.unregister(); finish(AuthenticationResult.FAILED) }
            { finished = true; launcher.unregister() }
        }
    }
}
