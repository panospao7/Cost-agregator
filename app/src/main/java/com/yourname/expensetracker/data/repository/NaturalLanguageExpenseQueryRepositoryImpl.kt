package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpense
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpenseQueryRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NaturalLanguageExpenseQueryRepositoryImpl @Inject constructor(
    private val expenseDao: ExpenseDao
) : NaturalLanguageExpenseQueryRepository {

    private companion object {
        private const val PAGE_SIZE = 2000
    }

    override suspend fun getExpensesBetween(startMs: Long, endMs: Long): List<NaturalLanguageExpense> {
        val result = mutableListOf<NaturalLanguageExpense>()
        var offset = 0

        while (true) {
            val page = expenseDao.getExpensesBetween(
                startDate = startMs,
                endDate = endMs,
                limit = PAGE_SIZE,
                offset = offset
            )

            if (page.isEmpty()) {
                break
            }

            result += page.map { expense ->
                NaturalLanguageExpense(
                    id = expense.id,
                    amount = expense.amount,
                    effectiveAmount = expense.effectiveAmount,
                    currency = expense.currency,
                    merchant = expense.merchant,
                    date = expense.date,
                    categoryId = expense.categoryId
                )
            }

            if (page.size < PAGE_SIZE) {
                break
            }

            offset += PAGE_SIZE
        }

        return result
    }
}
