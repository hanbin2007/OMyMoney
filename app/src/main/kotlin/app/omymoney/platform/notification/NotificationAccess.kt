package app.omymoney.platform.notification

internal enum class NotificationAction { REQUEST, SETTINGS }

internal fun notificationAction(sdk: Int, permissionGranted: Boolean, alreadyAsked: Boolean): NotificationAction =
    if (sdk >= 33 && !permissionGranted && !alreadyAsked) NotificationAction.REQUEST else NotificationAction.SETTINGS
