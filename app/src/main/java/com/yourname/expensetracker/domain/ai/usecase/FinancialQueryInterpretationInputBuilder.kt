package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiChatMessage
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.common.sha256Prefix
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.TimeProvider
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
        val merchantAliases = mutableMapOf<String, String>()
        val merchantAliasesByRawName = linkedMapOf<String, String>()
        val merchantLookup = linkedMapOf<String, String>()
        val categoryAliases = mutableMapOf<String, String>()
        val categoryAliasesByRawName = linkedMapOf<String, String>()
        val categoryLookup = linkedMapOf<String, Long>()
        // Maps the name/alias that will appear in categoryNames → category ID.
        // Under redaction the key is the alias; otherwise it is the raw name.
        val categoryNameToId = mutableMapOf<String, Long>()
        val categoryNames = if (shouldRedact) {
            categories
                .map { category ->
                    categoryNameToId[category.name] = category.id
                    val alias = sanitizeCategoryContext(category.name, shouldRedact = true)
                    if (alias.isNotBlank()) {
                        categoryAliases[alias] = category.name
                        categoryAliasesByRawName[category.name] = alias
                        categoryLookup[alias] = category.id
                        categoryNameToId[alias] = category.id
                    }
                    alias
                }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        } else {
            categories.forEach { category ->
                categoryLookup[category.name] = category.id
                categoryNameToId[category.name] = category.id
            }
            categories.map { it.name }.sorted()
        }
        val merchantNames = merchants
            .map { merchant ->
                val alias = sanitizeMerchantContext(merchant, shouldRedact)
                if (shouldRedact && alias.isNotBlank()) {
                    merchantAliases[alias] = merchant
                    merchantAliasesByRawName[merchant] = alias
                    merchantLookup[alias] = merchant
                } else if (!shouldRedact) {
                    merchantLookup[merchant] = merchant
                }
                alias
            }
            .filter { it.isNotBlank() }
            .distinct()
            .take(100)

        return FinancialQueryInterpretationInput(
            rawQuery = sanitizeFreeText(
                sanitizeQueryContext(rawQuery, shouldRedact, merchantAliasesByRawName, categoryAliasesByRawName),
                shouldRedact
            )
                .take(AppConfig.Ai.MAX_QUERY_INPUT_CHARS),
            currentTimeMs = timeProvider.now(),
            categoryNames = categoryNames,
            merchantNames = merchantNames,
            merchantLookupMap = merchantLookup,
            merchantAliasMap = merchantAliases,
            categoryLookupMap = categoryLookup,
            categoryAliasMap = categoryAliases,
            categoryNameToIdMap = categoryNameToId,
            conversationHistory = conversationHistory
                .takeLast(AppConfig.Ai.MAX_QUERY_HISTORY_TURNS_FOR_MODEL)
                .map { message ->
                    if (!shouldRedact) {
                        message
                    } else {
                        message.copy(
                            text = sanitizeFreeText(
                                sanitizeQueryContext(
                                    message.text,
                                    shouldRedact,
                                    merchantAliasesByRawName,
                                    categoryAliasesByRawName
                                ),
                                shouldRedact
                            ),
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

    private fun sanitizeQueryContext(
        text: String,
        shouldRedact: Boolean,
        merchantAliasesByRawName: Map<String, String>,
        categoryAliasesByRawName: Map<String, String>
    ): String {
        val trimmed = text.trim()
        if (!shouldRedact || trimmed.isBlank()) return trimmed

        val replacements = linkedMapOf<String, String>()
        merchantAliasesByRawName.forEach { (rawName, alias) ->
            if (rawName.isNotBlank() && alias.isNotBlank()) {
                replacements.putIfAbsent(rawName, alias)
            }
        }
        categoryAliasesByRawName.forEach { (rawName, alias) ->
            if (rawName.isNotBlank() && alias.isNotBlank()) {
                replacements.putIfAbsent(rawName, alias)
            }
        }

        return replacements.entries
            .sortedByDescending { it.key.length }
            .fold(trimmed) { acc, (rawName, alias) ->
                acc.replace(Regex(Regex.escape(rawName), RegexOption.IGNORE_CASE), alias)
            }
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

    private companion object {
        private val EMAIL_REGEX = Regex("""\b[\w._%+-]+@[\w.-]+\.[A-Za-z]{2,}\b""")
        private val IBAN_REGEX = Regex("""\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b""")
        private val CARD_REGEX = Regex("""\b(?:\d[ -]?){13,19}\b""")
        private val PHONE_REGEX = Regex("""\+?\d[\d\s().-]{6,}\d""")
        private val LONG_NUMBER_REGEX = Regex("""\b\d{10,}\b""")
    }
}
