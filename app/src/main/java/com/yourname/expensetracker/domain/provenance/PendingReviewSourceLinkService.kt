package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.domain.transaction.ExpenseSource

/**
 * PR3: Service for writing source links when a [PendingReview] is created.
 *
 * Called from the notification processing pipeline and other places where
 * pending reviews are persisted, to record review provenance before promotion.
 */
interface PendingReviewSourceLinkService {
    suspend fun linkSourcesForReview(
        review: PendingReview,
        reviewId: Long,
        sourceType: ExpenseSource = ExpenseSource.REVIEW_APPROVAL,
        correlationId: String?,
        context: PendingReviewSourceContext = PendingReviewSourceContext.empty()
    ): PendingReviewSourceLinkResult
}
