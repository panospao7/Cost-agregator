package com.yourname.expensetracker.domain.notification.capture

import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode

/**
 * Outcome of the unified notification capture gate.
 *
 * No notification text/extras should be extracted unless the decision is [Allowed].
 */
sealed interface NotificationCaptureDecision {
    /** Capture is allowed — proceed to extraction. */
    data object Allowed : NotificationCaptureDecision

    /** Capture is blocked permanently for this notification. */
    data class Blocked(
        val reason: NotificationCaptureBlockReason,
        val diagnosticReasonCode: DiagnosticReasonCode
    ) : NotificationCaptureDecision

    /** Capture is temporarily unavailable (e.g. gate not warmed up yet). */
    data class TemporarilyUnavailable(
        val reason: NotificationCaptureBlockReason,
        val retryable: Boolean
    ) : NotificationCaptureDecision
}

/** Reason why the capture gate blocked or deferred a notification. */
enum class NotificationCaptureBlockReason {
    RESTORE_MODE,
    SERVICE_SHUTTING_DOWN,
    PRIVACY_SETTING_DISABLED,
    PRIVACY_GATE_DENIED,
    PRIVACY_GATE_FAIL_CLOSED,
    PRIVACY_GATE_NOT_APPLICABLE,
    BLOCKED_PACKAGE,
    GATE_NOT_READY,
    GATE_ERROR
}
