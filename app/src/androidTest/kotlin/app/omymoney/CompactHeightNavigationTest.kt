package app.omymoney

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactHeightNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun compactLandscapeKeepsEveryDestinationReachable() {
        val config = compose.activity.resources.configuration
        assumeTrue(config.screenWidthDp >= 600 && config.screenHeightDp < 480)
        compose.waitForIdle()
        captureUiEvidence("a5-compact-height.png")
        compose.onNodeWithTag("navigation_bar").assertIsDisplayed()
        listOf("bills", "assets", "stats", "settings").forEach {
            compose.onNodeWithTag("nav_$it").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        }
    }
}
