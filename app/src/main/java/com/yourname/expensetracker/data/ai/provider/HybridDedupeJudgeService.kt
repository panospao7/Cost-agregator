package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.DedupeJudgeService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid dedupe judge service that routes to cloud or on-device based on settings.
 *
 * ## O1: Cloud→on-device fallback missing
 * Unlike [SmartReceiptAssistService], this service does NOT implement a
 * cloud→on-device fallback chain. If the selected route (e.g. [AiRoute.CLOUD])
 * fails (network error, timeout, API error), the failure is returned directly
 * to the caller without attempting the other route. A future enhancement should
 * add a retry/fallback mechanism: try cloud first, and if it fails, fall back
 * to on-device (or vice versa) before returning a failure.
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
        return when (router.decide(AiCapability.DEDUPE_JUDGE, settings).route) {
            AiRoute.CLOUD -> cloudDedupeJudgeService.judge(input)
            AiRoute.ON_DEVICE -> onDeviceDedupeJudgeService.judge(input)
            AiRoute.DETERMINISTIC_FALLBACK,
            AiRoute.DISABLED -> noOpDedupeJudgeService.judge(input)
        }
    }
}
