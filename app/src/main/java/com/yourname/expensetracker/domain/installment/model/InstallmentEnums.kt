package com.yourname.expensetracker.domain.installment.model

/**
 * Pure domain enums of the loan-installments feature (domain spec sections 2.1, 2.2, 3).
 *
 * Every enum here is persisted as its [name] in a bounded TEXT column (no type converter), the
 * same convention as `ExpenseSource`. The values therefore must match the strings documented on
 * `InstallmentPlan` and `InstallmentEntry` exactly. Use `fromCodeOrNull` on untrusted or stored
 * text: an unknown value yields `null` and the caller fails closed (it never guesses a status).
 *
 * No Android, Room or DAO types and no clock access live in this package.
 */

/** `installment_plans.kind`. */
enum class InstallmentPlanKind {
    PURCHASE_ATTACHED,
    STANDALONE;

    companion object {
        fun fromCodeOrNull(code: String?): InstallmentPlanKind? = entries.firstOrNull { it.name == code }
    }
}

/** `installment_plans.splitMethod`. STANDALONE plans are always [FIXED_PAYMENT]. */
enum class InstallmentSplitMethod {
    EQUAL_SPLIT,
    FIXED_PAYMENT;

    companion object {
        fun fromCodeOrNull(code: String?): InstallmentSplitMethod? = entries.firstOrNull { it.name == code }
    }
}

/** `installment_plans.frequency`. v1 supports MONTHLY only (OD-8). */
enum class InstallmentFrequency {
    MONTHLY;

    companion object {
        fun fromCodeOrNull(code: String?): InstallmentFrequency? = entries.firstOrNull { it.name == code }
    }
}

/** `installment_plans.residualPlacement` (OD-6). Recorded so regeneration is reproducible. */
enum class InstallmentResidualPlacement {
    FIRST,
    LAST;

    companion object {
        fun fromCodeOrNull(code: String?): InstallmentResidualPlacement? = entries.firstOrNull { it.name == code }
    }
}

/** `installment_plans.fulfilmentMode` (spec Q3, OD-4, OD-5). */
enum class InstallmentFulfilmentMode {
    AUTO_RECORD,
    CONFIRM;

    companion object {
        fun fromCodeOrNull(code: String?): InstallmentFulfilmentMode? = entries.firstOrNull { it.name == code }
    }
}

/** `installment_plans.status` (spec 3.1). */
enum class InstallmentPlanStatus {
    ACTIVE,
    COMPLETED,

    /** Stop. */
    CANCELLED,

    /** Refund / return ("everything reverses"). */
    REFUNDED,

    /** Early payoff. */
    PAID_OFF;

    /** CANCELLED, REFUNDED and PAID_OFF accept no transition except delete (INV-20). */
    val isTerminal: Boolean
        get() = this == CANCELLED || this == REFUNDED || this == PAID_OFF

    companion object {
        fun fromCodeOrNull(code: String?): InstallmentPlanStatus? = entries.firstOrNull { it.name == code }
    }
}

/** `installment_entries.kind`. At most one PAYOFF per plan. */
enum class InstallmentEntryKind {
    REGULAR,
    PAYOFF;

    companion object {
        fun fromCodeOrNull(code: String?): InstallmentEntryKind? = entries.firstOrNull { it.name == code }
    }
}

/**
 * `installment_entries.status` (spec 3.2). [PAID_BEFORE_TRACKING] (AMD-1, INV-32, OQ-18) is a
 * history-only status: it never has a linked expense and adds 0 to every spending aggregate.
 */
enum class InstallmentEntryStatus {
    SCHEDULED,
    PAID,
    SKIPPED,
    CANCELLED,
    REVERSED,
    PAID_BEFORE_TRACKING;

    companion object {
        fun fromCodeOrNull(code: String?): InstallmentEntryStatus? = entries.firstOrNull { it.name == code }
    }
}

/** `installment_entries.linkOrigin`. Set together with `linkedExpenseId`. */
enum class InstallmentLinkOrigin {
    AUTO_RECORDED,
    USER_CONFIRMED,
    USER_LINKED;

    companion object {
        fun fromCodeOrNull(code: String?): InstallmentLinkOrigin? = entries.firstOrNull { it.name == code }
    }
}

/**
 * Derived display state of a SCHEDULED entry (spec 3.2). Never stored. PAID_BEFORE_TRACKING gets
 * no display state (it is shown as "k paid before tracking").
 */
enum class InstallmentDueState {
    UPCOMING,
    DUE,
    OVERDUE
}

/**
 * Plan currencies supported in v1 (B-1): EUR and USD, both with 2 minor-unit digits. Any other
 * currency is refused with [InstallmentRefusalCode.UNSUPPORTED_CURRENCY]. Totals are always kept
 * per currency and are never summed across currencies.
 */
enum class InstallmentCurrency(val code: String) {
    EUR("EUR"),
    USD("USD");

    companion object {
        /** Exact (case-sensitive) match on the stored ISO 4217 code; anything else is unsupported. */
        fun fromCodeOrNull(code: String?): InstallmentCurrency? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Bounded reason codes stored in `statusReason` on plans and entries (spec 2.1, 2.2, 3.x).
 * Only enum names are ever persisted or logged: never free text.
 */
enum class InstallmentStatusReason {
    // Plan-level reasons.
    USER_STOP,
    USER_REFUND,
    USER_PAYOFF,
    AUTO_COMPLETED,

    // Entry-level reasons.
    // Skip reasons are bounded to exactly these three codes, no free text (OQ-20).
    USER_SKIP_LENDER_HOLIDAY,
    USER_SKIP_WAIVED,
    USER_SKIP_OTHER,
    PLAN_STOPPED,
    PLAN_REFUNDED,
    PAID_OFF_EARLY,
    SHORTENED,

    // One reason for every detach cause: the linked expense was deleted, or its currency or type
    // changed (spec 3.2).
    EXPENSE_REMOVED;

    /** True for the three bounded codes a user may pick when skipping an entry. */
    val isUserSkipReason: Boolean
        get() = this == USER_SKIP_LENDER_HOLIDAY || this == USER_SKIP_WAIVED || this == USER_SKIP_OTHER

    companion object {
        fun fromCodeOrNull(code: String?): InstallmentStatusReason? = entries.firstOrNull { it.name == code }
    }
}

/**
 * Bounded `adjustReason` values for a manual entry adjustment (spec 7.4 E1, INV-19). The spec
 * leaves the value set open ("bounded `adjustReason`"); this set is the minimum the edit
 * operations need and can be extended by the plan coordinator slice.
 */
enum class InstallmentAdjustReason {
    USER_AMOUNT,
    USER_DATE,
    USER_AMOUNT_AND_DATE,
    RESPREAD,
    DUE_DAY_CHANGED;

    companion object {
        fun fromCodeOrNull(code: String?): InstallmentAdjustReason? = entries.firstOrNull { it.name == code }
    }
}

/**
 * Why auto-record did not create an expense for a SCHEDULED entry on this run. This is a
 * deferral, not a refusal: the entry stays SCHEDULED (INV-13).
 */
enum class InstallmentDeferralReason {
    AUTO_RECORD_DEFERRED_CANDIDATE
}
