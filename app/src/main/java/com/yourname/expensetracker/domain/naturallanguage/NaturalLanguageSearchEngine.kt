package com.yourname.expensetracker.domain.naturallanguage

import com.yourname.expensetracker.domain.util.TimeProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Natural language search engine for expense queries.
 *
 * ## Known Limitations
 *
 * ### M1: Amount filters not currency-aware
 * Amount comparisons in [executeSearch] and [buildSearchFilter] compare raw
 * doubles without any currency conversion. If the user has expenses in multiple
 * currencies, an amount filter like "over $50" will match expenses of any currency
 * by nominal value only. A proper fix would require currency-aware amount comparison
 * that converts filter amounts to each expense's currency (or vice versa) using
 * the [com.yourname.expensetracker.domain.currency.CurrencyConverter].
 *
 * ### M2: Legacy parser bugs
 * - **Merchant extraction**: The [extractMerchants] method uses a basic regex
 *   (`(?:at|from)\s+([A-Z][a-zA-Z]+)`) that only captures single-word capitalized
 *   merchants preceded by "at" or "from". Multi-word merchants, merchants without
 *   those prepositions, and lowercase merchants are missed entirely.
 * - **Filter ignored during execution**: In [executeSearch], category and location
 *   filters are parsed during [interpretQuery] but NOT applied during the
 *   FIND_TRANSACTIONS query execution — only merchants and amounts are filtered.
 * - **No cross-filter narrowing**: When multiple filter types are extracted (e.g.
 *   date + category + amount), each is applied independently without cross-validation,
 *   which can produce broader result sets than the user intended.
 *
 * ### M3: Multi-filter drilldown produces broader results than answer
 * When multiple filters are combined (e.g. date range + category + merchant),
 * [executeSearch] returns the unfiltered expense list from the repository and then
 * applies filters in memory. Because the repository query is only date-bounded,
 * all matching-date expenses are loaded first; subsequent in-memory filters may
 * appear to produce a narrower set but the initial data pull can be very large.
 * For proper drilldown, filters should be pushed down to the repository/DAO layer.
 */
@Singleton
class NaturalLanguageSearchEngine @Inject constructor(
    private val expenseQueryRepository: NaturalLanguageExpenseQueryRepository,
    private val speechInputGateway: SpeechInputGateway,
    private val timeProvider: TimeProvider
) {
    
    private val amountPattern = Regex("""
        (?:€|\$|£|¥|EUR|USD|GBP)?\s*
        (\d+(?:\.\d{2})?)
        \s*(?:€|\$|£|¥|EUR|USD|GBP)?
    """.trimIndent(), RegexOption.IGNORE_CASE)
    
    private val datePatterns = listOf(
        // "last week", "this month", "yesterday", etc.
        // "last week" / "last month" / "last year"
        // NOTE: "last month" uses calendar-month semantics (previous calendar month, e.g. March 1–31 when
        // querying in April), NOT a rolling 30-day window.  This is consistent with "this month" which also
        // uses calendar boundaries.
        PatternWithExtractor(
            pattern = Regex("last (week|month|year)", RegexOption.IGNORE_CASE),
            extractor = { match ->
                val unit = match.groupValues[1]
                val now = timeProvider.now()
                val end = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
                val start = when (unit.lowercase()) {
                    "week" -> end.minusWeeks(1)
                    "month" -> {
                        // Calendar month: start = first day of previous month
                        end.withDayOfMonth(1).minusMonths(1)
                    }
                    "year" -> end.minusYears(1)
                    else -> end.minusMonths(1)
                }
                val adjustedEnd = when (unit.lowercase()) {
                    // Calendar month: end = last day of previous month (first of current month minus one day)
                    "month" -> end.withDayOfMonth(1).minusDays(1)
                    else -> end
                }
                DateRange(start, adjustedEnd)
            }
        ),
        PatternWithExtractor(
            pattern = Regex("this (week|month|year)", RegexOption.IGNORE_CASE),
            extractor = { match ->
                val unit = match.groupValues[1]
                val now = timeProvider.now()
                val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
                val start = when (unit.lowercase()) {
                    "week" -> today.minusDays(today.dayOfWeek.value.toLong() - 1)
                    "month" -> today.withDayOfMonth(1)
                    "year" -> today.withDayOfYear(1)
                    else -> today.withDayOfMonth(1)
                }
                DateRange(start, today)
            }
        ),
        PatternWithExtractor(
            pattern = Regex("(yesterday|today)", RegexOption.IGNORE_CASE),
            extractor = { match ->
                val day = match.groupValues[1].lowercase()
                val now = timeProvider.now()
                val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
                val date = when (day) {
                    "yesterday" -> today.minusDays(1)
                    "today" -> today
                    else -> today
                }
                DateRange(date, date)
            }
        ),
        PatternWithExtractor(
            pattern = Regex("(january|february|march|april|may|june|july|august|september|october|november|december)", RegexOption.IGNORE_CASE),
            extractor = { match ->
                val month = match.groupValues[1]
                val monthNum = java.time.Month.valueOf(month.uppercase()).value
                val now = timeProvider.now()
                val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
                val year = today.year
                val start = LocalDate.of(year, monthNum, 1)
                val end = start.withDayOfMonth(start.lengthOfMonth())
                DateRange(start, end)
            }
        ),
        PatternWithExtractor(
            pattern = Regex("(\\d{1,2})/(\\d{1,2})/(\\d{2,4})", RegexOption.IGNORE_CASE),
            extractor = { match ->
                val day = match.groupValues[1].toInt()
                val month = match.groupValues[2].toInt()
                val yearStr = match.groupValues[3]
                val year = if (yearStr.length == 2) 2000 + yearStr.toInt() else yearStr.toInt()
                val date = LocalDate.of(year, month, day)
                DateRange(date, date)
            }
        ),
        PatternWithExtractor(
            pattern = Regex("between (\\S+) and (\\S+)", RegexOption.IGNORE_CASE),
            extractor = { match ->
                // Simple date parsing - would need more sophisticated parsing in production
                val start = parseRelativeDate(match.groupValues[1])
                val end = parseRelativeDate(match.groupValues[2])
                DateRange(start, end)
            }
        ),
        PatternWithExtractor(
            pattern = Regex("over \\$(\\d+)"),
            extractor = { match ->
                null // This is for amount filtering, not dates
            }
        )
    )
    
    private val locationKeywords = listOf(
        "in", "at", "near", "around", "close to"
    )
    
    private val categoryKeywords = mapOf(
        "food" to listOf("restaurant", "grocery", "food", "dining", "eat"),
        "transport" to listOf("gas", "fuel", "uber", "taxi", "transport", "train", "bus"),
        "shopping" to listOf("clothing", "fashion", "electronics", "store", "shop", "amazon"),
        "entertainment" to listOf("netflix", "spotify", "movie", "cinema", "game", "entertainment"),
        "utilities" to listOf("electric", "water", "internet", "phone", "bill")
    )
    
    suspend fun interpretQuery(query: String): QueryInterpretation {
        val normalized = query.lowercase()
        
        // Extract entities
        val amounts = extractAmounts(normalized)
        val dateRange = extractDateRange(normalized)
        val locations = extractLocations(normalized)
        val categories = extractCategories(normalized)
        val merchants = extractMerchants(normalized)
        
        // Determine query type
        val queryType = determineQueryType(normalized)
        
        // Build search filter
        val filter = buildSearchFilter(amounts, dateRange, locations, categories, merchants)
        
        return QueryInterpretation(
            originalQuery = query,
            queryType = queryType,
            extractedAmounts = amounts,
            dateRange = dateRange,
            locations = locations,
            categories = categories,
            merchants = merchants,
            searchFilter = filter,
            confidence = calculateConfidence(query, amounts, dateRange, categories, merchants)
        )
    }
    
    /**
     * Executes the search described by the interpretation.
     *
     * ## Currency-awareness gap (M1)
     * Amount comparisons in this method compare raw [Double] values without
     * currency conversion. An expense with amount=50.0 in JPY will match a
     * filter "over 50" just as readily as one with amount=50.0 in EUR.
     * This is incorrect for multi-currency users. See class KDoc for details.
     *
     * ## Multi-filter drilldown (M3)
     * The initial data pull from [expenseQueryRepository] is only date-bounded;
     * all other filters (amount, merchant, category, location) are applied
     * in-memory after loading. This means loading can be unnecessarily broad
     * when multiple filters are active. Filters should ideally be pushed down
     * to the DAO layer for efficient querying.
     */
    suspend fun executeSearch(interpretation: QueryInterpretation): List<NaturalLanguageExpense> {
        val (startMs, endMs) = resolveDateRangeMillis(interpretation.dateRange)

        return when (interpretation.queryType) {
            QueryType.TOTAL_AMOUNT -> {
                expenseQueryRepository.getExpensesBetween(startMs, endMs)
            }
            QueryType.FIND_TRANSACTIONS -> {
                expenseQueryRepository
                    .getExpensesBetween(startMs, endMs)
                    .filter { expense ->
                        interpretation.merchants?.let { merchants ->
                            merchants.any { expense.merchant.contains(it, ignoreCase = true) }
                        } ?: true
                    }
                    .filter { expense ->
                        interpretation.extractedAmounts?.let { amounts ->
                            amounts.any { amount ->
                                when (amount.comparison) {
                                    AmountComparison.EXACTLY -> expense.amount == amount.value
                                    AmountComparison.OVER -> expense.amount > amount.value
                                    AmountComparison.UNDER -> expense.amount < amount.value
                                    AmountComparison.BETWEEN -> {
                                        val other = amounts.find { it != amount }
                                        other?.let { expense.amount in amount.value..it.value } ?: true
                                    }
                                }
                            }
                        } ?: true
                    }
            }
            QueryType.SPENDING_BY_CATEGORY -> {
                expenseQueryRepository.getExpensesBetween(startMs, endMs)
            }
            else -> emptyList()
        }
    }

    private fun resolveDateRangeMillis(dateRange: DateRange?): Pair<Long, Long> {
        val startMs = dateRange?.start?.let {
            it.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } ?: 0L
        val endMs = dateRange?.end?.let {
            it.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } ?: timeProvider.now()
        return startMs to endMs
    }
    
    private fun extractAmounts(query: String): List<ExtractedAmount>? {
        val amounts = mutableListOf<ExtractedAmount>()
        
        // Extract explicit amounts
        amountPattern.findAll(query).forEach { match ->
            val value = match.groupValues[1].toDoubleOrNull()
            if (value != null) {
                amounts.add(ExtractedAmount(value, AmountComparison.EXACTLY))
            }
        }
        
        // Check for comparison operators
        val overPattern = Regex("over|above|more than|greater than|>")
        val underPattern = Regex("under|below|less than|<")
        val betweenPattern = Regex("between")
        
        when {
            overPattern.containsMatchIn(query) -> {
                amounts.lastOrNull()?.let { it.comparison = AmountComparison.OVER }
            }
            underPattern.containsMatchIn(query) -> {
                amounts.lastOrNull()?.let { it.comparison = AmountComparison.UNDER }
            }
            betweenPattern.containsMatchIn(query) && amounts.size >= 2 -> {
                amounts[0].comparison = AmountComparison.BETWEEN
                amounts[1].comparison = AmountComparison.BETWEEN
            }
        }
        
        return if (amounts.isNotEmpty()) amounts else null
    }
    
    private fun extractDateRange(query: String): DateRange? {
        for (patternExtractor in datePatterns) {
            val match = patternExtractor.pattern.find(query)
            if (match != null) {
                val range = patternExtractor.extractor(match)
                if (range != null) return range
            }
        }
        return null
    }
    
    private fun extractLocations(query: String): List<String>? {
        val locations = mutableListOf<String>()
        
        for (keyword in locationKeywords) {
            val pattern = Regex("$keyword\\s+(\\S+)", RegexOption.IGNORE_CASE)
            pattern.findAll(query).forEach { match ->
                locations.add(match.groupValues[1])
            }
        }
        
        return if (locations.isNotEmpty()) locations else null
    }
    
    private fun extractCategories(query: String): List<String>? {
        val foundCategories = mutableListOf<String>()
        
        categoryKeywords.forEach { (category, keywords) ->
            if (keywords.any { query.contains(it, ignoreCase = true) }) {
                foundCategories.add(category)
            }
        }
        
        return if (foundCategories.isNotEmpty()) foundCategories else null
    }
    
    private fun extractMerchants(query: String): List<String>? {
        // In production, this would use a database of known merchants
        // For now, we'll extract capitalized words that might be merchant names
        val merchantPattern = Regex("""(?:at|from)\s+([A-Z][a-zA-Z]+)""")
        val merchants = merchantPattern.findAll(query).map { it.groupValues[1] }.toList()
        
        return if (merchants.isNotEmpty()) merchants else null
    }
    
    private fun determineQueryType(query: String): QueryType {
        return when {
            query.containsAny("total", "sum", "spent", "how much") -> QueryType.TOTAL_AMOUNT
            query.containsAny("spending", "breakdown", "by category") -> QueryType.SPENDING_BY_CATEGORY
            query.containsAny("average", "typical", "usual") -> QueryType.AVERAGE_SPENDING
            query.containsAny("find", "show", "list", "what did i buy") -> QueryType.FIND_TRANSACTIONS
            query.containsAny("trend", "increasing", "decreasing") -> QueryType.TREND_ANALYSIS
            else -> QueryType.FIND_TRANSACTIONS
        }
    }
    
    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it, ignoreCase = true) }
    }
    
    /**
     * Builds a [SearchFilter] from the extracted entities.
     *
     * ## Currency-awareness gap (M1)
     * The [minAmount], [maxAmount], and [exactAmount] fields store raw [Double]
     * values without any currency association. When this filter is later applied
     * in [executeSearch], amounts from expenses in different currencies are
     * compared by nominal value only. A future fix should normalize the filter
     * amounts by converting them to each expense's currency (or vice versa)
     * using [com.yourname.expensetracker.domain.currency.CurrencyConverter].
     * The converted amounts should be compared at the point of filter application
     * in [executeSearch], not stored in the filter itself.
     */
    private fun buildSearchFilter(
        amounts: List<ExtractedAmount>?,
        dateRange: DateRange?,
        locations: List<String>?,
        categories: List<String>?,
        merchants: List<String>?
    ): SearchFilter {
        return SearchFilter(
            minAmount = amounts?.find { it.comparison == AmountComparison.OVER }?.value,
            maxAmount = amounts?.find { it.comparison == AmountComparison.UNDER }?.value,
            exactAmount = amounts?.find { it.comparison == AmountComparison.EXACTLY }?.value,
            startDate = dateRange?.start?.let {
                it.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            },
            endDate = dateRange?.end?.let {
                it.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            },
            locations = locations,
            categories = categories,
            merchants = merchants
        )
    }
    
    private fun calculateConfidence(
        query: String,
        amounts: List<ExtractedAmount>?,
        dateRange: DateRange?,
        categories: List<String>?,
        merchants: List<String>?
    ): Double {
        var score = 50.0 // Base confidence
        
        // Boost for each extracted entity
        if (amounts != null) score += 15
        if (dateRange != null) score += 20
        if (categories != null) score += 10
        if (merchants != null) score += 10
        
        // Penalty for very short or vague queries
        if (query.length < 10) score -= 20
        if (!query.containsAny("at", "in", "on", "from", "to", "between")) score -= 10
        
        return score.coerceIn(0.0, 100.0)
    }
    
    private fun parseRelativeDate(dateStr: String): LocalDate {
        // Simplified date parsing - production would be more sophisticated
        val now = timeProvider.now()
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        return when (dateStr.lowercase()) {
            "yesterday" -> today.minusDays(1)
            "today" -> today
            "last week" -> today.minusWeeks(1)
            // Calendar month semantics: first day of previous calendar month
            "last month" -> today.withDayOfMonth(1).minusMonths(1)
            else -> today
        }
    }
    
    // Voice Input Support
    fun isVoiceInputAvailable(): Boolean = speechInputGateway.isAvailable()

    fun startVoiceInput(
        onResult: (String) -> Unit,
        onError: (SpeechInputError) -> Unit = {}
    ) {
        speechInputGateway.startListening(onResult, onError)
    }

    fun stopVoiceInput() {
        speechInputGateway.stopListening()
    }
    
    // Data Classes
    data class QueryInterpretation(
        val originalQuery: String,
        val queryType: QueryType,
        val extractedAmounts: List<ExtractedAmount>?,
        val dateRange: DateRange?,
        val locations: List<String>?,
        val categories: List<String>?,
        val merchants: List<String>?,
        val searchFilter: SearchFilter,
        val confidence: Double
    )
    
    enum class QueryType {
        TOTAL_AMOUNT,
        FIND_TRANSACTIONS,
        SPENDING_BY_CATEGORY,
        AVERAGE_SPENDING,
        TREND_ANALYSIS,
        UNKNOWN
    }
    
    data class ExtractedAmount(
        val value: Double,
        var comparison: AmountComparison = AmountComparison.EXACTLY
    )
    
    enum class AmountComparison {
        EXACTLY, OVER, UNDER, BETWEEN
    }
    
    data class DateRange(
        val start: LocalDate,
        val end: LocalDate
    )
    
    data class SearchFilter(
        val minAmount: Double?,
        val maxAmount: Double?,
        val exactAmount: Double?,
        val startDate: Long?,
        val endDate: Long?,
        val locations: List<String>?,
        val categories: List<String>?,
        val merchants: List<String>?
    )
    
    data class PatternWithExtractor(
        val pattern: Regex,
        val extractor: (MatchResult) -> DateRange?
    )
}
