package com.yourname.expensetracker.domain.usecase.warranty

import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus

/**
 * ai-fixes INV-10: the single derivation of a warranty's review state.
 *
 * Derived on read, never persisted. "Expired unconfirmed" is NOT a persisted status: it is
 * `status = EXPIRED && needsReview = true` (or a row that is past its end date but not yet
 * reconciled). Every UI count, filter and badge about review must use [of]; no ViewModel or
 * screen may inspect `needsReview` / `status` directly for review purposes.
 *
 * "Past end" uses `warrantyEndDate < now`, the same predicate as the expiry sweep
 * (`WarrantyDao.markExpiredWarranties`), so ACTIVE and pending rows share one expiry semantics.
 */
enum class WarrantyReviewState {
    /** Not under review (`needsReview = false`), or CLAIMED / TRANSFERRED. */
    NOT_IN_REVIEW,

    /** `needsReview = true` and either EXPIRED or already past its end date. */
    EXPIRED_UNCONFIRMED,

    /** `needsReview = true`, PENDING_REVIEW (or defensively ACTIVE), not yet past its end date. */
    AWAITING_REVIEW;

    companion object {
        /**
         * Evaluated in order:
         * 1. `needsReview = false`, or status CLAIMED / TRANSFERRED -> [NOT_IN_REVIEW]
         * 2. status EXPIRED, or `warrantyEndDate < now`            -> [EXPIRED_UNCONFIRMED]
         * 3. otherwise (PENDING_REVIEW / defensively ACTIVE)       -> [AWAITING_REVIEW]
         */
        fun of(warranty: Warranty, now: Long): WarrantyReviewState = when {
            !warranty.needsReview ||
                warranty.status == WarrantyStatus.CLAIMED ||
                warranty.status == WarrantyStatus.TRANSFERRED -> NOT_IN_REVIEW

            warranty.status == WarrantyStatus.EXPIRED || warranty.warrantyEndDate < now -> EXPIRED_UNCONFIRMED

            else -> AWAITING_REVIEW
        }
    }
}
