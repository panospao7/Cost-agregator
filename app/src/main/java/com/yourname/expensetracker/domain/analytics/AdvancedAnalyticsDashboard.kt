package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class AnalyticsDashboardData(
    val totalSpent: Double,
    val totalIncome: Double,
    val netCashflow: Double,
    val topCategories: List<DashboardCategoryBreakdown>,
    val topMerchants: List<DashboardMerchantBreakdown>,
    val monthlyTrend: List<MonthlyDataPoint>,
    val weeklyPattern: List<DayOfWeekSpending>,
    val insights: List<DashboardInsight>
)

data class DashboardCategoryBreakdown(
    val categoryId: Long,
    val categoryName: String,
    val amount: Double,
    val percentage: Double,
    val changeFromLastPeriod: Double
)

data class DashboardMerchantBreakdown(
    val merchant: String,
    val amount: Double,
    val transactionCount: Int
)

data class MonthlyDataPoint(
    val month: String,
    val spending: Double,
    val income: Double
)

data class DayOfWeekSpending(
    val dayOfWeek: Int, // 1 = Monday, 7 = Sunday
    val dayName: String,
    val averageSpending: Double,
    val transactionCount: Int
)

data class DashboardInsight(
    val type: DashboardInsightType,
    val title: UiText,
    val description: String,
    val severity: DashboardInsightSeverity
)

enum class DashboardInsightType {
    SPENDING_INCREASE,
    SPENDING_DECREASE,
    NEW_MERCHANT,
    RECURRING_PATTERN,
    BUDGET_WARNING,
    SAVINGS_OPPORTUNITY,
    SPENDING_PATTERN
}

enum class DashboardInsightSeverity {
    INFO,
    WARNING,
    ALERT
}

@Singleton
class AdvancedAnalyticsDashboard @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val expenseRepository: ExpenseRepository,
    private val timeProvider: TimeProvider
) {
    
    suspend fun generateDashboardData(
        startDate: Long,
        endDate: Long
    ): AnalyticsDashboardData = withContext(Dispatchers.IO) {
        val expenses = expenseRepository.getExpensesBetween(startDate, endDate)
        
        // Calculate totals
        var totalSpent = 0.0
        var totalIncome = 0.0
        
        for (expense in expenses) {
            when (expense.transactionType.name) {
                "PURCHASE", "WITHDRAWAL" -> totalSpent += expense.effectiveAmount
                "DEPOSIT" -> totalIncome += expense.effectiveAmount
                "TRANSFER" -> if (expense.transferDirection?.name == "INCOMING") {
                    totalIncome += expense.effectiveAmount
                }
            }
        }
        
        // Generate all dashboard components
        AnalyticsDashboardData(
            totalSpent = totalSpent,
            totalIncome = totalIncome,
            netCashflow = totalIncome - totalSpent,
            topCategories = getTopCategories(expenses),
            topMerchants = getTopMerchants(expenses),
            monthlyTrend = getMonthlyTrend(startDate, endDate),
            weeklyPattern = getWeeklyPattern(expenses),
            insights = generateInsights(expenses, totalSpent, totalIncome)
        )
    }
    
    private fun getTopCategories(expenses: List<com.yourname.expensetracker.data.database.entity.Expense>): List<DashboardCategoryBreakdown> {
        val categoryMap = mutableMapOf<Long?, Double>()
        val categoryCount = mutableMapOf<Long?, Int>()
        
        for (expense in expenses) {
            if (expense.transactionType.name == "PURCHASE") {
                val current = categoryMap[expense.categoryId] ?: 0.0
                categoryMap[expense.categoryId] = current + expense.effectiveAmount
                
                val count = categoryCount[expense.categoryId] ?: 0
                categoryCount[expense.categoryId] = count + 1
            }
        }
        
        val total = categoryMap.values.sum()
        
        return categoryMap.map { (catId, amount) ->
            DashboardCategoryBreakdown(
                categoryId = catId ?: 0L,
                categoryName = "Category $catId", // Would fetch actual name
                amount = amount,
                percentage = if (total > 0) (amount / total) * 100 else 0.0,
                changeFromLastPeriod = 0.0 // Would calculate from historical
            )
        }.sortedByDescending { it.amount }.take(5)
    }
    
    private fun getTopMerchants(expenses: List<com.yourname.expensetracker.data.database.entity.Expense>): List<DashboardMerchantBreakdown> {
        val merchantMap = mutableMapOf<String, Pair<Double, Int>>()
        
        for (expense in expenses) {
            if (expense.transactionType.name == "PURCHASE") {
                val (current, count) = merchantMap[expense.merchant] ?: Pair(0.0, 0)
                merchantMap[expense.merchant] = Pair(current + expense.effectiveAmount, count + 1)
            }
        }
        
        return merchantMap.map { (merchant, data) ->
            DashboardMerchantBreakdown(
                merchant = merchant,
                amount = data.first,
                transactionCount = data.second
            )
        }.sortedByDescending { it.amount }.take(5)
    }
    
    private suspend fun getMonthlyTrend(startDate: Long, endDate: Long): List<MonthlyDataPoint> {
        val calendar = java.util.Calendar.getInstance()
        val result = mutableListOf<MonthlyDataPoint>()
        
        calendar.timeInMillis = startDate
        val startMonth = calendar.get(java.util.Calendar.MONTH)
        val startYear = calendar.get(java.util.Calendar.YEAR)
        
        calendar.timeInMillis = endDate
        val endMonth = calendar.get(java.util.Calendar.MONTH)
        val endYear = calendar.get(java.util.Calendar.YEAR)
        
        var currentYear = startYear
        var currentMonth = startMonth
        
        while (currentYear < endYear || (currentYear == endYear && currentMonth <= endMonth)) {
            val monthKey = "$currentYear-${(currentMonth + 1).toString().padStart(2, '0')}"
            
            // Calculate month boundaries
            calendar.set(currentYear, currentMonth, 1, 0, 0, 0)
            val monthStart = calendar.timeInMillis
            calendar.set(currentYear, currentMonth, calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH), 23, 59, 59)
            val monthEnd = calendar.timeInMillis
            
            val expenses = expenseRepository.getExpensesBetween(monthStart, monthEnd)
            
            var spending = 0.0
            var income = 0.0
            
            for (expense in expenses) {
                when (expense.transactionType.name) {
                    "PURCHASE", "WITHDRAWAL" -> spending += expense.effectiveAmount
                    "DEPOSIT" -> income += expense.effectiveAmount
                }
            }
            
            result.add(MonthlyDataPoint(monthKey, spending, income))
            
            // Move to next month
            currentMonth++
            if (currentMonth > 11) {
                currentMonth = 0
                currentYear++
            }
        }
        
        return result
    }
    
    private fun getWeeklyPattern(expenses: List<com.yourname.expensetracker.data.database.entity.Expense>): List<DayOfWeekSpending> {
        val dayMap = mutableMapOf<Int, MutableList<Double>>()
        val dayNames = mapOf(
            1 to "Monday",
            2 to "Tuesday",
            3 to "Wednesday",
            4 to "Thursday",
            5 to "Friday",
            6 to "Saturday",
            7 to "Sunday"
        )
        
        val calendar = java.util.Calendar.getInstance()
        
        for (expense in expenses) {
            if (expense.transactionType.name == "PURCHASE") {
                calendar.timeInMillis = expense.date
                val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
                // Convert to Monday = 1 format
                val adjustedDay = if (dayOfWeek == java.util.Calendar.SUNDAY) 7 else dayOfWeek - 1
                
                val list = dayMap.getOrPut(adjustedDay) { mutableListOf() }
                list.add(expense.effectiveAmount)
            }
        }
        
        return (1..7).map { day ->
            val amounts = dayMap[day] ?: emptyList()
            DayOfWeekSpending(
                dayOfWeek = day,
                dayName = dayNames[day] ?: "Unknown",
                averageSpending = if (amounts.isNotEmpty()) amounts.average() else 0.0,
                transactionCount = amounts.size
            )
        }
    }
    
    private fun generateInsights(
        expenses: List<com.yourname.expensetracker.data.database.entity.Expense>,
        totalSpent: Double,
        totalIncome: Double
    ): List<DashboardInsight> {
        val insights = mutableListOf<DashboardInsight>()
        
        // Check spending vs income
        if (totalSpent > totalIncome * 0.9 && totalIncome > 0) {
            insights.add(
                DashboardInsight(
                    type = DashboardInsightType.BUDGET_WARNING,
                    title = UiText.from(R.string.domain_analytics_high_spending),
                    description = "You've spent ${String.format("%.1f", (totalSpent/totalIncome)*100)}% of your income this period",
                    severity = DashboardInsightSeverity.WARNING
                )
            )
        }
        
        // Check weekend spending
        val calendar = java.util.Calendar.getInstance()
        var weekendSpending = 0.0
        var weekdaySpending = 0.0
        
            for (expense in expenses) {
            if (expense.transactionType.name == "PURCHASE") {
                calendar.timeInMillis = expense.date
                val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
                if (dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY) {
                    weekendSpending += expense.effectiveAmount
                } else {
                    weekdaySpending += expense.effectiveAmount
                }
            }
        }
        
        if (weekendSpending > weekdaySpending / 5 * 2) {
            insights.add(
                DashboardInsight(
                    type = DashboardInsightType.SPENDING_PATTERN,
                    title = UiText.from(R.string.domain_analytics_weekend_high),
                    description = "Your weekend spending is higher than average weekday spending",
                    severity = DashboardInsightSeverity.INFO
                )
            )
        }
        
        // Check savings rate
        if (totalIncome > 0) {
            val savingsRate = ((totalIncome - totalSpent) / totalIncome) * 100
            if (savingsRate > 20) {
                insights.add(
                    DashboardInsight(
                        type = DashboardInsightType.SAVINGS_OPPORTUNITY,
                        title = UiText.from(R.string.domain_analytics_great_savings),
                        description = "You're saving ${String.format("%.1f", savingsRate)}% of your income!",
                        severity = DashboardInsightSeverity.INFO
                    )
                )
            }
        }
        
        return insights
    }
}
