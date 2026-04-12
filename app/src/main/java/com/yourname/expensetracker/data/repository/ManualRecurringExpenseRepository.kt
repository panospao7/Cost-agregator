package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ManualRecurringExpenseRepository @Inject constructor(
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

    suspend fun insert(expense: ManualRecurringExpense): Long = dao.insert(expense)

    suspend fun setActiveStatus(id: Long, isActive: Boolean) = dao.setActiveStatus(id, isActive)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun updateNextDate(id: Long, nextDate: Long) = dao.updateNextDate(id, nextDate)
}
