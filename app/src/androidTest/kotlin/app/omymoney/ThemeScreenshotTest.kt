package app.omymoney

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.ui.OMyMoneyApp
import app.omymoney.ui.theme.ThemeMode
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureThemeVariantsWithReachableNavigation() {
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val dynamic = mutableStateOf(false)
        compose.setContent { OMyMoneyApp(theme.value, dynamic.value) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = context.resources.configuration
        val directory = File(context.filesDir, "qa").apply { mkdirs() }
        for (useDynamic in listOf(false, true)) {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                compose.runOnIdle { theme.value = mode; dynamic.value = useDynamic }
                compose.waitForIdle()
                compose.onNodeWithTag("nav_bills").assertIsDisplayed()
                compose.onNodeWithTag("nav_settings").assertIsDisplayed()
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                val name = "a4_${config.screenWidthDp}dp_font${(config.fontScale * 100).roundToInt()}_${mode.name.lowercase()}_${if (useDynamic) "dynamic" else "fixed"}.png"
                File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        }
    }
}
