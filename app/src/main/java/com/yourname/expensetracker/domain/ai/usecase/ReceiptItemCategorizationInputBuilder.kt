package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.CloudCategoryOption
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptItemCategorizationInputBuilder @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val categoryRepository: CategoryRepository,
    private val receiptParser: ReceiptParser,
    private val aiPolicy: AiPolicy
) {
    /**
     * Builds input for receipt item categorization.
     */
    suspend fun build(receipt: ScannedReceipt, settings: AiSettings): ReceiptItemCategorizationInput {
        val shouldRedact =
            aiPolicy.canUseCloudFor(settings, AiCapability.RECEIPT_ITEM_CATEGORIZATION) &&
                aiPolicy.shouldRedact(settings, AiCapability.RECEIPT_ITEM_CATEGORIZATION)

        // Parse line items from JSON
        val lineItems = receipt.parsedItems?.let {
            receiptParser.lineItemsFromJson(it)
        }?.map { item ->
            if (!shouldRedact) {
                item.copy(description = sanitizeText(item.description, false, 80))
            } else {
                item.copy(description = sanitizeText(item.description, true, 80))
            }
        } ?: emptyList()
        
        // Get user's categories
        val categories = categoryRepository.getAll()
        val cloudCategoryOptions = if (shouldRedact) {
            categories.map { category ->
                CloudCategoryOption(
                    categoryId = category.id,
                    cloudName = cloudSafeCategoryName(category.id, category.name)
                )
            }
        } else {
            emptyList()
        }
        
        return ReceiptItemCategorizationInput(
            receiptId = receipt.id,
            merchant = sanitizeMerchant(receipt.parsedMerchant ?: "Unknown Merchant", shouldRedact),
            lineItems = lineItems,
            userCategories = categories,
            cloudCategoryOptions = cloudCategoryOptions,
            totalTax = receipt.parsedTaxAmount,
            currency = receipt.currency,
            redactBeforeCloud = shouldRedact
        )
    }

    private fun cloudSafeCategoryName(categoryId: Long, rawName: String): String {
        val seed = "$categoryId:$rawName"
        return "cat_${seed.sha256Prefix()}"
    }

    private fun sanitizeMerchant(raw: String, shouldRedact: Boolean): String {
        val trimmed = raw.trim().ifBlank { "Unknown Merchant" }.take(80)
        if (!shouldRedact) return trimmed
        return "merchant_${trimmed.sha256Prefix()}"
    }

    private fun sanitizeText(raw: String, shouldRedact: Boolean, maxChars: Int): String {
        val trimmed = raw.trim().take(maxChars)
        if (!shouldRedact) return trimmed

        val redacted = trimmed
            .replace(EMAIL_REGEX, "[REDACTED_EMAIL]")
            .replace(IBAN_REGEX, "[REDACTED_IBAN]")
            .replace(CARD_REGEX, "[REDACTED_CARD]")
            .replace(PHONE_REGEX, "[REDACTED_PHONE]")
            .replace(LONG_NUMBER_REGEX, "[REDACTED_NUMBER]")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)

        return if (redacted.isBlank()) "item_${trimmed.sha256Prefix()}" else redacted
    }

    private fun String.sha256Prefix(length: Int = 12): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString(separator = "") { "%02x".format(it) }.take(length)
    }

    private companion object {
        private val EMAIL_REGEX = Regex("""\b[\w._%+-]+@[\w.-]+\.[A-Za-z]{2,}\b""")
        private val IBAN_REGEX = Regex("""\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b""")
        private val CARD_REGEX = Regex("""\b(?:\d[ -]?){13,19}\b""")
        private val PHONE_REGEX = Regex("""\+?\d[\d\s().-]{6,}\d""")
        private val LONG_NUMBER_REGEX = Regex("""\b\d{10,}\b""")
    }
}
