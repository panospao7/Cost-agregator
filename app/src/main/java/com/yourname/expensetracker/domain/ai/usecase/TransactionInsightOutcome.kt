package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.dto.AiArtifactRecord

/**
 * Result of one [GenerateTransactionInsightUseCase] invocation (spec 1.9, INV-19).
 *
 * Every invocation ends in exactly one of [Generated] or [Skipped], or rethrows cancellation.
 * Cancellation is never an outcome.
 */
sealed interface TransactionInsightOutcome {

    /** The in-memory insight handoff. It is never written to `ai_artifacts` (INV-20). */
    data class Generated(val artifact: AiArtifactRecord) : TransactionInsightOutcome

    data class Skipped(val reason: TransactionInsightSkipReason) : TransactionInsightOutcome

    /** The artifact for [Generated], otherwise null. Callers use this to feed recommendation text. */
    val artifactOrNull: AiArtifactRecord?
        get() = (this as? Generated)?.artifact
}

/**
 * Closed, bounded reason set for a skipped insight. The enum name is the only diagnostic
 * text recorded for a skip (INV-3, INV-19): never a message, throwable or payload.
 */
enum class TransactionInsightSkipReason {
    AI_DISABLED,
    INSIGHT_DISABLED,
    ON_DEVICE_NOT_ALLOWED,
    APP_NOT_FOREGROUND,
    ROUTE_NOT_ON_DEVICE,
    ROUTE_REJECTED,
    BACKGROUND_USE_BLOCKED,
    BUSY,
    QUOTA_EXCEEDED,
    MODEL_UNAVAILABLE,
    TIMEOUT,
    EMPTY_OUTPUT,
    PROVIDER_ERROR
}
