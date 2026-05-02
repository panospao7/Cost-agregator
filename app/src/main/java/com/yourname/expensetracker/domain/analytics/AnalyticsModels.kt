package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.model.UiText

/**
 * Analytics result models.
 *
 * CURRENCY NOTE: All displayCurrency fields are required — callers must supply
 * the actual display currency derived from user settings.
 */

// === New Insights Models ===

data class MonthPeriod(
    val year: Int,
    val month: Int, // 0-indexed (Calendar.JANUARY = 0)
    val startMs: Long,
    val endMs: Long
) {
    companion object {
        val MONTH_NAMES = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
    }

    val label: String
        get() = "${MONTH_NAMES[month]} $year"

    val shortLabel: String
        get() = MONTH_NAMES[month]
}

data class CategoryInsight(
    val category: AnalyticsCategoryRef,
    val currentTotal: Double,
    val currentCount: Int,
    val previousTotal: Double?,
    val previousCount: Int?,
    val averageOverMonths: Double?,
    val monthsOfData: Int,
    val percentageOfTotal: Float,
    val changeFromPrevious: Float?, // percentage change
    val changeFromAverage: Float?, // percentage deviation from average
    val displayCurrency: String
)

data class MerchantInsight(
    val merchant: String,
    val avgAmount: Double,
    val minAmount: Double,
    val maxAmount: Double,
    val totalSpent: Double,
    val transactionCount: Int,
    val isLikelyRecurring: Boolean,
    val stdDeviation: Double?, // null if < 3 transactions
    val displayCurrency: String
)

data class SpendingPace(
    val currentMonthSpent: Double,
    val daysElapsed: Int,
    val daysInMonth: Int,
    val projectedTotal: Double,
    val previousMonthTotal: Double?,
    val averageMonthlyTotal: Double?,
    val pacePercentage: Float, // how far through the month's typical spend
    val paceStatus: PaceStatus,
    val displayCurrency: String
)

enum class PaceStatus {
    UNDER_PACE,    // spending less than typical
    ON_PACE,       // within 10% of typical
    OVER_PACE,     // spending more than typical
    NO_BASELINE    // not enough data
}

data class AnomalyTransaction(
    val expense: AnalyticsTransactionSummary,
    val merchantAvg: Double,
    val deviationMultiple: Float, // how many times the average
    val category: AnalyticsCategoryRef?,
    // Detection metadata — all optional; defaults preserve backward compat
    val detectionMethod: AnomalyMethod = AnomalyMethod.MULTIPLIER,
    val contextualNote: String? = null,  // e.g. "Unusual for a Tuesday evening"
    val categoryAvg: Double? = null,     // category-level avg, distinct from merchantAvg
    val displayCurrency: String
)

/**
 * Which statistical method flagged this transaction as anomalous.
 *
 * MULTIPLIER — existing path: historical merchant avg × adaptive multiplier (3×–5×)
 * IQR        — amount exceeds Q3 + 1.5 × IQR within the same category
 * MAD        — modified Z-score > 3.5 using Median Absolute Deviation (most robust for skewed data)
 * CONTEXTUAL — normal globally but anomalous for this (dayOfWeek, timeOfDay) context
 */
enum class AnomalyMethod {
    MULTIPLIER,
    IQR,
    MAD,
    CONTEXTUAL
}

data class RecurringExpense(
    val merchant: String,
    val avgAmount: Double,
    val frequency: Int, // transactions total
    val intervalDays: Int, // approximate days between transactions
    val amountVariation: Double, // max - min
    val isStable: Boolean, // low variation
    val displayCurrency: String
)

data class DayOfWeekInsight(
    val dayName: String,
    val dayIndex: Int, // 0=Mon, 6=Sun
    val totalSpent: Double,
    val transactionCount: Int,
    val avgPerTransaction: Double,
    val displayCurrency: String
)

data class MonthlyComparison(
    val currentMonth: MonthPeriod,
    val previousMonth: MonthPeriod?,
    val currentTotal: Double,
    val previousTotal: Double?,
    val changeAmount: Double?,
    val changePercentage: Float?,
    val currentCount: Int,
    val previousCount: Int?,
    val displayCurrency: String
)

enum class AnalyticsConversionWarningType {
    INVALID_HOME_CURRENCY,
    INVALID_TRANSACTION_CURRENCY,
    MISSING_EXCHANGE_RATE
}

data class AnalyticsConversionWarning(
    val type: AnalyticsConversionWarningType,
    val message: String,
    val affectedTransactionCount: Int,
    val sourceCurrencies: List<String> = emptyList()
)

data class InsightsSnapshot(
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val generatedAt: Long = 0L,
    val currentMonth: MonthPeriod,
    val monthlyComparison: MonthlyComparison,
    val categoryInsights: List<CategoryInsight>,
    val topMerchants: List<MerchantInsight>,
    val spendingPace: SpendingPace,
    val anomalies: List<AnomalyTransaction>,
    val recurringExpenses: List<RecurringExpense>,
    val dayOfWeekPattern: List<DayOfWeekInsight>,
    val largestTransaction: AnalyticsTransactionSummary?,
    val averageTransactionSize: Double,
    val medianTransactionSize: Double,
    val totalMonthsOfData: Int,
    val displayCurrency: String,
    val conversionWarnings: List<AnalyticsConversionWarning> = emptyList()
)

// === Legacy / Existing Models ===

data class SpendingPeriod(
    val label: String,
    val startDate: Long,
    val endDate: Long,
    val total: Double,
    val previousTotal: Double?,         // For comparison
    val byCategory: List<AnalyticsCategoryBreakdown>,
    val byMerchant: List<MerchantBreakdown>,
    val dailyTotals: Map<String, Double>,   // "2024-01-15" → 45.60
    val transactionCount: Int
) {
    val changePercent: Float?
        get() = if (previousTotal != null && previousTotal > 0)
            ((total - previousTotal) / previousTotal * 100).toFloat()
        else null
}

@Deprecated(
    message = "Use AnalyticsCategoryBreakdown",
    replaceWith = ReplaceWith("AnalyticsCategoryBreakdown")
)
typealias LegacyAnalyticsCategoryBreakdown = AnalyticsCategoryBreakdown

data class AnalyticsCategoryBreakdown(
    val category: AnalyticsCategoryRef,
    val total: Double,
    val count: Int,
    val percentage: Float,          // 0-100
    val displayCurrency: String
)

data class MerchantBreakdown(
    val name: String,
    val totalSpent: Double,
    val transactionCount: Int,
    val averageTransaction: Double,
    val categoryId: Long?,
    val displayCurrency: String
)

data class SpendingInsight(
    val type: InsightType,
    val icon: String,
    val title: UiText,
    val description: UiText,
    val severity: Float             // 0-1, how important/urgent
)

enum class InsightType {
    SPENDING_INCREASE,
    SPENDING_DECREASE,
    UNUSUAL_TRANSACTION,
    RECURRING_DETECTED,
    CATEGORY_TREND,
    BUDGET_WARNING,
    MERCHANT_FREQUENCY,
    DAILY_AVERAGE,
    TOP_MERCHANT,
    STREAK,
    NEW_MERCHANT,
    RECURRING_PATTERN,
    SAVINGS_OPPORTUNITY,
    SPENDING_PATTERN
}

data class RecurringCandidate(
    val merchant: String,
    val amount: Double,
    val intervalDays: Int,
    val occurrences: Int,
    val nextExpectedDate: Long?,
    val confidence: Float = 0.0f,
    val displayCurrency: String
)

/**
 * Time period selection for analytics views.
 *
 * @deprecated Use [PeriodKind] from domain.core.time instead.
 *   Mapping: TODAY→PeriodKind.TODAY, WEEK→PeriodKind.THIS_WEEK,
 *   MONTH→PeriodKind.THIS_MONTH, QUARTER→PeriodKind.THIS_QUARTER,
 *   YEAR→PeriodKind.THIS_YEAR, ALL→PeriodKind.CUSTOM (with explicit range).
 */
@Deprecated(
    message = "Use PeriodKind from domain.core.time instead",
    replaceWith = ReplaceWith("PeriodKind", "com.yourname.expensetracker.domain.core.time.PeriodKind")
)
enum class TimePeriod {
    TODAY, WEEK, MONTH, QUARTER, YEAR, ALL
}

/** Maps this legacy [TimePeriod] to [PeriodKind] for the migration. */
fun TimePeriod.toPeriodKind(): PeriodKind = when (this) {
    TimePeriod.TODAY -> PeriodKind.TODAY
    TimePeriod.WEEK -> PeriodKind.THIS_WEEK
    TimePeriod.MONTH -> PeriodKind.THIS_MONTH
    TimePeriod.QUARTER -> PeriodKind.THIS_QUARTER
    TimePeriod.YEAR -> PeriodKind.THIS_YEAR
    TimePeriod.ALL -> PeriodKind.CUSTOM
}

// === Feature 3: Year-over-Year Comparison Models ===

/**
 * Monthly spending total for a given year, used in YoY comparisons.
 */
data class MonthlyYearTotal(
    val month: Int,        // 0-indexed (Calendar.JANUARY = 0)
    val monthLabel: String, // "Jan", "Feb", ...
    val total: Double,
    val transactionCount: Int,
    val displayCurrency: String
)

/**
 * Side-by-side year comparison: current year vs prior year, broken down by month.
 * Only months with data in either year are included.
 */
data class YearOverYearComparison(
    val currentYear: Int,
    val priorYear: Int,
    val currentYearMonths: List<MonthlyYearTotal>,
    val priorYearMonths: List<MonthlyYearTotal>,
    val currentYearTotal: Double,
    val priorYearTotal: Double,
    val changePercent: Float?,   // null if no prior year data
    val deltaByMonth: List<Triple<String, Double, Double>>, // (monthLabel, currentTotal, priorTotal) for months with data in both years
    val displayCurrency: String
)

// === Feature 4: Spending Velocity Anomaly Models ===

/**
 * A calendar day where the aggregate spending rate was statistically anomalous.
 * Detection uses the same IQR approach as AnomalyDetector but operates on
 * daily totals instead of individual transactions.
 */
data class VelocityAnomaly(
    val dateMs: Long,
    val dayLabel: String,        // e.g. "Mon Jan 06"
    val dayTotal: Double,        // total spent that day
    val monthDailyAvg: Double,   // average daily spend for the month
    val deviationMultiple: Float, // dayTotal / monthDailyAvg
    val topMerchants: List<String>, // top contributors that day (up to 3)
    val displayCurrency: String
)

// === Feature 5: Post-Salary Sequential Pattern Models ===

/**
 * A spending category that consistently spikes in the days immediately after a salary deposit.
 */
data class PostSalaryCategory(
    val categoryName: String,
    val categoryIcon: String,
    val avgSpendAfterSalary: Double,  // average total spent in 7 days after salary
    val occurrences: Int,              // how many salary cycles showed this pattern
    val displayCurrency: String
)

/**
 * Summarises the detected salary events and the post-salary spending behaviour.
 */
data class PostSalaryPattern(
    val salaryCount: Int,              // number of detected salary deposits
    val avgSalaryAmount: Double,       // average deposit amount
    val avgDaysToFirstPurchase: Float, // avg days from deposit to first expense
    val topCategories: List<PostSalaryCategory>, // top spiking categories
    val avgTotalSpentIn7Days: Double,  // avg total spend within 7 days of salary
    val displayCurrency: String
)

// === Feature 6: Duplicate/Error Detection Models ===

enum class SuspectReason {
    NEAR_DUPLICATE,    // Same amount + merchant within 24h (possible double-charge)
    ROUND_AMOUNT,      // Suspiciously large round-number amount
    EXTREME_OUTLIER    // Single transaction >5x average transaction size
}

/**
 * A transaction flagged as a potential duplicate or data error.
 */
data class SuspectTransaction(
    val expenseId: Long,
    val dateMs: Long,
    val amount: Double,
    val currency: String,
    val merchant: String,
    val reason: SuspectReason,
    val reasonLabel: String,       // Human-readable reason
    val duplicateOfId: Long? = null // Set when NEAR_DUPLICATE; ID of the original transaction
)
