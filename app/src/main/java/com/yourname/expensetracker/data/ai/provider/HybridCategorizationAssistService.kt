package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.CategorizationAssistService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridCategorizationAssistService @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val router: AiCapabilityRouter,
    private val cloudCategorizationAssistService: CloudCategorizationAssistService,
    private val noOpCategorizationAssistService: NoOpCategorizationAssistService
) : CategorizationAssistService {

    override suspend fun suggest(input: CategorizationAssistInput): CategoryAssistSuggestion? {
        val settings = aiSettingsRepository.settings().first()
        return when (router.decide(AiCapability.CATEGORIZATION_FALLBACK, settings).route) {
            AiRoute.CLOUD -> cloudCategorizationAssistService.suggest(input)
            AiRoute.ON_DEVICE,
            AiRoute.DETERMINISTIC_FALLBACK,
            AiRoute.DISABLED -> noOpCategorizationAssistService.suggest(input)
        }
    }
}
