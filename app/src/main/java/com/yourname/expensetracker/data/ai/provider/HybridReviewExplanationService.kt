package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.HybridRouter
import com.yourname.expensetracker.domain.ai.model.ReviewExplanation
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReviewExplanationService
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

    private val hybridRouter = HybridRouter<ReviewExplanationInput, AiServiceResult<ReviewExplanation>>(
        aiSettingsRepository = aiSettingsRepository,
        router = router,
        capability = AiCapability.REVIEW_EXPLANATION,
        cloudFn = { cloudReviewExplanationService.generate(it) },
        onDeviceFn = { onDeviceReviewExplanationService.generate(it) },
        fallbackFn = { noOpReviewExplanationService.generate(it) }
    )

    override suspend fun generate(input: ReviewExplanationInput): AiServiceResult<ReviewExplanation> =
        hybridRouter.execute(input)
}
