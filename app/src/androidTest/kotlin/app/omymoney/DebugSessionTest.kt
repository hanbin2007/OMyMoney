package app.omymoney

import android.app.NotificationManager
import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DebugSessionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun continuousRecordingRequiresExplicitStartAndAccessibility() {
        val context = compose.activity
        val manager = context.getSystemService(NotificationManager::class.java)
        assertFalse(manager.activeNotifications.any { it.id == 1102 })
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_preferences").performScrollTo().performClick()
        compose.onNodeWithTag("open_diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("debug_session_start").performScrollTo().assertIsNotEnabled()
        val info = context.packageManager.getServiceInfo(ComponentName(context.packageName,
            "app.omymoney.platform.diagnostics.DebugSessionService"), 0)
        assertFalse("Diagnostic service cannot be externally started", info.exported)
        assertFalse(manager.activeNotifications.any { it.id == 1102 })
    }
}
