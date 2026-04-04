package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistAttemptDetail
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
        return input.isImageAnalysisMode && input.imagePath != null && input.imageMimeType != null
    }

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

    override suspend fun suggest(input: ReceiptAssistInput): AiServiceResult<ReceiptAssistSuggestion> {
        val settings = aiSettingsRepository.settings().first()
        val routeDecision = aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, settings)
        val attempts = mutableListOf<AttemptDetails>()
        
        Timber.d("SmartReceiptAssist: Starting analysis for receipt ${input.receiptId}, route: ${routeDecision.route}")

        // Attempt 1: Cloud Vision AI (if image available and cloud enabled)
        if (shouldAttemptCloudVision(input, settings)) {
            Timber.d("SmartReceiptAssist: Attempt 1 - Cloud Vision AI")
            val result = tryCloudVision(input)
            attempts.add(result.toAttemptDetails(1, AttemptMethod.CLOUD_VISION))

            if (result is AiServiceResult.Success && isGoodResult(result.value)) {
                Timber.d("SmartReceiptAssist: Cloud Vision succeeded with confidence ${result.value.total?.confidence}")
                return result.withExecutionMetadata(
                    usedImageInput = result.value.usedImageInput,
                    attempts = attempts
                )
            }
        }

        // Attempt 2: On-Device Vision AI (if available and image mode enabled)
        if (shouldAttemptOnDeviceVision(input, settings)) {
            Timber.d("SmartReceiptAssist: Attempt 2 - On-Device Vision AI")
            val result = tryOnDeviceVision(input)
            attempts.add(result.toAttemptDetails(2, AttemptMethod.ON_DEVICE_VISION))

            if (result is AiServiceResult.Success && isGoodResult(result.value)) {
                Timber.d("SmartReceiptAssist: On-Device Vision succeeded")
                return result.withExecutionMetadata(
                    usedImageInput = result.value.usedImageInput,
                    attempts = attempts
                )
            }
        }

        // Attempt 3: Cloud Text AI (OCR text)
        if (shouldAttemptCloudText(settings)) {
            Timber.d("SmartReceiptAssist: Attempt 3 - Cloud Text AI")
            val textInput = input.copy(isImageAnalysisMode = false)  // Force text mode
            val result = tryCloudText(textInput)
            attempts.add(result.toAttemptDetails(3, AttemptMethod.CLOUD_TEXT))

            if (result is AiServiceResult.Success && isGoodResult(result.value)) {
                Timber.d("SmartReceiptAssist: Cloud Text succeeded")
                return result.withExecutionMetadata(usedImageInput = false, attempts = attempts)
            }
        }

        // Attempt 4: On-Device Text AI (OCR text)
        if (shouldAttemptOnDeviceText(settings)) {
            Timber.d("SmartReceiptAssist: Attempt 4 - On-Device Text AI")
            val textInput = input.copy(isImageAnalysisMode = false)  // Force text mode
            val result = tryOnDeviceText(textInput)
            attempts.add(result.toAttemptDetails(4, AttemptMethod.ON_DEVICE_TEXT))

            if (result is AiServiceResult.Success && isGoodResult(result.value)) {
                Timber.d("SmartReceiptAssist: On-Device Text succeeded")
                return result.withExecutionMetadata(usedImageInput = false, attempts = attempts)
            }
        }

        // Attempt 5: Deterministic Fallback (no AI)
        Timber.d("SmartReceiptAssist: Attempt 5 - Deterministic Fallback")
        val fallbackResult = noOpReceiptAssistService.suggest(input)
        attempts.add(fallbackResult.toAttemptDetails(5, AttemptMethod.DETERMINISTIC_FALLBACK))

        logAttemptSummary(input.receiptId, attempts)

        return fallbackResult.withExecutionMetadata(usedImageInput = false, attempts = attempts)
    }

    private fun shouldAttemptCloudVision(
        input: ReceiptAssistInput, 
        settings: AiSettings
    ): Boolean {
        val cloudAllowed = aiPolicy.canUseCloudFor(settings, AiCapability.RECEIPT_EXTRACTION)
        return input.isImageAnalysisMode &&
               input.imagePath != null &&
               settings.receiptImageCloudEnabled &&
               cloudAllowed
    }

    private fun shouldAttemptOnDeviceVision(
        input: ReceiptAssistInput, 
        settings: AiSettings
    ): Boolean {
        val onDeviceAllowed = aiPolicy.shouldAllowOnDevice(settings, AiCapability.RECEIPT_EXTRACTION)
        return input.isImageAnalysisMode &&
               input.imagePath != null &&
               onDeviceAllowed
    }

    private fun shouldAttemptCloudText(
        settings: AiSettings
    ): Boolean = aiPolicy.canUseCloudFor(settings, AiCapability.RECEIPT_EXTRACTION)

    private fun shouldAttemptOnDeviceText(
        settings: AiSettings
    ): Boolean = aiPolicy.shouldAllowOnDevice(settings, AiCapability.RECEIPT_EXTRACTION)

    private suspend fun tryCloudVision(input: ReceiptAssistInput): AiServiceResult<ReceiptAssistSuggestion> =
        cloudReceiptAssistService.suggest(input)

    private suspend fun tryOnDeviceVision(input: ReceiptAssistInput): AiServiceResult<ReceiptAssistSuggestion> =
        onDeviceReceiptAssistService.suggest(input)

    private suspend fun tryCloudText(input: ReceiptAssistInput): AiServiceResult<ReceiptAssistSuggestion> =
        cloudReceiptAssistService.suggest(input)

    private suspend fun tryOnDeviceText(input: ReceiptAssistInput): AiServiceResult<ReceiptAssistSuggestion> =
        onDeviceReceiptAssistService.suggest(input)

    /**
     * Determines if a result is "good enough" to stop retrying.
     * Considers confidence score and presence of critical fields.
     */
    private fun isGoodResult(result: ReceiptAssistSuggestion): Boolean {
        
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

    private fun AiServiceResult<ReceiptAssistSuggestion>.toAttemptDetails(
        attemptNumber: Int,
        method: AttemptMethod
    ): AttemptDetails {
        return when (this) {
            is AiServiceResult.Success -> AttemptDetails(
                attemptNumber = attemptNumber,
                method = method,
                success = true,
                confidence = value.total?.confidence,
                errorMessage = null
            )
            is AiServiceResult.Failure -> AttemptDetails(
                attemptNumber = attemptNumber,
                method = method,
                success = false,
                confidence = null,
                errorMessage = when (val err = error) {
                    is AiServiceError.HttpError -> "HTTP ${err.code}"
                    is AiServiceError.Disabled -> err.reason
                    is AiServiceError.ParseError -> err.message
                    is AiServiceError.Unknown -> err.message
                    AiServiceError.Timeout -> "timeout"
                    AiServiceError.Offline -> "offline"
                    AiServiceError.SslError -> "ssl"
                }
            )
        }
    }

    private fun AiServiceResult<ReceiptAssistSuggestion>.withExecutionMetadata(
        usedImageInput: Boolean,
        attempts: List<AttemptDetails>
    ): AiServiceResult<ReceiptAssistSuggestion> {
        return when (this) {
            is AiServiceResult.Success -> AiServiceResult.Success(
                value.copy(
                    usedImageInput = usedImageInput,
                    attemptDetails = attempts.map { it.toDomainAttemptDetail() }
                )
            )
            is AiServiceResult.Failure -> this
        }
    }

    private fun AttemptDetails.toDomainAttemptDetail(): ReceiptAssistAttemptDetail {
        return ReceiptAssistAttemptDetail(
            attemptNumber = attemptNumber,
            method = method.name,
            success = success,
            confidence = confidence,
            errorMessage = errorMessage
        )
    }
}
