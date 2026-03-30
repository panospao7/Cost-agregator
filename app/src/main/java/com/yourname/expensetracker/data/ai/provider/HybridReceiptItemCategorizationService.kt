package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReceiptItemCategorizationService
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridReceiptItemCategorizationService @Inject constructor(
    private val onDeviceService: OnDeviceReceiptItemCategorizationService,
    private val cloudService: CloudReceiptItemCategorizationService,
    private val aiSettingsRepository: AiSettingsRepository
) : ReceiptItemCategorizationService {
    
    override suspend fun categorizeItems(input: ReceiptItemCategorizationInput): ReceiptItemCategorizationResult? {
        val settings = aiSettingsRepository.settings().first()
        
        return when (settings.preferredMode) {
            AiMode.CLOUD -> tryCloudThenOnDevice(input, settings)
            AiMode.ON_DEVICE -> onDeviceService.categorizeItems(input)
            AiMode.AUTO -> tryCloudThenOnDevice(input, settings)
        }
    }
    
    private suspend fun tryCloudThenOnDevice(
        input: ReceiptItemCategorizationInput,
        settings: AiSettings
    ): ReceiptItemCategorizationResult? {
        // Try cloud first if enabled
        if (settings.allowCloudAi) {
            try {
                val cloudResult = cloudService.categorizeItems(input)
                if (cloudResult != null) {
                    Timber.d("Using cloud receipt item categorization")
                    return cloudResult
                }
            } catch (e: Exception) {
                Timber.e(e, "Cloud categorization failed, falling back to on-device")
            }
        }
        
        // Fallback to on-device
        return onDeviceService.categorizeItems(input)
    }
}
