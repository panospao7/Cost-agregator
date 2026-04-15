package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
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
import kotlin.coroutines.cancellation.CancellationException

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
        val sourceHash = input.hashCode().toString()

        // ── 4. Cache freshness check ─────────────────────────────────────────
        val existing = aiArtifactRepository.getLatest(targetKey, AiCapability.REVIEW_EXPLANATION)
        if (existing.isFreshArtifact(AppConfig.Ai.PROMPT_VERSION_REVIEW, sourceHash, now)) {
            Timber.d("ExplainPendingReviewUseCase: fresh artifact found for review ${review.id}, skipping generation.")
            return
        }

        // ── 5a. Persist RUNNING tombstone ────────────────────────────────────
        val baseEntity = AiArtifactRecord(
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
            val serviceResult = reviewExplanationService.generate(input)

            val finalEntity = when (serviceResult) {
                is AiServiceResult.Success -> {
                    val explanation = serviceResult.value
                    baseEntity.copy(
                        status          = AiArtifactStatus.READY,
                        summaryText     = explanation.headline,
                        explanationText = explanation.body.withRouteDiagnostics(routeDecision),
                        updatedAt       = timeProvider.now()
                    )
                }
                is AiServiceResult.Failure -> {
                    baseEntity.copy(
                        status       = AiArtifactStatus.FAILED,
                        errorMessage = failureMessage(serviceResult.error.toReadableMessage(), routeDecision),
                        updatedAt    = timeProvider.now()
                    )
                }
            }

            aiArtifactRepository.upsert(finalEntity)
            Timber.d("ExplainPendingReviewUseCase: artifact stored with status ${finalEntity.status} for review ${review.id}.")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "ExplainPendingReviewUseCase: generation failed for review ${review.id}")
            aiArtifactRepository.upsert(
                baseEntity.copy(
                    status       = AiArtifactStatus.FAILED,
                    errorMessage = failureMessage(e.message, routeDecision),
                    updatedAt    = timeProvider.now()
                )
            )
        }
    }
}

private fun AiServiceError.toReadableMessage(): String = when (this) {
    AiServiceError.Timeout -> "Review explanation timed out"
    AiServiceError.Offline -> "No network connection"
    AiServiceError.SslError -> "Secure connection failed"
    is AiServiceError.HttpError -> "HTTP $code"
    is AiServiceError.ParseError -> message ?: "Response parse error"
    is AiServiceError.Disabled -> reason
    is AiServiceError.Unknown -> message ?: "Unknown service error"
}

private fun String?.withRouteDiagnostics(routeDecision: AiRouteDecision): String {
    val diagnostic = routeDecision.toRouteDiagnosticLine()
    val combined = listOfNotNull(this?.takeIf { it.isNotBlank() }, diagnostic).joinToString("\n")
    return combined.take(AppConfig.Ai.MAX_REVIEW_EXPLANATION_BODY_CHARS)
}

private fun failureMessage(message: String?, routeDecision: AiRouteDecision): String {
    val base = message?.takeIf { it.isNotBlank() } ?: "AI generation failed."
    return "$base ${routeDecision.toRouteDiagnosticLine()}".take(200)
}

private fun AiRouteDecision.toRouteDiagnosticLine(): String {
    val providerPart = providerName ?: "none"
    val modelPart = modelName ?: "none"
    return "Route: ${route.name}, provider: $providerPart, model: $modelPart. Reason: $reason"
}
