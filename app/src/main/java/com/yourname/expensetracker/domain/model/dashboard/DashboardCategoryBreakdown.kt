package com.yourname.expensetracker.domain.model.dashboard

data class DashboardCategoryBreakdown(
    val categoryId: Long,
    val categoryName: String,
    val categoryIcon: String,
    val categoryColor: String,
    val amount: Double,
    val percentage: Double,
    val changeFromLastPeriod: Double,
    val isPartial: Boolean = false,
    val warningMessage: String? = null
)
