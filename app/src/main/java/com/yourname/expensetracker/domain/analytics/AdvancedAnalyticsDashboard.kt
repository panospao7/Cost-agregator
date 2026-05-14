package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.text.UiTextArg
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class AnalyticsDashboardData(
    val totalSpent: Double,
    val totalIncome: Double,
    val netCashflow: Double,
    val displayCurrency: String,
    val topCategories: List<AnalyticsDashboardCategoryBreakdown>,
    val topMerchants: List<DashboardMerchantBreakdown>,
    val monthlyTrend: List<MonthlyDataPoint>,
    val weeklyPattern: List<DayOfWeekSpending>,
    val insights: List<DashboardInsight>,
    val conversionWarnings: List<AnalyticsConversionWarning> = emptyList(),
    val conversionConfidence: ConversionConfidence = ConversionConfidence.HIGH
)

data class AnalyticsDashboardCategoryBreakdown(
    val categoryId: Long,
    val categoryName: UiText,
    val amount: Double,
    val percentage: Double,
    val changeFromLastPeriod: Double,
    val displayCurrency: String
)

data class DashboardMerchantBreakdown(
    val merchant: String,
    val amount: Double,
    val transactionCount: Int,
    val displayCurrency: String
)

data class MonthlyDataPoint(
    val month: String,
    val spending: Double,
    val income: Double,
    val displayCurrency: String
)

data class DayOfWeekSpending(
    val dayOfWeek: Int, // 1 = Monday, 7 = Sunday
    val dayName: UiText,
    val averageSpending: Double,
    val transactionCount: Int,
    val displayCurrency: String
)

data class DashboardInsight(
    val type: DashboardInsightType,
    val title: UiText,
    val description: UiText,
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

/**
 * Confidence level for currency conversion completeness in the dashboard.
 *
 * - HIGH: all transactions converted without issues.
 * - PARTIAL: some transactions could not be converted (missing rates, etc.).
 * - LOW: a significant portion of data is unreliable or missing.
 */
enum class ConversionConfidence {
    HIGH,
    PARTIAL,
    LOW
}

@Singleton
class AdvancedAnalyticsDashboard @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val timeProvider: TimeProvider
) {
    
    suspend fun generateDashboardData(
        startDate: Long,
        endDate: Long
    ): AnalyticsDashboardData = withContext(Dispatchers.IO) {
        // S9-010: Do not fall back to "EUR" — throw if home currency unavailable
        val displayCurrency = currencySettingsRepository.homeCurrency().first()
        val expensesRaw = expenseRepository.getExpenseSnapshotsBetween(startDate, endDate)
        val comparisonExpenses = if (endDate > startDate) {
            val daysInPeriod = TimePeriodUtils.daysBetween(startDate, endDate).coerceAtLeast(1)
            val comparisonStart = TimePeriodUtils.addDays(startDate, -daysInPeriod)
            expenseRepository.getExpenseSnapshotsBetween(comparisonStart, startDate)
        } else {
            emptyList()
        }
        val expensesNormalization = analyticsCurrencyNormalizer.normalizeSnapshots(expensesRaw, displayCurrency)
        val comparisonNormalization = analyticsCurrencyNormalizer.normalizeSnapshots(comparisonExpenses, displayCurrency)
        val expenses = expensesNormalization.includedExpenses
        val categoryNamesById = categoryRepository.getAll().associate { it.id to it.name }
        
        // Calculate totals
        var totalSpent = 0.0
        var totalIncome = 0.0
        
        for (expense in expenses) {
            when (expense.transactionType) {
                DomainTransactionType.PURCHASE,
                DomainTransactionType.WITHDRAWAL -> totalSpent += expense.effectiveAmount
                DomainTransactionType.DEPOSIT -> totalIncome += expense.effectiveAmount
                else -> Unit
            }
        }
        
        // Derive conversion confidence from normalization results
        val conversionConfidence = computeConversionConfidence(
            expensesNormalization, comparisonNormalization
        )
        
        // Generate all dashboard components
        AnalyticsDashboardData(
            totalSpent = totalSpent,
            totalIncome = totalIncome,
            netCashflow = totalIncome - totalSpent,
            displayCurrency = displayCurrency,
            topCategories = getTopCategories(expenses, comparisonNormalization.includedExpenses, categoryNamesById, displayCurrency),
            topMerchants = getTopMerchants(expenses, displayCurrency),
            monthlyTrend = getMonthlyTrend(startDate, endDate, displayCurrency),
            weeklyPattern = getWeeklyPattern(expenses, displayCurrency),
            insights = generateInsights(expenses, totalSpent, totalIncome),
            conversionWarnings = (expensesNormalization.warnings + comparisonNormalization.warnings),
            conversionConfidence = conversionConfidence
        )
    }
    
    private fun getTopCategories(
        expenses: List<ExpenseSnapshot>,
        comparisonExpenses: List<ExpenseSnapshot>,
        categoryNamesById: Map<Long, String>,
        displayCurrency: String
    ): List<AnalyticsDashboardCategoryBreakdown> {
        val currentTotals = calculateCategoryTotals(expenses)
        val previousTotals = calculateCategoryTotals(comparisonExpenses)
        val total = currentTotals.values.sum()
        
        return currentTotals.map { (catId, amount) ->
            AnalyticsDashboardCategoryBreakdown(
                categoryId = catId ?: 0L,
                categoryName = catId
                    ?.let(categoryNamesById::get)
                    ?.let(UiText::DynamicString)
                    ?: UiText.fromKey(DomainTextKeys.COMMON_UNKNOWN),
                amount = amount,
                percentage = if (total > 0) (amount / total) * 100 else 0.0,
                changeFromLastPeriod = calculateChangeFromLastPeriod(
                    currentAmount = amount,
                    previousAmount = previousTotals[catId] ?: 0.0
                ),
                displayCurrency = displayCurrency
            )
        }.sortedByDescending { it.amount }.take(5)
    }
    
    private fun getTopMerchants(expenses: List<ExpenseSnapshot>, displayCurrency: String): List<DashboardMerchantBreakdown> {
        val merchantMap = mutableMapOf<String, Pair<Double, Int>>()
        
        for (expense in expenses) {
            if (expense.transactionType == DomainTransactionType.PURCHASE) {
                val (current, count) = merchantMap[expense.merchant] ?: Pair(0.0, 0)
                merchantMap[expense.merchant] = Pair(current + expense.effectiveAmount, count + 1)
            }
        }
        
        return merchantMap.map { (merchant, data) ->
            DashboardMerchantBreakdown(
                merchant = merchant,
                amount = data.first,
                transactionCount = data.second,
                displayCurrency = displayCurrency
            )
        }.sortedByDescending { it.amount }.take(5)
    }
    
    /**
     * Computes monthly spending/income trend for the dashboard.
     *
     * Every calendar month intersecting [startDate, endDate) is included in the
     * result, even months with zero spending or income — they are emitted as
     * [MonthlyDataPoint] with spending=0 and income=0 rather than being skipped.
     *
     * This ensures the trend chart never has "gaps" that could be misinterpreted
     * as missing data. Consumers that need to distinguish "no data" from
     * "genuinely zero spending" should check the [MonthlyDataPoint] values.
     */
    private suspend fun getMonthlyTrend(startDate: Long, endDate: Long, displayCurrency: String): List<MonthlyDataPoint> {
        val result = mutableListOf<MonthlyDataPoint>()
        if (endDate <= startDate) return result

        val monthlyBuckets = analyticsCurrencyNormalizer
            .normalizeSnapshots(expenseRepository.getExpenseSnapshotsBetween(startDate, endDate), displayCurrency)
            .includedExpenses
            .groupBy(::buildMonthKey)

        val startYear = TimePeriodUtils.getYear(startDate)
        val startMonth = TimePeriodUtils.getMonth(startDate)

        var currentYear = startYear
        var currentMonth = startMonth

        // Iterate with a calendar-month cursor; stop when the cursor reaches endDate
        // (half-open: buckets cover [startDate, endDate), so stop when monthStart >= endDate)
        while (true) {
            // A18: Replace Calendar with java.time.ZonedDateTime + ZoneId.systemDefault()
            val calendar = java.util.Calendar.getInstance()
            calendar.set(currentYear, currentMonth, 1, 0, 0, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            val monthStart = calendar.timeInMillis

            // Stop if the start of this month is at or beyond endDate (half-open upper bound)
            if (monthStart >= endDate) break

            // Advance calendar to the first day of the next month to get nextMonthStart
            calendar.add(java.util.Calendar.MONTH, 1)
            val nextMonthStart = calendar.timeInMillis

            // Clamp bucket to the requested half-open dashboard range [startDate, endDate)
            val bucketStart = maxOf(monthStart, startDate)
            val bucketEnd = minOf(nextMonthStart, endDate)

            val monthKey = "$currentYear-${(currentMonth + 1).toString().padStart(2, '0')}"

            val expenses = monthlyBuckets[monthKey].orEmpty().filter {
                it.date >= bucketStart && it.date < bucketEnd
            }

            var spending = 0.0
            var income = 0.0

            for (expense in expenses) {
                when (expense.transactionType) {
                    DomainTransactionType.PURCHASE,
                    DomainTransactionType.WITHDRAWAL -> spending += expense.effectiveAmount
                    DomainTransactionType.DEPOSIT -> income += expense.effectiveAmount
                    else -> Unit
                }
            }

            result.add(MonthlyDataPoint(monthKey, spending, income, displayCurrency))

            // Move to next month
            currentMonth++
            if (currentMonth > 11) {
                currentMonth = 0
                currentYear++
            }
        }

        return result
    }

    private fun calculateCategoryTotals(
        expenses: List<ExpenseSnapshot>
    ): Map<Long?, Double> {
        val categoryMap = mutableMapOf<Long?, Double>()

        for (expense in expenses) {
            if (expense.transactionType == DomainTransactionType.PURCHASE) {
                val current = categoryMap[expense.categoryId] ?: 0.0
                categoryMap[expense.categoryId] = current + expense.effectiveAmount
            }
        }

        return categoryMap
    }

    private fun calculateChangeFromLastPeriod(currentAmount: Double, previousAmount: Double): Double {
        return when {
            previousAmount > 0.0 -> ((currentAmount - previousAmount) / previousAmount) * 100.0
            currentAmount > 0.0 -> 100.0
            else -> 0.0
        }
    }

    private fun buildMonthKey(expense: ExpenseSnapshot): String {
        val year = TimePeriodUtils.getYear(expense.date)
        val month = TimePeriodUtils.getMonth(expense.date) + 1
        return String.format("%04d-%02d", year, month)
    }
    
    private fun getWeeklyPattern(expenses: List<ExpenseSnapshot>, displayCurrency: String): List<DayOfWeekSpending> {
        val dayMap = mutableMapOf<Int, MutableList<Double>>()
        val dayNames = mapOf<Int, UiText>(
            1 to UiText.fromKey(DomainTextKeys.COMMON_DAY_MONDAY),
            2 to UiText.fromKey(DomainTextKeys.COMMON_DAY_TUESDAY),
            3 to UiText.fromKey(DomainTextKeys.COMMON_DAY_WEDNESDAY),
            4 to UiText.fromKey(DomainTextKeys.COMMON_DAY_THURSDAY),
            5 to UiText.fromKey(DomainTextKeys.COMMON_DAY_FRIDAY),
            6 to UiText.fromKey(DomainTextKeys.COMMON_DAY_SATURDAY),
            7 to UiText.fromKey(DomainTextKeys.COMMON_DAY_SUNDAY)
        )
        
        // A18: Replace Calendar with java.time.ZonedDateTime + ZoneId.systemDefault()
        val calendar = java.util.Calendar.getInstance()
        
        for (expense in expenses) {
            if (expense.transactionType == DomainTransactionType.PURCHASE) {
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
                dayName = dayNames[day] ?: UiText.fromKey(DomainTextKeys.COMMON_UNKNOWN),
                averageSpending = if (amounts.isNotEmpty()) amounts.average() else 0.0,
                transactionCount = amounts.size,
                displayCurrency = displayCurrency
            )
        }
    }
    
    private fun generateInsights(
        expenses: List<ExpenseSnapshot>,
        totalSpent: Double,
        totalIncome: Double
    ): List<DashboardInsight> {
        val insights = mutableListOf<DashboardInsight>()
        
        // Check spending vs income
        if (totalSpent > totalIncome * 0.9 && totalIncome > 0) {
            insights.add(
                DashboardInsight(
                    type = DashboardInsightType.BUDGET_WARNING,
                    title = UiText.fromKey("domain_analytics_high_spending"),
                    description = UiText.fromKey(
                        DomainTextKeys.ANALYTICS_HIGH_SPENDING_DESCRIPTION_FORMAT,
                        UiTextArg.Percent((totalSpent / totalIncome) * 100)
                    ),
                    severity = DashboardInsightSeverity.WARNING
                )
            )
        }

        // Check weekend spending
        // A18: Replace Calendar with java.time.ZonedDateTime + ZoneId.systemDefault()
        val calendar = java.util.Calendar.getInstance()
        var weekendSpending = 0.0
        var weekdaySpending = 0.0

            for (expense in expenses) {
            if (expense.transactionType == DomainTransactionType.PURCHASE) {
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
                    title = UiText.fromKey("domain_analytics_weekend_high"),
                    description = UiText.fromKey(DomainTextKeys.ANALYTICS_WEEKEND_SPENDING_DESCRIPTION),
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
                        title = UiText.fromKey("domain_analytics_great_savings"),
                        description = UiText.fromKey(
                            DomainTextKeys.ANALYTICS_GREAT_SAVINGS_DESCRIPTION_FORMAT,
                            UiTextArg.Percent(savingsRate)
                        ),
                        severity = DashboardInsightSeverity.INFO
                    )
                )
            }
        }

        return insights
    }

    /**
     * Determines [ConversionConfidence] from the normalization results of the
     * current and comparison periods.
     *
     * - HIGH:  No conversion warnings at all.
     * - LOW:   >= 50% of input transactions excluded in either period, or
     *          any severe warning (missing exchange rate, invalid currency).
     * - PARTIAL: Warnings exist but below the LOW threshold.
     */
    private fun computeConversionConfidence(
        current: AnalyticsNormalizationResult,
        comparison: AnalyticsNormalizationResult
    ): ConversionConfidence {
        if (!current.hasWarnings && !comparison.hasWarnings) return ConversionConfidence.HIGH

        val hasSevere = current.severeWarnings.isNotEmpty() || comparison.severeWarnings.isNotEmpty()
        val isHeavyLoss = current.lossPercentage >= 50.0 || comparison.lossPercentage >= 50.0

        return if (hasSevere || isHeavyLoss) ConversionConfidence.LOW
        else ConversionConfidence.PARTIAL
    }
}
