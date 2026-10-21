package app.omymoney

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.platform.security.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PrivacyLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private class Auth : DeviceAuthentication {
        lateinit var result: (AuthenticationResult) -> Unit
        var cancelled = false
        override fun available(activity: ComponentActivity) = true
        override fun authenticate(activity: ComponentActivity, result: (AuthenticationResult) -> Unit): () -> Unit {
            this.result = result
            return { cancelled = true }
        }
    }

    private fun deferredSuccess(invalidate: Boolean, destroy: Boolean = false) {
        val prefs = PrivacyPreferences(instrumentation.targetContext.getSharedPreferences(
            "privacy-lifecycle-${UUID.randomUUID()}", Context.MODE_PRIVATE)).apply { setLockEnabled(true) }
        val auth = Auth()
        val lock = PrivacyLock(prefs, auth)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { lock.requestUnlock(it) }
            scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.runOnMainSync {
                auth.result(AuthenticationResult.SUCCESS)
                assertTrue("A stopped host must stay locked", lock.state.value.locked)
                assertTrue("Credential results delivered before onStart must remain pending", lock.state.value.authenticating)
                if (invalidate) lock.lock(cancelAuthentication = true)
            }
            scenario.moveToState(if (destroy) Lifecycle.State.DESTROYED else Lifecycle.State.RESUMED)
            instrumentation.runOnMainSync {
                assertEquals(invalidate || destroy, lock.state.value.locked)
                assertFalse(lock.state.value.authenticating)
                if (invalidate || destroy) assertTrue(auth.cancelled)
            }
        }
    }

    @Test fun credentialResultBeforeOnStartUnlocksOnlyAfterTheHostReturns() = deferredSuccess(false)
    @Test fun screenLockInvalidatesADeferredCredentialResult() = deferredSuccess(true)
    @Test fun destroyingTheHostDiscardsItsDeferredCredentialResult() = deferredSuccess(false, destroy = true)
}
