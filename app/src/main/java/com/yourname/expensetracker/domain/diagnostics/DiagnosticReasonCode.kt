package com.yourname.expensetracker.domain.diagnostics

enum class DiagnosticReasonCode {
    SUCCESS,
    NO_WORK,
    PRIVACY_DENIED,
    PRIVACY_FAIL_CLOSED,
    RESTORE_BLOCKED,
    WRITE_BARRIER_DENIED,
    READ_BARRIER_DENIED,
    FILTER_REJECTED,
    BLOCKED_PACKAGE,
    DUPLICATE,
    VALIDATION_FAILED,
    PARSER_FAILED,
    OCR_FAILED,
    MISSING_RATE,
    STALE_RATE,
    PERMISSION_DENIED,
    NOTIFICATION_PERMISSION_DENIED,
    NETWORK_UNAVAILABLE,
    PROVIDER_DISABLED,
    TOKEN_INVALID,
    CANCELLED_BY_SYSTEM,
    CANCELLED_BY_USER,
    SIDE_EFFECT_EXCEPTION,
    SOURCE_LINK_FAILED,
    UNKNOWN_ERROR,
    STOP_REQUESTED,
    TIMEOUT,
    // PR12J-1: Safe structured worker reason codes
    WORKER_SUCCESS,
    WORKER_NO_WORK,
    WORKER_SKIPPED,
    WORKER_TIMEOUT,
    WORKER_RETRYABLE_ERROR,
    WORKER_TRANSIENT_ERROR,
    WORKER_UNHANDLED_EXCEPTION,
    WORKER_CHECKPOINT_BLOCKED,
    WORKER_WRITE_BARRIER_DENIED,
    WORKER_STOP_REQUESTED,
    WORKER_PRIVACY_DENIED,
    WORKER_PRIVACY_FAIL_CLOSED,
    WORKER_NOTIFICATION_PERMISSION_DENIED,
    WORKER_CANCELLED,
    STALE_RUNNING_ABORTED,
    DAILY_BRIEFING_PIPELINE_TIMEOUT,
    RETENTION_PARTIAL_FAILURE,
    INCONSISTENCY_DETECTED,
    INCONSISTENCY_CHECK,
    // RP-12 12b (P3-007): item-dependent side effect had no permitted
    // structured receipt data (restricted storage mode / process restart).
    STRUCTURED_RECEIPT_DATA_UNAVAILABLE,

    // RP-10 10a (P1-001/P1-002): deferred notification capture outcomes —
    // controlled constants only, never payload-derived.
    DEFERRED_NOT_STORED,
    DEFERRED_STORAGE_POLICY_UNAVAILABLE,
    DEFERRED_TRANSIENT_ENCRYPT_FAILED,

    // RP-10 10b (P1-003): WorkManager enqueue failure transitioned the row.
    ENQUEUE_FAILED,

    // RP-10 10b (P1-004): caller cancellation accounted for, never swallowed.
    CAPTURE_CANCELLED,

    // RP-11 11c (NEW-P2-016): home-currency settings could not be resolved
    // (DataStore error/timeout) — no fabricated base snapshot; conversion
    // fields marked unavailable via the existing sentinel model.
    HOME_CURRENCY_UNAVAILABLE
}
