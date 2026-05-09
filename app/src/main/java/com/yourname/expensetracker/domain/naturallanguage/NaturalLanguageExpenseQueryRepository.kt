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

/**
 * W31: Keyset pagination cursor — (date, id) of the last row from the previous page.
 * Pass null for the first page.
 */
data class SearchCursor(
    val date: Long,
    val id: Long
)

interface NaturalLanguageExpenseQueryRepository {
    suspend fun getExpensesBetween(startMs: Long, endMs: Long): List<NaturalLanguageExpense>

    /**
     * Returns expenses within [startMs]..[endMs] that match the optional filters.
     * Filters are pushed as close to the data source as possible to avoid loading
     * all expenses into memory and filtering client-side.
     *
     * @deprecated Use [getExpensesBetweenFilteredKeyset] for category-push-down and keyset pagination.
     */
    @Deprecated("Use getExpensesBetweenFilteredKeyset for W30+W31 keyset pagination")
    suspend fun getExpensesBetweenFiltered(
        startMs: Long,
        endMs: Long,
        merchants: List<String>? = null,
        categories: List<String>? = null,
        minAmount: Double? = null,
        maxAmount: Double? = null
    ): List<NaturalLanguageExpense>

    /**
     * W30+W31: Filtered, keyset-paginated query for legacy NL.
     *
     * Pushes categoryIds, merchant LIKE, and transaction type filters to DAO SQL.
     * Returns a single page of results. Pass [cursor] = null for the first page.
     *
     * @param categoryIds Category IDs to filter by (null = no category filter).
     * @param transactionType Transaction type filter (null = no type filter).
     * @param keywordSearch Free-text LIKE pattern for merchant (null = no keyword filter).
     */
    suspend fun getExpensesBetweenFilteredKeyset(
        startMs: Long,
        endMs: Long,
        categoryIds: Set<Long>?,
        merchants: List<String>?,
        transactionType: String?,
        keywordSearch: String?,
        limit: Int,
        cursor: SearchCursor? = null
    ): List<NaturalLanguageExpense>
}
