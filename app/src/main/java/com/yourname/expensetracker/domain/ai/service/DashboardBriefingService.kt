package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.DashboardBriefing
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput

/**
 * Domain contract for AI-powered dashboard briefing generation.
 *
 * Implementations may be:
 * - [NoOpDashboardBriefingService]  — always returns null; used when AI is disabled or
 *   no real provider is configured (the default for PR 2).
 * - A real on-device or cloud provider wired in a future phase.
 *
 * The use case ([GenerateDashboardBriefingUseCase]) is responsible for:
 *  - checking cache freshness before calling this service,
 *  - persisting the returned [DashboardBriefing] as an [AiArtifactEntity],
 *  - handling null (no-op) returns gracefully.
 *
 * Returning `null` is a valid "I have nothing to say" response and must never
 * cause the app to crash or show an error to the user.
 */
interface DashboardBriefingService {
    /**
     * Generate a briefing from [input].
     *
     * @return A [DashboardBriefing] on success, or `null` if the provider is
     *         unavailable, disabled, or intentionally silent for this input.
     */
    suspend fun generate(input: DashboardBriefingInput): DashboardBriefing?
}
