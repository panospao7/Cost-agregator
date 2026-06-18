package com.yourname.expensetracker.domain.transaction

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultExpenseCategoryAssignmentService @Inject constructor(
    private val database: AppDatabase,
    private val expenseDao: ExpenseDao,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider,
    private val transactionEventDao: TransactionEventDao
) : ExpenseCategoryAssignmentPort {

    override suspend fun assignCategoryIfUnset(
        expenseId: Long, categoryId: Long, source: String, correlationId: String?
    ): CategoryAssignmentOutcome {
        return try {
            writeBarrier.checkWritesAllowed("ExpenseCategoryAssignment.assignCategory")
            // P3-03EA-09: Atomic update + event in one transaction
            database.withTransaction {
                val expense = expenseDao.getById(expenseId)
                    ?: return@withTransaction CategoryAssignmentOutcome.SkippedExpenseMissing
                if (expense.categoryId != null && expense.categoryId > 0L)
                    return@withTransaction CategoryAssignmentOutcome.SkippedAlreadySet

                expenseDao.updateCategory(expenseId, categoryId)
                transactionEventDao.insert(TransactionEvent(
                    expenseId = expenseId, eventType = "EXPENSE_CATEGORY_ASSIGNED",
                    source = source, actor = "system:category_assignment",
                    occurredAt = timeProvider.now(), dedupeKey = null, duplicateExpenseId = null,
                    beforeSnapshot = null, afterSnapshot = null, metadata = null,
                    reason = "Category $categoryId assigned", correlationId = correlationId
                ))
                CategoryAssignmentOutcome.Assigned
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            Timber.w(e, "Category assignment failed for expense %d", expenseId)
            CategoryAssignmentOutcome.Failed(e.message ?: "Unknown error")
        }
    }
}
