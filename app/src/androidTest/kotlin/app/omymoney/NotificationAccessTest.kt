package app.omymoney

import app.omymoney.platform.notification.*
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationAccessTest {
    @Test fun permissionOrChannelSettingsRemainReachableAfterDenialOrGrant() {
        assertEquals(NotificationAction.REQUEST, notificationAction(34, false, false))
        assertEquals(NotificationAction.SETTINGS, notificationAction(34, false, true))
        assertEquals(NotificationAction.SETTINGS, notificationAction(34, true, false))
        assertEquals(NotificationAction.SETTINGS, notificationAction(32, true, false))
    }
}
