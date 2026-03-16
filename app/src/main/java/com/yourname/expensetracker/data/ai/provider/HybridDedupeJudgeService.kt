package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.DedupeJudgeService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridDedupeJudgeService @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val router: AiCapabilityRouter,
    private val cloudDedupeJudgeService: CloudDedupeJudgeService,
    private val noOpDedupeJudgeService: NoOpDedupeJudgeService
) : DedupeJudgeService {

    override suspend fun judge(input: DedupeJudgeInput): DedupeJudgeSuggestion? {
        val settings = aiSettingsRepository.settings().first()
        return when (router.decide(AiCapability.DEDUPE_JUDGE, settings).route) {
            AiRoute.CLOUD -> cloudDedupeJudgeService.judge(input)
            AiRoute.ON_DEVICE,
            AiRoute.DETERMINISTIC_FALLBACK,
            AiRoute.DISABLED -> noOpDedupeJudgeService.judge(input)
        }
    }
}
