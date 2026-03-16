package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryOption
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.config.AppConfig
import javax.inject.Inject

class CategorizationAssistInputBuilder @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val aiPolicy: AiPolicy
) {

    suspend fun build(
        item: PendingReviewWithReceipt,
        settings: AiSettings
    ): CategorizationAssistInput {
        val review = item.review
        val shouldRedact = aiPolicy.shouldRedact(settings, AiCapability.CATEGORIZATION_FALLBACK)
        val categories = categoryRepository.getAll()

        return CategorizationAssistInput(
            targetType = AiTargetType.PENDING_REVIEW,
            targetId = review.id,
            merchant = review.suggestedMerchant.trim().take(120),
            amount = review.suggestedAmount,
            currency = review.suggestedCurrency.take(8),
            transactionType = runCatching {
                com.yourname.expensetracker.data.database.entity.TransactionType.valueOf(review.suggestedType)
            }.getOrDefault(com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE),
            date = review.suggestedDate,
            currentCategoryId = review.suggestedCategoryId,
            deterministicMatchType = review.matchType?.take(40),
            deterministicExplanation = review.explanation?.take(AppConfig.Ai.MAX_CAPTURE_SUPPORTING_TEXT_CHARS),
            candidateCategories = categories
                .sortedBy { it.name }
                .take(AppConfig.Ai.MAX_CATEGORY_OPTIONS_FOR_AI)
                .map { CategoryOption(id = it.id, name = it.name) },
            supportingText = buildSupportingText(item, shouldRedact)
        )
    }

    private fun buildSupportingText(
        item: PendingReviewWithReceipt,
        shouldRedact: Boolean
    ): String? {
        if (shouldRedact) return null

        val review = item.review
        return listOfNotNull(
            review.notificationTitle,
            review.notificationText,
            item.receipt?.rawOcrText
        )
            .joinToString("\n")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.take(AppConfig.Ai.MAX_CAPTURE_SUPPORTING_TEXT_CHARS)
    }
}
