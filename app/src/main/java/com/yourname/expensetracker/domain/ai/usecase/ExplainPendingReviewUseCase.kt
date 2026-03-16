package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReviewExplanationService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/**
 * Generates (or returns a cached) AI explanation artifact for a pending review.
 *
 * Flow:
 * 1. Gate: exit immediately if AI or review explanation is disabled in settings.
 * 2. Build the typed [ReviewExplanationInput] (with redaction/clamping applied).
 * 3. Derive [targetKey] = "pending_review:<reviewId>".
 * 4. Check the artifact cache: if a READY artifact exists and is within TTL
 *    for the current prompt version, skip generation (ViewModel observes the flow).
 * 5. Persist a RUNNING tombstone, call [ReviewExplanationService.generate], then
 *    upsert either a READY or FAILED artifact.
 *
 * Returning normally (no exception) always means the artifact store is in a
 * consistent state — callers do not need to inspect a return value.
 */
class ExplainPendingReviewUseCase @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiArtifactRepository: AiArtifactRepository,
    private val reviewExplanationService: ReviewExplanationService,
    private val aiCapabilityRouter: AiCapabilityRouter,
    private val inputBuilder: ReviewExplanationInputBuilder,
    private val timeProvider: TimeProvider
) {

    suspend operator fun invoke(review: PendingReview) {
        // ── 1. Settings gate ─────────────────────────────────────────────────
        val settings = aiSettingsRepository.settings().first()
        if (!settings.aiEnabled || !settings.reviewExplanationEnabled) {
            Timber.d("ExplainPendingReviewUseCase: AI or review explanation disabled, skipping.")
            return
        }

        // ── 2. Build input (with redaction + clamping) ───────────────────────
        val input = inputBuilder.build(review, settings)
        val routeDecision = aiCapabilityRouter.decide(AiCapability.REVIEW_EXPLANATION, settings)
        if (routeDecision.route == com.yourname.expensetracker.domain.ai.model.AiRoute.DISABLED) {
            Timber.d("ExplainPendingReviewUseCase: router disabled review explanation, skipping.")
            return
        }

        // ── 3. Derive target key ─────────────────────────────────────────────
        val targetKey = "pending_review:${review.id}"
        val now       = timeProvider.now()

        // ── 4. Cache freshness check ─────────────────────────────────────────
        val existing = aiArtifactRepository.getLatest(targetKey, AiCapability.REVIEW_EXPLANATION)
        if (existing != null &&
            existing.status == AiArtifactStatus.READY &&
            existing.promptVersion == AppConfig.Ai.PROMPT_VERSION_REVIEW &&
            existing.expiresAt != null && existing.expiresAt > now
        ) {
            Timber.d("ExplainPendingReviewUseCase: fresh artifact found for review ${review.id}, skipping generation.")
            return
        }

        // ── 5a. Persist RUNNING tombstone ────────────────────────────────────
        val sourceHash = input.hashCode().toString()
        val baseEntity = AiArtifactEntity(
            targetType    = AiTargetType.PENDING_REVIEW,
            targetKey     = targetKey,
            capability    = AiCapability.REVIEW_EXPLANATION,
            status        = AiArtifactStatus.RUNNING,
            mode          = when (routeDecision.route) {
                com.yourname.expensetracker.domain.ai.model.AiRoute.ON_DEVICE -> com.yourname.expensetracker.domain.ai.model.AiMode.ON_DEVICE
                com.yourname.expensetracker.domain.ai.model.AiRoute.CLOUD -> com.yourname.expensetracker.domain.ai.model.AiMode.CLOUD
                com.yourname.expensetracker.domain.ai.model.AiRoute.DETERMINISTIC_FALLBACK,
                com.yourname.expensetracker.domain.ai.model.AiRoute.DISABLED -> com.yourname.expensetracker.domain.ai.model.AiMode.AUTO
            },
            provider      = routeDecision.providerName,
            modelName     = routeDecision.modelName,
            promptVersion = AppConfig.Ai.PROMPT_VERSION_REVIEW,
            sourceHash    = sourceHash,
            createdAt     = now,
            updatedAt     = now,
            expiresAt     = now + AppConfig.Ai.REVIEW_EXPLANATION_TTL_MS
        )
        aiArtifactRepository.upsert(baseEntity)

        // ── 5b. Generate ─────────────────────────────────────────────────────
        try {
            val explanation = reviewExplanationService.generate(input)

            val finalEntity = if (explanation != null) {
                baseEntity.copy(
                    status          = AiArtifactStatus.READY,
                    summaryText     = explanation.headline,
                    explanationText = explanation.body,
                    updatedAt       = timeProvider.now()
                )
            } else {
                // No-op provider or provider declined — mark failed so we don't retry
                // until the user explicitly requests again.
                baseEntity.copy(
                    status       = AiArtifactStatus.FAILED,
                    errorMessage = routeDecision.reason,
                    updatedAt    = timeProvider.now()
                )
            }

            aiArtifactRepository.upsert(finalEntity)
            Timber.d("ExplainPendingReviewUseCase: artifact stored with status ${finalEntity.status} for review ${review.id}.")

        } catch (e: Exception) {
            Timber.e(e, "ExplainPendingReviewUseCase: generation failed for review ${review.id}")
            aiArtifactRepository.upsert(
                baseEntity.copy(
                    status       = AiArtifactStatus.FAILED,
                    errorMessage = e.message?.take(200),
                    updatedAt    = timeProvider.now()
                )
            )
        }
    }
}
