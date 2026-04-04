package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.entity.Expense
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportDataRepository @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository
) {
    suspend fun getExpensesBetween(startDate: Long, endDate: Long): List<Expense> =
        expenseRepository.getExpensesBetween(startDate, endDate)

    suspend fun getCategoryNameMap(): Map<Long, String> =
        categoryRepository.getAll().associate { it.id to it.name }
}
