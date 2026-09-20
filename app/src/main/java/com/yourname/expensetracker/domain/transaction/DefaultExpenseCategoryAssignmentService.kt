package com.yourname.expensetracker.domain.transaction

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
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
    private val transactionEventDao: TransactionEventDao,
    private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator
) : ExpenseCategoryAssignmentPort {

    override suspend fun assignCategoryIfUnset(
        expenseId: Long, categoryId: Long, source: String, correlationId: String?
    ): CategoryAssignmentOutcome {
        return try {
            writeBarrier.checkWritesAllowed("ExpenseCategoryAssignment.assignCategory")
            // P3-03EA-09: Atomic update + event in one transaction
            val outcome = database.withTransaction {
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
            outcome
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            Timber.w(e, "Category assignment failed for expense %d", expenseId)
            CategoryAssignmentOutcome.Failed(e.message ?: "Unknown error")
        }
    }

    /**
     * RP-11 FIX 4: side-effect dispatch moved OUT of the in-transaction port
     * call. Room transactions are re-entrant — dispatching from inside
     * [assignCategoryIfUnset] made the budget recheck / anomaly alert join the
     * caller's outer transaction (uncommitted reads, phantom dispatch on
     * rollback, extended lock window). The caller must invoke this only AFTER
     * its transaction committed and the outcome was [CategoryAssignmentOutcome.Assigned].
     */
    override suspend fun dispatchAssignedCategorySideEffects(
        expenseId: Long,
        source: String,
        correlationId: String?
    ) {
        try {
            transactionLifecycleCoordinator.dispatchCategoryAssignmentSideEffects(
                expenseId = expenseId,
                source = source,
                correlationId = correlationId
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            // Best-effort post-commit hook: the committed assignment stands; a
            // dispatch failure must not fail the already-committed operation.
            Timber.w(e, "Category assignment side effects failed for expense %d", expenseId)
        }
    }
}
