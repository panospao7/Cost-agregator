package com.yourname.expensetracker.domain.transaction.lifecycle

/**
 * Refund-netting INV-12 edit lock: thrown by the lifecycle coordinator, before any write or
 * event, when an edit would change the amount, currency or transaction type of an expense that
 * carries a refund link (`refundNettedAmount > 0`). Unlink the refund first.
 *
 * [code] is the bounded constant below and is also the exception message, so it is safe to log
 * (no payload, amounts or merchant text). Merchant, date, category, notes and ownership stay
 * editable.
 */
class RefundLinkedExpenseException(val code: String = EXPENSE_LOCKED_BY_REFUND_LINK) :
    IllegalStateException(code) {
    companion object {
        const val EXPENSE_LOCKED_BY_REFUND_LINK = "EXPENSE_LOCKED_BY_REFUND_LINK"
    }
}
