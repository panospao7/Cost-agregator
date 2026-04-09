package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.DashboardBriefingService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Generates (or returns a cached) AI dashboard briefing artifact.
 *
 * Flow:
 * 1. Gate: exit immediately if AI or dashboard briefing is disabled in settings.
 * 2. Build the typed [DashboardBriefingInput] from the current [ProcessedDashboardData].
 * 3. Derive a [targetKey] = "dashboard_home:yyyy-MM-dd".
 * 4. Check the artifact cache: if a READY artifact already exists for today and
 *    is still within TTL, skip generation (the ViewModel observes the flow).
 * 5. Persist a RUNNING tombstone, call [DashboardBriefingService.generate], then
 *    upsert either a READY or FAILED artifact.
 *
 * Returning normally (no exception) always means the artifact store is in a
 * consistent state — callers do not need to inspect a return value.
 */
class GenerateDashboardBriefingUseCase @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiArtifactRepository: AiArtifactRepository,
    private val dashboardBriefingService: DashboardBriefingService,
    private val aiCapabilityRouter: AiCapabilityRouter,
    private val inputBuilder: DashboardBriefingInputBuilder,
    private val timeProvider: TimeProvider
) {

    suspend operator fun invoke(
        processedData: ProcessedDashboardData,
        eventTimeMillis: Long? = null
    ) {
        // ── 1. Settings gate ─────────────────────────────────────────────────
        val settings = aiSettingsRepository.settings().first()
        if (!settings.aiEnabled || !settings.dashboardBriefingEnabled) {
            Timber.d("GenerateDashboardBriefingUseCase: AI or briefing disabled, skipping.")
            return
        }

        // ── 2. Build input ───────────────────────────────────────────────────
        val input = inputBuilder.build(processedData, eventTimeMillis)
        val routeDecision = aiCapabilityRouter.decide(AiCapability.DASHBOARD_BRIEFING, settings)
        if (routeDecision.route == AiRoute.DISABLED) {
            Timber.d("GenerateDashboardBriefingUseCase: router disabled briefing generation, skipping.")
            return
        }

        // ── 3. Derive target key ─────────────────────────────────────────────
        val targetKey = "dashboard_home:${input.dateKey}"
        val now       = eventTimeMillis ?: timeProvider.now()

        // ── 4. Cache freshness check ─────────────────────────────────────────
        val existing = aiArtifactRepository.getLatest(targetKey, AiCapability.DASHBOARD_BRIEFING)
        if (existing != null &&
            existing.status == AiArtifactStatus.READY &&
            existing.promptVersion == AppConfig.Ai.PROMPT_VERSION_DASHBOARD &&
            existing.expiresAt != null && existing.expiresAt > now
        ) {
            Timber.d("GenerateDashboardBriefingUseCase: fresh artifact found, skipping generation.")
            return
        }

        // ── 5a. Persist RUNNING tombstone ────────────────────────────────────
        val sourceHash = input.hashCode().toString()
        val baseEntity = AiArtifactRecord(
            targetType    = AiTargetType.DASHBOARD,
            targetKey     = targetKey,
            capability    = AiCapability.DASHBOARD_BRIEFING,
            status        = AiArtifactStatus.RUNNING,
            mode          = routeDecision.route.toArtifactMode(),
            provider      = routeDecision.providerName,
            modelName     = routeDecision.modelName,
            promptVersion = AppConfig.Ai.PROMPT_VERSION_DASHBOARD,
            sourceHash    = sourceHash,
            createdAt     = now,
            updatedAt     = now,
            expiresAt     = now + AppConfig.Ai.DASHBOARD_BRIEFING_TTL_MS
        )
        aiArtifactRepository.upsert(baseEntity)

        // ── 5b. Generate ─────────────────────────────────────────────────────
        try {
            val serviceResult = dashboardBriefingService.generate(input)

            val finalEntity = when (serviceResult) {
                is AiServiceResult.Success -> baseEntity.copy(
                    status      = AiArtifactStatus.READY,
                    summaryText = serviceResult.value.text
                        .take(AppConfig.Ai.MAX_BRIEFING_LENGTH_CHARS),
                    updatedAt   = timeProvider.now()
                )
                is AiServiceResult.Failure -> baseEntity.copy(
                    status       = AiArtifactStatus.FAILED,
                    errorMessage = failureMessage(serviceResult.error.toReadableMessage(), routeDecision),
                    updatedAt    = timeProvider.now()
                )
            }

            aiArtifactRepository.upsert(finalEntity)
            Timber.d("GenerateDashboardBriefingUseCase: artifact stored with status ${finalEntity.status}.")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "GenerateDashboardBriefingUseCase: generation failed")
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
    AiServiceError.Timeout -> "Dashboard briefing timed out"
    AiServiceError.Offline -> "No network connection"
    AiServiceError.SslError -> "Secure connection failed"
    is AiServiceError.HttpError -> "HTTP $code"
    is AiServiceError.ParseError -> message ?: "Response parse error"
    is AiServiceError.Disabled -> reason
    is AiServiceError.Unknown -> message ?: "Unknown service error"
}

private fun AiRoute.toArtifactMode(): AiMode = when (this) {
    AiRoute.ON_DEVICE -> AiMode.ON_DEVICE
    AiRoute.CLOUD -> AiMode.CLOUD
    AiRoute.DETERMINISTIC_FALLBACK,
    AiRoute.DISABLED -> AiMode.AUTO
}

private fun failureMessage(message: String?, routeDecision: AiRouteDecision): String {
    val base = message?.takeIf { it.isNotBlank() } ?: "AI generation failed."
    val providerPart = routeDecision.providerName ?: "none"
    val modelPart = routeDecision.modelName ?: "none"
    return "$base Route: ${routeDecision.route.name}, provider: $providerPart, model: $modelPart. Reason: ${routeDecision.reason}".take(200)
}
