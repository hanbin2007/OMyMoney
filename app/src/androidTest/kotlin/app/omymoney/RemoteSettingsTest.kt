package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recordingRequiresConsentAndCanBeStoppedWithoutPairing() {
        val manager = (compose.activity.application as OMyMoneyApplication).container.diagnostics
        runBlocking { manager.setEnabled(false) }
        runBlocking { manager.setExtraPackages(setOf("com.taobao.taobao")) }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_preferences").performScrollTo().performClick()
        compose.onNodeWithTag("open_diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics_recording").performClick()
        assertFalse(manager.state.value.enabled)
        compose.onNodeWithTag("diagnostics_consent_scope").assertTextContains("com.taobao.taobao", substring = true)
        compose.onNodeWithTag("diagnostics_consent_cancel").performClick()
        assertFalse(manager.state.value.enabled)
        compose.onNodeWithTag("diagnostics_recording").performClick()
        compose.onNodeWithTag("diagnostics_consent_accept").performClick()
        compose.waitUntil { manager.state.value.enabled }
        compose.onNodeWithTag("diagnostics_recording").performClick()
        compose.waitUntil { !manager.state.value.enabled }
        runBlocking { manager.setExtraPackages(emptySet()) }
    }

    @Test fun updaterIsReachableAndDoesNotInstallWithoutUserAction() {
        val updates = (compose.activity.application as OMyMoneyApplication).container.updates
        runBlocking { updates.settings(false, false) }
        compose.waitUntil(15000) { !updates.state.value.busy }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_preferences").performScrollTo().performClick()
        compose.onNodeWithTag("open_updates").performScrollTo().performClick()
        compose.onNodeWithTag("updates_screen").assertExists()
        compose.onNodeWithTag("updates_check").assertIsEnabled()
        compose.onNodeWithTag("updates_install").assertDoesNotExist()
    }
}
