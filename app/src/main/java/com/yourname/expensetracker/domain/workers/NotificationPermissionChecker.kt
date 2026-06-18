package com.yourname.expensetracker.domain.workers

/**
 * Abstraction over the OS-level "are notifications enabled" check so that
 * [WorkerExecutionGuard] can enforce [WorkerGuardRequest.requiresNotificationPermission]
 * without depending on Android framework types (e.g. NotificationManagerCompat).
 *
 * Keeping this as a domain interface lets the guard stay unit-testable with a
 * simple fake; the Android-backed implementation lives in the data layer.
 */
interface NotificationPermissionChecker {
    /** @return true if the app is currently allowed to post notifications. */
    fun areNotificationsEnabled(): Boolean
}
