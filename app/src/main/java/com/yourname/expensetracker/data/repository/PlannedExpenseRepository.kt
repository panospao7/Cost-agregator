package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

/**
 * Repository for planned-expense CRUD.
 *
 * ## Invariants (P2-16)
 * - [addPlannedExpense] sets [PlannedExpense.createdAt] and [updatedAt] if the
 *   incoming entity has them at 0L.
 * - The insert result is checked: a -1 from [OnConflictStrategy.IGNORE] throws
 *   [IllegalStateException] so callers do not silently proceed with a zero ID.
 * - [PlannedExpense.openSourceOccurrenceKey] is populated from
 *   [PlannedExpense.sourceOccurrenceKey] on insert when not already set.
 * - All write methods check [DatabaseWriteBarrier] before mutation.
 */
@Singleton
class PlannedExpenseRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val plannedExpenseDao: PlannedExpenseDao,
    private val timeProvider: TimeProvider
) {
    fun getAllPlannedExpenses(): Flow<List<PlannedExpense>> {
        return plannedExpenseDao.getAllPlannedExpenses()
    }

    fun getPlannedExpensesForPeriod(startMs: Long, endMs: Long): Flow<List<PlannedExpense>> {
        return plannedExpenseDao.getPlannedExpensesForPeriod(startMs, endMs)
    }

    /**
     * Inserts a [PlannedExpense] with lifecycle invariants enforced:
     * - Sets [PlannedExpense.createdAt] and [updatedAt] if missing.
     * - Populates [PlannedExpense.openSourceOccurrenceKey] from
     *   [PlannedExpense.sourceOccurrenceKey] if not already set.
     * - Checks the insert result; throws [IllegalStateException] on duplicate
     *   (DAO uses [OnConflictStrategy.IGNORE] → returns -1 on conflict).
     *
     * @return the inserted row ID (always > 0).
     * @throws IllegalStateException if a duplicate was silently skipped.
     */
    suspend fun addPlannedExpense(expense: PlannedExpense): Long {
        writeBarrier.checkWritesAllowed("PlannedExpenseRepository.addPlannedExpense")
        val now = timeProvider.now()
        val withTimestamps = expense.copy(
            createdAt = if (expense.createdAt == 0L) now else expense.createdAt,
            updatedAt = now,
            openSourceOccurrenceKey = expense.openSourceOccurrenceKey
                ?: expense.sourceOccurrenceKey
        )
        val id = plannedExpenseDao.insertPlannedExpense(withTimestamps)
        if (id == -1L) {
            Timber.w("PlannedExpense insert conflict — duplicate silently skipped (sourceOccurrenceKey=%s)",
                expense.sourceOccurrenceKey)
            throw IllegalStateException("Duplicate planned expense insert skipped by IGNORE conflict strategy")
        }
        return id
    }

    suspend fun deletePlannedExpense(expense: PlannedExpense) {
        writeBarrier.checkWritesAllowed("PlannedExpenseRepository.deletePlannedExpense")
        plannedExpenseDao.deletePlannedExpense(expense)
    }

    suspend fun deletePlannedExpenseById(id: Long) {
        writeBarrier.checkWritesAllowed("PlannedExpenseRepository.deletePlannedExpenseById")
        plannedExpenseDao.deletePlannedExpenseById(id)
    }
}
