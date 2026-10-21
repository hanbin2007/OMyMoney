package app.omymoney

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.omymoney.ui.AppDestination
import app.omymoney.ui.navigation.LedgerNavigation
import app.omymoney.ui.theme.LedgerTheme
import app.omymoney.ui.theme.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MaterialThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bottomNavigationHasNamedLargeTargetsAndOneSelectedDestination() {
        val selected = mutableStateOf(AppDestination.BILLS)
        compose.setContent {
            LedgerTheme(ThemeMode.LIGHT, false) {
                LedgerNavigation(selected.value, { selected.value = it })
            }
        }
        compose.onNodeWithTag("navigation_bar").assertExists()
        AppDestination.entries.forEach { destination ->
            val node = compose.onNodeWithTag("nav_${destination.route}")
            node.assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            node.assertIsSelected()
            AppDestination.entries.filterNot { it == destination }.forEach {
                compose.onNodeWithTag("nav_${it.route}").assertIsNotSelected()
            }
        }
        listOf("账单", "资产", "统计", "设置").forEach { compose.onNodeWithText(it).assertExists() }
    }

    @Test fun railKeepsTheSameDestinationsAndSelectionSemantics() {
        val selected = mutableStateOf(AppDestination.BILLS)
        compose.setContent {
            LedgerTheme(ThemeMode.DARK, false) {
                LedgerNavigation(selected.value, { selected.value = it }, expanded = true)
            }
        }
        compose.onNodeWithTag("navigation_rail").assertExists()
        compose.onNodeWithTag("navigation_bar").assertDoesNotExist()
        compose.onNodeWithTag("nav_assets").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .performClick().assertIsSelected()
        compose.onNodeWithText("资产").assertExists()
    }

    @Test fun explicitLightAndDarkOverrideTheSystemAndRemainReadable() {
        val mode = mutableStateOf(ThemeMode.LIGHT)
        var observed: ColorScheme? = null
        compose.setContent {
            LedgerTheme(mode.value, false) {
                val colors = MaterialTheme.colorScheme
                SideEffect { observed = colors }
                Surface { Text("账务正文", Modifier.testTag("body")) }
            }
        }
        compose.onNodeWithTag("body").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(observed!!.background.luminance() > 0.8f)
            assertTrue(contrast(observed!!.onSurface.luminance(), observed!!.surface.luminance()) >= 4.5)
            mode.value = ThemeMode.DARK
        }
        compose.onNodeWithTag("body").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(observed!!.background.luminance() < 0.1f)
            assertTrue(contrast(observed!!.onSurface.luminance(), observed!!.surface.luminance()) >= 4.5)
        }
    }

    @Test fun dynamicColorUsesTheSystemPaletteWhenAvailable() {
        var observed: ColorScheme? = null
        var expected: ColorScheme? = null
        compose.setContent {
            val context = LocalContext.current
            LedgerTheme(ThemeMode.LIGHT, true) {
                val colors = MaterialTheme.colorScheme
                SideEffect {
                    observed = colors
                    if (Build.VERSION.SDK_INT >= 31) expected = dynamicLightColorScheme(context)
                }
                Text("动态色正文")
            }
        }
        compose.onNodeWithText("动态色正文").assertIsDisplayed()
        compose.runOnIdle { if (Build.VERSION.SDK_INT >= 31) assertEquals(expected!!.primary, observed!!.primary) }
    }

    @Test fun fixedAndActualDynamicPalettesKeepUsedNormalTextRolesAboveMinimumContrast() {
        val configuration = mutableStateOf(ThemeMode.LIGHT to false)
        var observed: ColorScheme? = null
        compose.setContent {
            LedgerTheme(configuration.value.first, configuration.value.second) {
                val colors = MaterialTheme.colorScheme
                SideEffect { observed = colors }
                Surface { Text("Contrast acceptance") }
            }
        }
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (dynamic in listOf(false, true)) {
            compose.runOnIdle { configuration.value = mode to dynamic }
            compose.waitForIdle()
            compose.runOnIdle {
                val colors = requireNotNull(observed)
                val pairs = with(colors) { listOf(
                    "primary" to (onPrimary to primary), "primaryContainer" to (onPrimaryContainer to primaryContainer),
                    "secondary" to (onSecondary to secondary), "secondaryContainer" to (onSecondaryContainer to secondaryContainer),
                    "tertiary" to (onTertiary to tertiary), "tertiaryContainer" to (onTertiaryContainer to tertiaryContainer),
                    "background" to (onBackground to background), "body" to (onSurface to surface),
                    "supporting" to (onSurfaceVariant to surface), "variant" to (onSurfaceVariant to surfaceVariant),
                    "container" to (onSurface to surfaceContainer), "containerHigh" to (onSurfaceVariant to surfaceContainerHighest),
                    "buttonLabel" to (primary to surface), "error" to (error to surface),
                    "errorContainer" to (onErrorContainer to errorContainer), "onError" to (onError to error),
                    "snackbar" to (inverseOnSurface to inverseSurface), "snackbarAction" to (inversePrimary to inverseSurface)) }
                pairs.forEach { (name, colors) ->
                    val ratio = contrast(colors.first.luminance(), colors.second.luminance())
                    assertTrue("$mode dynamic=$dynamic $name contrast=$ratio", ratio >= 4.5)
                }
            }
        }
    }

    @Test fun allTypographyUsesZeroLetterSpacing() {
        var typography: Typography? = null
        compose.setContent {
            LedgerTheme(ThemeMode.LIGHT, false) {
                val current = MaterialTheme.typography
                SideEffect { typography = current }
                Column { Text("账单正文"); Text("标签", style = current.labelSmall) }
            }
        }
        compose.onNodeWithText("账单正文").assertIsDisplayed()
        compose.runOnIdle {
            val t = requireNotNull(typography)
            listOf(t.displayLarge, t.displayMedium, t.displaySmall, t.headlineLarge, t.headlineMedium, t.headlineSmall,
                t.titleLarge, t.titleMedium, t.titleSmall, t.bodyLarge, t.bodyMedium, t.bodySmall,
                t.labelLarge, t.labelMedium, t.labelSmall).forEach { assertEquals(0f, it.letterSpacing.value, 0.001f) }
        }
    }

    private fun contrast(a: Float, b: Float): Double = ((maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)).toDouble()
}
