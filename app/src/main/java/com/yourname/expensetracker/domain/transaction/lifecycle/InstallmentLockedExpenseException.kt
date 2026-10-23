package com.yourname.expensetracker.domain.transaction.lifecycle

/**
 * INV-22 purchase lock: thrown by the lifecycle coordinator, before any write or event,
 * when an edit would change the amount, currency, transaction type or ownership of an
 * expense whose `spendingExclusionReason` is set; any future duplicate-merge operation must throw it too.
 *
 * [code] is the bounded constant below and is also the exception message, so it is safe
 * to log (no payload, amounts or merchant text). Merchant, date, category, notes and
 * receipt links stay editable.
 */
class InstallmentLockedExpenseException(val code: String = EXPENSE_LOCKED_BY_INSTALLMENT_PLAN) :
    IllegalStateException(code) {
    companion object {
        const val EXPENSE_LOCKED_BY_INSTALLMENT_PLAN = "EXPENSE_LOCKED_BY_INSTALLMENT_PLAN"
    }
}
