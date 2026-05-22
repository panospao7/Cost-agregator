package com.yourname.expensetracker.domain.notification

/**
 * Defines the contract for every possible outcome produced by the notification
 * processing pipeline. Each sealed variant carries the originating
 * [packageName] and an optional [correlationId] for end-to-end tracing.
 *
 * Every outcome is recorded as a [PipelineDiagnosticEvent] in the diagnostic
 * ledger for observability and debugging.
 */
sealed interface NotificationPipelineOutcome {

    /**
     * The package name of the source app that sent the notification.
     */
    val packageName: String

    /**
     * End-to-end correlation ID used to trace this outcome across system boundaries.
     */
    val correlationId: String?

    /**
     * The notification was auto-accepted — an [Expense] was created directly
     * without user review (AI-driven routing).
     */
    data class AutoAccepted(
        override val packageName: String,
        override val correlationId: String?,
        val rawId: Long,
        val expenseId: Long
    ) : NotificationPipelineOutcome

    /**
     * The notification requires manual review — a [PendingReview] was created
     * for user approval.
     */
    data class NeedsReview(
        override val packageName: String,
        override val correlationId: String?,
        val rawId: Long,
        val reviewId: Long
    ) : NotificationPipelineOutcome

    /**
     * The notification was identified as a duplicate (fingerprint, canonical,
     * or pending-review match).
     */
    data class Duplicate(
        override val packageName: String,
        override val correlationId: String?,
        val reason: String
    ) : NotificationPipelineOutcome

    /**
     * The parser failed to extract a transaction from the notification, and no
     * transaction signal or oversized amount was detected.
     */
    data class ParserFailed(
        override val packageName: String,
        override val correlationId: String?,
        val rawId: Long?,
        val reason: String
    ) : NotificationPipelineOutcome

    /**
     * The notification was auto-rejected by the confidence router (low
     * confidence or explicit rejection signal).
     */
    data class AutoRejected(
        override val packageName: String,
        override val correlationId: String?,
        val rawId: Long?,
        val reason: String
    ) : NotificationPipelineOutcome

    /**
     * The notification was dropped before any processing occurred (e.g.
     * maintenance mode, filter rejection, or silent skip).
     */
    data class Dropped(
        override val packageName: String,
        override val correlationId: String?,
        val reason: String
    ) : NotificationPipelineOutcome

    /**
     * An unexpected error occurred during processing.
     */
    data class Error(
        override val packageName: String,
        override val correlationId: String?,
        val throwable: Throwable
    ) : NotificationPipelineOutcome
}
