package app.omymoney

import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun installedProductIsOMyMoney() {
        val context = compose.activity
        assertEquals("OMyMoney", context.packageManager.getApplicationLabel(context.applicationInfo).toString())
        assertEquals("app.omymoney.android", context.packageName)
    }

    @Test fun noLoginRequiredAndDestinationsAreReachable() {
        compose.onNodeWithText("账单").assertExists()
        listOf("bills", "assets", "stats", "settings").forEach { route ->
            compose.onNodeWithTag("nav_$route").performClick().assertIsSelected()
            compose.onNodeWithTag("screen_$route").assertExists()
        }
    }

    @Test fun packageAllowsSecureRemoteToolsButNotAutomaticBackup() {
        val context = compose.activity
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertFalse(android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
        assertFalse(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }
}
