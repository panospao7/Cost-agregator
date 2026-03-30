package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.DashboardBriefingService
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Generates AI insights for individual transactions (used by recommendations).
 *
 * This is a lightweight, synchronous-style use case that:
 * 1. Checks if AI is enabled
 * 2. Generates a short AI insight about the transaction (using dashboard briefing service)
 * 3. Returns an AiArtifactEntity (or null if AI fails/disabled)
 * 4. Times out after 3 seconds to avoid blocking recommendation generation
 *
 * Design notes:
 * - Uses DASHBOARD_BRIEFING capability (repurposed for transaction insights)
 * - Synchronous with timeout (3s max)
 * - No caching (recommendations are ephemeral)
 * - Returns null on any error (graceful degradation)
 */
class GenerateTransactionInsightUseCase @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiArtifactRepository: AiArtifactRepository,
    private val dashboardBriefingService: DashboardBriefingService,
    private val aiCapabilityRouter: AiCapabilityRouter,
    private val timeProvider: TimeProvider
) {

    companion object {
        private const val TIMEOUT_MS = 3000L
        private const val PROMPT_VERSION = "transaction_insight_v1"
    }

    /**
     * Generate an AI insight for a transaction.
     *
     * @param transaction The transaction to generate an insight for
     * @return AiArtifactEntity with insight text, or null if AI is disabled/fails
     */
    suspend operator fun invoke(transaction: Expense): AiArtifactEntity? {
        return withTimeoutOrNull(TIMEOUT_MS) {
            try {
                generateInsightInternal(transaction)
            } catch (e: Exception) {
                Timber.w(e, "GenerateTransactionInsightUseCase: failed for transaction ${transaction.id}")
                null
            }
        } ?: run {
            Timber.w("GenerateTransactionInsightUseCase: timed out after ${TIMEOUT_MS}ms")
            null
        }
    }

    private suspend fun generateInsightInternal(transaction: Expense): AiArtifactEntity? {
        // ── 1. Settings gate ─────────────────────────────────────────────────
        val settings = aiSettingsRepository.settings().first()
        Timber.d("GenerateTransactionInsightUseCase: Step 1 - Settings: aiEnabled=${settings.aiEnabled}, dashboardBriefingEnabled=${settings.dashboardBriefingEnabled}")
        
        if (!settings.aiEnabled || !settings.dashboardBriefingEnabled) {
            Timber.d("GenerateTransactionInsightUseCase: Step 1 FAILED - AI or briefing disabled")
            return null
        }

        // ── 2. Router check ──────────────────────────────────────────────────
        val routeDecision = aiCapabilityRouter.decide(AiCapability.DASHBOARD_BRIEFING, settings)
        Timber.d("GenerateTransactionInsightUseCase: Step 2 - Router: route=${routeDecision.route}, provider=${routeDecision.providerName}, model=${routeDecision.modelName}")
        
        if (routeDecision.route == AiRoute.DISABLED) {
            Timber.d("GenerateTransactionInsightUseCase: Step 2 FAILED - router disabled briefing")
            return null
        }

        // ── 3. Build minimal input ───────────────────────────────────────────
        val input = buildTransactionInput(transaction)
        Timber.d("GenerateTransactionInsightUseCase: Step 3 - Input built: headline='${input.weatherHeadline}', summary='${input.weatherSummary}', amount=${input.totalCommitted}")

        // ── 4. Generate insight ──────────────────────────────────────────────
        val now = timeProvider.now()
        val targetKey = "transaction:${transaction.id}"
        val sourceHash = transaction.hashCode().toString()

        val baseEntity = AiArtifactEntity(
            targetType = AiTargetType.EXPENSE,
            targetId = transaction.id,
            targetKey = targetKey,
            capability = AiCapability.DASHBOARD_BRIEFING,
            status = AiArtifactStatus.RUNNING,
            mode = routeDecision.route.toArtifactMode(),
            provider = routeDecision.providerName,
            modelName = routeDecision.modelName,
            promptVersion = PROMPT_VERSION,
            sourceHash = sourceHash,
            createdAt = now,
            updatedAt = now,
            expiresAt = now + AppConfig.Ai.DASHBOARD_BRIEFING_TTL_MS
        )

        Timber.d("GenerateTransactionInsightUseCase: Step 4 - Calling dashboardBriefingService.generate()...")
        val briefing = dashboardBriefingService.generate(input)
        
        if (briefing != null) {
            Timber.d("GenerateTransactionInsightUseCase: Step 4 SUCCESS - briefing text length: ${briefing.text.length}")
            return baseEntity.copy(
                status = AiArtifactStatus.READY,
                summaryText = briefing.text.take(AppConfig.Ai.MAX_BRIEFING_LENGTH_CHARS),
                updatedAt = timeProvider.now()
            )
        } else {
            Timber.w("GenerateTransactionInsightUseCase: Step 4 FAILED - briefing was null/empty")
            return null
        }
    }

    /**
     * Build a minimal DashboardBriefingInput from a transaction.
     * 
     * This creates a simple context for the AI to generate an insight.
     */
    private fun buildTransactionInput(transaction: Expense): DashboardBriefingInput {
        val dateKey = java.time.Instant.ofEpochMilli(transaction.date)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()

        return DashboardBriefingInput(
            dateKey = dateKey,
            weatherHeadline = "New transaction recorded",
            weatherSummary = "${transaction.merchant ?: "Unknown"} - €${transaction.amount}",
            discretionaryBudget = 0.0,
            totalCommitted = transaction.amount,
            totalLikely = 0.0,
            pendingReviewCount = 0,
            currentMonthSpent = transaction.amount,
            topCategories = emptyList(),
            budgetWarnings = buildList {
                if (transaction.amount > 100.0) {
                    add("High-value transaction: €${transaction.amount}")
                }
            },
            upcomingItems = buildList {
                transaction.merchant?.let { merchant ->
                    add("Transaction from: $merchant")
                }
            }
        )
    }

    private fun AiRoute.toArtifactMode(): AiMode = when (this) {
        AiRoute.ON_DEVICE -> AiMode.ON_DEVICE
        AiRoute.CLOUD -> AiMode.CLOUD
        AiRoute.DETERMINISTIC_FALLBACK,
        AiRoute.DISABLED -> AiMode.AUTO
    }
}
