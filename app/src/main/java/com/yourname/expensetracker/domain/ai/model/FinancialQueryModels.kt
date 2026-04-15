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

data class ExpenseQueryFilters(
    val period: PeriodRange? = null,
    val merchants: Set<String> = emptySet(),
    val categoryIds: Set<Long> = emptySet(),
    val transactionTypes: Set<DomainTransactionType> = emptySet(),
    val ownership: QueryOwnershipScope = QueryOwnershipScope.ALL,
    val minAmount: Double? = null,
    val maxAmount: Double? = null
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
