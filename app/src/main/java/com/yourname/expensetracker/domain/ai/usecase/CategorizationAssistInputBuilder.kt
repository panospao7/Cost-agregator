package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.ai.provider.internal.CloudPiiSanitizer
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryOption
import com.yourname.expensetracker.domain.ai.model.MerchantTransactionHint
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.dto.CategoryRef
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import timber.log.Timber
import javax.inject.Inject
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

class CategorizationAssistInputBuilder @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val aiPolicy: AiPolicy,
    private val expenseRepository: ExpenseRepository,
    private val merchantNormalizer: MerchantNormalizer
) {

    suspend fun build(
        item: PendingReviewWithReceipt,
        settings: AiSettings
    ): CategorizationAssistInput {
        val review = item.review
        val shouldRedact = aiPolicy.shouldRedact(settings, AiCapability.CATEGORIZATION_FALLBACK)
        val categoryRefs = categoryRepository.getAll().map { CategoryRef(id = it.id, name = it.name) }
        val rawMerchant = review.suggestedMerchant.trim().take(120)
        val merchant = if (shouldRedact) {
            CloudPiiSanitizer.sanitizeMerchant(rawMerchant, true)
        } else {
            rawMerchant
        }
        val merchantKey = merchantNormalizer.normalize(rawMerchant).canonical.searchKey
        val recentHints = fetchRecentTransactionHints(merchantKey, rawMerchant, shouldRedact)

        return CategorizationAssistInput(
            targetType = AiTargetType.PENDING_REVIEW,
            targetId = review.id,
            merchant = merchant,
            amount = review.suggestedAmount,
            currency = review.suggestedCurrency.take(8),
            transactionType = runCatching {
                DomainTransactionType.valueOf(review.suggestedType)
            }.getOrDefault(DomainTransactionType.PURCHASE),
            date = review.suggestedDate,
            currentCategoryId = review.suggestedCategoryId,
            deterministicMatchType = review.matchType?.take(40),
            deterministicExplanation = if (shouldRedact) {
                null
            } else {
                review.explanation?.take(AppConfig.Ai.MAX_CAPTURE_SUPPORTING_TEXT_CHARS)
            },
            candidateCategories = buildCategoryOptions(categoryRefs, shouldRedact),
            supportingText = buildReviewSupportingText(item, shouldRedact),
            recentTransactionsWithSameMerchant = recentHints
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
        val categoryRefs = categoryRepository.getAll().map { CategoryRef(id = it.id, name = it.name) }
        val rawMerchant = draftMerchant?.trim()?.takeIf { it.isNotBlank() }
            ?: receipt.parsedMerchant?.trim()?.takeIf { it.isNotBlank() }
            ?: ""
        val trimmedMerchant = rawMerchant.take(120)
        val merchant = if (shouldRedact) {
            CloudPiiSanitizer.sanitizeMerchant(trimmedMerchant, true)
        } else {
            trimmedMerchant
        }
        val normalizedResult = merchantNormalizer.normalize(rawMerchant)
        val recentHints = fetchRecentTransactionHints(normalizedResult.canonical.searchKey, rawMerchant, shouldRedact)

        return CategorizationAssistInput(
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = receipt.id,
            merchant = merchant,
            amount = draftAmount ?: receipt.parsedTotal ?: 0.0,
            currency = receipt.currency.take(8),
            transactionType = DomainTransactionType.PURCHASE,
            date = draftDate ?: receipt.parsedDate,
            currentCategoryId = currentCategoryId,
            deterministicMatchType = null,
            deterministicExplanation = null,
            candidateCategories = buildCategoryOptions(categoryRefs, shouldRedact),
            supportingText = buildReceiptSupportingText(receipt, shouldRedact),
            recentTransactionsWithSameMerchant = recentHints
        )
    }

    private suspend fun fetchRecentTransactionHints(
        merchantKey: String,
        merchant: String,
        shouldRedact: Boolean
    ): List<MerchantTransactionHint> {
        if (merchantKey.isBlank()) return emptyList()
        
        return try {
            val recentExpenses = expenseRepository.getRecentTransactionsForMerchant(merchantKey, 5)
            recentExpenses.map { expenseWithCategory ->
                val rawMerchant = expenseWithCategory.expense.merchant ?: merchant
                val rawCategory = expenseWithCategory.categoryName ?: "Uncategorized"
                MerchantTransactionHint(
                    merchant = rawMerchant,
                    categoryName = rawCategory,
                    cloudMerchant = if (shouldRedact) sanitizeHistoryMerchant(rawMerchant) else rawMerchant,
                    cloudCategoryName = if (shouldRedact) sanitizeHistoryCategory(rawCategory) else rawCategory
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "CategorizationAssistInputBuilder: failed to fetch recent transaction hints for merchant=%s", merchantKey)
            emptyList()
        }
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
        categories: List<CategoryRef>,
        shouldRedact: Boolean
    ): List<CategoryOption> {
        return categories
            .sortedBy { it.name }
            .take(AppConfig.Ai.MAX_CATEGORY_OPTIONS_FOR_AI)
            .map {
                CategoryOption(
                    id = it.id,
                    name = it.name,
                    cloudLabel = if (shouldRedact) sanitizeCategoryAlias(it.name) else it.name
                )
            }
    }

    private fun sanitizeHistoryMerchant(value: String): String {
        val trimmed = value.trim().take(80)
        if (trimmed.isBlank()) return "merchant_unknown"
        return CloudPiiSanitizer.sanitizeMerchant(trimmed, true)
    }

    private fun sanitizeHistoryCategory(value: String): String {
        return sanitizeCategoryAlias(value)
    }

    private fun sanitizeCategoryAlias(value: String): String {
        val trimmed = value.trim().take(80)
        if (trimmed.isBlank()) return "category_unknown"
        return "category_${trimmed.sha256Prefix()}"
    }

    private fun String.sha256Prefix(length: Int = 12): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString(separator = "") { "%02x".format(it) }.take(length)
    }
}
