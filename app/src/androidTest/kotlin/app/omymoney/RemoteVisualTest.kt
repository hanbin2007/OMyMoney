package app.omymoney

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemoteVisualTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        val config = compose.activity.resources.configuration
        val directory = File(compose.activity.filesDir, "qa/remote").apply { mkdirs() }
        val screen = if (name.startsWith("diagnostics")) "diagnostics_screen" else "updates_screen"
        File(directory, "${name}_${config.screenWidthDp}_${config.fontScale}.png").outputStream().use {
            assertTrue(compose.onNode(isRoot() and hasAnyDescendant(hasTestTag(screen))).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    @Test fun remoteSettingsRemainReadableAndReachable() {
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_preferences").performScrollTo().performClick()
        compose.onNodeWithTag("open_diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics_recording").assertIsDisplayed()
        capture("diagnostics")
        compose.onNodeWithTag("diagnostics_screen").performScrollToNode(hasTestTag("diagnostics_notification_counts"))
        compose.onNodeWithTag("diagnostics_notification_counts").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics_screen").performScrollToNode(hasTestTag("diagnostics_battery_settings"))
        compose.onNodeWithTag("diagnostics_battery_settings").assertIsDisplayed()
        capture("diagnostics_services")
        compose.onNodeWithTag("diagnostics_screen").performScrollToNode(hasTestTag("diagnostics_pair_code"))
        compose.onNodeWithTag("diagnostics_pair_code").assertIsDisplayed()
        capture("diagnostics_pair")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("open_updates").performScrollTo().performClick()
        compose.onNodeWithTag("updates_check").assertIsDisplayed()
        capture("updates")
        compose.onNodeWithTag("updates_screen").performScrollToNode(hasTestTag("updates_auto_download"))
        compose.onNodeWithTag("updates_auto_download").assertIsDisplayed()
    }
}
