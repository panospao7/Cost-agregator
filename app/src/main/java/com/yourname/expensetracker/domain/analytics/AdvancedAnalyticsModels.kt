package com.yourname.expensetracker.domain.analytics

import androidx.compose.runtime.Immutable
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus

/**
 * Period definition for analytics queries.
 */
enum class AnalyticsPeriod {
    WEEK, MONTH, QUARTER, YEAR, CUSTOM
}

/**
 * Represents a time range for analytics calculations.
 */
@Immutable
data class PeriodRange(
    val period: AnalyticsPeriod,
    val startMs: Long,
    val endMs: Long,
    val label: String,
    val comparisonRange: PeriodRange?
)

/**
 * Enhanced category analytics with budget context and trends.
 */
@Immutable
data class EnhancedCategoryAnalytics(
    val category: Category,
    val period: PeriodRange,
    
    // Core metrics
    val totalSpent: Double,
    val transactionCount: Int,
    val averagePerTransaction: Double,
    val medianTransaction: Double,
    
    // Comparison
    val previousPeriodTotal: Double?,
    val changePercent: Float?,
    val trendDirection: CategoryTrendDirection,
    
    // Budget context
    val budgetAmount: Double?,
    val budgetUtilizationPercent: Float?,
    val budgetRemaining: Double?,
    val budgetStatus: BudgetHealthStatus?,
    
    // Distribution
    val minTransaction: Double,
    val maxTransaction: Double,
    val percentile25: Double,
    val percentile75: Double,
    
    // Trend visualization
    val sparklineData: List<Double>,
    
    // Spending velocity (positive = accelerating)
    val velocity: Double
)

enum class CategoryTrendDirection {
    UP_FAST,    // >20% increase
    UP,         // 5-20% increase  
    STABLE,     // -5% to +5%
    DOWN,       // 5-20% decrease
    DOWN_FAST   // >20% decrease
}

/**
 * Enhanced merchant analytics with loyalty and price trends.
 */
@Immutable
data class EnhancedMerchantAnalytics(
    val merchant: String,
    val merchantKey: String,
    val period: PeriodRange,
    
    // Core metrics
    val totalSpent: Double,
    val transactionCount: Int,
    val averagePerVisit: Double,
    val medianPerVisit: Double,
    
    // Frequency analysis
    val visitFrequency: MerchantVisitFrequency,
    val averageDaysBetweenVisits: Double?,
    val predictedNextVisitDate: Long?,
    
    // Price trend
    val priceTrend: MerchantPriceTrend,
    val firstPurchaseAmount: Double?,
    val latestPurchaseAmount: Double?,
    val priceChangePercent: Float?,
    
    // Loyalty metrics
    val loyaltyScore: Float,
    val consistencyRating: MerchantConsistencyRating,
    val consecutiveMonthsVisited: Int,
    
    // Spending distribution by day of week (0=Mon, 6=Sun)
    val spendingByDayOfWeek: Map<Int, Double>,
    
    // Recent transactions preview
    val recentTransactions: List<Expense>
)

enum class MerchantVisitFrequency {
    DAILY, WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY, RARE
}

enum class MerchantPriceTrend {
    INCREASING_FAST, INCREASING, STABLE, DECREASING, DECREASING_FAST, INSUFFICIENT_DATA
}

enum class MerchantConsistencyRating {
    HIGHLY_CONSISTENT, CONSISTENT, VARIABLE, IRREGULAR, NEW_MERCHANT
}

/**
 * Spending pattern analysis results.
 */
@Immutable
data class SpendingPatternAnalysis(
    val period: PeriodRange,
    
    // Day of week breakdown
    val dayOfWeekStats: Map<Int, DayOfWeekStats>,
    val mostActiveDayIndex: Int,
    val leastActiveDayIndex: Int,
    
    // Weekend vs Weekday comparison
    val weekendVsWeekday: WeekendWeekdayComparison,
    
    // Time of day distribution
    val timeOfDayDistribution: Map<TimeSlot, Double>,
    
    // Detected behavioral patterns
    val detectedPatterns: List<DetectedPattern>
)

@Immutable
data class DayOfWeekStats(
    val dayName: String,
    val dayIndex: Int,
    val totalSpent: Double,
    val transactionCount: Int,
    val averagePerDay: Double,
    val percentageOfWeek: Float
)

@Immutable
data class WeekendWeekdayComparison(
    val weekdayTotal: Double,
    val weekdayCount: Int,
    val weekendTotal: Double,
    val weekendCount: Int,
    val weekdayAveragePerTransaction: Double,
    val weekendAveragePerTransaction: Double,
    val weekendToWeekdayRatio: Float
)

enum class TimeSlot {
    EARLY_MORNING,   // 6-9
    MORNING,         // 9-12
    AFTERNOON,       // 12-17
    EVENING,         // 17-21
    NIGHT,           // 21-24
    LATE_NIGHT       // 0-6
}

@Immutable
data class DetectedPattern(
    val type: SpendingPatternType,
    val description: String,
    val confidence: Float,
    val affectedMerchants: List<String>
)

enum class SpendingPatternType {
    WEEKEND_WARRIOR,
    LUNCH_BROWSER,
    COMMUTER,
    SUBSCRIPTION_HEAVY,
    IMPULSE_BUYER,
    PLANNER,
    OCCASIONAL_SPLURGER
}

/**
 * Statistical insights for a period.
 */
@Immutable
data class StatisticalInsights(
    val period: PeriodRange,
    
    // Transaction size distribution
    val histogramBins: List<HistogramBin>,
    val percentiles: TransactionPercentiles,
    
    // Volatility metrics
    val volatilityIndex: Float,
    val coefficientOfVariation: Float,
    val standardDeviation: Double,
    
    // Central tendencies
    val meanTransaction: Double,
    val medianTransaction: Double,
    val modeTransaction: Double?,
    
    // Extremes
    val largestTransaction: Expense?,
    val smallestTransaction: Expense?,
    
    // Daily spending stats
    val averageDailySpend: Double,
    val maxDailySpend: Double,
    val daysWithSpending: Int,
    val daysWithoutSpending: Int
)

@Immutable
data class HistogramBin(
    val rangeStart: Double,
    val rangeEnd: Double,
    val count: Int,
    val total: Double,
    val percentage: Float
)

@Immutable
data class TransactionPercentiles(
    val p10: Double,
    val p25: Double,
    val p50: Double,
    val p75: Double,
    val p90: Double,
    val p95: Double,
    val p99: Double
)