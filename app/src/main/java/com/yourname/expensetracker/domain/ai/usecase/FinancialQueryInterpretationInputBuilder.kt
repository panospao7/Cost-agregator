package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiChatMessage
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.TimeProvider
import java.security.MessageDigest
import javax.inject.Inject

class FinancialQueryInterpretationInputBuilder @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val expenseRepository: ExpenseRepository,
    private val timeProvider: TimeProvider,
    private val aiPolicy: AiPolicy
) {

    suspend fun build(
        rawQuery: String,
        settings: AiSettings,
        conversationHistory: List<AiChatMessage> = emptyList()
    ): FinancialQueryInterpretationInput {
        val shouldRedact =
            aiPolicy.canUseCloudFor(settings, AiCapability.QUERY_INTERPRETATION) &&
                aiPolicy.shouldRedact(settings, AiCapability.QUERY_INTERPRETATION)
        val categories = categoryRepository.getAll()
        val merchants = expenseRepository.getRecentMerchantNames()
        val categoryNames = if (shouldRedact) {
            categories
                .map { sanitizeCategoryContext(it.name, shouldRedact = true) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        } else {
            categories.map { it.name }.sorted()
        }

        return FinancialQueryInterpretationInput(
            rawQuery = sanitizeFreeText(rawQuery, shouldRedact)
                .take(AppConfig.Ai.MAX_QUERY_INPUT_CHARS),
            currentTimeMs = timeProvider.now(),
            categoryNames = categoryNames,
            merchantNames = merchants
                .map { sanitizeMerchantContext(it, shouldRedact) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(100),
            conversationHistory = conversationHistory
                .takeLast(AppConfig.Ai.MAX_QUERY_HISTORY_TURNS_FOR_MODEL)
                .map { message ->
                    if (!shouldRedact) {
                        message
                    } else {
                        message.copy(
                            text = sanitizeFreeText(message.text, shouldRedact),
                            payloadJson = null
                        )
                    }
                }
        )
    }

    private fun sanitizeMerchantContext(value: String, shouldRedact: Boolean): String {
        val trimmed = value.trim().take(80)
        if (!shouldRedact) return trimmed
        if (trimmed.isBlank()) return ""
        return "merchant_${trimmed.sha256Prefix()}"
    }

    private fun sanitizeCategoryContext(value: String, shouldRedact: Boolean): String {
        val trimmed = value.trim().take(80)
        if (!shouldRedact) return trimmed
        if (trimmed.isBlank()) return ""
        return "category_${trimmed.sha256Prefix()}"
    }

    private fun sanitizeFreeText(text: String, shouldRedact: Boolean): String {
        val trimmed = text.trim()
        if (!shouldRedact) {
            return trimmed.take(AppConfig.Ai.MAX_QUERY_INPUT_CHARS)
        }

        return trimmed
            .replace(EMAIL_REGEX, "[REDACTED_EMAIL]")
            .replace(IBAN_REGEX, "[REDACTED_IBAN]")
            .replace(CARD_REGEX, "[REDACTED_CARD]")
            .replace(PHONE_REGEX, "[REDACTED_PHONE]")
            .replace(LONG_NUMBER_REGEX, "[REDACTED_NUMBER]")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(AppConfig.Ai.MAX_QUERY_INPUT_CHARS)
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
