package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.ReviewExplanation
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.ai.service.ReviewExplanationService
import javax.inject.Inject

/**
 * No-op implementation of [ReviewExplanationService].
 *
 * Always returns `null`, signalling "nothing to generate". This is the default
 * binding for PR 3. A real on-device or cloud provider will replace this in a
 * future phase once the AI inference layer exists.
 *
 * Using a no-op provider (rather than a null binding) keeps the Hilt graph
 * satisfied and all call sites free of null checks on the service itself.
 */
class NoOpReviewExplanationService @Inject constructor() : ReviewExplanationService {

    override suspend fun generate(input: ReviewExplanationInput): ReviewExplanation? = null
}
