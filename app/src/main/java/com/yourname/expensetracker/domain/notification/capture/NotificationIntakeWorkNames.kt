package com.yourname.expensetracker.domain.notification.capture

/**
 * Single source of the WorkManager unique-work name for a notification intake row
 * (P-01-1). Every enqueue site (`capture`, `captureForRetry`, `recoverPending`)
 * must use this function so a row can never be scheduled under two names.
 *
 * Upgrade note: work enqueued by earlier builds under the bare `<id>` name is
 * neither cancelled nor renamed. A duplicate worker for the same row is safe
 * because `NotificationIntakeWorker` claims the row atomically; the loser of the
 * claim is a no-op.
 */
internal object NotificationIntakeWorkNames {
    private const val UNIQUE_NAME_PREFIX = "notification-intake-"

    fun uniqueWorkName(intakeId: Long): String = "$UNIQUE_NAME_PREFIX$intakeId"
}
