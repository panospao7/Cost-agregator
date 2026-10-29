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
    HOME_CURRENCY_UNAVAILABLE,

    // RP-17 17-C: bank transaction contract skips (before lifecycle; typed
    // controlled codes, never payload-derived).
    TRANSFER_METADATA_MISSING,
    REFUND_UNSUPPORTED,
    INVALID_AMOUNT,

    // RP-17 17-E: connection was disconnected when a token write was attempted
    // (conditional update affected zero rows).
    CONNECTION_DISCONNECTED,

    // app-lock-privacy-mode S2 (OD-12a): controlled privacy-pipeline codes, emitted only through
    // PrivacyDiagnosticsEmitter (typed metadata, never payload-derived). Append-only: persisted by
    // name as TEXT and parsed with runCatching readers, so older builds tolerate unknown names.
    // Notification content policy (spec 10).
    NOTIF_POLICY_SETTINGS_UNREADABLE,
    NOTIF_POLICY_FALLBACK_EXCEPTION,
    NOTIF_ACTIONS_SUPPRESSED_LOCK,
    NOTIF_REPOSTED_DISCREET,
    NOTIF_REPOST_FAILED,
    NOTIF_REPOST_UNCONFIRMED,

    // Deep-link policy (spec 5.7, closed set).
    DL_APPLIED,
    DL_HELD_LOCKED,
    DL_HELD_REPLACED,
    DL_HELD_EXPIRED,
    DL_REJECTED_ACTION,
    DL_REJECTED_MALFORMED,
    DL_REJECTED_UNKNOWN_HOST,
    DL_REJECTED_BANK_HOST,
    DL_PARAM_UNKNOWN_DROPPED,
    DL_PARAM_INVALID_DROPPED,
    DL_BRIEFING_KEY_NOT_DELIVERED,
    DL_BRIEFING_STATE_UNREADABLE,
    DL_TARGET_NOT_FOUND,
    DL_TARGET_READ_BLOCKED,
    DL_DIAGNOSTICS_SUPPRESSED,

    // App-lock settings durability (spec 10; AMD-1, AMD-10).
    LOCK_SETTING_PERSIST_FAILED,
    LOCK_SETTINGS_UNREADABLE,
    LOCK_SETTINGS_REVIEW_SAVED,

    // App-lock controller (S16, spec 10 "Lock" group; INV-37). Appended at the tail, existing order
    // kept. The bucket / cause enums named in the spec travel as typed metadata, never as codes.
    LOCK_SHOWN_COLD_START,
    LOCK_SHOWN_TIMEOUT,
    LOCK_SHOWN_SETTINGS_UNREADABLE,
    LOCK_UNLOCKED,
    LOCK_AUTH_CANCELLED,
    LOCK_AUTH_LOCKOUT,
    LOCK_AUTH_ERROR,
    LOCK_UNAVAILABLE_API,
    LOCK_UNAVAILABLE_NOT_SECURE,
    LOCK_UNAVAILABLE_CAPABILITY,
    LOCK_INERT_NO_CREDENTIAL,
    LOCK_INERT_HW_UNAVAILABLE,
    LOCK_INERT_UNKNOWN,
    LOCK_INERT_API_UNSUPPORTED,
    LOCK_INERT_SETTINGS_UNREADABLE,
    LOCK_ENABLED,
    LOCK_DISABLED,
    LOCK_SETTINGS_RECOVERED
}
