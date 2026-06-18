package com.yourname.expensetracker.domain.transaction

/**
 * Lifecycle-aware port for assigning expense categories.
 *
 * Replaces direct `expenseDao.updateCategory()` calls from receipt lifecycle
 * code so that category assignment writes proper transaction events,
 * respects the write barrier, and is auditable.
 *
 * P3-BLOCKER-09 / P2-18: Prevents bypassing transaction lifecycle.
 */
interface ExpenseCategoryAssignmentPort {
    suspend fun assignCategoryIfUnset(
        expenseId: Long,
        categoryId: Long,
        source: String,
        correlationId: String? = null
    ): CategoryAssignmentOutcome
}

sealed interface CategoryAssignmentOutcome {
    data object Assigned : CategoryAssignmentOutcome
    data object SkippedAlreadySet : CategoryAssignmentOutcome
    data object SkippedExpenseMissing : CategoryAssignmentOutcome
    data class Failed(val reason: String) : CategoryAssignmentOutcome
}
