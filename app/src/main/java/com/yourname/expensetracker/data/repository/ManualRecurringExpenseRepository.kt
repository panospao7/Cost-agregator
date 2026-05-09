package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

@Singleton
class ManualRecurringExpenseRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val dao: ManualRecurringExpenseDao
) {
    /**
     * Returns only active recurring expenses.
     * B4: contract changed from all-rows to active-only for consistency
     * with [RecurringExpenseRepository].
     */
    suspend fun getAll(): List<ManualRecurringExpense> = dao.getAllActive()

    /** Returns all rows including inactive — use only when explicitly needed. */
    suspend fun getAllIncludingInactive(): List<ManualRecurringExpense> = dao.getAll()

    suspend fun insert(expense: ManualRecurringExpense): Long {
        writeBarrier.checkWritesAllowed("ManualRecurringExpenseRepository.insert")
        return dao.insert(expense)
    }

    suspend fun setActiveStatus(id: Long, isActive: Boolean) {
        writeBarrier.checkWritesAllowed("ManualRecurringExpenseRepository.setActiveStatus")
        dao.setActiveStatus(id, isActive)
    }

    suspend fun deleteById(id: Long) {
        writeBarrier.checkWritesAllowed("ManualRecurringExpenseRepository.deleteById")
        dao.deleteById(id)
    }

    suspend fun updateNextDate(id: Long, nextDate: Long) {
        writeBarrier.checkWritesAllowed("ManualRecurringExpenseRepository.updateNextDate")
        dao.updateNextDate(id, nextDate)
    }
}
