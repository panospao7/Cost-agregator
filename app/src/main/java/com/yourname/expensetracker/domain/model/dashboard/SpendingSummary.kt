package com.yourname.expensetracker.domain.model.dashboard

data class SpendingSummary(
    val totalSpent: Double,
    val previousTotalSpent: Double?,
    val changePercent: Float?,
    val dailyHistory: List<Float>,
    val previousDailyHistory: List<Float>,
    val transactionCount: Int
)
