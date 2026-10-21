package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.omymoney.data.AutomationSettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AutomationSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun consentPauseAndDeniedPermissionLeaveManualEntryAvailable() {
        val repo = (compose.activity.application as OMyMoneyApplication).container.store.automation
        runBlocking { repo.setEnabled(false); repo.setPaused(false) }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_automation").performClick()
        compose.onNodeWithTag("automation_enable").assertIsOff().performClick()
        compose.onNodeWithText("允许本地读取交易页面？").assertIsDisplayed()
        captureUiEvidence("b10-consent.png")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("automation_enable").assertIsOff().performClick()
        compose.onNodeWithTag("automation_consent").performClick()
        compose.waitUntil(5000) { runBlocking { repo.read().settings.enabled } }
        compose.onNodeWithTag("automation_settings").performScrollToNode(hasTestTag("automation_status"))
        compose.onNodeWithTag("automation_status").assertTextEquals("未授予无障碍权限")
        compose.onNodeWithTag("automation_pause").performClick()
        compose.waitUntil(5000) { runBlocking { repo.read().settings.paused } }
        compose.onNodeWithTag("automation_settings").performScrollToNode(hasTestTag("automation_status"))
        compose.onNodeWithTag("automation_status").assertTextEquals("已暂停")
        compose.onNodeWithTag("automation_settings").performScrollToNode(hasTestTag("automation_app_com.ss.android.ugc.aweme"))
        compose.onNodeWithTag("automation_app_com.ss.android.ugc.aweme").performClick()
        compose.waitUntil(5000) { runBlocking { "com.ss.android.ugc.aweme" in repo.read().settings.packages } }
        captureUiEvidence("b10-apps.png")
        compose.onNodeWithTag("automation_settings").performScrollToNode(hasTestTag("automation_enable"))
        compose.onNodeWithTag("automation_enable").performClick()
        compose.onNodeWithTag("screen_back").performClick()
        compose.onNodeWithTag("nav_bills").performClick()
        compose.onNodeWithTag("add_bill").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("bill_amount").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bill_amount").assertIsDisplayed().performTextReplacement("0.01")
        compose.onNodeWithTag("bill_save").assertIsEnabled().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("add_bill").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(runBlocking { repo.read().settings.enabled })
    }
    @Test fun corruptOrUnknownSettingsFailClosed() {
        assertFalse(AutomationSettingsRepository.decode(null).settings.enabled)
        assertTrue(AutomationSettingsRepository.decode("broken").corrupt)
        assertTrue(AutomationSettingsRepository.decode("{\"enabled\":true,\"packages\":[\"not.allowed\"]}").corrupt)
        assertFalse(AutomationSettingsRepository.decode("{\"enabled\":true,\"packages\":[\"not.allowed\"]}").settings.enabled)
    }
}
