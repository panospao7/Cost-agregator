package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.entity.Expense
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeterministicExpenseExportPager @Inject constructor(
    private val expenseRepository: ExpenseRepository
) {

    companion object {
        const val EXPORT_PAGE_SIZE = 2000
    }

    suspend fun fetchAllBetween(
        startDate: Long,
        endDate: Long,
        pageSize: Int = EXPORT_PAGE_SIZE
    ): List<Expense> {
        require(pageSize > 0) { "pageSize must be greater than 0" }

        val expenses = mutableListOf<Expense>()
        var offset = 0

        while (true) {
            val page = expenseRepository.getExpensesBetweenPagedForDeterministicExport(
                startDate = startDate,
                endDate = endDate,
                limit = pageSize,
                offset = offset
            )
            if (page.isEmpty()) break

            expenses += page
            if (page.size < pageSize) break
            offset += pageSize
        }

        return expenses
    }
}
