package com.yourname.expensetracker.domain.transaction

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P3-BLOCKER-09 / PR 5: Lifecycle-aware category assignment that replaces
 * direct [ExpenseDao.updateCategory] calls from [ReceiptLinkService].
 *
 * P3-EB0-03: Uses [TransactionEvent] (NOT ReceiptLifecycleEvent) to avoid
 * writing invalid receiptId=0 events.
 */
@Singleton
class DefaultExpenseCategoryAssignmentService @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider,
    private val transactionEventDao: TransactionEventDao
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

            transactionEventDao.insert(TransactionEvent(
                expenseId = expenseId,
                eventType = "EXPENSE_CATEGORY_ASSIGNED",
                source = source,
                actor = "system:category_assignment",
                occurredAt = now,
                dedupeKey = null,
                duplicateExpenseId = null,
                beforeSnapshot = null,
                afterSnapshot = null,
                metadata = null,
                reason = "Category $categoryId assigned",
                correlationId = correlationId
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
