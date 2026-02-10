package com.yourname.expensetracker.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import java.util.Calendar
import javax.inject.Inject

// === State Widget sealed class for Bento Grid ===
sealed class DashboardWidget {
    data class SafeToSpend(
        val amount: Double,
        val totalBudget: Double?,
        val daysRemaining: Int
    ) : DashboardWidget()

    data class SpendingPaceWidget(
        val pace: SpendingPace
    ) : DashboardWidget()

    data class PendingReviewAlert(
        val count: Int
    ) : DashboardWidget()

    data class PeriodSummary(
        val todaySpent: Double,
        val weekSpent: Double,
        val monthSpent: Double
    ) : DashboardWidget()

    data class TopCategories(
        val categories: List<CategorySpending>
    ) : DashboardWidget()

    data class BudgetHealthWidget(
        val statuses: List<BudgetStatus>,
        val summary: String?
    ) : DashboardWidget()

    data class RecentTransactions(
        val expenses: List<Expense>
    ) : DashboardWidget()

    data class NaturalLanguageInsight(
        val text: String,
        val icon: String
    ) : DashboardWidget()
}

data class CategorySpending(
    val category: Category,
    val total: Double,
    val percentage: Float
)

data class DashboardState(
    val widgets: List<DashboardWidget> = emptyList(),
    val totalSpent: Double = 0.0,
    val transactionCount: Int = 0,
    val isServiceRunning: Boolean = true, // For pulse dot
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val insightsEngine: InsightsEngine
) : ViewModel() {

    val dashboard: StateFlow<DashboardState> = combine(
        repository.getAllExpenses(),
        categoryRepository.allCategories,
        budgetRepository.getBudgetStatuses(),
        repository.getPendingReviewCount()
    ) { expenses, categories, budgetStatuses, pendingCount ->

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        // Time boundaries
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis

        val tempCal = cal.clone() as Calendar
        tempCal.firstDayOfWeek = Calendar.MONDAY
        tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (tempCal.timeInMillis > todayStart) tempCal.add(Calendar.DAY_OF_YEAR, -7)
        val weekStart = tempCal.timeInMillis

        cal.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = cal.timeInMillis

        val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE }
        val categoryMap = categories.associateBy { it.id }
        val totalSpent = purchases.sumOf { it.amount }
        val monthSpent = purchases.filter { it.date >= monthStart }.sumOf { it.amount }
        val weekSpent = purchases.filter { it.date >= weekStart }.sumOf { it.amount }
        val todaySpent = purchases.filter { it.date >= todayStart }.sumOf { it.amount }

        // Days remaining in month
        val daysInMonth = Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        val daysRemaining = daysInMonth - dayOfMonth

        // Overall budget (if set)
        val overallBudget = budgetStatuses.find { it.budget.categoryId == null }
        val safeToSpend = overallBudget?.remainingAmount ?: (0.0) 

        // Category totals
        val categoryTotals = purchases
            .groupBy { it.categoryId }
            .mapNotNull { (catId, exps) ->
                val cat = catId?.let { categoryMap[it] } ?: return@mapNotNull null
                val catTotal = exps.sumOf { it.amount }
                CategorySpending(
                    category = cat,
                    total = catTotal,
                    percentage = if (totalSpent > 0) (catTotal / totalSpent * 100).toFloat() else 0f
                )
            }
            .sortedByDescending { it.total }

        // Spending pace logic (adapted for flow combine)
        val previousMonthStart = insightsEngine.getMonthPeriod(now, -1).startMs
        val previousMonthEnd = monthStart
        val previousMonthTotal = purchases
            .filter { it.date >= previousMonthStart && it.date < previousMonthEnd }
            .sumOf { it.amount }
        
        val projectedTotal = if (dayOfMonth > 0)
            monthSpent * daysInMonth.toDouble() / dayOfMonth else monthSpent
            
        val pace = SpendingPace(
            currentMonthSpent = monthSpent,
            daysElapsed = dayOfMonth,
            daysInMonth = daysInMonth,
            projectedTotal = projectedTotal,
            previousMonthTotal = if (previousMonthTotal > 0) previousMonthTotal else null,
            averageMonthlyTotal = null,
            pacePercentage = if (previousMonthTotal > 0) {
                val expected = previousMonthTotal * dayOfMonth / daysInMonth
                (monthSpent / expected * 100).toFloat()
            } else 0f,
            paceStatus = when {
                previousMonthTotal <= 0 -> PaceStatus.NO_BASELINE
                monthSpent < previousMonthTotal * dayOfMonth / daysInMonth * 0.9 -> PaceStatus.UNDER_PACE
                monthSpent > previousMonthTotal * dayOfMonth / daysInMonth * 1.1 -> PaceStatus.OVER_PACE
                else -> PaceStatus.ON_PACE
            }
        )

        // Natural language insight
        val insightText = buildNaturalLanguageInsight(
            monthSpent, previousMonthTotal, todaySpent, purchases.size
        )

        // Budget summary
        val exceeded = budgetStatuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
        val budgetSummary = if (budgetStatuses.isNotEmpty()) {
            if (exceeded > 0) "$exceeded budgets exceeded!" else "All budgets on track"
        } else null

        // === Build widget list ===
        val widgets = mutableListOf<DashboardWidget>()

        // Hero: Safe-to-Spend (or total spent if no overall budget)
        widgets.add(
            DashboardWidget.SafeToSpend(
                amount = if (overallBudget != null) safeToSpend else monthSpent,
                totalBudget = overallBudget?.budget?.amount,
                daysRemaining = daysRemaining
            )
        )

        // Spending Pace
        if (pace.paceStatus != PaceStatus.NO_BASELINE) {
            widgets.add(DashboardWidget.SpendingPaceWidget(pace))
        }

        // Pending Review Alert
        if (pendingCount > 0) {
            widgets.add(DashboardWidget.PendingReviewAlert(pendingCount))
        }

        // Natural language insight
        if (insightText != null) {
            widgets.add(DashboardWidget.NaturalLanguageInsight(insightText.first, insightText.second))
        }

        // Period summary
        widgets.add(DashboardWidget.PeriodSummary(todaySpent, weekSpent, monthSpent))

        // Budget health
        if (budgetStatuses.isNotEmpty()) {
            widgets.add(DashboardWidget.BudgetHealthWidget(budgetStatuses, budgetSummary))
        }

        // Top categories
        if (categoryTotals.isNotEmpty()) {
            widgets.add(DashboardWidget.TopCategories(categoryTotals.take(5)))
        }

        // Recent transactions
        if (purchases.isNotEmpty()) {
            widgets.add(DashboardWidget.RecentTransactions(purchases.take(5)))
        }

        DashboardState(
            widgets = widgets,
            totalSpent = totalSpent,
            transactionCount = purchases.size,
            isLoading = false
        )
    }
        .debounce(300)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

    private fun buildNaturalLanguageInsight(
        monthSpent: Double,
        previousMonthTotal: Double,
        todaySpent: Double,
        txCount: Int
    ): Pair<String, String>? {
        if (previousMonthTotal > 0) {
            val diff = monthSpent - previousMonthTotal
            return if (diff < 0) {
                Pair(
                    "You've spent €${String.format("%.0f", -diff)} less than last month so far.",
                    "📉"
                )
            } else if (diff > previousMonthTotal * 0.2) {
                Pair(
                    "Spending is €${String.format("%.0f", diff)} higher than last month.",
                    "📈"
                )
            } else null
        }
        if (txCount > 0 && todaySpent > 0) {
            return Pair(
                "You've spent €${String.format("%.2f", todaySpent)} today across $txCount transactions.",
                "💡"
            )
        }
        return null
    }
}
