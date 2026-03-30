package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReceiptAssistService
import com.yourname.expensetracker.domain.config.AppConfig
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Smart receipt assist service with intelligent retry chain.
 * 
 * Implements the following retry strategy:
 * 1. Cloud Vision AI (if image available) - Highest accuracy
 * 2. On-Device Vision AI (if available) - Privacy-preserving, offline capable
 * 3. Cloud Text AI (OCR text) - Fallback when vision fails
 * 4. On-Device Text AI (OCR text) - Last AI resort
 * 5. Deterministic Fallback - No AI, just use existing parsed data
 * 
 * This ensures maximum accuracy while respecting user privacy settings.
 */
@Singleton
class SmartReceiptAssistService @Inject constructor(
    private val cloudReceiptAssistService: CloudReceiptAssistService,
    private val onDeviceReceiptAssistService: OnDeviceReceiptAssistService,
    private val noOpReceiptAssistService: NoOpReceiptAssistService,
    private val aiCapabilityRouter: AiCapabilityRouter,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiPolicy: AiPolicy
) : ReceiptAssistService {

    override fun usedImageInput(input: ReceiptAssistInput): Boolean {
        // Returns true if any attempt used image analysis
        return lastUsedImageInput
    }

    @Volatile
    private var lastUsedImageInput: Boolean = false

    @Volatile
    private var lastAttemptDetails: AttemptDetails? = null

    data class AttemptDetails(
        val attemptNumber: Int,
        val method: AttemptMethod,
        val success: Boolean,
        val confidence: Float?,
        val errorMessage: String? = null
    )

    enum class AttemptMethod {
        CLOUD_VISION,
        ON_DEVICE_VISION,
        CLOUD_TEXT,
        ON_DEVICE_TEXT,
        DETERMINISTIC_FALLBACK
    }

    override suspend fun suggest(input: ReceiptAssistInput): ReceiptAssistSuggestion? {
        val settings = aiSettingsRepository.settings().first()
        val routeDecision = aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, settings)
        val attempts = mutableListOf<AttemptDetails>()
        
        Timber.d("SmartReceiptAssist: Starting analysis for receipt ${input.receiptId}, route: ${routeDecision.route}")

        // Attempt 1: Cloud Vision AI (if image available and cloud enabled)
        if (shouldAttemptCloudVision(input, settings, routeDecision)) {
            Timber.d("SmartReceiptAssist: Attempt 1 - Cloud Vision AI")
            val result = tryCloudVision(input)
            attempts.add(AttemptDetails(1, AttemptMethod.CLOUD_VISION, result != null, result?.total?.confidence))
            
            if (isGoodResult(result)) {
                lastUsedImageInput = true
                lastAttemptDetails = attempts.last()
                Timber.d("SmartReceiptAssist: Cloud Vision succeeded with confidence ${result?.total?.confidence}")
                return result
            }
        }

        // Attempt 2: On-Device Vision AI (if available and image mode enabled)
        if (shouldAttemptOnDeviceVision(input, settings, routeDecision)) {
            Timber.d("SmartReceiptAssist: Attempt 2 - On-Device Vision AI")
            val result = tryOnDeviceVision(input)
            attempts.add(AttemptDetails(2, AttemptMethod.ON_DEVICE_VISION, result != null, result?.total?.confidence))
            
            if (isGoodResult(result)) {
                lastUsedImageInput = true
                lastAttemptDetails = attempts.last()
                Timber.d("SmartReceiptAssist: On-Device Vision succeeded")
                return result
            }
        }

        // Attempt 3: Cloud Text AI (OCR text)
        if (shouldAttemptCloudText(settings, routeDecision)) {
            Timber.d("SmartReceiptAssist: Attempt 3 - Cloud Text AI")
            val textInput = input.copy(isImageAnalysisMode = false)  // Force text mode
            val result = tryCloudText(textInput)
            attempts.add(AttemptDetails(3, AttemptMethod.CLOUD_TEXT, result != null, result?.total?.confidence))
            
            if (isGoodResult(result)) {
                lastUsedImageInput = false
                lastAttemptDetails = attempts.last()
                Timber.d("SmartReceiptAssist: Cloud Text succeeded")
                return result
            }
        }

        // Attempt 4: On-Device Text AI (OCR text)
        if (shouldAttemptOnDeviceText(settings, routeDecision)) {
            Timber.d("SmartReceiptAssist: Attempt 4 - On-Device Text AI")
            val textInput = input.copy(isImageAnalysisMode = false)  // Force text mode
            val result = tryOnDeviceText(textInput)
            attempts.add(AttemptDetails(4, AttemptMethod.ON_DEVICE_TEXT, result != null, result?.total?.confidence))
            
            if (isGoodResult(result)) {
                lastUsedImageInput = false
                lastAttemptDetails = attempts.last()
                Timber.d("SmartReceiptAssist: On-Device Text succeeded")
                return result
            }
        }

        // Attempt 5: Deterministic Fallback (no AI)
        Timber.d("SmartReceiptAssist: Attempt 5 - Deterministic Fallback")
        val fallbackResult = noOpReceiptAssistService.suggest(input)
        attempts.add(AttemptDetails(5, AttemptMethod.DETERMINISTIC_FALLBACK, fallbackResult != null, null))
        
        lastUsedImageInput = false
        lastAttemptDetails = attempts.last()
        
        logAttemptSummary(input.receiptId, attempts)
        
        return fallbackResult
    }

    /**
     * Returns the details of the last attempt for debugging/UI display.
     */
    fun getLastAttemptDetails(): AttemptDetails? = lastAttemptDetails

    private fun shouldAttemptCloudVision(
        input: ReceiptAssistInput, 
        settings: AiSettings,
        routeDecision: com.yourname.expensetracker.domain.ai.model.AiRouteDecision
    ): Boolean {
        return input.isImageAnalysisMode &&
               input.imagePath != null &&
               settings.receiptImageCloudEnabled &&
               (routeDecision.route == AiRoute.CLOUD)
    }

    private fun shouldAttemptOnDeviceVision(
        input: ReceiptAssistInput, 
        settings: AiSettings,
        routeDecision: com.yourname.expensetracker.domain.ai.model.AiRouteDecision
    ): Boolean {
        return input.isImageAnalysisMode &&
               input.imagePath != null &&
               settings.allowOnDeviceAi &&
               (routeDecision.route == AiRoute.ON_DEVICE)
    }

    private fun shouldAttemptCloudText(
        settings: AiSettings,
        routeDecision: com.yourname.expensetracker.domain.ai.model.AiRouteDecision
    ): Boolean {
        return settings.allowCloudAi &&
               (routeDecision.route == AiRoute.CLOUD)
    }

    private fun shouldAttemptOnDeviceText(
        settings: AiSettings,
        routeDecision: com.yourname.expensetracker.domain.ai.model.AiRouteDecision
    ): Boolean {
        return settings.allowOnDeviceAi &&
               (routeDecision.route == AiRoute.ON_DEVICE)
    }

    private suspend fun tryCloudVision(input: ReceiptAssistInput): ReceiptAssistSuggestion? {
        return try {
            cloudReceiptAssistService.suggest(input)
        } catch (e: Exception) {
            Timber.w(e, "SmartReceiptAssist: Cloud Vision failed")
            null
        }
    }

    private suspend fun tryOnDeviceVision(input: ReceiptAssistInput): ReceiptAssistSuggestion? {
        return try {
            onDeviceReceiptAssistService.suggest(input)
        } catch (e: Exception) {
            Timber.w(e, "SmartReceiptAssist: On-Device Vision failed")
            null
        }
    }

    private suspend fun tryCloudText(input: ReceiptAssistInput): ReceiptAssistSuggestion? {
        return try {
            cloudReceiptAssistService.suggest(input)
        } catch (e: Exception) {
            Timber.w(e, "SmartReceiptAssist: Cloud Text failed")
            null
        }
    }

    private suspend fun tryOnDeviceText(input: ReceiptAssistInput): ReceiptAssistSuggestion? {
        return try {
            onDeviceReceiptAssistService.suggest(input)
        } catch (e: Exception) {
            Timber.w(e, "SmartReceiptAssist: On-Device Text failed")
            null
        }
    }

    /**
     * Determines if a result is "good enough" to stop retrying.
     * Considers confidence score and presence of critical fields.
     */
    private fun isGoodResult(result: ReceiptAssistSuggestion?): Boolean {
        if (result == null) return false
        
        // Check if we have at least merchant or total (critical fields)
        val hasCriticalField = result.merchant != null || result.total != null
        if (!hasCriticalField) return false
        
        // Check confidence thresholds
        val minConfidence = AppConfig.Ai.MIN_RECEIPT_CONFIDENCE_FOR_AI_FALLBACK  // 0.70f
        val merchantConfidence = result.merchant?.confidence ?: 0f
        val totalConfidence = result.total?.confidence ?: 0f
        
        // Accept if either critical field has good confidence
        return (result.merchant != null && merchantConfidence >= minConfidence) ||
               (result.total != null && totalConfidence >= minConfidence)
    }

    private fun logAttemptSummary(receiptId: Long, attempts: List<AttemptDetails>) {
        val summary = attempts.joinToString(" | ") { attempt ->
            "${attempt.attemptNumber}.${attempt.method}: ${if (attempt.success) "✓" else "✗"} ${attempt.confidence?.let { "($it)" } ?: ""}"
        }
        Timber.d("SmartReceiptAssist: Receipt $receiptId attempt summary: $summary")
    }
}
