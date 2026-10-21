package app.omymoney

import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.platform.accessibility.*
import org.junit.Assert.*
import org.junit.Test

class CaptureRuntimeRefreshTest {
    @Test fun activityResumeReplacesStaleLockedStatusWithTheActualScreenState() {
        val app = ApplicationProvider.getApplicationContext<OMyMoneyApplication>()
        val actual = AccessibilityStatus.unlocked(app)
        AccessibilityStatus.mutable.value = CaptureRuntime(connected = true, unlocked = !actual)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals(actual, AccessibilityStatus.runtime.value.unlocked) }
        }
        AccessibilityStatus.mutable.value = CaptureRuntime()
    }
}
