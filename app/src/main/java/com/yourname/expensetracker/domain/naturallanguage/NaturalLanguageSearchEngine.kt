package com.yourname.expensetracker.domain.naturallanguage

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * LEGACY NL: This engine is partially migrated. For new query work, use
 * ExecuteFinancialQueryUseCase which provides full dataQuality, currency-aware
 * filtering, and normalized results via MoneyAggregateBuilder.
 *
 * Remaining legacy gaps documented inline (W14-W16, W30-W31).
 * This engine is feature-contained — no new query features will be added.
 *
 * Natural language search engine for expense queries.
 *
 * ## SRH-20: Hybrid query cascading fallback (planned)
 * Currently only one interpretation path works at a time (on-device model).
 * The plan is to implement a cascading try-fallback pattern:
 *
 * 1. **On-device interpretation** (try first):
 *    Use [OnDeviceQueryInterpretationService] for fast, offline-capable parsing.
 *    Typically handles simple queries like "how much did I spend on food last month"
 *    with high accuracy and sub-100ms latency.
 * 2. **Cloud interpretation** (fallback 1):
 *    If on-device confidence < threshold (e.g. < 0.6) or the query contains
 *    ambiguous entities, delegate to a cloud NLU service (e.g. Google Dialogflow,
 *    OpenAI function calling) via [CloudQueryInterpretationService]. This handles
 *    complex multi-filter queries and learns from new patterns.
 * 3. **Legacy regex** (final fallback):
 *    If cloud is unavailable (offline/timeout) or also returns low confidence,
 *    fall back to the existing [interpretQuery] regex-based extraction. This
 *    guarantees basic functionality even without network connectivity.
 *
 * The cascading pattern ensures graceful degradation:
 * ```
 * val interpretation = onDeviceService.interpret(input)
 *     ?: cloudService.interpret(input)
 *     ?: interpretQuery(input)  // legacy regex
 * ```
 * Each stage should set [QueryInterpretation.confidence] appropriately so the
 * caller can decide whether to display results or ask for clarification.
 *
 * ## Known Limitations
 *
 * ### M1: Amount filters not currency-aware
 * ~~Amount comparisons in [executeSearch] and [buildSearchFilter] compare raw
 * doubles without any currency conversion.~~
 *
 * ## W16-FIXED: Currency-aware amount filtering
 * Amount comparisons normalize amounts to home currency via
 * [CurrencyConverter.convertAsOf] at historical rates. Failed conversions are
 * excluded (no raw fallback) and surfaced via [QueryDataQuality].
 *
 * ### W14-FIXED: Multi-word merchant extraction
 * [extractMerchants] uses a multi-word regex with stop-word lookahead and
 * cross-references [MerchantNormalizationRepository] aliases. Original query
 * casing is preserved.
 *
 * ### W15/W30/W31-FIXED: Category push-down + keyset pagination
 * Category filters are pushed to DAO SQL via [getExpensesBetweenFilteredKeyset].
 * Keyset pagination with [SearchCursor] replaces offset pagination (no duplicates).
 */
@Deprecated("Legacy NL engine. Use ExecuteFinancialQueryUseCase for new query work. This engine is feature-contained.")
@Singleton
class NaturalLanguageSearchEngine @Inject constructor(
    private val expenseQueryRepository: NaturalLanguageExpenseQueryRepository,
    private val speechInputGateway: SpeechInputGateway,
    private val timeProvider: TimeProvider,
    private val currencyConverter: CurrencyConverter,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val categoryRepository: CategoryRepository,
    private val merchantNormalizationRepository: com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
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
        val merchants = extractMerchants(normalized, query)  // pass original query for case-sensitive patterns

        // Category and location filters are parsed but NOT applied in the legacy NL path.
        // Warn so callers know to use ExecuteFinancialQueryUseCase for filtered queries.
        if (categories != null || locations != null) {
            Timber.w("Category/location filters parsed but not applied in legacy NL. " +
                "Use ExecuteFinancialQueryUseCase for filtered queries.")
        }
        
        // Determine query type
        val queryType = determineQueryType(normalized)
        
        // Build search filter
        // TODO (W15): Apply parsed category/location/merchant filters to repository queries.
        // Currently filters are extracted but not pushed to DAO filters.
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
            confidence = calculateConfidence(query, amounts, dateRange, categories, merchants),
            dataQuality = QueryDataQuality(unsupportedLocations = locations != null)
        )
    }
    
    /**
     * Executes the search described by the interpretation.
     *
     * ## SR-1: Currency-aware amount filtering
     * Amount comparisons normalize expense amounts to the user's home currency
     * via [CurrencyConverter] before comparing with the filter value. Expenses
     * in foreign currencies are converted using the latest available exchange
     * rate. If conversion fails (missing rate), the raw amount is used as
     * fallback and the comparison may be inaccurate — this is logged.
     *
     * ## SRH-2: Category and location filters are parsed but NOT applied
     * The [interpretQuery] method extracts [locations] and [categories] from
     * the natural language query and stores them in [SearchFilter]. However,
     * [executeSearch] currently only filters by **merchants** and **amounts**
     * — the category and location filters are **ignored** during query execution.
     * This means a query like "find food expenses in Paris" will match ALL
     * transactions in the date range regardless of category or location.
     *
     * ### Fix guidance for SRH-2:
     * To apply category filters, inject a `CategoryRepository` into this class and
     * build a lookup map from category name (e.g., "food") → list of category IDs
     * using the `categoryKeywords` mapping. Then filter expenses by `categoryId`:
     * ```
     * val categoryIdsByName: Map<String, List<Long>> = categoryRepository.getAll()
     *     .flatMap { cat -> (categoryKeywords[cat.name] ?: emptyList()).map { it to cat.id } }
     *     .groupBy({ it.first }, { it.second })
     * interpretation.searchFilter.categories?.let { names ->
     *     val targetIds = names.flatMap { categoryIdsByName[it] ?: emptyList() }.toSet()
     *     if (targetIds.isNotEmpty()) filter { it.categoryId in targetIds }
     * }
     * ```
     * For location filters, the [NaturalLanguageExpense] model has no location field
     * — location data lives on the raw [Expense] entity (latitude/longitude). To
     * support location-based NL search, either add a location field to
     * [NaturalLanguageExpense] or geo-resolve location names via a geocoding service
     * and compare against expense coordinates in a separate query.
     *
     * ## Multi-filter drilldown (M3)
     * The initial data pull from [expenseQueryRepository] is only date-bounded;
     * all other filters (amount, merchant, category, location) are applied
     * in-memory after loading. This means loading can be unnecessarily broad
     * when multiple filters are active. Filters should ideally be pushed down
     * to the DAO layer for efficient querying.
     */
    /**
     * Executes a search and returns results labeled with match quality.
     *
     * Each result is wrapped in a [SearchResult] indicating whether the expense
     * matched all query criteria (EXACT) or only some (PARTIAL). This allows
     * downstream consumers to display match quality alongside results.
     */
    suspend fun executeSearch(interpretation: QueryInterpretation): List<SearchResult> {
        // PR5: Location filters are not supported — return empty results with warning flag.
        if (!interpretation.locations.isNullOrEmpty()) {
            Timber.w("NaturalLanguageSearchEngine: location filter not supported — returning empty results")
            interpretation.dataQuality = interpretation.dataQuality.copy(unsupportedLocations = true)
            return emptyList()
        }

        val (startMs, endMs) = resolveDateRangeMillis(interpretation.dateRange)
        val homeCurrency = currencySettingsRepository.homeCurrency().first()

        val merchants = interpretation.merchants
        val categories = interpretation.categories

        // Resolve parsed category names to category IDs for DAO push-down (W15)
        val targetCategoryIds: Set<Long> = if (categories.isNullOrEmpty()) {
            emptySet()
        } else {
            val allCategories = categoryRepository.getAll()
            val categoryNameToId = allCategories.associate { it.normalizedName to it.id }
            categories.mapNotNull { catName ->
                categoryNameToId[catName.trim().lowercase()]
            }.toSet()
        }
        val hasCategoryFilter = targetCategoryIds.isNotEmpty()

        // W16: Never pass minAmount/maxAmount to repository.
        // Amount filtering is done in-memory after currency normalization.
        var failedConversions = 0
        val dataQuality = QueryDataQuality(
            unsupportedLocations = interpretation.locations != null
        )

        // NLP-5: Loop through all keyset pages for full result correctness
        val preFiltered = mutableListOf<NaturalLanguageExpense>()
        val categoryFilter = targetCategoryIds.takeIf { it.isNotEmpty() }
        var cursor: SearchCursor? = null
        while (true) {
            val page = expenseQueryRepository.getExpensesBetweenFilteredKeyset(
                startMs = startMs, endMs = endMs,
                categoryIds = categoryFilter,
                merchants = merchants,
                transactionType = null,
                keywordSearch = null,
                limit = PAGE_SIZE_LARGE,
                cursor = cursor
            )
            if (page.isEmpty()) break
            preFiltered.addAll(page)
            if (page.size < PAGE_SIZE_LARGE) break
            val last = page.last()
            cursor = SearchCursor(date = last.date, id = last.id)
        }

        // W16: Apply currency-safe amount filtering.
        // Normalize each row to home currency via convertAsOf(expense.date).
        // Exclude failed conversions — no raw fallback.
        val hasAmountFilter = interpretation.extractedAmounts != null &&
            interpretation.extractedAmounts.isNotEmpty()

        val normalizedRows: List<Pair<NaturalLanguageExpense, Double>> = preFiltered.mapNotNull { expense ->
            val normalizedAmount = if (expense.currency == homeCurrency) {
                expense.effectiveAmount
            } else {
                currencyConverter.convertAsOf(
                    amount = expense.effectiveAmount,
                    fromCurrency = expense.currency,
                    toCurrency = homeCurrency,
                    atMillis = expense.date
                )?.convertedAmount
            }
            if (normalizedAmount == null) {
                failedConversions++
                Timber.w("W16: Excluding expense %d (%s %.2f %s) — no rate for %s→%s as of %d",
                    expense.id, expense.merchant, expense.effectiveAmount, expense.currency,
                    expense.currency, homeCurrency, expense.date)
                null
            } else {
                expense to normalizedAmount
            }
        }

        // Apply amount filter if present
        val matched = if (hasAmountFilter) {
            normalizedRows.filter { (_, normalizedAmount) ->
                interpretation.extractedAmounts!!.any { amount ->
                    when (amount.comparison) {
                        AmountComparison.EXACTLY -> normalizedAmount == amount.value
                        AmountComparison.OVER -> normalizedAmount > amount.value
                        AmountComparison.UNDER -> normalizedAmount < amount.value
                        AmountComparison.BETWEEN -> {
                            val other = interpretation.extractedAmounts.find { it != amount }
                            other?.let { normalizedAmount in amount.value..it.value } ?: true
                        }
                    }
                }
            }
        } else {
            normalizedRows
        }

        val dataQualityFinal = dataQuality.copy(
            failedCurrencyConversions = failedConversions
        )

        // Label each result with match quality
        val hasMerchantFilter = interpretation.merchants != null
        val needsAmountMatch = hasAmountFilter && !hasCategoryFilter && !hasMerchantFilter
        val needsMerchantMatch = hasMerchantFilter

        return matched.map { (expense, _) ->
            val matchedMerchant = if (needsMerchantMatch) {
                interpretation.merchants!!.any { expense.merchant.contains(it, ignoreCase = true) }
            } else {
                !hasMerchantFilter  // exact when no merchant filter
            }
            val matchedAmount = if (needsAmountMatch) {
                normalizedRows.any { (e, norm) ->
                    e == expense && interpretation.extractedAmounts!!.any { amount ->
                        when (amount.comparison) {
                            AmountComparison.EXACTLY -> norm == amount.value
                            AmountComparison.OVER -> norm > amount.value
                            AmountComparison.UNDER -> norm < amount.value
                            AmountComparison.BETWEEN -> {
                                val other = interpretation.extractedAmounts.find { it != amount }
                                other?.let { norm in amount.value..it.value } ?: true
                            }
                        }
                    }
                }
            } else {
                !hasAmountFilter  // exact when no amount filter
            }
            val matchedCategory = !hasCategoryFilter || expense.categoryId in targetCategoryIds

            SearchResult(
                expense = expense,
                matchType = if (matchedMerchant && matchedAmount && matchedCategory)
                    MatchType.EXACT else MatchType.PARTIAL
            )
        }.also {
            // W15+W16: Attach dataQuality to interpretation for caller consumption
            interpretation.dataQuality = dataQualityFinal
            if (dataQualityFinal.hasWarnings) {
                Timber.w("Legacy NL search completed with dataQuality warnings: %s", dataQualityFinal)
            }
        }
    }

    /** Backward-compatible convenience accessor for callers that don't need match-type labeling. */
    @Deprecated("Use executeSearch() which returns List<SearchResult> with match-type labels",
        ReplaceWith("executeSearch(interpretation).map { it.expense }"))
    suspend fun executeSearchLegacy(interpretation: QueryInterpretation): List<NaturalLanguageExpense> {
        return executeSearch(interpretation).map { it.expense }
    }

    /**
     * SRH-19-FIXED: Legacy search now defaults to last 3 months instead of 0→now.
     *
     * Previously, when no date range was specified in a query, the start was set
     * to 0L (epoch start, Jan 1 1970), which meant searching the ENTIRE history.
     * This was both slow and confusing for users who would get results from years ago.
     *
     * The new default is 3 months back from now, which covers most "recent spending"
     * queries while remaining performant. Callers that need a wider range should
     * explicitly specify dates in their query (e.g. "last year", "since 2023").
     */
    private fun resolveDateRangeMillis(dateRange: DateRange?): Pair<Long, Long> {
        val now = timeProvider.now()
        val startMs = dateRange?.start?.let {
            it.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } ?: DEFAULT_SEARCH_WINDOW_START_MS(now)
        val endMs = dateRange?.end?.let {
            it.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } ?: now
        return startMs to endMs
    }

    companion object {
        /** W30+W31: Max rows per keyset page for legacy NL queries. */
        private const val PAGE_SIZE_LARGE = 500

        /**
         * Default search window: 3 months before [now].
         * Used when no explicit date range is specified in the query.
         */
        private fun DEFAULT_SEARCH_WINDOW_START_MS(now: Long): Long {
            return TimePeriodUtils.addMonths(now, -3)
        }
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
    
    // W14: Improved merchant extraction with multi-word capture + alias lookup.
    // Captures multi-word names after "at"/"from", stopping before known filter keywords.
    private val merchantStopWords = setOf(
        "in", "on", "for", "with", "last", "this", "over", "under",
        "yesterday", "today", "january", "february", "march", "april",
        "may", "june", "july", "august", "september", "october",
        "november", "december", "near", "around", "between"
    )
    private val merchantPattern = Regex(
        """(?:at|from)\s+([A-Za-z][A-Za-z\s&]*?)(?=\s*(?:${merchantStopWords.joinToString("|")})\b|$)""",
        RegexOption.IGNORE_CASE
    )

    private suspend fun extractMerchants(query: String, originalQuery: String): List<String>? {
        // Step 1: Multi-word regex extraction from original (case-preserving) query
        val rawMatches = merchantPattern.findAll(originalQuery)
            .map { it.groupValues[1].trim() }
            .filter { it.length >= 2 && !it.all { c -> c.isDigit() } }
            .distinctBy { it.lowercase() }
            .toList()

        // Step 2: Look up each raw match against known aliases
        val resolved = rawMatches.flatMap { raw ->
            val alias = merchantNormalizationRepository.getAliasByRawName(raw)
                ?: merchantNormalizationRepository.getAliasByNormalizedKey(
                    com.yourname.expensetracker.domain.util.MerchantKeyGenerator.generate(raw)
                )
            if (alias != null) listOf(alias.rawName) else listOf(raw)
        }.distinctBy { it.lowercase() }

        // Step 3: Supplementary lookup — known merchant names appearing anywhere in query
        val topMerchants = merchantNormalizationRepository.getTopMerchants(100)
        val knownFromQuery = topMerchants
            .filter { canonical ->
                originalQuery.contains(canonical.normalizedName, ignoreCase = true) ||
                    originalQuery.contains(canonical.searchKey, ignoreCase = true)
            }
            .map { it.normalizedName }

        val allMerchants = (resolved + knownFromQuery)
            .distinctBy { it.lowercase() }

        return if (allMerchants.isNotEmpty()) allMerchants else null
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
     * ## SR-1: Currency-aware filtering (fixed in v112)
     * Amount normalization now happens at filter-application time in
     * [executeSearch], not here in the filter builder. The [minAmount],
     * [maxAmount], and [exactAmount] fields store raw filter values as
     * extracted from the query; the actual comparison converts each
     * expense's amount to the user's home currency using [CurrencyConverter].
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
        val confidence: Double,
        var dataQuality: QueryDataQuality = QueryDataQuality()
    )

    /**
     * W15+W16: Carries data-quality metadata about the search — e.g. whether
     * parsed location filters are unsupported, or how many rows had failed
     * currency conversions.
     */
    data class QueryDataQuality(
        val unsupportedLocations: Boolean = false,
        val failedCurrencyConversions: Int = 0
    ) {
        val hasWarnings: Boolean
            get() = unsupportedLocations || failedCurrencyConversions > 0
    }
    
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
