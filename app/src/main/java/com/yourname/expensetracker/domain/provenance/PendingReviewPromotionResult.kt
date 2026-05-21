package com.yourname.expensetracker.domain.provenance

/**
 * PR3: Result of promoting pending-review source links to an expense.
 */
data class PendingReviewPromotionResult(
    val attempted: Int,
    val inserted: Int,
    val alreadyExists: Int,
    val failed: Int,
    val failures: List<String> = emptyList()
) {
    val hasFatalFailure: Boolean get() = failed > 0
}
