package com.yourname.expensetracker.domain.transaction.lifecycle

/**
 * R18: thrown/returned when a delete or money edit targets an expense that is linked
 * to a shared group. No single-group-expense delete/sync path exists, so the
 * lifecycle coordinator fails closed before any write or event.
 *
 * [code] is one of the bounded constants below and is also the exception message,
 * so it is safe to log (no payload, amounts, or merchant text).
 */
class GroupLinkedExpenseException(val code: String) : IllegalStateException(code) {
    companion object {
        const val EXPENSE_DELETE_BLOCKED_GROUP_LINKED = "EXPENSE_DELETE_BLOCKED_GROUP_LINKED"
        const val EXPENSE_EDIT_BLOCKED_GROUP_LINKED = "EXPENSE_EDIT_BLOCKED_GROUP_LINKED"
    }
}
