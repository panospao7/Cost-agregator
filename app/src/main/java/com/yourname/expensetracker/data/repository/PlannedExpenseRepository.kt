package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

/**
 * TODO (P2-16): Insert conflict and timestamp gaps.
 * - [PlannedExpenseDao.insertPlannedExpense] uses OnConflictStrategy.IGNORE;
 *   the returned insert ID is not checked, so duplicate-silently-skipped bugs
 *   are possible. Callers should receive a sealed result (Inserted/Duplicate/Error).
 * - [PlannedExpense.createdAt] and [updatedAt] default to 0L. These should be
 *   set explicitly at the repository/coordinator boundary.
 * - Consider introducing a [PlannedExpenseLifecycleCoordinator] for lifecycle-owned writes.
 */
@Singleton
class PlannedExpenseRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val plannedExpenseDao: PlannedExpenseDao
) {
    fun getAllPlannedExpenses(): Flow<List<PlannedExpense>> {
        return plannedExpenseDao.getAllPlannedExpenses()
    }

    fun getPlannedExpensesForPeriod(startMs: Long, endMs: Long): Flow<List<PlannedExpense>> {
        return plannedExpenseDao.getPlannedExpensesForPeriod(startMs, endMs)
    }

    suspend fun addPlannedExpense(expense: PlannedExpense): Long {
        writeBarrier.checkWritesAllowed("PlannedExpenseRepository.addPlannedExpense")
        return plannedExpenseDao.insertPlannedExpense(expense)
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
