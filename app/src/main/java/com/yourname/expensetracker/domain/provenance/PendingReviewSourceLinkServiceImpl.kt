package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PR3: Default implementation of [PendingReviewSourceLinkService].
 *
 * Calls [PendingReviewSourcePayloadFactory] to get payloads, then writes
 * each payload via [SourceLinkWriter.linkTarget] targeting PENDING_REVIEW.
 *
 * Treats AlreadyExists as success. Fatal only on Rejected.
 * Does NOT open its own transaction — callers must wrap in a Room transaction.
 */
@Singleton
class PendingReviewSourceLinkServiceImpl @Inject constructor(
    private val sourceLinkWriter: SourceLinkWriter
) : PendingReviewSourceLinkService {

    override suspend fun linkSourcesForReview(
        review: PendingReview,
        reviewId: Long,
        sourceType: ExpenseSource,
        correlationId: String?,
        context: PendingReviewSourceContext
    ): PendingReviewSourceLinkResult {
        val payloads = PendingReviewSourcePayloadFactory.fromReview(
            review = review,
            sourceType = sourceType,
            context = context
        )

        if (payloads.isEmpty()) {
            Timber.d("No source links to write for pending review reviewId=%d", reviewId)
            return PendingReviewSourceLinkResult(
                attempted = 0,
                inserted = 0,
                alreadyExists = 0,
                failed = 0
            )
        }

        var inserted = 0
        var alreadyExists = 0
        var failed = 0
        val failures = mutableListOf<String>()

        for (payload in payloads) {
            val result = sourceLinkWriter.linkTarget(
                targetType = TargetEntityType.PENDING_REVIEW,
                targetId = reviewId,
                payload = payload,
                correlationId = correlationId
            )
            when (result) {
                is SourceLinkWriteResult.Inserted -> {
                    inserted++
                }
                is SourceLinkWriteResult.AlreadyExists -> {
                    alreadyExists++
                }
                is SourceLinkWriteResult.Rejected -> {
                    failed++
                    failures.add("Source link rejected: ${result.reason}")
                    Timber.w("Source link rejected for pending review reviewId=%d: %s", reviewId, result.reason)
                }
            }
        }

        return PendingReviewSourceLinkResult(
            attempted = payloads.size,
            inserted = inserted,
            alreadyExists = alreadyExists,
            failed = failed,
            failures = failures
        )
    }
}
