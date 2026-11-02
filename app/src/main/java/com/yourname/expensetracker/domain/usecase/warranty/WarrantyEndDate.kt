package com.yourname.expensetracker.domain.usecase.warranty

import com.yourname.expensetracker.domain.util.TimePeriodUtils

/**
 * Single owner of the manual/edit warranty end-date arithmetic (warranty-edit INV-1, INV-2).
 *
 * Pure: no clock, no I/O. Day boundaries come from [TimePeriodUtils], which reads the system
 * zone at call time (INV-17). The arithmetic is the one `addManualWarranty` has always used;
 * the receipt-path copies (use case, extractor) are deliberately not touched (O-10).
 */
object WarrantyEndDate {

    /**
     * Exclusive (half-open) end of a warranty: the start of the local day after the day on which
     * [months] months added to the start of [purchaseDay]'s local day land. Month-end dates clamp
     * (Jan 31 + 1 month -> end Mar 1 00:00, the last covered day being Feb 28).
     *
     * @param purchaseDay any instant (epoch millis) on the purchase's local day
     * @param months warranty duration in whole months
     */
    fun exclusiveEnd(purchaseDay: Long, months: Int): Long {
        val purchaseStart = TimePeriodUtils.getStartOfDay(purchaseDay)
        val endDateMidnight = TimePeriodUtils.addMonths(purchaseStart, months)
        return TimePeriodUtils.getEndOfDay(endDateMidnight)
    }

    /**
     * Result of [resolveEditDates].
     *
     * @property purchaseDate value to persist as the purchase date
     * @property warrantyEndDate value to persist as the (exclusive) end date
     * @property endChanged true when [warrantyEndDate] differs from the stored end date
     */
    data class ResolvedDates(
        val purchaseDate: Long,
        val warrantyEndDate: Long,
        val endChanged: Boolean
    )

    /**
     * INV-2: recompute dates only when the chosen local purchase day differs from the local day of
     * the stored purchase date (current zone) or the months differ. Otherwise the stored purchase
     * date and end date are returned untouched (legacy/non-canonical rows and zone changes must
     * not drift).
     */
    fun resolveEditDates(
        storedPurchase: Long,
        storedEnd: Long,
        storedMonths: Int,
        chosenPurchase: Long,
        chosenMonths: Int
    ): ResolvedDates {
        val sameDay = TimePeriodUtils.getStartOfDay(storedPurchase) ==
            TimePeriodUtils.getStartOfDay(chosenPurchase)
        if (sameDay && storedMonths == chosenMonths) {
            return ResolvedDates(
                purchaseDate = storedPurchase,
                warrantyEndDate = storedEnd,
                endChanged = false
            )
        }
        val newEnd = exclusiveEnd(chosenPurchase, chosenMonths)
        return ResolvedDates(
            purchaseDate = TimePeriodUtils.getStartOfDay(chosenPurchase),
            warrantyEndDate = newEnd,
            endChanged = newEnd != storedEnd
        )
    }
}
