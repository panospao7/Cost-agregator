package com.yourname.expensetracker.domain.notification.capture

sealed interface NotificationIntakeCaptureResult {
    /** Intake row created, worker enqueued. */
    data class Enqueued(val intakeId: Long, val correlationId: String) : NotificationIntakeCaptureResult
    /** Duplicate fingerprint detected. */
    data class Duplicate(val correlationId: String) : NotificationIntakeCaptureResult
    /** DO_NOT_STORE: must process synchronously with sanitized storage only. */
    data object RequiresSynchronousProcessing : NotificationIntakeCaptureResult
    /** Policy drop (e.g. insert conflict). */
    data class Dropped(val correlationId: String, val reason: String) : NotificationIntakeCaptureResult
}
