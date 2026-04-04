package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpense
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpenseQueryRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NaturalLanguageExpenseQueryRepositoryImpl @Inject constructor(
    private val expenseDao: ExpenseDao
) : NaturalLanguageExpenseQueryRepository {

    override suspend fun getExpensesBetween(startMs: Long, endMs: Long): List<NaturalLanguageExpense> {
        return expenseDao.getExpensesBetweenFlow(startMs, endMs).first().map { expense ->
            NaturalLanguageExpense(
                id = expense.id,
                amount = expense.amount,
                effectiveAmount = expense.effectiveAmount,
                merchant = expense.merchant,
                date = expense.date,
                categoryId = expense.categoryId
            )
        }
    }
}
