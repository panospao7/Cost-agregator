package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.HybridRouter
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReceiptItemCategorizationService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridReceiptItemCategorizationService @Inject constructor(
    private val onDeviceService: OnDeviceReceiptItemCategorizationService,
    private val cloudService: CloudReceiptItemCategorizationService,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiCapabilityRouter: AiCapabilityRouter
) : ReceiptItemCategorizationService {
    
    private val hybridRouter = HybridRouter<ReceiptItemCategorizationInput, ReceiptItemCategorizationResult?>(
        aiSettingsRepository = aiSettingsRepository,
        router = aiCapabilityRouter,
        capability = AiCapability.RECEIPT_ITEM_CATEGORIZATION,
        cloudFn = { cloudService.categorizeItems(it) },
        onDeviceFn = { onDeviceService.categorizeItems(it) },
        fallbackFn = { null }
    )

    override suspend fun categorizeItems(input: ReceiptItemCategorizationInput): ReceiptItemCategorizationResult? =
        hybridRouter.execute(input)
}
