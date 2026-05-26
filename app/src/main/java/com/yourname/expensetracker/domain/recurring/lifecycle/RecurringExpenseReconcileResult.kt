package com.yourname.expensetracker.domain.recurring.lifecycle

/**
 * Structured result of reconciling an expense with its recurring occurrence links.
 * Replaces opaque Boolean/Unit returns so callers can distinguish between:
 * - successful link/unlink/relink/snapshot update,
 * - no matching occurrence found,
 * - expense not eligible for recurring matching.
 */
sealed interface RecurringExpenseReconcileResult {
    /** Expense was successfully linked to a PLANNED occurrence. */
    data class Linked(
        val expenseId: Long,
        val occurrenceId: Long,
        val plannedExpenseId: Long?
    ) : RecurringExpenseReconcileResult

    /** Expense was unlinked from its previous occurrence (occurrence reopened to PLANNED). */
    data class Unlinked(
        val expenseId: Long,
        val occurrenceId: Long,
        val reason: String
    ) : RecurringExpenseReconcileResult

    /** Expense was unlinked from one occurrence and linked to a different one. */
    data class Relinked(
        val expenseId: Long,
        val oldOccurrenceId: Long,
        val newOccurrenceId: Long
    ) : RecurringExpenseReconcileResult

    /** Expense still matches its linked occurrence — payment snapshot updated. */
    data class UpdatedLinkedSnapshot(
        val expenseId: Long,
        val occurrenceId: Long
    ) : RecurringExpenseReconcileResult

    /** No matching PLANNED occurrence was found for this expense. */
    data class NoMatch(
        val expenseId: Long,
        val reason: String
    ) : RecurringExpenseReconcileResult

    /** Expense is not eligible for recurring matching (e.g. transfer, not-mine, deposit). */
    data class Skipped(
        val expenseId: Long,
        val reason: String
    ) : RecurringExpenseReconcileResult
}
