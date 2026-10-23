package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.ai.provider.OnDeviceDashboardBriefingService
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.AppForegroundState
import com.yourname.expensetracker.domain.ai.util.AiArtifactSourceHash
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Generates an on-device AI insight for one transaction (used by recommendation enrichment).
 *
 * Decision flow (spec 3.3, one invocation):
 * 1. Settings gate, evaluated once: aiEnabled, then transactionInsightEnabled, then allowOnDeviceAi.
 * 2. Foreground short-circuit (INV-17) before any router or AICore call.
 * 3. Router decision for [AiCapability.TRANSACTION_INSIGHT]; only ON_DEVICE proceeds (INV-16).
 * 4. Unredacted input (data stays on device) to [OnDeviceDashboardBriefingService].
 *
 * Steps 3 and 4 together are bounded by [AppConfig.Ai.TRANSACTION_INSIGHT_TIMEOUT_MS] (INV-18),
 * so a stalled model-status check in the router cannot starve the caller.
 *
 * Design notes:
 * - Depends on the on-device service only. There is no cloud, hybrid or privacy-settings
 *   dependency, so the insight can never reach a cloud provider (INV-16).
 * - The record handed back is in-memory only; it is never persisted to `ai_artifacts` (INV-20).
 * - Never retried. Every invocation ends in exactly one [TransactionInsightOutcome], or rethrows
 *   cancellation (INV-4, INV-19). The skip reason enum name is the only diagnostic text (INV-3).
 */
class GenerateTransactionInsightUseCase @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val onDeviceBriefingService: OnDeviceDashboardBriefingService,
    private val aiCapabilityRouter: AiCapabilityRouter,
    private val appForegroundState: AppForegroundState,
    private val inputBuilder: TransactionInsightInputBuilder,
    private val timeProvider: TimeProvider,
    private val aiRuntimeDiagnostics: AiRuntimeDiagnostics
) {

    companion object {
        private const val PROMPT_VERSION = "transaction_insight_v1"
        private const val DIAGNOSTIC_TYPE = "transaction_insight"
    }

    /**
     * Generate an on-device insight for a transaction.
     *
     * @param transaction The transaction to generate an insight for
     * @return [TransactionInsightOutcome.Generated] with the in-memory artifact, or
     *   [TransactionInsightOutcome.Skipped] with a bounded reason. Cancellation is rethrown.
     */
    suspend operator fun invoke(transaction: Expense): TransactionInsightOutcome {
        val outcome = try {
            evaluate(transaction)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // INV-3: class name only; never the message, the throwable or transaction content.
            Timber.w("GenerateTransactionInsightUseCase: PROVIDER_ERROR class=%s", e::class.java.simpleName)
            TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.PROVIDER_ERROR)
        }
        if (outcome is TransactionInsightOutcome.Skipped) {
            aiRuntimeDiagnostics.recordInteraction(DIAGNOSTIC_TYPE, outcome.reason.name)
        }
        return outcome
    }

    private suspend fun evaluate(transaction: Expense): TransactionInsightOutcome {
        // ── S0. Settings gate (read once) ────────────────────────────────────
        val settings = aiSettingsRepository.settings().first()
        if (!settings.aiEnabled) return skipped(TransactionInsightSkipReason.AI_DISABLED)
        if (!settings.transactionInsightEnabled) return skipped(TransactionInsightSkipReason.INSIGHT_DISABLED)
        if (!settings.allowOnDeviceAi) return skipped(TransactionInsightSkipReason.ON_DEVICE_NOT_ALLOWED)

        // ── S1. Foreground short-circuit: no router/AICore call of any kind ──
        if (!appForegroundState.isAppInForeground()) {
            return skipped(TransactionInsightSkipReason.APP_NOT_FOREGROUND)
        }

        // ── S2 + S3 under one bound (INV-18): router decision (which may query the AICore
        // model status), input build and generation. A timeout is Skipped(TIMEOUT); an outer
        // cancellation is not a timeout and still propagates out of withTimeoutOrNull. ──
        return withTimeoutOrNull(AppConfig.Ai.TRANSACTION_INSIGHT_TIMEOUT_MS) {
            routeAndGenerate(transaction, settings)
        } ?: skipped(TransactionInsightSkipReason.TIMEOUT)
    }

    private suspend fun routeAndGenerate(
        transaction: Expense,
        settings: AiSettings
    ): TransactionInsightOutcome {
        // ── S2. Router decision ──────────────────────────────────────────────
        val routeDecision = aiCapabilityRouter.decide(AiCapability.TRANSACTION_INSIGHT, settings)
        when (routeDecision.route) {
            AiRoute.ON_DEVICE -> Unit
            // Defect guard: policy forbids a cloud route for this capability (INV-16).
            AiRoute.CLOUD -> return skipped(TransactionInsightSkipReason.ROUTE_REJECTED)
            AiRoute.DETERMINISTIC_FALLBACK,
            AiRoute.DISABLED -> return skipped(TransactionInsightSkipReason.ROUTE_NOT_ON_DEVICE)
        }

        // ── S3. Build unredacted input and generate on device ────────────────
        val input = inputBuilder.build(transaction, false)
        val now = timeProvider.now()

        val briefingResult = onDeviceBriefingService.generate(input)

        return when (briefingResult) {
            is AiServiceResult.Success -> {
                val text = briefingResult.value.text
                if (text.isBlank()) {
                    skipped(TransactionInsightSkipReason.EMPTY_OUTPUT)
                } else {
                    TransactionInsightOutcome.Generated(
                        AiArtifactRecord(
                            targetType = AiTargetType.EXPENSE,
                            targetId = transaction.id,
                            targetKey = "transaction:${transaction.id}",
                            capability = AiCapability.TRANSACTION_INSIGHT,
                            status = AiArtifactStatus.READY,
                            mode = AiMode.ON_DEVICE,
                            provider = routeDecision.providerName,
                            modelName = routeDecision.modelName,
                            promptVersion = PROMPT_VERSION,
                            summaryText = text.take(AppConfig.Ai.MAX_BRIEFING_LENGTH_CHARS),
                            sourceHash = AiArtifactSourceHash.forTransactionInsight(transaction),
                            createdAt = now,
                            updatedAt = timeProvider.now(),
                            expiresAt = now + AppConfig.Ai.TRANSACTION_INSIGHT_TTL_MS
                        )
                    )
                }
            }
            is AiServiceResult.Failure -> skipped(briefingResult.error.toSkipReason())
        }
    }

    private fun skipped(reason: TransactionInsightSkipReason): TransactionInsightOutcome =
        TransactionInsightOutcome.Skipped(reason)

    /**
     * Maps a typed service error to a bounded reason. The on-device service carries GenAI error
     * codes only inside free text, and INV-19 forbids parsing text, so every GenAI failure
     * collapses to PROVIDER_ERROR (accepted collapse, spec 2 INV-19 / G2-12).
     */
    private fun AiServiceError.toSkipReason(): TransactionInsightSkipReason = when (this) {
        AiServiceError.Timeout -> TransactionInsightSkipReason.TIMEOUT
        is AiServiceError.ParseError -> TransactionInsightSkipReason.EMPTY_OUTPUT
        else -> TransactionInsightSkipReason.PROVIDER_ERROR
    }
}
