package com.yourname.expensetracker.domain.naturallanguage

data class NaturalLanguageExpense(
    val id: Long,
    val amount: Double,
    val effectiveAmount: Double,
    val currency: String,
    val merchant: String,
    val date: Long,
    val categoryId: Long?
)

/**
 * Indicates how closely a search result matched the query.
 */
enum class MatchType {
    /** The expense matches ALL extracted query criteria (merchant, amount, category, date). */
    EXACT,
    /** The expense matches some but not all query criteria. */
    PARTIAL
}

/**
 * Wraps a [NaturalLanguageExpense] with its match quality against the query.
 */
data class SearchResult(
    val expense: NaturalLanguageExpense,
    val matchType: MatchType
)

interface NaturalLanguageExpenseQueryRepository {
    suspend fun getExpensesBetween(startMs: Long, endMs: Long): List<NaturalLanguageExpense>

    /**
     * Returns expenses within [startMs]..[endMs] that match the optional filters.
     * Filters are pushed as close to the data source as possible to avoid loading
     * all expenses into memory and filtering client-side.
     */
    suspend fun getExpensesBetweenFiltered(
        startMs: Long,
        endMs: Long,
        merchants: List<String>? = null,
        categories: List<String>? = null,
        minAmount: Double? = null,
        maxAmount: Double? = null
    ): List<NaturalLanguageExpense>
}
