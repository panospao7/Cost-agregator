package com.yourname.expensetracker.domain.model

data class CategoryInfo(
    val id: Long,
    val name: String,
    val icon: String,
    val color: String,
    val isIncome: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "name cannot be blank" }
        require(icon.isNotBlank()) { "icon cannot be blank" }
        require(color.isNotBlank()) { "color cannot be blank" }
    }
}
