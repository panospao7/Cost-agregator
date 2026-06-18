package com.yourname.expensetracker.domain.ai.model

data class FinancialQueryDataQuality(
    val isPartial: Boolean = false,
    val warnings: List<String> = emptyList(),
    val excludedCount: Int = 0,
    val staleRateCount: Int = 0,
    val missingRateCount: Int = 0
)
