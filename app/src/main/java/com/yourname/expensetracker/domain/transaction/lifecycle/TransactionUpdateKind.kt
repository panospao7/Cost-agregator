package com.yourname.expensetracker.domain.transaction.lifecycle

enum class TransactionUpdateKind {
    FULL,
    CATEGORY_ONLY,
    LOCATION_ONLY,
    BUSINESS_FLAGS_ONLY,
    MERCHANT,
    TYPE,
    TRANSFER_DETAILS,
    AMOUNT,
    DATE,
    CURRENCY,
    OWNERSHIP,
    PAYMENT_CORE,

    /** The spending-exclusion flag changed (installment purchase / reversal); nothing else did. */
    SPENDING_INCLUSION;

    /** Whether this update kind can affect recurring occurrence matching. */
    fun affectsRecurringMatch(): Boolean = when (this) {
        FULL, MERCHANT, TYPE, TRANSFER_DETAILS, AMOUNT, DATE, CURRENCY, OWNERSHIP, PAYMENT_CORE -> true
        CATEGORY_ONLY, LOCATION_ONLY, BUSINESS_FLAGS_ONLY, SPENDING_INCLUSION -> false
    }

    /** Whether this update kind can change the expense category. */
    fun involvesCategoryChange(): Boolean = when (this) {
        FULL, CATEGORY_ONLY -> true
        else -> false
    }
}
