package com.yourname.expensetracker.domain.analytics.fixtures

/**
 * Expected Results for Golden Data Sets.
 *
 * These pre-calculated values serve as the "oracle" for verification tests.
 * Any analytics engine should produce results matching these values when
 * run against the corresponding GoldenDataSets.
 */
object ExpectedResults {

    // ============================================================================
    // Simple Month Purchases (3 transactions: 10 + 20 + 30)
    // ============================================================================
    object SimpleMonth {
        const val TOTAL_SPENT = 60.0
        const val TRANSACTION_COUNT = 3
        const val AVERAGE_TRANSACTION = 20.0
        val CATEGORY_TOTALS = mapOf(
            1L to 10.0,  // Coffee Shop
            2L to 20.0,  // Grocery Store
            3L to 30.0   // Restaurant
        )
    }

    // ============================================================================
    // Split Transaction (100 total, 50 effective)
    // ============================================================================
    object SplitTransaction {
        const val TOTAL_SPENT = 50.0
        const val TRANSACTION_COUNT = 1
        const val AVERAGE_TRANSACTION = 50.0
        const val RAW_AMOUNT = 100.0
        const val EFFECTIVE_AMOUNT = 50.0
    }

    // ============================================================================
    // Excluded Transactions (one excluded: 500 not mine, one included: 100)
    // ============================================================================
    object ExcludedTransactions {
        const val TOTAL_SPENT = 100.0  // Only the "Personal Item" counts
        const val TRANSACTION_COUNT = 1
        const val RAW_AMOUNT_SUM = 600.0  // 500 + 100
        const val EFFECTIVE_AMOUNT_SUM = 100.0
    }

    // ============================================================================
    // Percentage Split (1000 total, 70% share = 700 effective)
    // ============================================================================
    object PercentageSplit {
        const val TOTAL_SPENT = 700.0
        const val RAW_AMOUNT = 1000.0
        const val SHARE_PERCENTAGE = 70
        const val EFFECTIVE_AMOUNT = 700.0
    }

    // ============================================================================
    // Empty Dataset
    // ============================================================================
    object EmptyDataset {
        const val TOTAL_SPENT = 0.0
        const val TRANSACTION_COUNT = 0
        const val AVERAGE_TRANSACTION = 0.0
    }

    // ============================================================================
    // Single Transaction
    // ============================================================================
    object SingleTransaction {
        const val TOTAL_SPENT = 50.0
        const val TRANSACTION_COUNT = 1
        const val AVERAGE_TRANSACTION = 50.0
        const val MEDIAN_TRANSACTION = 50.0
    }

    // ============================================================================
    // Previous Month (Feb 2026)
    // ============================================================================
    object PreviousMonth {
        const val TOTAL_SPENT = 40.0  // 15 + 25
        const val TRANSACTION_COUNT = 2
    }

    // ============================================================================
    // Two Month Comparison (Feb + Mar 2026)
    // ============================================================================
    object TwoMonthComparison {
        const val TOTAL_SPENT = 100.0  // 60 (Mar) + 40 (Feb)
        const val MARCH_TOTAL = 60.0
        const val FEBRUARY_TOTAL = 40.0
        const val MONTH_OVER_MONTH_CHANGE_PERCENT = 50.0f  // (60-40)/40 * 100
    }

    // ============================================================================
    // Recurring Merchant (Starbucks - stable amounts around 4.50-5.00)
    // ============================================================================
    object RecurringMerchant {
        const val TRANSACTION_COUNT = 5
        const val TOTAL_SPENT = 23.75  // 4.50 + 5.00 + 4.75 + 4.50 + 5.00
        const val AVERAGE_AMOUNT = 4.75
        const val MIN_AMOUNT = 4.50
        const val MAX_AMOUNT = 5.00
        const val VARIATION = 0.50  // max - min
        const val IS_STABLE = true
    }

    // ============================================================================
    // Mixed Transaction Types
    // ============================================================================
    object MixedTypes {
        const val PURCHASE_TOTAL = 30.0  // Only PURCHASE counts
        const val DEPOSIT_AMOUNT = 100.0  // Should not count toward spending
        const val WITHDRAWAL_AMOUNT = 50.0  // Should not count toward spending
        const val SPENDING_TRANSACTION_COUNT = 1  // Only PURCHASE
    }

    // ============================================================================
    // Day of Week Spread
    // ============================================================================
    object DayOfWeekSpread {
        const val TOTAL_SPENT = 280.0  // 10+20+30+40+50+60+70
        const val MONDAY_TOTAL = 10.0
        const val TUESDAY_TOTAL = 20.0
        const val WEDNESDAY_TOTAL = 30.0
        const val THURSDAY_TOTAL = 40.0
        const val FRIDAY_TOTAL = 50.0
        const val SATURDAY_TOTAL = 60.0
        const val SUNDAY_TOTAL = 70.0

        val TOTALS_BY_DAY = listOf(
            MONDAY_TOTAL,    // 0 = Monday
            TUESDAY_TOTAL,   // 1 = Tuesday
            WEDNESDAY_TOTAL, // 2 = Wednesday
            THURSDAY_TOTAL,  // 3 = Thursday
            FRIDAY_TOTAL,    // 4 = Friday
            SATURDAY_TOTAL,  // 5 = Saturday
            SUNDAY_TOTAL     // 6 = Sunday
        )
    }

    // ============================================================================
    // Anomaly Detection (Regular Cafe: avg ~10.50, anomalous: 150)
    // ============================================================================
    object AnomalyScenario {
        const val TRANSACTION_COUNT = 5
        const val NORMAL_TRANSACTION_COUNT = 4
        const val ANOMALY_COUNT = 1
        const val TOTAL_SPENT = 192.0  // 10 + 12 + 9 + 11 + 150
        const val AVERAGE_WITHOUT_ANOMALY = 10.5
        const val ANOMALY_DEVIATION_MULTIPLE = 14.29f  // 150 / 10.5
        const val ANOMALY_TRANSACTION_ID = 30L
        const val ANOMALY_AMOUNT = 150.0
    }

    // ============================================================================
    // Complex Scenario (multiple edge cases combined)
    // ============================================================================
    object ComplexScenario {
        // Effective amounts:
        // 31L: 25.0 (normal)
        // 32L: 40.0 (split 50%)
        // 33L: 0.0 (excluded)
        // 34L: 150.0 (split 50%)
        // 35L: 45.0 (normal)
        // 36L: 0.0 (deposit - not purchase)
        const val TOTAL_SPENT = 260.0  // 25 + 40 + 0 + 150 + 45 + 0
        const val TRANSACTION_COUNT = 4  // Excluding excluded and non-PURCHASE

        val CATEGORY_TOTALS = mapOf(
            1L to 25.0,   // Food & Dining
            3L to 85.0,   // Entertainment: 40 + 45
            4L to 150.0   // Travel
        )
    }

    // ============================================================================
    // Month Period Definitions
    // ============================================================================
    object MonthPeriods {
        // March 2026 (UTC)
        const val MARCH_2026_YEAR = 2026
        const val MARCH_2026_MONTH = 2  // 0-indexed: March = 2
        const val MARCH_2026_START_MS = 1772323200000L  // March 1, 2026 00:00:00 UTC
        const val MARCH_2026_END_MS = 1775001599000L   // March 31, 2026 23:59:59 UTC

        // February 2026 (UTC)
        const val FEBRUARY_2026_YEAR = 2026
        const val FEBRUARY_2026_MONTH = 1  // 0-indexed: February = 1
        const val FEBRUARY_2026_START_MS = 1769904000000L  // Feb 1, 2026 00:00:00 UTC
        const val FEBRUARY_2026_END_MS = 1772323199000L    // Feb 28, 2026 23:59:59 UTC

        // April 2026 (reference "now") (UTC)
        const val APRIL_2026_YEAR = 2026
        const val APRIL_2026_MONTH = 3  // 0-indexed: April = 3
        const val APRIL_2026_START_MS = 1775001600000L  // April 1, 2026 00:00:00 UTC
    }

    // ============================================================================
    // Pace Calculation (as of April 1, 2026)
    // ============================================================================
    object PaceCalculation {
        const val DAYS_ELAPSED_MARCH = 31  // Full month elapsed
        const val DAYS_IN_MARCH = 31
        const val SIMPLE_MONTH_PROJECTED = 60.0  // March already complete
    }

    // ============================================================================
    // Floating Point Tolerance
    // ============================================================================
    const val DEFAULT_TOLERANCE = 0.01  // For Double comparisons
    const val PERCENTAGE_TOLERANCE = 0.1f  // For percentage/Float comparisons

    /**
     * Helper to check if a value is within expected range with tolerance.
     */
    fun isWithinTolerance(expected: Double, actual: Double, tolerance: Double = DEFAULT_TOLERANCE): Boolean {
        return kotlin.math.abs(expected - actual) <= tolerance
    }

    /**
     * Helper to check if a percentage is within expected range.
     */
    fun isPercentageWithinTolerance(expected: Float, actual: Float, tolerance: Float = PERCENTAGE_TOLERANCE): Boolean {
        return kotlin.math.abs(expected - actual) <= tolerance
    }
}
