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

@Singleton
class HybridReceiptAssistService @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val router: AiCapabilityRouter,
    private val cloudReceiptAssistService: CloudReceiptAssistService,
    private val onDeviceReceiptAssistService: OnDeviceReceiptAssistService,
    private val noOpReceiptAssistService: NoOpReceiptAssistService
) : ReceiptAssistService {

    private var lastUsedImageInput = false

    override suspend fun suggest(input: ReceiptAssistInput): AiServiceResult<ReceiptAssistSuggestion> {
        val settings = aiSettingsRepository.settings().first()
        return when (router.decide(AiCapability.RECEIPT_EXTRACTION, settings).route) {
            AiRoute.CLOUD -> {
                lastUsedImageInput = cloudReceiptAssistService.usedImageInput(input) && settings.receiptImageCloudEnabled
                cloudReceiptAssistService.suggest(input)
            }
            AiRoute.ON_DEVICE -> {
                lastUsedImageInput = false
                onDeviceReceiptAssistService.suggest(input)
            }
            AiRoute.DETERMINISTIC_FALLBACK,
            AiRoute.DISABLED -> {
                lastUsedImageInput = false
                noOpReceiptAssistService.suggest(input)
            }
        }
    }

    override fun usedImageInput(input: ReceiptAssistInput): Boolean = lastUsedImageInput
}
