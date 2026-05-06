package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReceiptAssistService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

// AID-4: This service can be simplified by using HybridRouter:
// val router = HybridRouter(aiSettingsRepository, router, AiCapability.RECEIPT_EXTRACTION,
//     cloudFn = { cloudReceiptAssistService.suggest(it) },
//     onDeviceFn = { onDeviceReceiptAssistService.suggest(it) },
//     fallbackFn = { noOpReceiptAssistService.suggest(it) }
// )
// override suspend fun suggest(input: ReceiptAssistInput): AiServiceResult<ReceiptAssistSuggestion> = router.execute(input)
@Singleton
class HybridReceiptAssistService @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val router: AiCapabilityRouter,
    private val cloudReceiptAssistService: CloudReceiptAssistService,
    private val onDeviceReceiptAssistService: OnDeviceReceiptAssistService,
    private val noOpReceiptAssistService: NoOpReceiptAssistService
) : ReceiptAssistService {

    override suspend fun suggest(input: ReceiptAssistInput): AiServiceResult<ReceiptAssistSuggestion> {
        val settings = aiSettingsRepository.settings().first()
        return when (router.decide(AiCapability.RECEIPT_EXTRACTION, settings).route) {
            AiRoute.CLOUD -> cloudReceiptAssistService.suggest(input)
            AiRoute.ON_DEVICE -> onDeviceReceiptAssistService.suggest(input)
            AiRoute.DETERMINISTIC_FALLBACK,
            AiRoute.DISABLED -> noOpReceiptAssistService.suggest(input)
        }
    }

    // usedImageInput(input) intentionally not overridden — the interface default returns false,
    // which is the only safe stateless answer here. Actual per-request image usage lives in
    // ReceiptAssistSuggestion.usedImageInput returned by each delegate's suggest() call.
    // Delegating to cloudReceiptAssistService here would over-report image usage on
    // ON_DEVICE / DISABLED / DETERMINISTIC_FALLBACK routes where cloud image analysis
    // does not run.
}
