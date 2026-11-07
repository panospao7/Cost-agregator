package com.yourname.expensetracker.domain.transaction

import com.yourname.expensetracker.domain.core.money.MoneyAmount

/**
 * Refund netting (DR-05) value objects. In memory only; every value is a bounded code, an id,
 * a count or an amount. No merchant, notes or free text (INV-22).
 */

/** Bounded source of a link row. v1 has one value. */
enum class RefundLinkSource { USER_CONFIRMED }

/** Why a link was refused. Bounded diagnostic codes. */
enum class RefundLinkRefusal {
    REFUND_NOT_FOUND,
    PURCHASE_NOT_FOUND,
    SAME_ROW,
    NOT_A_DEPOSIT,
    NOT_A_PURCHASE,
    CURRENCY_MISMATCH,
    CURRENCY_DIGITS_UNAVAILABLE,
    INVALID_AMOUNT,
    NOTHING_TO_APPLY,
    REFUND_BEFORE_PURCHASE,
    REFUND_LINKED_ELSEWHERE,
    INSTALLMENT_MANAGED,
    GROUP_LINKED
}

/** Why a link or unlink write could not run (INV-23 and unexpected errors). */
enum class RefundWriteFailure { WRITE_BLOCKED, INTERNAL }

/** Why a refund read is unavailable (INV-17). Never rendered as an empty or zero value. */
enum class RefundReadFailure { READ_BLOCKED, READ_FAILED }

sealed interface RefundLinkResult {
    data class Linked(val linkId: Long, val applied: MoneyAmount) : RefundLinkResult
    data class AlreadyLinked(val linkId: Long) : RefundLinkResult
    data class Refused(val code: RefundLinkRefusal) : RefundLinkResult
    data class Failed(val code: RefundWriteFailure) : RefundLinkResult
}

sealed interface RefundUnlinkResult {
    data class Unlinked(val linkId: Long) : RefundUnlinkResult
    data object NotLinked : RefundUnlinkResult
    data class Failed(val code: RefundWriteFailure) : RefundUnlinkResult
}

/**
 * One ranked purchase candidate for a refund. Display text (merchant, category) is read live by
 * the UI and is never copied in.
 */
data class RefundCandidate(
    val purchaseId: Long,
    val purchaseDate: Long,
    val amount: MoneyAmount,
    val remaining: MoneyAmount,
    val exactAmountMatch: Boolean,
    val merchantMatch: Boolean,
    val dayDelta: Int
)

sealed interface RefundCandidates {
    data class Available(val items: List<RefundCandidate>) : RefundCandidates
    data class Unavailable(val reason: RefundReadFailure) : RefundCandidates
}

/** One link seen from [RefundLinks.expenseId]. [counterpartId] is the other row of the link. */
data class RefundLinkInfo(
    val linkId: Long,
    val counterpartId: Long,
    val appliedAmount: MoneyAmount,
    val createdAt: Long
)

sealed interface RefundLinks {
    data class Available(val expenseId: Long, val links: List<RefundLinkInfo>) : RefundLinks
    data class Unavailable(val reason: RefundReadFailure) : RefundLinks
}
