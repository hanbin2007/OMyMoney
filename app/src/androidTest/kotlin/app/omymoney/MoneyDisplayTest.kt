package app.omymoney

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.omymoney.ui.components.MoneyText
import app.omymoney.ui.theme.LedgerTheme
import app.omymoney.ui.theme.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoneyDisplayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completeLargeAmountFitsOneLineAtTwoHundredPercentFont() {
        compose.setContent {
            LedgerTheme(ThemeMode.LIGHT, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Box(Modifier.width(328.dp)) {
                        MoneyText("CNY -90000000000000000.00", Modifier.fillMaxWidth().testTag("money_text"), MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("money_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertFalse(layouts.single().hasVisualOverflow)
        compose.onNodeWithText("CNY -90000000000000000.00").assertIsDisplayed()
    }
}
