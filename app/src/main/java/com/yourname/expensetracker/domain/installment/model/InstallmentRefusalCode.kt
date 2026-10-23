package com.yourname.expensetracker.domain.installment.model

import com.yourname.expensetracker.domain.schedule.ScheduleLinkRefusal

/**
 * Bounded refusal codes of the installment feature (domain spec 7.0, 3.1, 7.4, 7.5, INV-5,
 * INV-16, INV-32, INV-33). A refusal writes nothing. Only the enum name may be logged, persisted
 * or shown as a fixed string: never plan content, amounts, dates or identifiers.
 */
enum class InstallmentRefusalCode {
    // 7.0 declaration preconditions on the purchase expense.
    NOT_A_SPENDING_EXPENSE,
    NOT_MINE,
    SHARED_NOT_SUPPORTED,
    SPLIT_NOT_SUPPORTED,
    ALREADY_DECLARED,
    LINKED_ELSEWHERE,

    // 7.0 plan inputs.
    INVALID_COUNT,
    INVALID_AMOUNT,
    UNSUPPORTED_CURRENCY,
    FIRST_DUE_BEFORE_PURCHASE,
    AMOUNT_TOO_SMALL_FOR_COUNT,
    INVALID_PAID_BEFORE_TRACKING,

    // 7.0 / INV-33 standalone category.
    CATEGORY_REQUIRED,
    CATEGORY_NOT_FOUND,

    // INV-16 / 7.4 currency integrity.
    CURRENCY_MISMATCH,
    CURRENCY_LOCKED,

    // 7.5 delete rule.
    PLAN_HAS_PAYMENTS,

    // Transition table: the requested change is not legal from the current state (3.1, 3.2, INV-20).
    ILLEGAL_TRANSITION,

    // Additions beyond the 7.0 list, needed so the pure layer never returns an unbounded value.
    /** A date input is outside the supported civil-date range. */
    INVALID_DATE,

    /** The purchase expense does not exist (checked inside the declaration transaction). */
    PURCHASE_NOT_FOUND;

    companion object {
        fun fromCodeOrNull(code: String?): InstallmentRefusalCode? = entries.firstOrNull { it.name == code }

        /** One-to-one mapping from the schedule-exclusivity policy's own refusal enum. */
        fun fromScheduleRefusal(refusal: ScheduleLinkRefusal): InstallmentRefusalCode =
            when (refusal) {
                ScheduleLinkRefusal.NOT_A_SPENDING_EXPENSE -> NOT_A_SPENDING_EXPENSE
                ScheduleLinkRefusal.NOT_MINE -> NOT_MINE
                ScheduleLinkRefusal.SHARED_NOT_SUPPORTED -> SHARED_NOT_SUPPORTED
                ScheduleLinkRefusal.SPLIT_NOT_SUPPORTED -> SPLIT_NOT_SUPPORTED
                ScheduleLinkRefusal.ALREADY_DECLARED -> ALREADY_DECLARED
                ScheduleLinkRefusal.LINKED_ELSEWHERE -> LINKED_ELSEWHERE
                ScheduleLinkRefusal.CURRENCY_MISMATCH -> CURRENCY_MISMATCH
            }
    }
}
