package com.yourname.expensetracker.domain.model

data class CategoryBreakdown(
    val category: CategoryInfo,
    val totalAmount: Double,
    val transactionCount: Int,
    val percentageOfTotal: Double,
    val periodLabel: String
) {
    init {
        require(totalAmount.isFinite()) { "totalAmount must be finite" }
        require(transactionCount >= 0) { "transactionCount cannot be negative" }
        require(percentageOfTotal.isFinite() && percentageOfTotal in 0.0..100.0) {
            "percentageOfTotal must be between 0 and 100"
        }
        require(periodLabel.isNotBlank()) { "periodLabel cannot be blank" }
    }
}
