package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.core.time.PeriodRange

/**
 * Period definition for analytics queries.
 *
 * @deprecated Use [PeriodKind] instead. Migrate callers to PeriodKind.THIS_WEEK,
 *   PeriodKind.THIS_MONTH, PeriodKind.THIS_QUARTER, PeriodKind.THIS_YEAR,
 *   or PeriodKind.CUSTOM.
 */
@Deprecated(
    message = "Use PeriodKind from domain.core.time instead",
    replaceWith = ReplaceWith("PeriodKind", "com.yourname.expensetracker.domain.core.time.PeriodKind")
)
enum class AnalyticsPeriod {
    WEEK, MONTH, QUARTER, YEAR, CUSTOM
}

/**
 * Represents a time range for analytics calculations.
 *
 * @deprecated Use [PeriodRange] from domain.core.time instead.
 */
@Deprecated(
    message = "Use PeriodRange from domain.core.time instead",
    replaceWith = ReplaceWith("PeriodRange", "com.yourname.expensetracker.domain.core.time.PeriodRange")
)
data class AnalyticsPeriodRange(
    val period: AnalyticsPeriod,
    val startMs: Long,
    val endMs: Long,
    val label: String,
    val comparisonRange: AnalyticsPeriodRange?
)

/**
 * Enhanced category analytics with budget context and trends.
 */
data class EnhancedCategoryAnalytics(
    val category: AnalyticsCategoryRef,
    val period: AnalyticsPeriodRange,
    val displayCurrency: String,
    
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
data class EnhancedMerchantAnalytics(
    val merchant: String,
    val period: AnalyticsPeriodRange,
    val displayCurrency: String,
    
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
    val recentTransactions: List<AnalyticsTransactionSummary>
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
data class SpendingPatternAnalysis(
    val period: AnalyticsPeriodRange,
    
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

data class DayOfWeekStats(
    val dayName: String,
    val dayIndex: Int,
    val totalSpent: Double,
    val transactionCount: Int,
    val averagePerDay: Double,
    val percentageOfWeek: Float,
    val displayCurrency: String
)

data class WeekendWeekdayComparison(
    val weekdayTotal: Double,
    val weekdayCount: Int,
    val weekendTotal: Double,
    val weekendCount: Int,
    val weekdayAveragePerTransaction: Double,
    val weekendAveragePerTransaction: Double,
    val weekendToWeekdayRatio: Float,
    val displayCurrency: String
)

enum class TimeSlot {
    EARLY_MORNING,   // 6-9
    MORNING,         // 9-12
    AFTERNOON,       // 12-17
    EVENING,         // 17-21
    NIGHT,           // 21-24
    LATE_NIGHT       // 0-6
}

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
data class StatisticalInsights(
    val period: AnalyticsPeriodRange,
    val displayCurrency: String,
    
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
    val largestTransaction: AnalyticsTransactionSummary?,
    val smallestTransaction: AnalyticsTransactionSummary?,
    
    // Daily spending stats
    val averageDailySpend: Double,
    val maxDailySpend: Double,
    val daysWithSpending: Int,
    val daysWithoutSpending: Int
)

data class HistogramBin(
    val rangeStart: Double,
    val rangeEnd: Double,
    val count: Int,
    val total: Double,
    val percentage: Float,
    val displayCurrency: String
)

data class TransactionPercentiles(
    val p10: Double,
    val p25: Double,
    val p50: Double,
    val p75: Double,
    val p90: Double,
    val p95: Double,
    val p99: Double,
    val displayCurrency: String
)

data class AnalyticsCategoryRef(
    val id: Long,
    val name: String,
    val icon: String,
    val color: String
)

data class AnalyticsTransactionSummary(
    val id: Long,
    val amount: Double,
    val effectiveAmount: Double,
    val currency: String,
    val merchant: String,
    val date: Long,
    val categoryId: Long?
)
