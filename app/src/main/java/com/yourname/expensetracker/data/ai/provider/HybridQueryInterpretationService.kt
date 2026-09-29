package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.HybridRouter
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.QueryInterpretationService
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

    private val hybridRouter = HybridRouter<FinancialQueryInterpretationInput, FinancialQueryInterpretationResult>(
        aiSettingsRepository = aiSettingsRepository,
        router = router,
        capability = AiCapability.QUERY_INTERPRETATION,
        cloudFn = { cloudQueryInterpretationService.interpret(it) },
        onDeviceFn = { onDeviceQueryInterpretationService.interpret(it) },
        fallbackFn = { noOpQueryInterpretationService.interpret(it) }
    )

    override suspend fun interpret(input: FinancialQueryInterpretationInput): FinancialQueryInterpretationResult =
        hybridRouter.execute(input)
}
