package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.domain.transaction.ExpenseSource

/**
 * PR3: Promotes source links from a [PendingReview] to the approved [Expense].
 *
 * When a pending review is approved and an expense is created, all source links
 * that were attached to the pending review are promoted (copied) to the expense,
 * with role transformations and additional promotion metadata.
 */
interface PendingReviewSourceLinkPromoter {
    suspend fun promotePendingReviewLinksToExpense(
        pendingReviewId: Long,
        expenseId: Long,
        correlationId: String?,
        source: ExpenseSource = ExpenseSource.REVIEW_APPROVAL
    ): PendingReviewPromotionResult
}
