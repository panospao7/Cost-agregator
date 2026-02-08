package com.yourname.expensetracker.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.analytics.*
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

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val insightsEngine: InsightsEngine
) : ViewModel() {

    private val _state = MutableStateFlow(AnalyticsState())
    val state: StateFlow<AnalyticsState> = _state.asStateFlow()

    private val _selectedPeriod = MutableStateFlow(TimePeriod.MONTH)

    init {
        combine(
            repository.getAllExpenses(),
            categoryRepository.allCategories,
            _selectedPeriod
        ) { expenses, categories, period ->
            Triple(expenses, categories, period)
        }
        .debounce(300)
        .onEach { (expenses, categories, period) ->
            _state.update { it.copy(isLoading = true, selectedPeriod = period) }
            computeAnalytics(expenses, categories, period)
        }
        .flowOn(Dispatchers.Default)
        .launchIn(viewModelScope)
    }

    fun selectPeriod(period: TimePeriod) {
        _selectedPeriod.value = period
    }

    private suspend fun computeAnalytics(
        allExpenses: List<Expense>,
        categories: List<Category>,
        period: TimePeriod
    ) {
        val purchases = allExpenses.filter { it.transactionType == TransactionType.PURCHASE }
        val now = System.currentTimeMillis()
        val categoryMap = categories.associateBy { it.id }

        // Calculate date ranges
        val (currentStart, currentEnd) = getPeriodRange(period, now)
        val periodLength = currentEnd - currentStart
        val previousStart = currentStart - periodLength
        val previousEnd = currentStart

        // Current period expenses
        val currentExpenses = purchases.filter { it.date in currentStart..currentEnd }
        val previousExpenses = purchases.filter { it.date in previousStart..previousEnd }

        val currentTotal = currentExpenses.sumOf { it.amount }
        val previousTotal = previousExpenses.sumOf { it.amount }

        val changePercent = if (previousTotal > 0) {
            ((currentTotal - previousTotal) / previousTotal * 100).toFloat()
        } else null

        // Category breakdown
        val categoryBreakdown = currentExpenses
            .groupBy { it.categoryId }
            .mapNotNull { (catId, exps) ->
                val cat = catId?.let { categoryMap[it] } ?: return@mapNotNull null
                CategoryBreakdown(
                    category = cat,
                    total = exps.sumOf { it.amount },
                    count = exps.size,
                    percentage = if (currentTotal > 0)
                        (exps.sumOf { it.amount } / currentTotal * 100).toFloat()
                    else 0f
                )
            }
            .sortedByDescending { it.total }

        // Merchant breakdown
        val merchantBreakdown = currentExpenses
            .groupBy { it.merchant.uppercase() }
            .map { (_, exps) ->
                val total = exps.sumOf { it.amount }
                MerchantBreakdown(
                    name = exps.first().merchant,
                    totalSpent = total,
                    transactionCount = exps.size,
                    averageTransaction = total / exps.size,
                    categoryId = exps.firstOrNull()?.categoryId
                )
            }
            .sortedByDescending { it.totalSpent }

        // Daily totals for chart
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
        // Using duplicate detection logic for now to stay compatible with UI model
        val recurring = insightsEngine.detectRecurring(purchases)

        _state.update {
            it.copy(
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
    }

    private fun getPeriodRange(period: TimePeriod, now: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now

        return when (period) {
            TimePeriod.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TimePeriod.WEEK -> {
                cal.add(Calendar.DAY_OF_YEAR, -7)
                Pair(cal.timeInMillis, now)
            }
            TimePeriod.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TimePeriod.YEAR -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TimePeriod.ALL -> {
                Pair(0L, now)
            }
        }
    }
}
