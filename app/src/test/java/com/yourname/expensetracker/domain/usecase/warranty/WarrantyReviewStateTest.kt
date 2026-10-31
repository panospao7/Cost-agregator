package com.yourname.expensetracker.domain.usecase.warranty

import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ai-fixes INV-10 / AE-16: [WarrantyReviewState.of] is the single review-state derivation.
 */
class WarrantyReviewStateTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun warranty(
        status: WarrantyStatus,
        needsReview: Boolean,
        endDate: Long
    ) = Warranty(
        id = 1L,
        receiptId = 1L,
        productName = "Synthetic Product",
        merchantName = "Synthetic Merchant",
        purchaseDate = now - 100 * day,
        warrantyDurationMonths = 12,
        warrantyEndDate = endDate,
        status = status,
        needsReview = needsReview
    )

    @Test
    fun `needsReview false is NOT_IN_REVIEW for every status`() {
        for (status in WarrantyStatus.values()) {
            assertEquals(
                "status=$status",
                WarrantyReviewState.NOT_IN_REVIEW,
                WarrantyReviewState.of(warranty(status, needsReview = false, endDate = now + day), now)
            )
            assertEquals(
                "status=$status past end",
                WarrantyReviewState.NOT_IN_REVIEW,
                WarrantyReviewState.of(warranty(status, needsReview = false, endDate = now - day), now)
            )
        }
    }

    @Test
    fun `claimed and transferred are NOT_IN_REVIEW even when needsReview is true`() {
        for (status in listOf(WarrantyStatus.CLAIMED, WarrantyStatus.TRANSFERRED)) {
            assertEquals(
                "status=$status",
                WarrantyReviewState.NOT_IN_REVIEW,
                WarrantyReviewState.of(warranty(status, needsReview = true, endDate = now - day), now)
            )
        }
    }

    @Test
    fun `pending with a future end date is AWAITING_REVIEW`() {
        assertEquals(
            WarrantyReviewState.AWAITING_REVIEW,
            WarrantyReviewState.of(warranty(WarrantyStatus.PENDING_REVIEW, needsReview = true, endDate = now + 5 * day), now)
        )
    }

    @Test
    fun `pending already past its end date is EXPIRED_UNCONFIRMED before reconciliation has run`() {
        // AE-16: still PENDING_REVIEW in the DB, endDate = now - 1d.
        assertEquals(
            WarrantyReviewState.EXPIRED_UNCONFIRMED,
            WarrantyReviewState.of(warranty(WarrantyStatus.PENDING_REVIEW, needsReview = true, endDate = now - day), now)
        )
    }

    @Test
    fun `reconciled expired pending is EXPIRED_UNCONFIRMED regardless of end date`() {
        assertEquals(
            WarrantyReviewState.EXPIRED_UNCONFIRMED,
            WarrantyReviewState.of(warranty(WarrantyStatus.EXPIRED, needsReview = true, endDate = now - day), now)
        )
        assertEquals(
            WarrantyReviewState.EXPIRED_UNCONFIRMED,
            WarrantyReviewState.of(warranty(WarrantyStatus.EXPIRED, needsReview = true, endDate = now + day), now)
        )
    }

    @Test
    fun `end date equal to now is not yet past and stays AWAITING_REVIEW`() {
        // Same predicate as the expiry sweep: warrantyEndDate < now.
        assertEquals(
            WarrantyReviewState.AWAITING_REVIEW,
            WarrantyReviewState.of(warranty(WarrantyStatus.PENDING_REVIEW, needsReview = true, endDate = now), now)
        )
        assertEquals(
            WarrantyReviewState.EXPIRED_UNCONFIRMED,
            WarrantyReviewState.of(warranty(WarrantyStatus.PENDING_REVIEW, needsReview = true, endDate = now - 1L), now)
        )
    }

    @Test
    fun `defensive ACTIVE with needsReview is handled as awaiting or expired by end date`() {
        // INV-14: ACTIVE && needsReview should not exist; handled defensively.
        assertEquals(
            WarrantyReviewState.AWAITING_REVIEW,
            WarrantyReviewState.of(warranty(WarrantyStatus.ACTIVE, needsReview = true, endDate = now + day), now)
        )
        assertEquals(
            WarrantyReviewState.EXPIRED_UNCONFIRMED,
            WarrantyReviewState.of(warranty(WarrantyStatus.ACTIVE, needsReview = true, endDate = now - day), now)
        )
    }
}
