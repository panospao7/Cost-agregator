package com.yourname.expensetracker.domain.notification.capture

/**
 * Identifies whether a notification capture originated from an Android
 * NotificationListenerService callback or a manual active-notification refresh.
 */
enum class CaptureSource {
    LISTENER,
    REFRESH
}
