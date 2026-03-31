package com.yourname.expensetracker.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.analytics.*
import com.yourname.expensetracker.domain.location.AreaSpending
import com.yourname.expensetracker.domain.location.AreaSpendingEngine
import com.yourname.expensetracker.domain.location.LocatedExpense
import com.yourname.expensetracker.domain.location.LocationInsightsEngine
import com.yourname.expensetracker.domain.location.PlaceInsight
import com.yourname.expensetracker.domain.location.TravelDetectionEngine
import com.yourname.expensetracker.domain.location.TravelInsight
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

data class BudgetVsActualItem(
    val categoryName: String,
    val categoryIcon: String,
    val categoryColor: String,
    val budgetAmount: Double,
    val actualSpent: Double,
    val percentUsed: Float // 0.0 - 1.0+
)

data class AnalyticsState(
    val selectedPeriod: TimePeriod = TimePeriod.MONTH,
    val currentTotal: Double = 0.0,
    val previousTotal: Double? = null,
    val changePercent: Float? = null,
    val transactionCount: Int = 0,
    val categoryBreakdown: List<CategoryBreakdown> = emptyList(),
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
    val isLoading: Boolean = true
)

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
    private val locationInsightsEngine: LocationInsightsEngine,
    private val areaSpendingEngine: AreaSpendingEngine,
    private val travelDetectionEngine: TravelDetectionEngine,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _selectedPeriod = MutableStateFlow(TimePeriod.MONTH)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<AnalyticsState> = combine(
        expenseRepository.getAllExpenses().catch { emit(emptyList()) },
        categoryRepository.allCategories.catch { emit(emptyList()) },
        _selectedPeriod
    ) { expenses, categories, period ->
        Triple(expenses, categories, period)
    }
    .debounce(300)
    .flatMapLatest { (expenses, categories, period) ->
        flow {
            emit(AnalyticsState(isLoading = true, selectedPeriod = period))
            // Filter out isNotMine expenses from analytics
            val filteredExpenses = expenses.filter { !it.isNotMine }
            val result = computeAnalyticsInternal(filteredExpenses, categories, period)
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
        allExpenses: List<Expense>,
        categories: List<Category>,
        period: TimePeriod
    ): AnalyticsState {
        val purchases = allExpenses.filter { it.transactionType == TransactionType.PURCHASE }
        val now = timeProvider.now()
        val categoryMap = categories.associateBy { it.id }

        // Calculate date ranges
        val (currentStart, currentEnd) = getPeriodRange(period, now)
        val periodLength = currentEnd - currentStart
        val previousStart = currentStart - periodLength
        val previousEnd = currentStart

        // Current period expenses
        val currentExpenses = purchases.filter { it.date in currentStart..currentEnd }
        val previousExpenses = purchases.filter { it.date in previousStart..previousEnd }

        // Use Repository for Totals and Trends
        // We collect ONE item from the flow since we are in a triggered block
        val summary = analyticsRepository.getSpendingSummary(currentStart, currentEnd).first()
        val catBreakdown = analyticsRepository.getCategoryBreakdown(currentStart, currentEnd).first()

        val currentTotal = summary.totalSpent
        val previousTotal = summary.previousTotalSpent ?: 0.0
        val changePercent = summary.changePercent

        // Category breakdown
        val categoryBreakdown = catBreakdown // Repo returns Domain model directly

        // Merchant breakdown (Still manual for now, or move to Repo later)
        val merchantBreakdown = currentExpenses
            .groupBy { it.merchant.uppercase() }
            .map { (_, exps) ->
                val totalAmount = exps.sumOf { it.effectiveAmount }
                MerchantBreakdown(
                    name = exps.first().merchant,
                    totalSpent = totalAmount,
                    transactionCount = exps.size,
                    averageTransaction = totalAmount / exps.size,
                    categoryId = exps.firstOrNull()?.categoryId
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
            TimePeriod.YEAR -> 365
            TimePeriod.ALL -> {
                val oldest = purchases.minOfOrNull { it.date } ?: now
                ((now - oldest) / 86_400_000L).toInt().coerceIn(7, 365)
            }
        }
        val dailyTotals = insightsEngine.buildDailyTotals(currentExpenses, chartDays)

        // Insights
        val insightsSnapshot = insightsEngine.generateInsights(categories, allExpenses)
        val insights = insightsEngine.getLegacyInsights(insightsSnapshot)
        // Note: dayOfWeekPattern is now computed period-aware below from currentExpenses

        // Recurring (use the list from snapshot but mapped to legacy if needed, or just legacy detection)
        // Refactor: Use RecurringExpenseEngine directly to ensure consistent detection (LOG-020)
        // We filter purchases for relevance but the engine can handle the full list too. 
        // Note: The engine normally looks at 12 months. 'allExpenses' here might be limited by 'period' if we passed filtered list?
        // Actually generateInsights receives 'allExpenses' (usually full list or large subset).
        // Let's assume 'allExpenses' passed to computeAnalytics is sufficient.
        val patterns = recurringExpenseEngine.getPatterns(allExpenses)
        
        val recurring = patterns.map { pattern ->
             RecurringCandidate(
                 merchant = pattern.merchantName,
                 amount = pattern.averageAmount,
                 intervalDays = pattern.periodVarianceDays, // Mapping variance or calculating interval? 
                 // RecurringPattern stores frequency enum, not raw days. We need to map back for UI if it expects days.
                 // Actually RecurringCandidate.intervalDays seems to act as "average interval".
                 // Let's approximate from Frequency.
                 occurrences = 0, // RecurringPattern doesn't expose raw count easily in this model unless we add it. 
                 // For now, let's keep it 0 or map frequency.days
                 nextExpectedDate = pattern.nextExpectedDate,
                 confidence = pattern.confidence
             )
        }.toMutableList()
        
        // Fix: RecurringCandidate needs 'occurrences' and 'intervalDays'. 
        // The new engine abstracts this. If the UI relies on it, we might need to expose it in RecurringPattern or calculate it.
        // For now, let's map frequency days.
        patterns.forEachIndexed { index, p ->
            recurring[index] = recurring[index].copy(
                intervalDays = p.frequency.days,
                occurrences = 3 // Minimum required by engine, placeholder
            )
        }

        // ── Year-over-Year Comparison ──────────────────────────────────────────
        val yearOverYear = computeYearOverYear(purchases, now)

        // ── Spending Velocity Anomalies ──────────────────────────────────
        val velocityAnomalies = computeVelocityAnomalies(currentExpenses)

        // ── Post-Salary Sequential Pattern ─────────────────────────────────
        val postSalaryPattern = computePostSalaryPattern(allExpenses, categories)

        // ── Duplicate/Error Detection ──────────────────────────────────────
        val suspectTransactions = detectSuspectTransactions(currentExpenses)

        // ── Budget vs Actual per Category ───────────────────────────────────
        val budgetVsActual = try {
            val budgetStatuses = budgetRepository.getBudgetStatuses().first()
            budgetStatuses
                .mapNotNull { bs ->
                    // Use safe navigation to handle null categories gracefully
                    val category = bs.category ?: return@mapNotNull null
                    BudgetVsActualItem(
                        categoryName = category.name,
                        categoryIcon = category.icon,
                        categoryColor = category.color,
                        budgetAmount = bs.budget.amount,
                        actualSpent = bs.spentAmount,
                        percentUsed = bs.percentUsed
                    )
                }
                .sortedByDescending { it.actualSpent }
        } catch (_: Exception) {
            emptyList()
        }

        // ── Advanced analytics (parallel) ────────────────────────────────────
        val advancedPeriod = timePeriodToAnalyticsPeriod(period)
        val advRange = if (advancedPeriod != null) {
            advancedAnalyticsEngine.getPeriodRange(advancedPeriod)
        } else {
            // For TODAY or ALL (no AnalyticsPeriod equivalent), build a manual range
            PeriodRange(
                period = AnalyticsPeriod.MONTH,
                startMs = currentStart,
                endMs = currentEnd,
                label = period.name,
                comparisonRange = null
            )
        }

        data class AdvResult(
            val cats: List<EnhancedCategoryAnalytics>,
            val merchs: List<EnhancedMerchantAnalytics>,
            val patterns: SpendingPatternAnalysis?,
            val stats: StatisticalInsights?
        )
        val advResult = coroutineScope {
            val catDeferred = async { advancedAnalyticsEngine.getCategoryAnalytics(advRange) }
            val merchDeferred = async { advancedAnalyticsEngine.getMerchantAnalytics(advRange, limit = 15) }
            val patternsDeferred = async { advancedAnalyticsEngine.getSpendingPatterns(advRange) }
            val statsDeferred = async { advancedAnalyticsEngine.getStatisticalInsights(advRange) }
            AdvResult(
                cats = try { catDeferred.await() } catch (_: Exception) { emptyList() },
                merchs = try { merchDeferred.await() } catch (_: Exception) { emptyList() },
                patterns = try { patternsDeferred.await() } catch (_: Exception) { null },
                stats = try { statsDeferred.await() } catch (_: Exception) { null }
            )
        }

        // ── Day-of-week pattern (period-aware, computed from currentExpenses) ─
        val cal2 = java.util.Calendar.getInstance()
        val dowNames = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val dowTotals = DoubleArray(7)
        val dowCounts = IntArray(7)
        currentExpenses.forEach { exp ->
            cal2.timeInMillis = exp.date
            val idx = when (cal2.get(java.util.Calendar.DAY_OF_WEEK)) {
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
                avgPerTransaction = if (dowCounts[idx] > 0) dowTotals[idx] / dowCounts[idx] else 0.0
            )
        }

        // ── Hour-of-day pattern (period-aware) ────────────────────────────────
        val hourTotals = DoubleArray(24)
        currentExpenses.forEach { exp ->
            cal2.timeInMillis = exp.date
            val hour = cal2.get(java.util.Calendar.HOUR_OF_DAY)
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
            LocatedExpense(
                expenseId = exp.id,
                latitude = lat,
                longitude = lon,
                amount = exp.effectiveAmount,
                merchant = exp.merchant,
                date = exp.date,
                locationSource = exp.locationSource,
                placeId = exp.placeId
            )
        }

        val locationInsights = locationInsightsEngine.compute(locatedExpenses).take(10)
        val areaSpending = areaSpendingEngine.compute(purchases)
        val travelInsight = travelDetectionEngine.compute(purchases)

        return AnalyticsState(
            selectedPeriod = period,
            currentTotal = currentTotal,
            previousTotal = if (previousTotal > 0) previousTotal else null,
            changePercent = changePercent,
            transactionCount = currentExpenses.size,
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
            isLoading = false
        )
    }


    private fun timePeriodToAnalyticsPeriod(period: TimePeriod): AnalyticsPeriod? = when (period) {
        TimePeriod.WEEK -> AnalyticsPeriod.WEEK
        TimePeriod.MONTH -> AnalyticsPeriod.MONTH
        TimePeriod.YEAR -> AnalyticsPeriod.YEAR
        TimePeriod.TODAY -> null  // No equivalent; caller uses raw range
        TimePeriod.ALL -> null    // No equivalent; caller uses raw range
    }

    private fun computeVelocityAnomalies(
        currentExpenses: List<com.yourname.expensetracker.data.database.entity.Expense>
    ): List<VelocityAnomaly> {
        if (currentExpenses.size < 5) return emptyList()

        val cal = java.util.Calendar.getInstance()
        val dayFormat = java.text.SimpleDateFormat("EEE MMM dd", java.util.Locale.getDefault())

        // Group expenses by day key (year-month-day)
        val byDay = mutableMapOf<Long, MutableList<com.yourname.expensetracker.data.database.entity.Expense>>()
        currentExpenses.forEach { exp ->
            cal.timeInMillis = exp.date
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val dayMs = cal.timeInMillis
            byDay.getOrPut(dayMs) { mutableListOf() }.add(exp)
        }

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
                    ?.sortedByDescending { it.amount }
                    ?.take(3)
                    ?.map { it.merchant }
                    ?: emptyList()
                VelocityAnomaly(
                    dateMs = dayMs,
                    dayLabel = dayFormat.format(java.util.Date(dayMs)),
                    dayTotal = total,
                    monthDailyAvg = avg,
                    deviationMultiple = (total / avg).toFloat(),
                    topMerchants = topMerchants
                )
            }
            .sortedByDescending { it.deviationMultiple }
            .take(5)
    }

    private fun computeYearOverYear(
        purchases: List<com.yourname.expensetracker.data.database.entity.Expense>,
        now: Long
    ): YearOverYearComparison? {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val currentYear = cal.get(java.util.Calendar.YEAR)
        val priorYear = currentYear - 1

        val monthNames = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")

        fun monthTotals(year: Int): List<MonthlyYearTotal> {
            val tempCal = java.util.Calendar.getInstance()
            return purchases
                .filter { exp ->
                    tempCal.timeInMillis = exp.date
                    tempCal.get(java.util.Calendar.YEAR) == year
                }
                .groupBy { exp ->
                    tempCal.timeInMillis = exp.date
                    tempCal.get(java.util.Calendar.MONTH)
                }
                .map { (month, exps) ->
                    MonthlyYearTotal(
                        month = month,
                        monthLabel = monthNames[month],
                        total = exps.sumOf { it.effectiveAmount },
                        transactionCount = exps.size
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
            deltaByMonth = deltaByMonth
        )
    }

    private fun getPeriodRange(period: TimePeriod, now: Long): Pair<Long, Long> {
        return when (period) {
            TimePeriod.TODAY -> {
                val start = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
                Pair(start, now)
            }
            TimePeriod.WEEK -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getWeekRange(now, 0).let { (start, end) -> start to end }
            TimePeriod.MONTH -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getMonthRange(now, 0) // Current month
            TimePeriod.YEAR -> {
                 // Start of year logic wasn't in Utils yet, let's keep local or add to Utils.
                 // Utils had getMonthRange.
                 val cal = Calendar.getInstance()
                 cal.timeInMillis = now
                 cal.set(Calendar.DAY_OF_YEAR, 1)
                 cal.set(Calendar.HOUR_OF_DAY, 0)
                 cal.set(Calendar.MINUTE, 0)
                 cal.set(Calendar.SECOND, 0)
                 cal.set(Calendar.MILLISECOND, 0)
                 Pair(cal.timeInMillis, now)
            }
            TimePeriod.ALL -> Pair(0L, now)
        }
    }
    private fun computePostSalaryPattern(
        allExpenses: List<com.yourname.expensetracker.data.database.entity.Expense>,
        categories: List<com.yourname.expensetracker.data.database.entity.Category>
    ): PostSalaryPattern? {
        // Identify salary-like deposits: DEPOSIT transactions (or INCOMING TRANSFER)
        val deposits = allExpenses.filter { exp ->
            exp.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT ||
            (exp.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER &&
             exp.transferDirection == com.yourname.expensetracker.data.database.entity.TransferDirection.INCOMING)
        }.sortedBy { it.date }

        if (deposits.isEmpty()) return null

        // Only keep the largest deposit per calendar month (likely salary)
        val cal = java.util.Calendar.getInstance()
        val salaryEvents = deposits
            .groupBy { exp ->
                cal.timeInMillis = exp.date
                val y = cal.get(java.util.Calendar.YEAR)
                val m = cal.get(java.util.Calendar.MONTH)
                y * 100 + m
            }
            .mapValues { (_, exps) -> exps.maxByOrNull { it.amount } }
            .mapNotNull { (_, v) -> v }  // Safe extraction without force unwrap
            .sortedBy { it.date }

        if (salaryEvents.size < 2) return null // Need at least 2 cycles for a pattern

        val windowMs = 7L * 24 * 60 * 60 * 1000 // 7 days in ms
        val purchases = allExpenses.filter {
            it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE
        }
        val categoryMap = categories.associateBy { it.id }

        // For each salary event, collect purchases in the following 7 days
        data class CycleData(
            val salaryDate: Long,
            val salaryAmount: Double,
            val purchasesAfter: List<com.yourname.expensetracker.data.database.entity.Expense>
        )

        val cycles = salaryEvents.map { salary ->
            val windowEnd = salary.date + windowMs
            val after = purchases.filter { it.date in salary.date..windowEnd }
            CycleData(salary.date, salary.amount, after)
        }

        // Average days from salary to first purchase
        val avgDaysToFirst = cycles
            .mapNotNull { c -> c.purchasesAfter.minOfOrNull { it.date }?.let { first ->
                ((first - c.salaryDate) / 86_400_000.0).toFloat()
            }}
            .let { if (it.isEmpty()) 0f else it.average().toFloat() }

        // Average total spend per cycle within 7 days
        val avgTotalIn7Days = cycles.map { c -> c.purchasesAfter.sumOf { it.effectiveAmount } }.average()

        // Per-category accumulation across cycles
        data class CatAccum(var totalSpent: Double = 0.0, var cycleCount: Int = 0)
        val catAccum = mutableMapOf<Long?, CatAccum>()
        cycles.forEach { c ->
            val spentByCategory = c.purchasesAfter
                .groupBy { it.categoryId }
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
                    occurrences = accum.cycleCount
                )
            }

        return PostSalaryPattern(
            salaryCount = salaryEvents.size,
            avgSalaryAmount = salaryEvents.map { it.amount }.average(),
            avgDaysToFirstPurchase = avgDaysToFirst,
            topCategories = topCategories,
            avgTotalSpentIn7Days = avgTotalIn7Days
        )
    }

    private fun detectSuspectTransactions(
        currentExpenses: List<com.yourname.expensetracker.data.database.entity.Expense>
    ): List<SuspectTransaction> {
        if (currentExpenses.isEmpty()) return emptyList()

        val suspects = mutableListOf<SuspectTransaction>()
        val windowMs = 24L * 60 * 60 * 1000 // 24-hour duplicate window

        // Average transaction amount for outlier detection
        val avgAmount = currentExpenses.map { it.amount }.average()

        // Track already-flagged IDs to avoid double-reporting
        val flagged = mutableSetOf<Long>()

        val sorted = currentExpenses.sortedBy { it.date }

        // 1. Near-duplicate detection: same amount + merchant within 24h
        for (i in sorted.indices) {
            val a = sorted[i]
            for (j in i + 1 until sorted.size) {
                val b = sorted[j]
                if (b.date - a.date > windowMs) break
                if (Math.abs(a.amount - b.amount) < 0.01 &&
                    a.merchant.trim().equals(b.merchant.trim(), ignoreCase = true) &&
                    !flagged.contains(b.id)) {
                    flagged.add(b.id)
                    suspects.add(
                        SuspectTransaction(
                            expenseId = b.id,
                            dateMs = b.date,
                            amount = b.amount,
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
                exp.amount >= 500.0 &&
                exp.amount % 50.0 == 0.0 &&
                (avgAmount <= 0 || exp.amount > avgAmount * 2.0)) {
                flagged.add(exp.id)
                suspects.add(
                    SuspectTransaction(
                        expenseId = exp.id,
                        dateMs = exp.date,
                        amount = exp.amount,
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
                if (!flagged.contains(exp.id) && exp.amount > avgAmount * 5.0) {
                    flagged.add(exp.id)
                    val multiple = String.format(java.util.Locale.US, "%.1f", exp.amount / avgAmount)
                    suspects.add(
                        SuspectTransaction(
                            expenseId = exp.id,
                            dateMs = exp.date,
                            amount = exp.amount,
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

}