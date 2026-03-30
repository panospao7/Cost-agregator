package com.yourname.expensetracker.domain.model

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
    val status: PeriodStatus
)
