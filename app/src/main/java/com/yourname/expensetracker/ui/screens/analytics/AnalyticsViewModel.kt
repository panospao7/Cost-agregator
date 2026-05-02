package com.yourname.expensetracker.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.analytics.*
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.location.AreaSpending
import com.yourname.expensetracker.domain.location.AreaSpendingEngine
import com.yourname.expensetracker.domain.location.LocatedExpense
import com.yourname.expensetracker.domain.location.LocationInsightsEngine
import com.yourname.expensetracker.domain.location.PlaceInsight
import com.yourname.expensetracker.domain.location.TravelDetectionEngine
import com.yourname.expensetracker.domain.location.TravelInsight
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class BudgetVsActualItem(
    val categoryName: String,
    val categoryIcon: String,
    val categoryColor: String,
    val budgetAmount: Double,
    val actualSpent: Double,
    val percentUsed: Float, // 0.0 - 1.0+
    /** Placeholder default; should be populated with actual home currency from [CurrencySettingsRepository]. */
    val displayCurrency: String = "EUR"
) {
    val moneyBudgetAmount: MoneyAmount get() = MoneyAmount(budgetAmount, CurrencyCode(displayCurrency))
    val moneyActualSpent: MoneyAmount get() = MoneyAmount(actualSpent, CurrencyCode(displayCurrency))
}

data class AnalyticsState(
    val selectedPeriod: TimePeriod = TimePeriod.MONTH,
    val currentTotal: Double = 0.0,
    val previousTotal: Double? = null,
    val changePercent: Float? = null,
    val transactionCount: Int = 0,
    val categoryBreakdown: List<AnalyticsCategoryBreakdown> = emptyList(),
    val merchantBreakdown: List<MerchantBreakdown> = emptyList(),
    val dailyTotals: Map<String, Double> = emptyMap(),
    val insights: List<SpendingInsight> = emptyList(),
    val recurring: List<RecurringCandidate> = emptyList(),
    val yearOverYear: YearOverYearComparison? = null,
    val velocityAnomalies: List<VelocityAnomaly> = emptyList(),
    val postSalaryPattern: PostSalaryPattern? = null,
    val suspectTransactions: List<SuspectTransaction> = emptyList(),
    val dayOfWeekPattern: List<DayOfWeekInsight> = emptyList(),
    val budgetVsActual: List<BudgetVsActualItem> = emptyList(),
    // Advanced analytics (merged from AdvancedAnalyticsScreen)
    val statisticalInsights: StatisticalInsights? = null,
    val enhancedCategories: List<EnhancedCategoryAnalytics> = emptyList(),
    val enhancedMerchants: List<EnhancedMerchantAnalytics> = emptyList(),
    val spendingPatterns: SpendingPatternAnalysis? = null,
    val hourOfDayPattern: List<Pair<Int, Double>> = emptyList(), // hour(0-23) -> total spent
    val currentDateRange: Pair<Long, Long>? = null, // period start/end for filter navigation
    // Location insights (B5, B1, B2)
    val locationInsights: List<PlaceInsight> = emptyList(),
    val areaSpending: List<AreaSpending> = emptyList(),
    val travelInsight: TravelInsight? = null,
    // F13: Spending Personality Profile
    val personalityProfile: SpendingPersonalityProfile? = null,
    val isLoading: Boolean = true,
    /** Placeholder default; overridden by [CurrencySettingsRepository.homeCurrency] during init. */
    val homeCurrency: String = "EUR",
    val conversionWarnings: List<AnalyticsConversionWarning> = emptyList(),
    /**
     * Plain-text quality warnings derived from [conversionWarnings].
     * Populated automatically when the state is built. Consumers (UI, forecast,
     * health) use this for simple "show warning banner" logic without parsing
     * the richer [AnalyticsConversionWarning] type.
     */
    val qualityWarnings: List<String> = emptyList(),
    val latestRateTimestamp: Long? = null,
    val referenceNowMillis: Long = 0L
) {
    val moneyCurrentTotal: MoneyAmount get() = MoneyAmount(currentTotal, CurrencyCode(homeCurrency))
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val insightsEngine: InsightsEngine,
    private val recurringExpenseEngine: com.yourname.expensetracker.domain.logic.RecurringExpenseEngine,
    private val analyticsRepository: com.yourname.expensetracker.data.repository.AnalyticsRepository,
    private val advancedAnalyticsEngine: AdvancedAnalyticsEngine,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val locationInsightsEngine: LocationInsightsEngine,
    private val areaSpendingEngine: AreaSpendingEngine,
 private val travelDetectionEngine: TravelDetectionEngine,
 private val spendingPersonalityClassifier: SpendingPersonalityClassifier,
 private val timeProvider: TimeProvider,
 private val currencyConverter: CurrencyConverter,
 private val currencySettingsRepository: CurrencySettingsRepository
) : ViewModel() {

    private data class PeriodCacheKey(
        val period: TimePeriod,
        val startMs: Long,
        val endMs: Long,
        val categoriesHash: Int,
        val latestExpenseTimestamp: Long,
        val expenseCount: Int,
        val dataVersion: Long,
        val budgetsHash: Int,
        val budgetDataVersion: Long,
        val homeCurrency: String,
        val rateTimestamp: Long
    )

    private data class ExpenseFreshness(
        val latestExpenseTimestamp: Long = 0L,
        val expenseCount: Int = 0,
        val dataVersion: Long = 0L
    )

    private data class BudgetFreshness(
        val budgetsHash: Int = 0,
        val dataVersion: Long = 0L
    )

    private data class AnalyticsInputs(
        val categories: List<Category>,
        val period: TimePeriod,
        val expenseFreshness: ExpenseFreshness,
        val budgetFreshness: BudgetFreshness,
        val homeCurrency: String,
        val rateTimestamp: Long
    )

    private data class AdvResult(
        val cats: List<EnhancedCategoryAnalytics>,
        val merchs: List<EnhancedMerchantAnalytics>,
        val patterns: SpendingPatternAnalysis?,
        val stats: StatisticalInsights?,
        val warnings: List<AnalyticsConversionWarning> = emptyList()
    )

    private data class BudgetChartResult(
        val items: List<BudgetVsActualItem>,
        val warnings: List<AnalyticsConversionWarning>
    )

    private val analyticsCache = ConcurrentHashMap<PeriodCacheKey, AnalyticsState>()
    private val advancedCache = ConcurrentHashMap<PeriodCacheKey, AdvResult>()
    @Volatile
    private var lastExpenseDataVersion: Long = -1L
    @Volatile
    private var lastBudgetDataVersion: Long = -1L

    private val _selectedPeriod = MutableStateFlow(TimePeriod.MONTH)

    private val analyticsBaseInputs = combine(
        categoryRepository.allCategories.catch { emit(emptyList()) },
        _selectedPeriod,
        expenseRepository.getAllExpenses()
            .scan(ExpenseFreshness()) { previous, expenses ->
                ExpenseFreshness(
                    latestExpenseTimestamp = expenses.maxOfOrNull { it.date } ?: 0L,
                    expenseCount = expenses.size,
                    dataVersion = previous.dataVersion + 1L
                )
            }
            .drop(1)
            .catch { emit(ExpenseFreshness()) },
        budgetRepository.allBudgets
            .scan(BudgetFreshness()) { previous, budgets ->
                BudgetFreshness(
                    budgetsHash = computeBudgetsHash(budgets),
                    dataVersion = previous.dataVersion + 1L
                )
            }
            .drop(1)
            .catch { emit(BudgetFreshness()) }
    ) { categories, period, expenseFreshness, budgetFreshness ->
        categories to Triple(period, expenseFreshness, budgetFreshness)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<AnalyticsState> = combine(
        analyticsBaseInputs,
        currencySettingsRepository.homeCurrency()
            .catch { emit("EUR") },
        currencySettingsRepository.lastRateUpdate()
            .catch { emit(0L) }
    ) { baseInputs, homeCurrency, rateTimestamp ->
        val (categories, analyticsBase) = baseInputs
        val (period, expenseFreshness, budgetFreshness) = analyticsBase
        AnalyticsInputs(
            categories = categories,
            period = period,
            expenseFreshness = expenseFreshness,
            budgetFreshness = budgetFreshness,
            homeCurrency = homeCurrency,
            rateTimestamp = rateTimestamp
        )
    }
    .debounce(300)
    .flatMapLatest { inputs ->
        flow {
            val categories = inputs.categories
            val period = inputs.period
            val freshness = inputs.expenseFreshness
            val budgetFreshness = inputs.budgetFreshness
            val homeCurrency = inputs.homeCurrency
            val rateTimestamp = inputs.rateTimestamp

            emit(AnalyticsState(isLoading = true, selectedPeriod = period, homeCurrency = homeCurrency, latestRateTimestamp = rateTimestamp, referenceNowMillis = timeProvider.now(), qualityWarnings = emptyList()))

            if (
                freshness.dataVersion != lastExpenseDataVersion ||
                budgetFreshness.dataVersion != lastBudgetDataVersion
            ) {
                analyticsCache.clear()
                advancedCache.clear()
                lastExpenseDataVersion = freshness.dataVersion
                lastBudgetDataVersion = budgetFreshness.dataVersion
            }

            val now = timeProvider.now()
            val (currentStart, currentEnd) = getPeriodRange(period, now)
            val cacheKey = PeriodCacheKey(
                period = period,
                startMs = currentStart,
                endMs = currentEnd,
                categoriesHash = categories.hashCode(),
                latestExpenseTimestamp = freshness.latestExpenseTimestamp,
                expenseCount = freshness.expenseCount,
                dataVersion = freshness.dataVersion,
                budgetsHash = budgetFreshness.budgetsHash,
                budgetDataVersion = budgetFreshness.dataVersion,
                homeCurrency = homeCurrency,
                rateTimestamp = rateTimestamp
            )

            val cached = analyticsCache[cacheKey]
            if (cached != null) {
                emit(cached.copy(isLoading = false, selectedPeriod = period, homeCurrency = homeCurrency, latestRateTimestamp = rateTimestamp))
                return@flow
            }

            val result = computeAnalyticsInternal(categories, period, currentStart, currentEnd, now, cacheKey, homeCurrency, rateTimestamp)
            analyticsCache[cacheKey] = result
            emit(result)
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AnalyticsState()
    )

    fun selectPeriod(period: TimePeriod) {
        _selectedPeriod.value = period
    }

    private suspend fun computeAnalyticsInternal(
        categories: List<Category>,
        period: TimePeriod,
        currentStart: Long,
        currentEnd: Long,
        now: Long,
        cacheKey: PeriodCacheKey,
        homeCurrency: String,
        rateTimestamp: Long
    ): AnalyticsState {
        val purchases = expenseRepository.getExpensesBetween(currentStart, currentEnd)
            .asSequence()
            .filter { it.transactionType == TransactionType.PURCHASE }
            .toList()

        val fullWindowStart = when (period) {
            TimePeriod.ALL -> 0L
            else -> TimePeriodUtils.getStartOfYear(now)
        }
        val periodLength = currentEnd - currentStart
        val previousStart = currentStart - periodLength
        val previousEnd = currentStart
        val allExpenses = expenseRepository.getExpensesBetween(fullWindowStart, currentEnd)
        val previousExpenses = expenseRepository.getExpensesBetween(previousStart, previousEnd)
            .asSequence()
            .filter { it.transactionType == TransactionType.PURCHASE }
            .toList()

        val currentNormalization = analyticsCurrencyNormalizer.normalizeExpenses(purchases, homeCurrency)
        val previousNormalization = analyticsCurrencyNormalizer.normalizeExpenses(previousExpenses, homeCurrency)
        val allNormalization = analyticsCurrencyNormalizer.normalizeExpenses(allExpenses, homeCurrency)

        val yearOverYearLookbackStart = TimePeriodUtils.getYearRange(TimePeriodUtils.getYear(now) - 1).first
        val yearOverYearExpenses = expenseRepository.getExpensesBetween(yearOverYearLookbackStart, currentEnd)
            .asSequence()
            .filter { it.transactionType == TransactionType.PURCHASE }
            .toList()
        val yearOverYearNormalization = analyticsCurrencyNormalizer.normalizeExpenses(yearOverYearExpenses, homeCurrency)
        val warningNormalization = analyticsCurrencyNormalizer.normalizeExpenses(
            (purchases + previousExpenses + allExpenses + yearOverYearExpenses)
                .associateBy { it.id }
                .values
                .toList(),
            homeCurrency
        )

        val categoryEntityMap = categories.associateBy { it.id }
        val analyticsCategories = categories.map {
            AnalyticsCategoryRef(
                id = it.id,
                name = it.name,
                icon = it.icon,
                color = it.color
            )
        }

        // Current/previous period expenses
        val currentExpenseSnapshots = currentNormalization.includedExpenses
        val allExpenseSnapshots = allNormalization.includedExpenses
        val previousExpenseSnapshots = previousNormalization.includedExpenses

        // SAFE: currentExpenseSnapshots is pre-normalized via AnalyticsCurrencyNormalizer at line 292
        val currentTotal = currentExpenseSnapshots.sumOf { it.effectiveAmount }
        // SAFE: previousExpenseSnapshots is pre-normalized via AnalyticsCurrencyNormalizer at line 293
        val previousTotal = previousExpenseSnapshots.sumOf { it.effectiveAmount }
        val changePercent = if (previousTotal > 0.0) {
            ((currentTotal - previousTotal) / previousTotal * 100).toFloat()
        } else {
            null
        }

        val totalForPercentage = currentTotal.coerceAtLeast(0.0)
        val categoryBreakdown = currentExpenseSnapshots
            .groupBy { it.categoryId }
            .mapNotNull { (categoryId, snapshots) ->
                val category = categoryId
                    ?.let { id -> analyticsCategories.firstOrNull { it.id == id } }
                    ?: AnalyticsCategoryRef(
                        id = -1,
                        name = "Uncategorized",
                        icon = "❓",
                    color = "#9E9E9E"
                )
                // SAFE: snapshots grouped from currentExpenseSnapshots (normalized at line 292)
                val total = snapshots.sumOf { it.effectiveAmount }
                AnalyticsCategoryBreakdown(
                    category = category,
                    total = total,
                    count = snapshots.size,
                    percentage = if (totalForPercentage > 0.0) ((total / totalForPercentage) * 100f).toFloat() else 0f,
                    displayCurrency = homeCurrency
                )
            }
            .sortedByDescending { it.total }

        // Merchant breakdown (Still manual for now, or move to Repo later)
        val merchantBreakdown = currentExpenseSnapshots
            .groupBy { it.canonicalMerchantKey() }
            .map { (_, exps) ->
                // SAFE: exps grouped from currentExpenseSnapshots (normalized at line 292)
                val totalAmount = exps.sumOf { it.effectiveAmount }
                MerchantBreakdown(
                    name = resolveMerchantDisplayName(exps),
                    totalSpent = totalAmount,
                    transactionCount = exps.size,
                    averageTransaction = totalAmount / exps.size,
                    categoryId = exps.firstOrNull()?.categoryId,
                    displayCurrency = homeCurrency
                )
            }
            .sortedByDescending { it.totalSpent }

        // Daily totals for chart
        // Repo returns daily history as list of floats (daily totals)
        // InsightsEngine.buildDailyTotals previously returned Map<String, Double>
        // We need to check what the UI expects.
        // AnalyticsViewModel State: val dailyTotals: Map<String, Double>
        // We need to map Repo's list back to a Map if the UI depends on it. 
        // Or refactor UI. Let's look at `dailyTotals` usage in `AnalyticsScreen` later.
        // For now, let's keep using `insightsEngine` for `dailyTotals` to avoid breaking specific UI graph formatting 
        // OR map the repo data. 
        // Actually, `insightsEngine.buildDailyTotals` probably formats dates as keys. 
        // The repo returns just values. 
        // Let's stick to `insightsEngine.buildDailyTotals` for `dailyTotals` UNTIL we update the UI to accept a list.
        // But we SHOULD upgrade `getPeriodRange` to use Utils.
        val chartDays = when (period) {
            TimePeriod.TODAY -> 1
            TimePeriod.WEEK -> 7
            TimePeriod.MONTH -> 30
            TimePeriod.QUARTER -> 90
            TimePeriod.YEAR -> 365
            TimePeriod.ALL -> {
                val oldest = currentExpenseSnapshots.minOfOrNull { it.date } ?: now
                TimePeriodUtils.daysBetween(oldest, now).coerceIn(7, 365)
            }
        }
        val dailyTotals = insightsEngine.buildDailyTotals(currentExpenseSnapshots, chartDays)

        // Insights
        val budgetChartResult = buildBudgetVsActualItems(homeCurrency, currentExpenseSnapshots)
        val conversionWarnings = mergeWarnings(warningNormalization.warnings + budgetChartResult.warnings)
        val latestRateTimestamp = listOfNotNull(
            currentNormalization.latestRateTimestamp,
            previousNormalization.latestRateTimestamp,
            allNormalization.latestRateTimestamp,
            yearOverYearNormalization.latestRateTimestamp,
            warningNormalization.latestRateTimestamp,
            rateTimestamp.takeIf { it > 0L }
        ).maxOrNull()
        val insightsSnapshot = insightsEngine.generateInsights(
            analyticsCategories,
            allExpenseSnapshots,
            displayCurrency = homeCurrency,
            conversionWarnings = conversionWarnings
        )
        val insights = insightsEngine.getLegacyInsights(insightsSnapshot, homeCurrency)
        // Note: dayOfWeekPattern is now computed period-aware below from currentExpenses

        // Recurring (use the list from snapshot but mapped to legacy if needed, or just legacy detection)
        // Refactor: Use RecurringExpenseEngine directly to ensure consistent detection (LOG-020)
        // We filter purchases for relevance but the engine can handle the full list too. 
        // Note: The engine normally looks at 12 months. 'allExpenses' here might be limited by 'period' if we passed filtered list?
        // Actually generateInsights receives 'allExpenses' (usually full list or large subset).
        // Let's assume 'allExpenses' passed to computeAnalytics is sufficient.
        val patterns = recurringExpenseEngine.getPatternsFromSnapshots(allExpenseSnapshots)
        val recurringOccurrencesByMerchant = allExpenseSnapshots
            .groupBy { it.canonicalMerchantKey() }
            .mapValues { (_, snapshots) ->
                snapshots.size to resolveMerchantDisplayName(snapshots)
            }
        
        val recurring = patterns.map { pattern ->
            val patternMerchantKey = ExpenseSnapshot(
                id = -1L,
                amount = pattern.averageAmount,
                effectiveAmount = pattern.averageAmount,
                currency = "",
                merchant = pattern.merchantName,
                merchantKey = null,
                transactionType = DomainTransactionType.PURCHASE,
                date = 0L,
                categoryId = pattern.categoryId,
                isNotMine = false,
                transferDirection = null,
                notes = null
            ).canonicalMerchantKey()
            val (occurrences, displayMerchantName) = recurringOccurrencesByMerchant[patternMerchantKey]
                ?: (0 to pattern.merchantName)
            val intervalDays = pattern.frequency.fixedIntervalDays
                ?: pattern.frequency.calendarMonths?.times(30)
                ?: 0
            RecurringCandidate(
                merchant = displayMerchantName,
                amount = pattern.averageAmount,
                intervalDays = intervalDays,
                occurrences = occurrences,
                nextExpectedDate = pattern.nextExpectedDate,
                confidence = pattern.confidence,
                displayCurrency = homeCurrency
            )
        }

        // ── Year-over-Year Comparison ──────────────────────────────────────────
        val yearOverYear = computeYearOverYear(yearOverYearNormalization.includedExpenses, now, homeCurrency)

        // ── Spending Velocity Anomalies ──────────────────────────────────
        val velocityAnomalies = computeVelocityAnomalies(currentExpenseSnapshots, homeCurrency)

        // ── Post-Salary Sequential Pattern ─────────────────────────────────
        val postSalaryPattern = computePostSalaryPattern(allNormalization.includedExpenses, categories, homeCurrency)

        // ── Duplicate/Error Detection ──────────────────────────────────────
        val suspectTransactions = detectSuspectTransactions(currentExpenseSnapshots, homeCurrency)

        // ── Budget vs Actual per Category ───────────────────────────────────
        val budgetVsActual = budgetChartResult.items

        // ── Advanced analytics (parallel) ────────────────────────────────────
        // Keep ViewModel period range as single source of truth.
        // MONTH/QUARTER/YEAR are rolling windows in this ViewModel, so pass CUSTOM
        // to avoid engine calendar-period interpretation.

        // === ANALYTICS DEBUG ===
        val periodDays = ((currentEnd - currentStart) / (24 * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
        val dateFormatter = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
        fun formatTimestamp(ms: Long): String = dateFormatter.format(java.util.Date(ms))

        Timber.d("=== ANALYTICS DEBUG ===")
        Timber.d("Period: $period")
        Timber.d("Date Range: ${formatTimestamp(currentStart)} → ${formatTimestamp(currentEnd)}")
        Timber.d("Period Days: $periodDays")
        Timber.d("Transactions: ${currentExpenseSnapshots.size}")
        Timber.d("Total: $homeCurrency$currentTotal")
        Timber.d("Daily Totals: $dailyTotals")
        Timber.d("Average Daily: $homeCurrency${if (periodDays > 0) currentTotal / periodDays else 0.0} ($homeCurrency$currentTotal / $periodDays days)")
        Timber.d("========================")
        // === END DEBUG ===

        val advancedPeriod = timePeriodToAnalyticsPeriod(period)
        val advRange = if (advancedPeriod != null) {
            advancedAnalyticsEngine.getPeriodRange(advancedPeriod)
        } else {
            AnalyticsPeriodRange(
                period = AnalyticsPeriod.CUSTOM,
                startMs = currentStart,
                endMs = currentEnd,
                label = period.name,
                comparisonRange = AnalyticsPeriodRange(
                    period = AnalyticsPeriod.CUSTOM,
                    startMs = previousStart,
                    endMs = previousEnd,
                    label = "PREVIOUS_${period.name}",
                    comparisonRange = null
                )
            )
        }

        val advResult = advancedCache[cacheKey] ?: coroutineScope {
            val catDeferred = async { advancedAnalyticsEngine.getCategoryAnalytics(advRange, displayCurrency = homeCurrency) }
            val merchDeferred = async { advancedAnalyticsEngine.getMerchantAnalytics(advRange, displayCurrency = homeCurrency, limit = 15) }
            val patternsDeferred = async { advancedAnalyticsEngine.getSpendingPatterns(advRange, displayCurrency = homeCurrency) }
            val statsDeferred = async { advancedAnalyticsEngine.getStatisticalInsights(advRange, displayCurrency = homeCurrency) }
            val (cats, catWarnings) = try { catDeferred.await() } catch (_: Exception) { emptyList<EnhancedCategoryAnalytics>() to emptyList() }
            val (merchs, merchWarnings) = try { merchDeferred.await() } catch (_: Exception) { emptyList<EnhancedMerchantAnalytics>() to emptyList() }
            val (patterns, patternWarnings) = try { patternsDeferred.await() } catch (_: Exception) { null to emptyList<AnalyticsConversionWarning>() }
            val (stats, statWarnings) = try { statsDeferred.await() } catch (_: Exception) { null to emptyList<AnalyticsConversionWarning>() }
            AdvResult(
                cats = cats,
                merchs = merchs,
                patterns = patterns,
                stats = stats,
                warnings = (catWarnings + merchWarnings + patternWarnings + statWarnings).distinct()
            ).also { advancedCache[cacheKey] = it }
        }

        // ── Day-of-week pattern (period-aware, computed from currentExpenses) ─
        val dowNames = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val dowTotals = DoubleArray(7)
        val dowCounts = IntArray(7)
        currentExpenseSnapshots.forEach { exp ->
            val idx = when (TimePeriodUtils.getDayOfWeek(exp.date)) {
                java.util.Calendar.MONDAY -> 0
                java.util.Calendar.TUESDAY -> 1
                java.util.Calendar.WEDNESDAY -> 2
                java.util.Calendar.THURSDAY -> 3
                java.util.Calendar.FRIDAY -> 4
                java.util.Calendar.SATURDAY -> 5
                java.util.Calendar.SUNDAY -> 6
                else -> 0
            }
            dowTotals[idx] += exp.effectiveAmount
            dowCounts[idx]++
        }
        val dayOfWeekPattern = (0..6).map { idx ->
            DayOfWeekInsight(
                dayIndex = idx,
                dayName = dowNames[idx],
                totalSpent = dowTotals[idx],
                transactionCount = dowCounts[idx],
                avgPerTransaction = if (dowCounts[idx] > 0) dowTotals[idx] / dowCounts[idx] else 0.0,
                displayCurrency = homeCurrency
            )
        }

        // ── Hour-of-day pattern (period-aware) ────────────────────────────────
        val hourTotals = DoubleArray(24)
        currentExpenseSnapshots.forEach { exp ->
            val hour = TimePeriodUtils.getHourOfDay(exp.date)
            hourTotals[hour] += exp.effectiveAmount
        }
        val hourOfDayPattern = (0..23)
            .map { h -> Pair(h, hourTotals[h]) }
            .filter { (_, v) -> v > 0 }

        // ── Location analytics (B5 / B1 / B2) ────────────────────────────────
        // Convert located purchases (any period) to LocatedExpense for the engine.
        val locatedExpenses = purchases.mapNotNull { exp ->
            val lat = exp.latitude ?: return@mapNotNull null
            val lon = exp.longitude ?: return@mapNotNull null
            val normalized = currentNormalization.normalizedExpenses.firstOrNull { it.snapshot.id == exp.id } ?: return@mapNotNull null
            LocatedExpense(
                expenseId = exp.id,
                latitude = lat,
                longitude = lon,
                amount = normalized.normalizedEffectiveAmount,
                merchant = exp.merchant,
                date = exp.date,
                locationSource = exp.locationSource,
                placeId = exp.placeId
            )
        }

        val locationInsights = locationInsightsEngine.compute(locatedExpenses).take(10)
        val normalizedPurchases = purchases.mapNotNull { purchase ->
            val normalized = currentNormalization.normalizedExpenses.firstOrNull { it.snapshot.id == purchase.id } ?: return@mapNotNull null
            purchase.copy(
                amount = normalized.normalizedEffectiveAmount,
                currency = homeCurrency,
                isSharedExpense = false,
                myShareAmount = null,
                mySharePercentage = null
            )
        }
        val areaSpending = areaSpendingEngine.compute(normalizedPurchases)
        val travelInsight = travelDetectionEngine.compute(normalizedPurchases)

        // ── F13: Spending Personality Profile ────────────────────────────────
        val personalityProfile = try {
            spendingPersonalityClassifier.classify()
        } catch (e: Exception) {
            null
        }

 return AnalyticsState(
  selectedPeriod = period,
  currentTotal = currentTotal,
  previousTotal = if (previousTotal > 0) previousTotal else null,
  changePercent = changePercent,
  transactionCount = currentExpenseSnapshots.size,
 categoryBreakdown = categoryBreakdown,
 merchantBreakdown = merchantBreakdown,
 dailyTotals = dailyTotals,
 insights = insights,
 recurring = recurring,
 yearOverYear = yearOverYear,
 velocityAnomalies = velocityAnomalies,
 postSalaryPattern = postSalaryPattern,
 suspectTransactions = suspectTransactions,
 dayOfWeekPattern = dayOfWeekPattern,
 budgetVsActual = budgetVsActual,
 statisticalInsights = advResult.stats,
 enhancedCategories = advResult.cats,
 enhancedMerchants = advResult.merchs,
 spendingPatterns = advResult.patterns,
 hourOfDayPattern = hourOfDayPattern,
 currentDateRange = Pair(currentStart, currentEnd),
 locationInsights = locationInsights,
 areaSpending = areaSpending,
  travelInsight = travelInsight,
  personalityProfile = personalityProfile,
  isLoading = false,
  homeCurrency = homeCurrency,
  conversionWarnings = mergeWarnings(conversionWarnings + advResult.warnings),
  qualityWarnings = mergeWarnings(conversionWarnings + advResult.warnings).map { it.message },
  latestRateTimestamp = latestRateTimestamp,
  referenceNowMillis = timeProvider.now()
  )
    }


    @Suppress("DEPRECATION")
    private fun timePeriodToAnalyticsPeriod(period: TimePeriod): AnalyticsPeriod? = when (period) {
        TimePeriod.WEEK -> AnalyticsPeriod.WEEK
        // MONTH/QUARTER/YEAR use rolling windows from getPeriodRange(), so use CUSTOM.
        TimePeriod.MONTH,
        TimePeriod.QUARTER,
        TimePeriod.YEAR,
        TimePeriod.TODAY,
        TimePeriod.ALL -> null
    }

    /** Maps [TimePeriod] to [PeriodKind] for the migration to domain.core.time. */
    @Suppress("DEPRECATION")
    private fun TimePeriod.toPeriodKind(): com.yourname.expensetracker.domain.core.time.PeriodKind = when (this) {
        TimePeriod.TODAY -> com.yourname.expensetracker.domain.core.time.PeriodKind.TODAY
        TimePeriod.WEEK -> com.yourname.expensetracker.domain.core.time.PeriodKind.THIS_WEEK
        TimePeriod.MONTH -> com.yourname.expensetracker.domain.core.time.PeriodKind.THIS_MONTH
        TimePeriod.QUARTER -> com.yourname.expensetracker.domain.core.time.PeriodKind.THIS_QUARTER
        TimePeriod.YEAR -> com.yourname.expensetracker.domain.core.time.PeriodKind.THIS_YEAR
        TimePeriod.ALL -> com.yourname.expensetracker.domain.core.time.PeriodKind.CUSTOM
    }

    private fun computeBudgetsHash(budgets: List<Budget>): Int {
        return budgets
            .sortedBy { it.id }
            .fold(1) { acc, budget -> 31 * acc + budget.hashCode() }
    }

    /**
     * @param currentExpenses Pre-normalized (via AnalyticsCurrencyNormalizer at call site line ~292)
     */
    private fun computeVelocityAnomalies(
        currentExpenses: List<ExpenseSnapshot>,
        displayCurrency: String
    ): List<VelocityAnomaly> {
        if (currentExpenses.size < 5) return emptyList()

        val dayFormat = java.text.SimpleDateFormat("EEE MMM dd", java.util.Locale.getDefault())

        // Group expenses by day key (year-month-day)
        val byDay = mutableMapOf<Long, MutableList<ExpenseSnapshot>>()
        currentExpenses.forEach { exp ->
            val dayMs = TimePeriodUtils.getStartOfDay(exp.date)
            byDay.getOrPut(dayMs) { mutableListOf() }.add(exp)
        }

        // SAFE: currentExpenses is pre-normalized via AnalyticsCurrencyNormalizer (caller line ~292)
        val dailyTotals = byDay.mapValues { (_, exps) -> exps.sumOf { it.effectiveAmount } }
        if (dailyTotals.size < 3) return emptyList()

        val totals = dailyTotals.values.sorted()
        val q1 = totals[totals.size / 4]
        val q3 = totals[(totals.size * 3) / 4]
        val iqr = q3 - q1
        val upperFence = q3 + 1.5 * iqr
        val avg = totals.average()

        if (avg <= 0) return emptyList()

        return dailyTotals
            .filter { (_, total) -> total > upperFence && total > avg * 2.0 }
            .map { (dayMs, total) ->
                val topMerchants = byDay[dayMs]
                    ?.sortedByDescending { it.effectiveAmount }
                    ?.take(3)
                    ?.map { it.merchant }
                    ?: emptyList()
                VelocityAnomaly(
                    dateMs = dayMs,
                    dayLabel = dayFormat.format(java.util.Date(dayMs)),
                    dayTotal = total,
                    monthDailyAvg = avg,
                    deviationMultiple = (total / avg).toFloat(),
                    topMerchants = topMerchants,
                    displayCurrency = displayCurrency
                )
            }
            .sortedByDescending { it.deviationMultiple }
            .take(5)
    }

    /**
     * @param purchases Pre-normalized via AnalyticsCurrencyNormalizer at call site (line ~301)
     */
    private fun computeYearOverYear(
        purchases: List<ExpenseSnapshot>,
        now: Long,
        displayCurrency: String
    ): YearOverYearComparison? {
        val currentYear = TimePeriodUtils.getYear(now)
        val priorYear = currentYear - 1

        val monthNames = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")

        fun monthTotals(year: Int): List<MonthlyYearTotal> {
            return purchases
                .filter { exp ->
                    TimePeriodUtils.getYear(exp.date) == year
                }
                .groupBy { exp ->
                    TimePeriodUtils.getMonth(exp.date)
                }
                .map { (month, exps) ->
                    MonthlyYearTotal(
                        month = month,
                        monthLabel = monthNames[month],
                        // SAFE: purchases is pre-normalized via AnalyticsCurrencyNormalizer (caller line ~301)
                        total = exps.sumOf { it.effectiveAmount },
                        transactionCount = exps.size,
                        displayCurrency = displayCurrency
                    )
                }
                .sortedBy { it.month }
        }

        val currentMonths = monthTotals(currentYear)
        val priorMonths = monthTotals(priorYear)

        // Need at least some data to be useful
        if (currentMonths.isEmpty() && priorMonths.isEmpty()) return null

        val currentTotal = currentMonths.sumOf { it.total }
        val priorTotal = priorMonths.sumOf { it.total }

        val changePercent = if (priorTotal > 0)
            ((currentTotal - priorTotal) / priorTotal * 100).toFloat()
        else null

        // Months that appear in both years
        val currentMap = currentMonths.associateBy { it.month }
        val priorMap = priorMonths.associateBy { it.month }
        val sharedMonths = (currentMap.keys + priorMap.keys).distinct().sorted()
        val deltaByMonth = sharedMonths.mapNotNull { month ->
            val c = currentMap[month]
            val p = priorMap[month]
            if (c != null || p != null) {
                Triple(monthNames[month], c?.total ?: 0.0, p?.total ?: 0.0)
            } else null
        }

        return YearOverYearComparison(
            currentYear = currentYear,
            priorYear = priorYear,
            currentYearMonths = currentMonths,
            priorYearMonths = priorMonths,
            currentYearTotal = currentTotal,
            priorYearTotal = priorTotal,
            changePercent = changePercent,
            deltaByMonth = deltaByMonth,
            displayCurrency = displayCurrency
        )
    }

    private fun getPeriodRange(period: TimePeriod, now: Long): Pair<Long, Long> {
        return when (period) {
            TimePeriod.TODAY -> {
                val start = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
                Pair(start, com.yourname.expensetracker.domain.util.TimePeriodUtils.getEndOfDay(now))
            }
            TimePeriod.WEEK -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getWeekRange(now, 0).let { (start, end) -> start to end }
            TimePeriod.MONTH -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getMonthRange(now)
            TimePeriod.QUARTER -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getQuarterRange(now)
            TimePeriod.YEAR -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getYearRange(now)
            TimePeriod.ALL -> Pair(0L, now)
        }
    }
    /**
     * @param allExpenses Pre-normalized via AnalyticsCurrencyNormalizer at call site (line ~294)
     */
    private fun computePostSalaryPattern(
        allExpenses: List<ExpenseSnapshot>,
        categories: List<com.yourname.expensetracker.data.database.entity.Category>,
        displayCurrency: String
    ): PostSalaryPattern? {
        // Identify salary-like deposits: DEPOSIT transactions (or INCOMING TRANSFER)
        val deposits = allExpenses.filter { exp ->
            exp.transactionType == DomainTransactionType.DEPOSIT ||
            (exp.transactionType == DomainTransactionType.TRANSFER &&
             exp.transferDirection == DomainTransferDirection.INCOMING)
        }.sortedBy { it.date }

        if (deposits.isEmpty()) return null

        // Only keep the largest deposit per calendar month (likely salary)
        val salaryEvents = deposits
            .groupBy { exp ->
                val y = TimePeriodUtils.getYear(exp.date)
                val m = TimePeriodUtils.getMonth(exp.date)
                y * 100 + m
            }
            .mapValues { (_, exps) -> exps.maxByOrNull { it.effectiveAmount } }
            .mapNotNull { (_, v) -> v }  // Safe extraction without force unwrap
            .sortedBy { it.date }

        if (salaryEvents.size < 2) return null // Need at least 2 cycles for a pattern

        val windowMs = 7L * TimePeriodUtils.DAY_IN_MILLIS // 7 days in ms
        val purchases = allExpenses.filter {
            it.transactionType == DomainTransactionType.PURCHASE
        }
        val categoryMap = categories.associateBy { it.id }

        // For each salary event, collect purchases in the following 7 days
        data class CycleData(
            val salaryDate: Long,
            val salaryAmount: Double,
            val purchasesAfter: List<ExpenseSnapshot>
        )

        val cycles = salaryEvents.map { salary ->
            val windowEnd = salary.date + windowMs
            val after = purchases.filter { it.date >= salary.date && it.date < windowEnd }
            CycleData(salary.date, salary.effectiveAmount, after)
        }

        // Average days from salary to first purchase
        val avgDaysToFirst = cycles
            .mapNotNull { c -> c.purchasesAfter.minOfOrNull { it.date }?.let { first ->
                TimePeriodUtils.daysBetween(c.salaryDate, first).toFloat()
            }}
            .let { if (it.isEmpty()) 0f else it.average().toFloat() }

        // Average total spend per cycle within 7 days
        // SAFE: allExpenses pre-normalized via AnalyticsCurrencyNormalizer (caller line ~294)
        val avgTotalIn7Days = cycles.map { c -> c.purchasesAfter.sumOf { it.effectiveAmount } }.average()

        // Per-category accumulation across cycles
        data class CatAccum(var totalSpent: Double = 0.0, var cycleCount: Int = 0)
        val catAccum = mutableMapOf<Long?, CatAccum>()
        cycles.forEach { c ->
            val spentByCategory = c.purchasesAfter
                .groupBy { it.categoryId }
                // SAFE: allExpenses pre-normalized via AnalyticsCurrencyNormalizer (caller line ~294)
                .mapValues { (_, exps) -> exps.sumOf { it.effectiveAmount } }
            spentByCategory.forEach { (catId, spent) ->
                val a = catAccum.getOrPut(catId) { CatAccum() }
                a.totalSpent += spent
                a.cycleCount++
            }
        }

        val topCategories = catAccum
            .entries
            .sortedByDescending { it.value.totalSpent }
            .take(5)
            .mapNotNull { (catId, accum) ->
                val cat = categoryMap[catId]
                PostSalaryCategory(
                    categoryName = cat?.name ?: "Uncategorized",
                    categoryIcon = cat?.icon ?: "💸",
                    avgSpendAfterSalary = accum.totalSpent / accum.cycleCount,
                    occurrences = accum.cycleCount,
                    displayCurrency = displayCurrency
                )
            }

        return PostSalaryPattern(
            salaryCount = salaryEvents.size,
            avgSalaryAmount = salaryEvents.map { it.effectiveAmount }.average(),
            avgDaysToFirstPurchase = avgDaysToFirst,
            topCategories = topCategories,
            avgTotalSpentIn7Days = avgTotalIn7Days,
            displayCurrency = displayCurrency
        )
    }

    private fun detectSuspectTransactions(
        currentExpenses: List<ExpenseSnapshot>,
        displayCurrency: String
    ): List<SuspectTransaction> {
        if (currentExpenses.isEmpty()) return emptyList()

        val suspects = mutableListOf<SuspectTransaction>()
        val windowMs = TimePeriodUtils.DAY_IN_MILLIS // 24-hour duplicate window

        // Average transaction amount for outlier detection
        val avgAmount = currentExpenses.map { it.effectiveAmount }.average()

        // Track already-flagged IDs to avoid double-reporting
        val flagged = mutableSetOf<Long>()

        val sorted = currentExpenses.sortedBy { it.date }

        // 1. Near-duplicate detection: same amount + merchant within 24h
        for (i in sorted.indices) {
            val a = sorted[i]
            for (j in i + 1 until sorted.size) {
                val b = sorted[j]
                if (b.date - a.date > windowMs) break
                if (Math.abs(a.effectiveAmount - b.effectiveAmount) < 0.01 &&
                    a.merchant.trim().equals(b.merchant.trim(), ignoreCase = true) &&
                    !flagged.contains(b.id)) {
                    flagged.add(b.id)
                    suspects.add(
                        SuspectTransaction(
                            expenseId = b.id,
                            dateMs = b.date,
                            amount = b.effectiveAmount,
                            currency = displayCurrency,
                            merchant = b.merchant,
                            reason = SuspectReason.NEAR_DUPLICATE,
                            reasonLabel = "Possible double charge",
                            duplicateOfId = a.id
                        )
                    )
                }
            }
        }

        // 2. Round-amount anomaly: large round amount that is also an outlier (>2x avg)
        currentExpenses.forEach { exp ->
            if (!flagged.contains(exp.id) &&
                exp.effectiveAmount >= 500.0 &&
                exp.effectiveAmount % 50.0 == 0.0 &&
                (avgAmount <= 0 || exp.effectiveAmount > avgAmount * 2.0)) {
                flagged.add(exp.id)
                suspects.add(
                        SuspectTransaction(
                            expenseId = exp.id,
                            dateMs = exp.date,
                            amount = exp.effectiveAmount,
                            currency = displayCurrency,
                            merchant = exp.merchant,
                            reason = SuspectReason.ROUND_AMOUNT,
                            reasonLabel = "Unusually large round amount"
                    )
                )
            }
        }

        // 3. Extreme outlier: amount > 5x the period average
        if (avgAmount > 0) {
            currentExpenses.forEach { exp ->
                if (!flagged.contains(exp.id) && exp.effectiveAmount > avgAmount * 5.0) {
                    flagged.add(exp.id)
                    val multiple = String.format(java.util.Locale.US, "%.1f", exp.effectiveAmount / avgAmount)
                    suspects.add(
                        SuspectTransaction(
                            expenseId = exp.id,
                            dateMs = exp.date,
                            amount = exp.effectiveAmount,
                            currency = displayCurrency,
                            merchant = exp.merchant,
                            reason = SuspectReason.EXTREME_OUTLIER,
                            reasonLabel = "${multiple}x your average spend"
                        )
                    )
                }
            }
        }

        return suspects.sortedByDescending { it.amount }.take(10)
    }

    private fun Expense.toExpenseSnapshot(): ExpenseSnapshot {
        return ExpenseSnapshot(
            id = id,
            amount = amount,
            effectiveAmount = effectiveAmount,
            currency = currency,
            merchant = merchant,
            merchantKey = merchantKey,
            transactionType = when (transactionType) {
                TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
                TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
                TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
                TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
                TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
            },
            date = date,
            categoryId = categoryId,
            isNotMine = isNotMine,
            transferDirection = when (transferDirection) {
                com.yourname.expensetracker.data.database.entity.TransferDirection.INCOMING -> DomainTransferDirection.INCOMING
                com.yourname.expensetracker.data.database.entity.TransferDirection.OUTGOING -> DomainTransferDirection.OUTGOING
                null -> null
            },
            notes = notes
        )
    }

    /**
     * @param currentExpenseSnapshots Pre-normalized via AnalyticsCurrencyNormalizer at caller (line ~292)
     */
    private suspend fun buildBudgetVsActualItems(
        homeCurrency: String,
        currentExpenseSnapshots: List<ExpenseSnapshot>
    ): BudgetChartResult {
        return try {
            // SAFE: currentExpenseSnapshots is pre-normalized via AnalyticsCurrencyNormalizer at line 292
            val actualSpentByCategory = currentExpenseSnapshots
                .groupBy { it.categoryId }
                .mapValues { (_, snapshots) -> snapshots.sumOf { it.effectiveAmount } }

            val warnings = mutableListOf<AnalyticsConversionWarning>()
            val items = budgetRepository.getBudgetStatuses().first()
                .mapNotNull { status ->
                    val category = status.category ?: return@mapNotNull null
                    val convertedBudgetAmount = convertBudgetAmountToHomeCurrency(
                        amount = status.effectiveLimit,
                        sourceCurrency = status.currency,
                        homeCurrency = homeCurrency,
                        warnings = warnings
                    ) ?: return@mapNotNull null

                    val actualSpent = actualSpentByCategory[category.id] ?: 0.0
                    BudgetVsActualItem(
                        categoryName = category.name,
                        categoryIcon = category.icon,
                        categoryColor = category.color,
                        budgetAmount = convertedBudgetAmount,
                        actualSpent = actualSpent,
                        percentUsed = if (convertedBudgetAmount > 0.0) {
                            (actualSpent / convertedBudgetAmount).toFloat()
                        } else {
                            0f
                        },
                        displayCurrency = homeCurrency
                    )
                }
                .sortedByDescending { it.actualSpent }

            BudgetChartResult(items = items, warnings = mergeWarnings(warnings))
        } catch (_: Exception) {
            BudgetChartResult(items = emptyList(), warnings = emptyList())
        }
    }

    private suspend fun convertBudgetAmountToHomeCurrency(
        amount: Double,
        sourceCurrency: String,
        homeCurrency: String,
        warnings: MutableList<AnalyticsConversionWarning>
    ): Double? {
        val normalizedSourceCurrency = sourceCurrency.trim().uppercase(Locale.ROOT)
        if (normalizedSourceCurrency.isBlank()) {
            warnings += AnalyticsConversionWarning(
                type = AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY,
                message = "Analytics excluded budget(s) with invalid currency codes.",
                affectedTransactionCount = 1
            )
            return null
        }

        if (normalizedSourceCurrency == homeCurrency.uppercase(Locale.ROOT)) {
            return amount
        }

        val conversion = currencyConverter.convert(
            amount = amount,
            fromCurrency = normalizedSourceCurrency,
            toCurrency = homeCurrency
        ) ?: run {
            warnings += AnalyticsConversionWarning(
                type = AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
                message = "Analytics excluded budget(s) because exchange rates were unavailable.",
                affectedTransactionCount = 1,
                sourceCurrencies = listOf(normalizedSourceCurrency)
            )
            return null
        }

        return conversion.convertedAmount
    }

    private fun mergeWarnings(warnings: List<AnalyticsConversionWarning>): List<AnalyticsConversionWarning> {
        return warnings
            .groupBy { it.type to it.message }
            .values
            .map { groupedWarnings ->
                AnalyticsConversionWarning(
                    type = groupedWarnings.first().type,
                    message = groupedWarnings.first().message,
                    affectedTransactionCount = groupedWarnings.sumOf { it.affectedTransactionCount },
                    sourceCurrencies = groupedWarnings.flatMap { it.sourceCurrencies }.distinct().sorted()
                )
            }
    }

}
