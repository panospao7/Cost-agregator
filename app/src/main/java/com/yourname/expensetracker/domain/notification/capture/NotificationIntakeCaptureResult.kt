package com.yourname.expensetracker.domain.notification.capture

sealed interface NotificationIntakeCaptureResult {
    /** Intake row created, worker enqueued. */
    data class Enqueued(val intakeId: Long, val correlationId: String) : NotificationIntakeCaptureResult

    /**
     * Duplicate fingerprint detected.
     *
     * [existingIntakeId] identifies the intake row that already holds this
     * content when that row could be resolved (canonical-conflict lookup on the
     * deferred path); it is null when only existence was proven (live-path
     * fingerprint existence check).
     */
    data class Duplicate(
        val existingIntakeId: Long?,
        val correlationId: String
    ) : NotificationIntakeCaptureResult

    /** DO_NOT_STORE: must process synchronously with sanitized storage only. */
    data object RequiresSynchronousProcessing : NotificationIntakeCaptureResult

    /**
     * RP-10 10a (P1-002): no durable intake row and no payload was persisted
     * because the resolved privacy policy forbids storage (DO_NOT_STORE).
     */
    data class NotStored(val correlationId: String) : NotificationIntakeCaptureResult

    /**
     * RP-10 10a (P1-002): durable intake failed before any insert. [reasonCode]
     * is a controlled constant (never exception text or payload).
     */
    data class StorageFailure(
        val correlationId: String,
        val reasonCode: String
    ) : NotificationIntakeCaptureResult

    /** Policy drop (e.g. insert conflict, barrier-blocked write). */
    data class Dropped(val correlationId: String, val reason: String) : NotificationIntakeCaptureResult
}
