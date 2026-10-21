package app.omymoney

import android.content.Intent
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.platform.security.PrivacyPreferences
import app.omymoney.ui.theme.ThemeMode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PrivacyProcessTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun coldProcessRetainsThemeAndFailsClosed() {
        val phase = InstrumentationRegistry.getArguments().getString("privacyPhase")
        assumeTrue(phase in listOf("prepare", "verify"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (phase == "prepare") {
            val preferences = PrivacyPreferences(context)
            assertTrue(preferences.setTheme(ThemeMode.DARK))
            assertTrue(preferences.setDynamicColor(false))
            assertTrue(preferences.setSecureScreens(true))
            assertTrue(preferences.setLockEnabled(true))
            return
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("privacy_locked").fetchSemanticsNodes().isNotEmpty() }
            val container = (context.applicationContext as OMyMoneyApplication).container
            assertEquals(ThemeMode.DARK, container.preferences.state.value.theme)
            assertFalse(container.preferences.state.value.dynamicColor)
            assertTrue(container.privacyLock.state.value.locked)
            assertTrue("The first activity must be tracked as foreground", container.appVisible.value)
            compose.onNodeWithTag("nav_bills").assertDoesNotExist()
            scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        }
    }
}
