package com.yourname.expensetracker.data.store

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only facade over [ExpenseDao].
 * Inject this into UI/read repositories instead of [ExpenseDao] directly.
 * Write paths must use [ExpenseWriteStore] or [TransactionLifecycleCoordinator].
 */
@Singleton
class ExpenseReadStore @Inject constructor(
    private val expenseDao: ExpenseDao
) {
    suspend fun getById(id: Long): Expense? = expenseDao.getById(id)

    fun getAllFlow(limit: Int): Flow<List<Expense>> = expenseDao.getAllFlow(limit)

    suspend fun getPage(limit: Int = 100, offset: Int = 0): List<Expense> =
        expenseDao.getPage(limit, offset)

    fun getAllWithCategoryFlow(limit: Int = 200): Flow<List<ExpenseWithCategory>> =
        expenseDao.getAllWithCategoryFlow(limit)

    suspend fun getTotalCount(): Int = expenseDao.getTotalCount()
}
