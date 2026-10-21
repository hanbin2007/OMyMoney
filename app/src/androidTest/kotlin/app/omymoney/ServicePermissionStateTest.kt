package app.omymoney

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.platform.accessibility.AccessibilityStatus
import app.omymoney.platform.accessibility.LedgerAccessibilityService
import app.omymoney.platform.notification.NotificationCaptureStatus
import app.omymoney.platform.notification.TransactionNotificationListener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ServicePermissionStateTest {
    @Test fun enabledAccessibilityGrantIsNotReportedRevokedJustBecauseTheServiceIsUnbound() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("permissionStateQa") == "true")
        val app = ApplicationProvider.getApplicationContext<OMyMoneyApplication>()
        val component = ComponentName(app, LedgerAccessibilityService::class.java)
        assertTrue(Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component })
        // Default UiAutomation deliberately suppresses binding, without revoking the saved grant.
        InstrumentationRegistry.getInstrumentation().uiAutomation
        assertTrue("A saved OS grant and a running service are different states", AccessibilityStatus.granted(app))
        AccessibilityStatus.refreshAccess(app)
        assertFalse("A retained grant must not authorize financial capture without a bound callback", AccessibilityStatus.ready(app))
    }

    @Test fun notificationAccessOpensThisAppsSystemDetailRatherThanAnUnrelatedNotificationPermission() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val app = ApplicationProvider.getApplicationContext<OMyMoneyApplication>()
        val intent = NotificationCaptureStatus.settingsIntent(app)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, intent.action)
        assertEquals(ComponentName(app, TransactionNotificationListener::class.java).flattenToString(),
            intent.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME))
    }

    @Test fun notificationSettingsFallBackWhenAnOemHasNoDetailActivity() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val actions = mutableListOf<String?>()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun startActivity(intent: Intent) {
                actions += intent.action
                if (actions.size == 1) throw ActivityNotFoundException("synthetic missing details")
            }
        }
        NotificationCaptureStatus.openSettings(context)
        assertEquals(listOf(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), actions)
    }
}
