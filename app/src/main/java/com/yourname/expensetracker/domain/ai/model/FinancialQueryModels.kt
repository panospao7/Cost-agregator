package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.PeriodRange
import com.yourname.expensetracker.domain.model.UiText

enum class QueryMetric {
    LIST,
    TOTAL,
    COUNT,
    AVERAGE,
    MAX,
    MIN
}

enum class QueryGrouping {
    NONE,
    CATEGORY,
    MERCHANT,
    DAY,
    WEEK,
    MONTH
}

enum class QueryComparison {
    NONE,
    PREVIOUS_EQUIVALENT_PERIOD
}

enum class AnswerMode {
    INLINE_ANSWER,
    NAVIGATE,
    BOTH
}

enum class QueryOwnershipScope {
    ALL,
    MINE,
    NOT_MINE,
    SHARED,
    TRANSFER
}

/**
 * Filter parameters for financial queries.
 *
 * ## SRH-7: Currency-aware search filters (planned)
 * Currently [minAmount] and [maxAmount] compare against the raw
 * [effectiveAmount] regardless of the expense's currency. This means a filter
 * like "over $50" will match a ¥5000 expense (≈$33) because the raw numeric
 * comparison uses 5000 > 50, which is incorrect.
 *
 * The plan is to make amount filters currency-aware via
 * [com.yourname.expensetracker.domain.currency.MultiCurrencyRepository]:
 *
 * 1. When applying filters in the query execution layer
 *    ([InterpretFinancialQueryUseCase] or [NaturalLanguageSearchEngine]),
 *    first resolve the filter's currency from the query context (default to
 *    home currency if unspecified).
 * 2. Normalize each expense's [effectiveAmount] to the filter's currency
 *    using [MultiCurrencyRepository] before applying the comparison.
 * 3. For batch efficiency, use a bulk conversion API:
 *    ```
 *    val normalized = multiCurrencyRepository.convertAll(
 *        expenses, fromCurrency = null, toCurrency = filterCurrency
 *    )
 *    ```
 * 4. If conversion fails (missing rate), log a warning and fall back to raw
 *    comparison with a `isPartial` flag so the caller can display a disclaimer.
 *
 * This ensures that "over $50" correctly matches only expenses whose
 * home-currency-equivalent exceeds $50, not all expenses with raw amount > 50.
 */
data class ExpenseQueryFilters(
    val period: PeriodRange? = null,
    val merchants: Set<String> = emptySet(),
    val categoryIds: Set<Long> = emptySet(),
    val transactionTypes: Set<DomainTransactionType> = emptySet(),
    val ownership: QueryOwnershipScope = QueryOwnershipScope.ALL,
    /** Raw amount floor filter. Not currency-aware — compares against effectiveAmount regardless of currency. */
    val minAmount: Double? = null,
    /** Raw amount ceiling filter. Not currency-aware — compares against effectiveAmount regardless of currency. */
    val maxAmount: Double? = null,
    /** Filter by ISO-4217 currency code (e.g. "EUR", "USD", "JPY"). */
    val currency: String? = null,
    /** Filter by source type (e.g. "manual", "import", "receipt_scan", "email"). */
    val sourceType: String? = null,
    /** Filter by expense status (e.g. "active", "archived", "flagged"). */
    val status: String? = null
)

data class FinancialQueryIntent(
    val rawQuery: String,
    val normalizedQuery: String,
    val filters: ExpenseQueryFilters,
    val metric: QueryMetric,
    val grouping: QueryGrouping = QueryGrouping.NONE,
    val comparison: QueryComparison = QueryComparison.NONE,
    val answerMode: AnswerMode = AnswerMode.BOTH
)

data class FinancialQueryInterpretationInput(
    val rawQuery: String,
    val currentTimeMs: Long,
    val localeTag: String = "en-US",
    val categoryNames: List<String> = emptyList(),
    val merchantNames: List<String> = emptyList(),
    val merchantLookupMap: Map<String, String> = emptyMap(),
    val merchantAliasMap: Map<String, String> = emptyMap(),
    val categoryLookupMap: Map<String, Long> = emptyMap(),
    val categoryAliasMap: Map<String, String> = emptyMap(),
    /**
     * Maps canonical category name (or redacted alias) → category ID.
     * Used by the interpretation service to resolve model-emitted category
     * names/aliases back to [ExpenseQueryFilters.categoryIds].
     */
    val categoryNameToIdMap: Map<String, Long> = emptyMap(),
    val conversationHistory: List<AiChatMessage> = emptyList()
)

sealed interface FinancialQueryInterpretationResult {
    data class Structured(
        val intent: FinancialQueryIntent
    ) : FinancialQueryInterpretationResult

    data class Clarification(
        val prompt: String,
        val options: List<String> = emptyList()
    ) : FinancialQueryInterpretationResult

    data class Unsupported(
        val reason: String
    ) : FinancialQueryInterpretationResult
}

sealed interface FinancialQueryResult {
    data class Summary(
        val title: UiText,
        val primaryText: String,
        val supportingText: String? = null,
        val drilldownIntent: FinancialQueryIntent? = null
    ) : FinancialQueryResult

    data class Breakdown(
        val title: UiText,
        val rows: List<Row>,
        val drilldownIntent: FinancialQueryIntent? = null
    ) : FinancialQueryResult {
        data class Row(
            val label: String,
            val amount: Double? = null,
            val count: Int? = null,
            val valueText: String? = null
        )
    }

    data class TransactionList(
        val title: UiText,
        val previewCount: Int,
        val drilldownIntent: FinancialQueryIntent
    ) : FinancialQueryResult

    data class Clarification(
        val prompt: String,
        val options: List<String> = emptyList()
    ) : FinancialQueryResult

    data class Unsupported(
        val reason: String
    ) : FinancialQueryResult
}

enum class AssistantMessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

enum class AssistantMessageKind {
    QUERY,
    RESULT,
    CLARIFICATION,
    ERROR
}

data class AiChatSession(
    val id: Long = 0,
    val title: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

data class AiChatMessage(
    val id: Long = 0,
    val sessionId: Long,
    val role: AssistantMessageRole,
    val kind: AssistantMessageKind,
    val text: String,
    val payloadJson: String? = null,
    val createdAt: Long
)
