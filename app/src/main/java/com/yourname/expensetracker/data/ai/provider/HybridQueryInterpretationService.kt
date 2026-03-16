package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.QueryInterpretationService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridQueryInterpretationService @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val router: AiCapabilityRouter,
    private val cloudQueryInterpretationService: CloudQueryInterpretationService,
    private val onDeviceQueryInterpretationService: OnDeviceQueryInterpretationService,
    private val noOpQueryInterpretationService: NoOpQueryInterpretationService
) : QueryInterpretationService {

    override suspend fun interpret(
        input: FinancialQueryInterpretationInput
    ): FinancialQueryInterpretationResult {
        val settings = aiSettingsRepository.settings().first()
        return when (router.decide(AiCapability.QUERY_INTERPRETATION, settings).route) {
            AiRoute.CLOUD -> cloudQueryInterpretationService.interpret(input)
            AiRoute.ON_DEVICE -> onDeviceQueryInterpretationService.interpret(input)
            AiRoute.DETERMINISTIC_FALLBACK,
            AiRoute.DISABLED -> noOpQueryInterpretationService.interpret(input)
        }
    }
}
