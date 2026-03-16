package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.config.AppConfig
import javax.inject.Inject

/**
 * Maps a [PendingReview] entity to a [ReviewExplanationInput] suitable for
 * passing to [ReviewExplanationService].
 *
 * Responsibilities:
 * 1. Field mapping — copies the deterministic fields from [PendingReview].
 * 2. Redaction — when [AiPolicy.shouldRedact] is true, strips the raw
 *    notification text (replaces with null) before it can reach a cloud
 *    provider.
 * 3. Clamping — even when redaction is off, the notification text is clamped
 *    to [AppConfig.Ai.MAX_REVIEW_TEXT_CHARS_FOR_CLOUD] characters to cap the
 *    payload size sent to any cloud endpoint.
 */
class ReviewExplanationInputBuilder @Inject constructor(
    private val aiPolicy: AiPolicy
) {
    fun build(review: PendingReview, settings: AiSettings): ReviewExplanationInput {
        val shouldRedact = aiPolicy.shouldRedact(settings, AiCapability.REVIEW_EXPLANATION)

        val safeNotificationText = when {
            shouldRedact -> null
            else -> review.notificationText
                ?.take(AppConfig.Ai.MAX_REVIEW_TEXT_CHARS_FOR_CLOUD)
        }

        return ReviewExplanationInput(
            reviewId              = review.id,
            merchant              = review.suggestedMerchant,
            amount                = review.suggestedAmount,
            currency              = review.suggestedCurrency,
            suggestedType         = review.suggestedType,
            suggestedCategoryId   = review.suggestedCategoryId,
            confidence            = review.confidence,
            matchType             = review.matchType,
            explanation           = review.explanation,
            packageName           = review.packageName,
            notificationTitle     = if (shouldRedact) null else review.notificationTitle,
            notificationText      = safeNotificationText
        )
    }
}
