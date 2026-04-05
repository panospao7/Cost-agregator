package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReceiptItemCategorizationService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridReceiptItemCategorizationService @Inject constructor(
    private val onDeviceService: OnDeviceReceiptItemCategorizationService,
    private val cloudService: CloudReceiptItemCategorizationService,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiCapabilityRouter: AiCapabilityRouter
) : ReceiptItemCategorizationService {
    
    override suspend fun categorizeItems(input: ReceiptItemCategorizationInput): ReceiptItemCategorizationResult? {
        val settings = aiSettingsRepository.settings().first()

        val routeDecision = aiCapabilityRouter.decide(AiCapability.RECEIPT_ITEM_CATEGORIZATION, settings)
        return when (routeDecision.route) {
            AiRoute.CLOUD -> cloudService.categorizeItems(input)
            AiRoute.ON_DEVICE -> onDeviceService.categorizeItems(input)
            else -> null
        }
    }
}
