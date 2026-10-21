package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.view.KeyEvent
import app.omymoney.core.model.BillQuery
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BackupFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun backupIsReachableAndCancellingSystemPickerChangesNothing() {
        rule.onNodeWithTag("nav_settings").performClick()
        rule.onNodeWithTag("catalog_settings").performScrollToNode(hasTestTag("settings_backup"))
        rule.onNodeWithTag("settings_backup").performClick()
        rule.onNodeWithTag("backup_export").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag("backup_import").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("备份包含账单、草稿和附件，未加密。请妥善保管，并留意所选文件服务的同步设置。").assertIsDisplayed()
        val store = (rule.activity.application as OMyMoneyApplication).container.store
        val before = runBlocking { store.ledger.observeBills(BillQuery()).first() }
        rule.onNodeWithTag("backup_import").performClick()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(300, 5000)
        captureUiEvidence("a8-system-import-picker.png")
        instrumentation.uiAutomation.executeShellCommand("input keyevent ${KeyEvent.KEYCODE_BACK}").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        rule.onNodeWithTag("backup_import").assertIsDisplayed()
        assertEquals(before, runBlocking { store.ledger.observeBills(BillQuery()).first() })
    }
}
