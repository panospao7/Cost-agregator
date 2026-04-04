package com.yourname.expensetracker.domain.lifestyle

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.UiText
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow

@Singleton
class LifestyleInflationDetector @Inject constructor(
    private val expenseDao: ExpenseDao
) {
    
    suspend fun analyzeLifestyleInflation(
        monthsToAnalyze: Int = 12
    ): LifestyleInflationReport {
        val endDate = System.currentTimeMillis()
        val startDate = endDate - (monthsToAnalyze * 30L * 24 * 60 * 60 * 1000)
        
        // Get all expenses in the period
        val expenses = expenseDao.getExpensesBetweenFlow(startDate, endDate).first()
        
        // Separate income and spending
        val incomeByMonth = mutableMapOf<YearMonth, Double>()
        val spendingByMonth = mutableMapOf<YearMonth, Double>()
        val discretionaryByMonth = mutableMapOf<YearMonth, Double>()
        
        expenses.forEach { expense ->
            val date = Instant.ofEpochMilli(expense.date)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val month = YearMonth.from(date)
            
            when (expense.transactionType) {
                TransactionType.DEPOSIT -> {
                    incomeByMonth[month] = (incomeByMonth[month] ?: 0.0) + expense.effectiveAmount
                }
                TransactionType.PURCHASE -> {
                    spendingByMonth[month] = (spendingByMonth[month] ?: 0.0) + expense.effectiveAmount
                    
                    // Track discretionary spending (non-essential categories)
                    if (isDiscretionaryExpense(expense)) {
                        discretionaryByMonth[month] = (discretionaryByMonth[month] ?: 0.0) + expense.effectiveAmount
                    }
                }
                else -> { /* ignore */ }
            }
        }
        
        // Calculate correlation between income and spending
        val incomeSpendingCorrelation = calculateCorrelation(
            incomeByMonth.map { it.value },
            spendingByMonth.map { it.value }
        )
        
        // Calculate income elasticity of spending
        val incomeElasticity = calculateIncomeElasticity(incomeByMonth, spendingByMonth)
        
        // Detect lifestyle creep patterns
        val lifestyleCreep = detectLifestyleCreep(incomeByMonth, spendingByMonth, discretionaryByMonth)
        
        // Calculate trend
        val incomeTrend = calculateTrend(incomeByMonth.map { it.value })
        val spendingTrend = calculateTrend(spendingByMonth.map { it.value })
        
        // Detect hedonic adaptation (spending increases but satisfaction doesn't)
        val hedonicAdaptation = detectHedonicAdaptation(spendingByMonth, discretionaryByMonth)
        
        return LifestyleInflationReport(
            analysisPeriodMonths = monthsToAnalyze,
            incomeSpendingCorrelation = incomeSpendingCorrelation,
            incomeElasticity = incomeElasticity,
            lifestyleCreepDetected = lifestyleCreep.isNotEmpty(),
            lifestyleCreepAlerts = lifestyleCreep,
            incomeGrowthRate = incomeTrend,
            spendingGrowthRate = spendingTrend,
            lifestyleInflationRate = spendingTrend - incomeTrend,
            hedonicAdaptationScore = hedonicAdaptation,
            monthlyData = buildMonthlyData(incomeByMonth, spendingByMonth, discretionaryByMonth),
            recommendations = generateRecommendations(
                incomeSpendingCorrelation,
                incomeElasticity,
                lifestyleCreep,
                spendingTrend,
                incomeTrend
            )
        )
    }
    
    private fun isDiscretionaryExpense(expense: Expense): Boolean {
        // These are typically non-essential categories
        val discretionaryKeywords = listOf(
            "dining", "restaurant", "entertainment", "hobbies", 
            "shopping", "fashion", "beauty", "spa", "vacation",
            "travel", "luxury", "subscriptions", "streaming"
        )
        
        return discretionaryKeywords.any { keyword ->
            expense.merchant.contains(keyword, ignoreCase = true) ||
            expense.notes?.contains(keyword, ignoreCase = true) == true
        }
    }
    
    private fun calculateCorrelation(list1: List<Double>, list2: List<Double>): Double {
        if (list1.size != list2.size || list1.size < 2) return 0.0
        
        val n = list1.size
        val sum1 = list1.sum()
        val sum2 = list2.sum()
        val sum1Sq = list1.sumOf { it * it }
        val sum2Sq = list2.sumOf { it * it }
        val pSum = list1.zip(list2).sumOf { it.first * it.second }
        
        val num = pSum - (sum1 * sum2 / n)
        val den = kotlin.math.sqrt((sum1Sq - sum1 * sum1 / n) * (sum2Sq - sum2 * sum2 / n))
        
        return if (den == 0.0) 0.0 else num / den
    }
    
    private fun calculateIncomeElasticity(
        incomeByMonth: Map<YearMonth, Double>,
        spendingByMonth: Map<YearMonth, Double>
    ): Double {
        val sortedMonths = incomeByMonth.keys.sorted()
        if (sortedMonths.size < 2) return 0.0
        
        var totalElasticity = 0.0
        var count = 0
        
        for (i in 1 until sortedMonths.size) {
            val prevMonth = sortedMonths[i - 1]
            val currMonth = sortedMonths[i]
            
            val prevIncome = incomeByMonth[prevMonth] ?: continue
            val currIncome = incomeByMonth[currMonth] ?: continue
            val prevSpending = spendingByMonth[prevMonth] ?: continue
            val currSpending = spendingByMonth[currMonth] ?: continue
            
            if (prevIncome > 0 && prevSpending > 0) {
                val incomeChange = (currIncome - prevIncome) / prevIncome
                val spendingChange = (currSpending - prevSpending) / prevSpending
                
                if (incomeChange != 0.0) {
                    val elasticity = spendingChange / incomeChange
                    totalElasticity += elasticity
                    count++
                }
            }
        }
        
        return if (count > 0) totalElasticity / count else 0.0
    }
    
    private fun detectLifestyleCreep(
        incomeByMonth: Map<YearMonth, Double>,
        spendingByMonth: Map<YearMonth, Double>,
        discretionaryByMonth: Map<YearMonth, Double>
    ): List<LifestyleCreepAlert> {
        val alerts = mutableListOf<LifestyleCreepAlert>()
        val sortedMonths = incomeByMonth.keys.sorted()
        
        if (sortedMonths.size < 3) return alerts
        
        for (i in 2 until sortedMonths.size) {
            val month = sortedMonths[i]
            val prevMonth = sortedMonths[i - 1]
            val prevPrevMonth = sortedMonths[i - 2]
            
            val income = incomeByMonth[month] ?: continue
            val prevIncome = incomeByMonth[prevMonth] ?: continue
            val prevPrevIncome = incomeByMonth[prevPrevMonth] ?: continue
            
            val spending = spendingByMonth[month] ?: continue
            val prevSpending = spendingByMonth[prevMonth] ?: continue
            val prevPrevSpending = spendingByMonth[prevPrevMonth] ?: continue
            
            // Detect if spending is growing faster than income
            if (income > prevIncome && spending > prevSpending) {
                val incomeGrowth = (income - prevIncome) / prevIncome
                val spendingGrowth = (spending - prevSpending) / prevSpending
                
                if (spendingGrowth > incomeGrowth * 1.5) {
                    val discretionary = discretionaryByMonth[month] ?: 0.0
                    val prevDiscretionary = discretionaryByMonth[prevMonth] ?: 0.0
                    val discGrowth = if (prevDiscretionary > 0) 
                        (discretionary - prevDiscretionary) / prevDiscretionary else 0.0
                    
                    alerts.add(
                        LifestyleCreepAlert(
                            month = month.toString(),
                            incomeGrowthPercent = incomeGrowth * 100,
                            spendingGrowthPercent = spendingGrowth * 100,
                            discretionaryGrowthPercent = discGrowth * 100,
                            severity = if (spendingGrowth > incomeGrowth * 2) 
                                CreepSeverity.HIGH 
                            else 
                                CreepSeverity.MEDIUM,
                            description = "Spending increased ${String.format("%.1f", spendingGrowth * 100)}% " +
                                    "while income only increased ${String.format("%.1f", incomeGrowth * 100)}%"
                        )
                    )
                }
            }
        }
        
        return alerts
    }
    
    private fun calculateTrend(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        
        val first = values.first()
        val last = values.last()
        
        return if (first > 0) (last - first) / first else 0.0
    }
    
    private fun detectHedonicAdaptation(
        spendingByMonth: Map<YearMonth, Double>,
        discretionaryByMonth: Map<YearMonth, Double>
    ): Double {
        // Hedonic adaptation: spending keeps increasing but satisfaction plateaus
        // We'll estimate this by looking at discretionary spending volatility
        val discretionaryValues = discretionaryByMonth.values.toList()
        if (discretionaryValues.size < 3) return 0.0
        
        val mean = discretionaryValues.average()
        val variance = discretionaryValues.sumOf { (it - mean).pow(2) } / discretionaryValues.size
        val stdDev = kotlin.math.sqrt(variance)
        
        // Higher coefficient of variation suggests hedonic treadmill
        val coefficientOfVariation = if (mean > 0) stdDev / mean else 0.0
        
        // Score from 0-100, higher = more adaptation
        return (coefficientOfVariation * 100).coerceIn(0.0, 100.0)
    }
    
    private fun buildMonthlyData(
        incomeByMonth: Map<YearMonth, Double>,
        spendingByMonth: Map<YearMonth, Double>,
        discretionaryByMonth: Map<YearMonth, Double>
    ): List<MonthlyLifestyleData> {
        val allMonths = (incomeByMonth.keys + spendingByMonth.keys).distinct().sorted()
        
        return allMonths.map { month ->
            val income = incomeByMonth[month] ?: 0.0
            val spending = spendingByMonth[month] ?: 0.0
            val discretionary = discretionaryByMonth[month] ?: 0.0
            
            MonthlyLifestyleData(
                month = month.toString(),
                income = income,
                totalSpending = spending,
                discretionarySpending = discretionary,
                essentialSpending = spending - discretionary,
                savingsRate = if (income > 0) ((income - spending) / income * 100) else 0.0,
                lifestyleScore = calculateLifestyleScore(income, spending, discretionary)
            )
        }
    }
    
    private fun calculateLifestyleScore(income: Double, spending: Double, discretionary: Double): Double {
        if (income <= 0) return 0.0
        
        val savingsRate = (income - spending) / income
        val discretionaryRatio = if (spending > 0) discretionary / spending else 0.0
        
        // Score based on savings rate and discretionary spending balance
        // Higher savings and balanced discretionary = better score
        val savingsScore = (savingsRate * 50).coerceIn(0.0, 50.0)
        val discretionaryScore = (50 - discretionaryRatio * 50).coerceIn(0.0, 50.0)
        
        return savingsScore + discretionaryScore
    }
    
    private fun generateRecommendations(
        correlation: Double,
        elasticity: Double,
        lifestyleCreep: List<LifestyleCreepAlert>,
        spendingTrend: Double,
        incomeTrend: Double
    ): List<LifestyleRecommendation> {
        val recommendations = mutableListOf<LifestyleRecommendation>()
        
        if (elasticity > 1.2) {
            recommendations.add(
                LifestyleRecommendation(
                    type = RecommendationType.REDUCE_ELASTICITY,
                    title = UiText.from(R.string.domain_lifestyle_spending_faster),
                    description = "Your spending increases ${String.format("%.1f", elasticity * 100)}% " +
                            "for every 1% increase in income. Consider automating savings to reduce lifestyle creep.",
                    priority = RecommendationPriority.HIGH,
                    actionItems = listOf(
                        "Set up automatic transfers to savings when you get paid",
                        "Review discretionary spending categories",
                        "Create a 'pay yourself first' budget rule"
                    )
                )
            )
        }
        
        if (lifestyleCreep.isNotEmpty()) {
            val recentCreep = lifestyleCreep.last()
            recommendations.add(
                LifestyleRecommendation(
                    type = RecommendationType.LIFESTYLE_CREEP_ALERT,
                    title = UiText.from(R.string.domain_lifestyle_creep),
                    description = "In ${recentCreep.month}, your spending grew ${String.format("%.1f", recentCreep.spendingGrowthPercent)}% " +
                            "while income grew ${String.format("%.1f", recentCreep.incomeGrowthPercent)}%",
                    priority = if (recentCreep.severity == CreepSeverity.HIGH) 
                        RecommendationPriority.HIGH 
                    else 
                        RecommendationPriority.MEDIUM,
                    actionItems = listOf(
                        "Review subscriptions and recurring expenses",
                        "Implement a 48-hour rule for large purchases",
                        "Calculate the opportunity cost of increased spending"
                    )
                )
            )
        }
        
        if (spendingTrend > incomeTrend + 0.05) {
            recommendations.add(
                LifestyleRecommendation(
                    type = RecommendationType.SPENDING_REVIEW,
                    title = UiText.from(R.string.domain_lifestyle_outpacing),
                    description = "Over the analysis period, your spending has grown ${String.format("%.1f", spendingTrend * 100)}% " +
                            "while income grew ${String.format("%.1f", incomeTrend * 100)}%",
                    priority = RecommendationPriority.MEDIUM,
                    actionItems = listOf(
                        "Audit all recurring subscriptions",
                        "Identify 'lifestyle upgrades' from the past year",
                        "Set spending caps for discretionary categories"
                    )
                )
            )
        }
        
        if (correlation < 0.3) {
            recommendations.add(
                LifestyleRecommendation(
                    type = RecommendationType.INCOME_OPTIMIZATION,
                    title = UiText.from(R.string.domain_lifestyle_not_aligned),
                    description = "There's a weak correlation between your income and spending patterns. " +
                            "This might indicate irregular income or inconsistent budgeting.",
                    priority = RecommendationPriority.LOW,
                    actionItems = listOf(
                        "Build a larger emergency fund for income volatility",
                        "Consider income smoothing strategies",
                        "Track irregular income sources separately"
                    )
                )
            )
        }
        
        return recommendations
    }
    
    data class LifestyleInflationReport(
        val analysisPeriodMonths: Int,
        val incomeSpendingCorrelation: Double,
        val incomeElasticity: Double,
        val lifestyleCreepDetected: Boolean,
        val lifestyleCreepAlerts: List<LifestyleCreepAlert>,
        val incomeGrowthRate: Double,
        val spendingGrowthRate: Double,
        val lifestyleInflationRate: Double,
        val hedonicAdaptationScore: Double,
        val monthlyData: List<MonthlyLifestyleData>,
        val recommendations: List<LifestyleRecommendation>
    )
    
    data class LifestyleCreepAlert(
        val month: String,
        val incomeGrowthPercent: Double,
        val spendingGrowthPercent: Double,
        val discretionaryGrowthPercent: Double,
        val severity: CreepSeverity,
        val description: String
    )
    
    enum class CreepSeverity {
        LOW, MEDIUM, HIGH
    }
    
    data class MonthlyLifestyleData(
        val month: String,
        val income: Double,
        val totalSpending: Double,
        val discretionarySpending: Double,
        val essentialSpending: Double,
        val savingsRate: Double,
        val lifestyleScore: Double
    )
    
    data class LifestyleRecommendation(
        val type: RecommendationType,
        val title: UiText,
        val description: String,
        val priority: RecommendationPriority,
        val actionItems: List<String>
    )
    
    enum class RecommendationType {
        REDUCE_ELASTICITY,
        LIFESTYLE_CREEP_ALERT,
        SPENDING_REVIEW,
        INCOME_OPTIMIZATION,
        SAVINGS_BOOST
    }
    
    enum class RecommendationPriority {
        LOW, MEDIUM, HIGH
    }
}
