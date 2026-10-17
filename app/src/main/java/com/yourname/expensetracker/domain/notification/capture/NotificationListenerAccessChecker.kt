package com.yourname.expensetracker.domain.notification.capture

/**
 * R17: read-only probe for the OS-level notification-listener access grant.
 *
 * Only a Boolean leaves this boundary. Implementations must fail closed (return false)
 * on any platform error and must never expose the raw enabled-listeners string.
 * This probe never changes any privacy setting and never bypasses NotificationCaptureGate.
 */
interface NotificationListenerAccessChecker {
    fun isListenerAccessGranted(): Boolean
}
