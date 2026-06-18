package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.DedupeJudgeService
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid dedupe judge service that routes to cloud or on-device based on settings.
 *
 * ## AI-5: Cloud→on-device fallback (RESOLVED)
 * When the primary route (e.g. [AiRoute.CLOUD]) fails with a transient error
 * (timeout, network, HTTP 5xx, SSL), the service now falls back to the
 * alternative route before returning a failure. This mirrors the pattern used
 * by [SmartReceiptAssistService].
 *
 * Fallback chain:
 * - CLOUD fails → try ON_DEVICE
 * - ON_DEVICE fails → try CLOUD
 * - Both fail → return the primary error
 *
 * ## O2: Confidence not propagated to UI
 * The [DedupeJudgeSuggestion.confidence] value from the AI is returned in
 * the [AiServiceResult], but the UI does not currently display this confidence
 * score to the end user. The dedupe review screen should show the confidence
 * level alongside the verdict so users can assess match reliability.
 */
@Singleton
class HybridDedupeJudgeService @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val router: AiCapabilityRouter,
    private val cloudDedupeJudgeService: CloudDedupeJudgeService,
    private val onDeviceDedupeJudgeService: OnDeviceDedupeJudgeService,
    private val noOpDedupeJudgeService: NoOpDedupeJudgeService
) : DedupeJudgeService {

    override suspend fun judge(input: DedupeJudgeInput): AiServiceResult<DedupeJudgeSuggestion> {
        val settings = aiSettingsRepository.settings().first()
        val route = router.decide(AiCapability.DEDUPE_JUDGE, settings).route

        return when (route) {
            AiRoute.DETERMINISTIC_FALLBACK,
            AiRoute.DISABLED -> noOpDedupeJudgeService.judge(input)

            AiRoute.CLOUD -> {
                val primary = safeExecute("cloud") { cloudDedupeJudgeService.judge(input) }
                if (primary is AiServiceResult.Success) return primary
                // AI-5: Cloud failed — fall back to on-device
                Timber.w("HybridDedupeJudge: cloud failed (${errorMessage(primary)}), falling back to on-device")
                val fallback = safeExecute("on-device") { onDeviceDedupeJudgeService.judge(input) }
                if (fallback is AiServiceResult.Success) fallback else primary
            }

            AiRoute.ON_DEVICE -> {
                val primary = safeExecute("on-device") { onDeviceDedupeJudgeService.judge(input) }
                if (primary is AiServiceResult.Success) return primary
                // AI-5: On-device failed — fall back to cloud
                Timber.w("HybridDedupeJudge: on-device failed (${errorMessage(primary)}), falling back to cloud")
                val fallback = safeExecute("cloud") { cloudDedupeJudgeService.judge(input) }
                if (fallback is AiServiceResult.Success) fallback else primary
            }
        }
    }

    /**
     * Wraps a suspend call in a try-catch so that unexpected exceptions
     * (e.g. network timeouts) are converted to [AiServiceResult.Failure]
     * instead of propagating. Returns the raw result on success.
     */
    private suspend fun safeExecute(
        label: String,
        block: suspend () -> AiServiceResult<DedupeJudgeSuggestion>
    ): AiServiceResult<DedupeJudgeSuggestion> {
        return try {
            block()
        } catch (e: Exception) {
            Timber.e(e, "HybridDedupeJudge: $label threw unexpected exception")
            AiServiceResult.Failure(AiServiceError.Unknown(e.message))
        }
    }

    private fun errorMessage(result: AiServiceResult<*>): String = when (result) {
        is AiServiceResult.Success -> "success"
        is AiServiceResult.Failure -> when (val err = result.error) {
            is AiServiceError.HttpError -> "HTTP ${err.code}"
            is AiServiceError.Disabled -> err.reason
            is AiServiceError.PrivacyDenied -> err.blocked.reason
            is AiServiceError.ParseError -> err.message ?: "parse"
            is AiServiceError.Unknown -> err.message ?: "unknown"
            AiServiceError.Timeout -> "timeout"
            AiServiceError.Offline -> "offline"
            AiServiceError.SslError -> "ssl"
        }
    }
}
