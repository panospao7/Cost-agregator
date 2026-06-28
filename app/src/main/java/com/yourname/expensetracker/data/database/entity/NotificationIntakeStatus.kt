package com.yourname.expensetracker.data.database.entity

/**
 * Status machine for [NotificationIntakeEntity] processing lifecycle.
 */
enum class NotificationIntakeStatus {
    /** Row inserted, ready for processing. */
    RECEIVED,
    /** Worker has claimed this row. */
    PROCESSING,
    /** Processing completed successfully (expense or review created). */
    PROCESSED,
    /** Duplicate detected — no processing needed. */
    DROPPED_DUPLICATE,
    /** Dropped by policy (filter, privacy, blocked package). */
    DROPPED_POLICY,
    /** Filter rejected — not a financial notification. */
    FILTER_REJECTED,
    /** Temporary failure — will retry. */
    FAILED_RETRYABLE,
    /** Final failure after max attempts. */
    FAILED_FINAL,
    /** Row is stale and was cancelled. */
    CANCELLED_STALE,
    /** Cannot process because raw payload is unavailable per privacy policy. */
    PAYLOAD_UNAVAILABLE_PRIVACY,
    /** Privacy gate denied processing at worker runtime. */
    PRIVACY_DENIED
}
