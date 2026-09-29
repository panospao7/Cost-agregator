package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.HybridRouter
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.CategorizationAssistService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridCategorizationAssistService @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val router: AiCapabilityRouter,
    private val cloudCategorizationAssistService: CloudCategorizationAssistService,
    private val onDeviceCategorizationAssistService: OnDeviceCategorizationAssistService,
    private val noOpCategorizationAssistService: NoOpCategorizationAssistService
) : CategorizationAssistService {

    private val hybridRouter = HybridRouter<CategorizationAssistInput, CategoryAssistSuggestion?>(
        aiSettingsRepository = aiSettingsRepository,
        router = router,
        capability = AiCapability.CATEGORIZATION_FALLBACK,
        cloudFn = { cloudCategorizationAssistService.suggest(it) },
        onDeviceFn = { onDeviceCategorizationAssistService.suggest(it) },
        fallbackFn = { noOpCategorizationAssistService.suggest(it) }
    )

    override suspend fun suggest(input: CategorizationAssistInput): CategoryAssistSuggestion? =
        hybridRouter.execute(input)
}
