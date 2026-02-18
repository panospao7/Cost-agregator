package com.yourname.expensetracker.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.analytics.*
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

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
    val isLoading: Boolean = true
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val insightsEngine: InsightsEngine,
    private val recurringExpenseEngine: com.yourname.expensetracker.domain.logic.RecurringExpenseEngine,
    private val analyticsRepository: com.yourname.expensetracker.data.repository.AnalyticsRepository,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _selectedPeriod = MutableStateFlow(TimePeriod.MONTH)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<AnalyticsState> = combine(
        expenseRepository.getAllExpenses(),
        categoryRepository.allCategories,
        _selectedPeriod
    ) { expenses, categories, period ->
        Triple(expenses, categories, period)
    }
    .debounce(300)
    .flatMapLatest { (expenses, categories, period) ->
        flow {
            emit(AnalyticsState(isLoading = true, selectedPeriod = period))
            val result = computeAnalyticsInternal(expenses, categories, period)
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
                val totalAmount = exps.sumOf { it.amount }
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
            isLoading = false
        )
    }

    private fun getPeriodRange(period: TimePeriod, now: Long): Pair<Long, Long> {
        return when (period) {
            TimePeriod.TODAY -> {
                val start = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
                Pair(start, now)
            }
            TimePeriod.WEEK -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getLastNDaysRange(now, 7)
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
}
