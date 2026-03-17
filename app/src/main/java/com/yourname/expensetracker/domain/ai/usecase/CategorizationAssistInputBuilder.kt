package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
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
                TransactionType.valueOf(review.suggestedType)
            }.getOrDefault(TransactionType.PURCHASE),
            date = review.suggestedDate,
            currentCategoryId = review.suggestedCategoryId,
            deterministicMatchType = review.matchType?.take(40),
            deterministicExplanation = review.explanation?.take(AppConfig.Ai.MAX_CAPTURE_SUPPORTING_TEXT_CHARS),
            candidateCategories = buildCategoryOptions(categories),
            supportingText = buildReviewSupportingText(item, shouldRedact)
        )
    }

    suspend fun build(
        receipt: ScannedReceipt,
        draftMerchant: String?,
        draftAmount: Double?,
        draftDate: Long?,
        currentCategoryId: Long?,
        settings: AiSettings
    ): CategorizationAssistInput {
        val shouldRedact = aiPolicy.shouldRedact(settings, AiCapability.CATEGORIZATION_FALLBACK)
        val categories = categoryRepository.getAll()
        val merchant = draftMerchant?.trim()?.takeIf { it.isNotBlank() }
            ?: receipt.parsedMerchant?.trim()?.takeIf { it.isNotBlank() }
            ?: ""

        return CategorizationAssistInput(
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = receipt.id,
            merchant = merchant.take(120),
            amount = draftAmount ?: receipt.parsedTotal ?: 0.0,
            currency = receipt.currency.take(8),
            transactionType = TransactionType.PURCHASE,
            date = draftDate ?: receipt.parsedDate,
            currentCategoryId = currentCategoryId,
            deterministicMatchType = null,
            deterministicExplanation = null,
            candidateCategories = buildCategoryOptions(categories),
            supportingText = buildReceiptSupportingText(receipt, shouldRedact)
        )
    }

    private fun buildReviewSupportingText(
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

    private fun buildReceiptSupportingText(
        receipt: ScannedReceipt,
        shouldRedact: Boolean
    ): String? {
        if (shouldRedact) return null

        val usableOcrText = receipt.rawOcrText
            .takeIf { it.isNotBlank() }
            ?.takeUnless { it.startsWith("[OCR Failed", ignoreCase = true) }
            ?.takeUnless { it.startsWith("Scan Failed:", ignoreCase = true) }

        return listOfNotNull(
            receipt.parsedMerchant?.takeIf { it.isNotBlank() }?.let { "Parsed merchant: $it" },
            receipt.parsedTotal?.let { "Parsed total: $it ${receipt.currency}" },
            receipt.parsedDate?.let { "Parsed date: $it" },
            usableOcrText
        )
            .joinToString("\n")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.take(AppConfig.Ai.MAX_CAPTURE_SUPPORTING_TEXT_CHARS)
    }

    private fun buildCategoryOptions(
        categories: List<com.yourname.expensetracker.data.database.entity.Category>
    ): List<CategoryOption> {
        return categories
            .sortedBy { it.name }
            .take(AppConfig.Ai.MAX_CATEGORY_OPTIONS_FOR_AI)
            .map { CategoryOption(id = it.id, name = it.name) }
    }
}
