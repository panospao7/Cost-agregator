package com.yourname.expensetracker.data.database.model

data class DashboardWidgetConfig(
    val id: String,
    val order: Int,
    val isVisible: Boolean = true
)
