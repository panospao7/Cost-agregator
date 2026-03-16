package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.ReviewExplanation
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReviewExplanationService
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridReviewExplanationService @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val router: AiCapabilityRouter,
    private val cloudReviewExplanationService: CloudReviewExplanationService,
    private val onDeviceReviewExplanationService: OnDeviceReviewExplanationService,
    private val noOpReviewExplanationService: NoOpReviewExplanationService
) : ReviewExplanationService {

    override suspend fun generate(input: ReviewExplanationInput): ReviewExplanation? {
        val settings = aiSettingsRepository.settings().first()
        return when (router.decide(AiCapability.REVIEW_EXPLANATION, settings).route) {
            AiRoute.CLOUD -> cloudReviewExplanationService.generate(input)
            AiRoute.ON_DEVICE -> onDeviceReviewExplanationService.generate(input)
            AiRoute.DETERMINISTIC_FALLBACK,
            AiRoute.DISABLED -> noOpReviewExplanationService.generate(input)
        }
    }
}
