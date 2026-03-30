package com.yourname.expensetracker.domain.model

data class CategoryInfo(
    val id: Long,
    val name: String,
    val icon: String,
    val color: String,
    val isIncome: Boolean = false
)
