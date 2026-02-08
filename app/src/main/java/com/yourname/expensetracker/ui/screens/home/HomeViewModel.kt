package com.yourname.expensetracker.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import java.util.Calendar

data class CategorySpending(
    val category: Category,
    val total: Double,
    val percentage: Float
)

data class DashboardState(
    val totalSpent: Double = 0.0,
    val todaySpent: Double = 0.0,
    val weekSpent: Double = 0.0,
    val monthSpent: Double = 0.0,
    val transactionCount: Int = 0,
    val topCategories: List<CategorySpending> = emptyList(),
    val recentExpenses: List<Expense> = emptyList()
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    val dashboard: StateFlow<DashboardState> = combine(
        repository.getAllExpenses(),
        categoryRepository.allCategories
    ) { expenses, categories ->
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        // Reset to start of today
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis

        // Start of week (Monday)
        val tempCal = cal.clone() as Calendar
        tempCal.firstDayOfWeek = Calendar.MONDAY
        tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (tempCal.timeInMillis > todayStart) {
            tempCal.add(Calendar.DAY_OF_YEAR, -7)
        }
        val weekStart = tempCal.timeInMillis

        // Start of month
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = cal.timeInMillis

        val purchases = expenses.filter { 
            it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE 
        }

        val categoryMap = categories.associateBy { it.id }

        val totalSpent = purchases.sumOf { it.amount }
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

        val topCategories = categoryTotals.take(5)

        DashboardState(
            totalSpent = totalSpent,
            todaySpent = purchases.filter { it.date >= todayStart }.sumOf { it.amount },
            weekSpent = purchases.filter { it.date >= weekStart }.sumOf { it.amount },
            monthSpent = purchases.filter { it.date >= monthStart }.sumOf { it.amount },
            transactionCount = purchases.size,
            topCategories = topCategories,
            recentExpenses = purchases.take(5)
        )
    }.debounce(300)
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())
}
