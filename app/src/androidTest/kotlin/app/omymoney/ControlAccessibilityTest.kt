package app.omymoney

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.omymoney.ui.components.ArchiveSwitch
import app.omymoney.ui.theme.LedgerTheme
import app.omymoney.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    @Test fun archiveToggleHasAFullSizeInteractiveRowAndCorrectState() {
        val enabled = mutableStateOf(false)
        compose.setContent { LedgerTheme(ThemeMode.LIGHT, false) { ArchiveSwitch(enabled.value) { enabled.value = it } } }
        compose.onNodeWithText("显示已归档").assertHasClickAction().assertIsOff()
            .assertHeightIsAtLeast(48.dp).performClick().assertIsOn()
    }
}
