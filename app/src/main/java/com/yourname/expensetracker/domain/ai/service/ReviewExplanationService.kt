package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.ReviewExplanation
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput

/**
 * Domain contract for AI-powered review explanation generation.
 *
 * Implementations may be:
 * - [NoOpReviewExplanationService]  — always returns null; used when AI is disabled or
 *   no real provider is configured (the default for PR 3).
 * - A real on-device or cloud provider wired in a future phase.
 *
 * The use case ([ExplainPendingReviewUseCase]) is responsible for:
 *  - checking settings gates before calling this service,
 *  - checking cache freshness before calling this service,
 *  - persisting the returned [ReviewExplanation] as an [AiArtifactEntity],
 *  - handling null (no-op) returns gracefully.
 *
 * Returning `null` is a valid "I have nothing to say" response and must never
 * cause the app to crash or show an error to the user.
 */
interface ReviewExplanationService {
    /**
     * Generate an explanation from [input].
     *
     * @return A [ReviewExplanation] on success, or `null` if the provider is
     *         unavailable, disabled, or intentionally silent for this input.
     */
    suspend fun generate(input: ReviewExplanationInput): ReviewExplanation?
}
