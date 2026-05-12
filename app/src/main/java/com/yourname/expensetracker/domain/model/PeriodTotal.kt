package com.yourname.expensetracker.domain.model

import com.yourname.expensetracker.domain.analytics.AnalyticsDataQuality

enum class PeriodType { YEAR, MONTH, WEEK, DAY }

enum class PeriodStatus { UNDER_AVERAGE, OVER_AVERAGE, CURRENT, NO_DATA }

data class PeriodTotal(
    val periodLabel: String,
    val periodKey: String,
    val totalAmount: Double,
    val transactionCount: Int,
    val periodType: PeriodType,
    val startDateMs: Long,
    val endDateMs: Long,
    val status: PeriodStatus,
    val dataQuality: AnalyticsDataQuality? = null,
    /** E2-005: Propagated from MoneyAggregate.isPartial — true when conversion failures exist. */
    val isPartial: Boolean = false,
    /** E2-005: Propagated from MoneyAggregate.warningMessage — describes excluded transactions. */
    val warningMessage: String? = null
) {
    init {
        require(periodLabel.isNotBlank()) { "periodLabel cannot be blank" }
        require(periodKey.isNotBlank()) { "periodKey cannot be blank" }
        require(totalAmount.isFinite()) { "totalAmount must be finite" }
        require(transactionCount >= 0) { "transactionCount cannot be negative" }
        require(endDateMs >= startDateMs) { "endDateMs must be greater than or equal to startDateMs" }
    }
}
