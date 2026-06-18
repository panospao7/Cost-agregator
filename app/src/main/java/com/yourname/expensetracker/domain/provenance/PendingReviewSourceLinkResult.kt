package com.yourname.expensetracker.domain.provenance

/**
 * PR3: Result of a [PendingReviewSourceLinkService.linkSourcesForReview] call.
 */
data class PendingReviewSourceLinkResult(
    val attempted: Int,
    val inserted: Int,
    val alreadyExists: Int,
    val failed: Int,
    val failures: List<String> = emptyList()
) {
    val hasFatalFailure: Boolean get() = failed > 0
}
