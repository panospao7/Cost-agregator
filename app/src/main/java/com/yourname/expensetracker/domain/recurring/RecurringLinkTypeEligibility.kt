package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.data.database.entity.TransactionType

/**
 * The SINGLE layer-1 rule of the recurring lifecycle: which expenses (by ownership flag and
 * transaction type) may be linked to, or stay linked to, a recurring occurrence. The layer-2 rule
 * (INV-5 installment exclusivity) stays with [RecurringInstallmentEligibility].
 *
 * Two forms, because creating a link and keeping one are different decisions (owner item 37(b),
 * decision 46(a)):
 *
 * - [mayCreateLink]: NEW links (auto-link, manual link, generation-time matching through
 *   [OccurrenceConflictResolver], and the claim path). A not-mine expense, a TRANSFER, a DEPOSIT and
 *   an UNKNOWN expense (an unclassified row is not a confirmed bill payment) are never linked.
 * - [mayKeepLink]: RE-VALIDATION of an EXISTING link (the reconcile "still matches" branch, which the
 *   bulk-update reconcile also runs through). It excludes only a not-mine expense, a TRANSFER and a
 *   DEPOSIT, so an existing link on an UNKNOWN expense is kept and a bulk edit never reverts a PAID
 *   occurrence to PLANNED.
 *
 * Pure: no I/O, no state.
 */
object RecurringLinkTypeEligibility {

    /** True when an expense of [type] with the given ownership flag may be given a NEW recurring link. */
    fun mayCreateLink(type: TransactionType, isNotMine: Boolean): Boolean =
        !isNotMine &&
            type != TransactionType.TRANSFER &&
            type != TransactionType.DEPOSIT &&
            type != TransactionType.UNKNOWN

    /** True when an EXISTING recurring link of an expense of [type] may be kept (UNKNOWN is kept, R2). */
    fun mayKeepLink(type: TransactionType, isNotMine: Boolean): Boolean =
        !isNotMine &&
            type != TransactionType.TRANSFER &&
            type != TransactionType.DEPOSIT
}
