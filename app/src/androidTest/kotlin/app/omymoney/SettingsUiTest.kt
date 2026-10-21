package app.omymoney

import android.app.KeyguardManager
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.omymoney.android.R
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test

class SettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun unavailableCredentialFeedbackIsVisibleAndScreenshotOptionPersists() {
        assumeFalse(compose.activity.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav_settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_preferences").performClick()
        compose.onNodeWithTag("preferences_screen").performScrollToNode(hasTestTag("privacy_enable"))
        compose.onNodeWithTag("privacy_enable").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.privacy_no_credential)).assertIsDisplayed()
        compose.waitForIdle(); captureUiEvidence("c8-settings-error.png")
        dismissNotice()
        compose.onNodeWithTag("preferences_screen").performScrollToNode(hasTestTag("privacy_screenshots"))
        compose.onNodeWithTag("privacy_screenshots").performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        dismissNotice()
        compose.onNodeWithTag("preferences_screen").performScrollToNode(hasTestTag("privacy_screenshots"))
        compose.onNodeWithTag("privacy_screenshots").performClick()
        compose.waitForIdle()
        assertFalse(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
    private fun dismissNotice() {
        val dismissible = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss)
        compose.onAllNodes(dismissible).fetchSemanticsNodes().forEach {
            compose.onNode(dismissible).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss) { dismiss -> dismiss() }
        }
    }
}
