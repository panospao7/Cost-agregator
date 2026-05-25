package com.yourname.expensetracker.domain.transaction

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleEventWriter
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleEvent
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P3-BLOCKER-09 / PR 5: Lifecycle-aware category assignment that replaces
 * direct [ExpenseDao.updateCategory] calls from [ReceiptLinkService].
 */
@Singleton
class DefaultExpenseCategoryAssignmentService @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider,
    private val eventWriter: ReceiptLifecycleEventWriter
) : ExpenseCategoryAssignmentPort {

    override suspend fun assignCategoryIfUnset(
        expenseId: Long,
        categoryId: Long,
        source: String,
        correlationId: String?
    ): CategoryAssignmentOutcome {
        try {
            writeBarrier.checkWritesAllowed("ExpenseCategoryAssignment.assignCategory")
            val expense = expenseDao.getById(expenseId)
                ?: return CategoryAssignmentOutcome.SkippedExpenseMissing
            if (expense.categoryId != null)
                return CategoryAssignmentOutcome.SkippedAlreadySet

            val now = timeProvider.now()
            expenseDao.updateCategory(expenseId, categoryId)

            eventWriter.write(ReceiptLifecycleEvent(
                receiptId = 0L,
                sourceType = "EXPENSE",
                documentType = "EXPENSE",
                eventType = "EXPENSE_CATEGORY_ASSIGNED",
                newStatus = null,
                actor = "system:category_assignment",
                message = "Category $categoryId assigned to expense $expenseId (source=$source)"
            ))
            return CategoryAssignmentOutcome.Assigned
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Category assignment failed for expense %d", expenseId)
            return CategoryAssignmentOutcome.Failed(e.message ?: "Unknown error")
        }
    }
}
