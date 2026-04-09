package com.yourname.expensetracker.domain.model.dashboard

data class SpendingSummary(
    val totalSpent: Double,
    val previousTotalSpent: Double?,
    val changePercent: Double?,
    val dailyHistory: List<Double>,
    val previousDailyHistory: List<Double>,
    val transactionCount: Int
)
