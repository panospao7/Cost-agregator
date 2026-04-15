package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
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

    private data class AttemptPlan(
        val attemptNumber: Int,
        val method: AttemptMethod
    )

    private data class RouteViability(
        val cloudAvailable: Boolean,
        val onDeviceAvailable: Boolean
    )

    override suspend fun suggest(input: ReceiptAssistInput): AiServiceResult<ReceiptAssistSuggestion> {
        val settings = aiSettingsRepository.settings().first()
        val routeDecision = aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, settings)
        val attempts = mutableListOf<AttemptDetails>()
        val orderedAttempts = orderedAttemptsFor(routeDecision.route)
        
        Timber.d("SmartReceiptAssist: Starting analysis for receipt ${input.receiptId}, route: ${routeDecision.route}")

        if (routeDecision.route == AiRoute.DETERMINISTIC_FALLBACK || routeDecision.route == AiRoute.DISABLED) {
            Timber.d("SmartReceiptAssist: Router selected ${routeDecision.route}; skipping AI attempts")
            val fallbackResult = noOpReceiptAssistService.suggest(input)
            attempts.add(fallbackResult.toAttemptDetails(5, AttemptMethod.DETERMINISTIC_FALLBACK))
            logAttemptSummary(input.receiptId, attempts)
            return fallbackResult.withExecutionMetadata(usedImageInput = false, attempts = attempts)
        }

        val routeViability = resolveRouteViability(settings, routeDecision.route)

        for (plan in orderedAttempts) {
            val result = executeAttempt(plan, input, settings, routeViability) ?: continue
            attempts.add(result.toAttemptDetails(plan.attemptNumber, plan.method))

            if (result is AiServiceResult.Success && isGoodResult(result.value)) {
                val usedImageInput = plan.method == AttemptMethod.CLOUD_VISION ||
                    plan.method == AttemptMethod.ON_DEVICE_VISION
                return result.withExecutionMetadata(usedImageInput = usedImageInput, attempts = attempts)
            }
        }

        // Attempt 5: Deterministic Fallback (no AI)
        Timber.d("SmartReceiptAssist: Attempt 5 - Deterministic Fallback")
        val fallbackResult = noOpReceiptAssistService.suggest(input)
        attempts.add(fallbackResult.toAttemptDetails(5, AttemptMethod.DETERMINISTIC_FALLBACK))

        logAttemptSummary(input.receiptId, attempts)

        return fallbackResult.withExecutionMetadata(usedImageInput = false, attempts = attempts)
    }

    private fun orderedAttemptsFor(route: AiRoute): List<AttemptPlan> {
        return if (route == AiRoute.CLOUD) {
            listOf(
                AttemptPlan(1, AttemptMethod.CLOUD_VISION),
                AttemptPlan(2, AttemptMethod.ON_DEVICE_VISION),
                AttemptPlan(3, AttemptMethod.CLOUD_TEXT),
                AttemptPlan(4, AttemptMethod.ON_DEVICE_TEXT)
            )
        } else {
            listOf(
                AttemptPlan(1, AttemptMethod.ON_DEVICE_VISION),
                AttemptPlan(2, AttemptMethod.CLOUD_VISION),
                AttemptPlan(3, AttemptMethod.ON_DEVICE_TEXT),
                AttemptPlan(4, AttemptMethod.CLOUD_TEXT)
            )
        }
    }

    private suspend fun executeAttempt(
        plan: AttemptPlan,
        input: ReceiptAssistInput,
        settings: AiSettings,
        routeViability: RouteViability
    ): AiServiceResult<ReceiptAssistSuggestion>? {
        return when (plan.method) {
            AttemptMethod.CLOUD_VISION -> {
                if (!shouldAttemptCloudVision(input, settings, routeViability)) return null
                Timber.d("SmartReceiptAssist: Attempt ${plan.attemptNumber} - Cloud Vision AI")
                tryCloudVision(input)
            }
            AttemptMethod.ON_DEVICE_VISION -> {
                if (!shouldAttemptOnDeviceVision(input, routeViability)) return null
                Timber.d("SmartReceiptAssist: Attempt ${plan.attemptNumber} - On-Device Vision AI")
                tryOnDeviceVision(input)
            }
            AttemptMethod.CLOUD_TEXT -> {
                if (!shouldAttemptCloudText(routeViability)) return null
                Timber.d("SmartReceiptAssist: Attempt ${plan.attemptNumber} - Cloud Text AI")
                tryCloudText(input.copy(isImageAnalysisMode = false))
            }
            AttemptMethod.ON_DEVICE_TEXT -> {
                if (!shouldAttemptOnDeviceText(routeViability)) return null
                Timber.d("SmartReceiptAssist: Attempt ${plan.attemptNumber} - On-Device Text AI")
                tryOnDeviceText(input.copy(isImageAnalysisMode = false))
            }
            AttemptMethod.DETERMINISTIC_FALLBACK -> null
        }
    }

    private suspend fun resolveRouteViability(
        settings: AiSettings,
        selectedRoute: AiRoute
    ): RouteViability {
        return when (selectedRoute) {
            AiRoute.CLOUD -> RouteViability(
                cloudAvailable = true,
                onDeviceAvailable = aiCapabilityRouter.decide(
                    capability = AiCapability.RECEIPT_EXTRACTION,
                    settings = settings.copy(preferredMode = AiMode.ON_DEVICE)
                ).route == AiRoute.ON_DEVICE
            )
            AiRoute.ON_DEVICE -> RouteViability(
                cloudAvailable = aiCapabilityRouter.decide(
                    capability = AiCapability.RECEIPT_EXTRACTION,
                    settings = settings.copy(preferredMode = AiMode.CLOUD)
                ).route == AiRoute.CLOUD,
                onDeviceAvailable = true
            )
            AiRoute.DETERMINISTIC_FALLBACK,
            AiRoute.DISABLED -> RouteViability(
                cloudAvailable = false,
                onDeviceAvailable = false
            )
        }
    }

    private fun shouldAttemptCloudVision(
        input: ReceiptAssistInput,
        settings: AiSettings,
        routeViability: RouteViability
    ): Boolean {
        return routeViability.cloudAvailable &&
            input.isImageAnalysisMode &&
            input.imagePath != null &&
            input.imageMimeType != null &&
            settings.receiptImageCloudEnabled
    }

    private fun shouldAttemptOnDeviceVision(
        input: ReceiptAssistInput,
        routeViability: RouteViability
    ): Boolean {
        return input.isImageAnalysisMode &&
            input.imagePath != null &&
            routeViability.onDeviceAvailable
    }

    private fun shouldAttemptCloudText(routeViability: RouteViability): Boolean =
        routeViability.cloudAvailable

    private fun shouldAttemptOnDeviceText(routeViability: RouteViability): Boolean =
        routeViability.onDeviceAvailable

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
