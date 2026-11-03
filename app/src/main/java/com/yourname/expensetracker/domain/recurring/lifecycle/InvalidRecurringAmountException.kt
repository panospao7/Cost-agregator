package com.yourname.expensetracker.domain.recurring.lifecycle

/**
 * Thrown by [RecurringRuleLifecycleCoordinator] before any write when a recurring rule's amount
 * or currency fails the per-currency amount policy. Nothing has been written when this is thrown.
 *
 * The message is exactly [reasonCode], a bounded constant. It never carries the amount, the
 * currency, the merchant or any other user data.
 */
class InvalidRecurringAmountException(val reasonCode: String) : IllegalArgumentException(reasonCode) {
    companion object {
        const val NOT_POSITIVE = "RECURRING_AMOUNT_NOT_POSITIVE"
        const val ABOVE_CAP = "RECURRING_AMOUNT_ABOVE_CAP"
        const val CURRENCY_INVALID = "RECURRING_CURRENCY_INVALID"
        const val DIGITS_UNAVAILABLE = "RECURRING_CURRENCY_DIGITS_UNAVAILABLE"
    }
}
