package com.yourname.expensetracker.domain.model

data class PeriodDrillDownState(
    val currentLevel: PeriodType,
    val selectedPeriod: PeriodTotal?,
    val parentPeriod: PeriodTotal?,
    val periodTotals: List<PeriodTotal>,
    val categoryBreakdown: List<CategoryBreakdown>,
    val isLoading: Boolean = false,
    val error: String? = null
)
