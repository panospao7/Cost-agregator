package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpense
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpenseQueryRepository
import timber.log.Timber
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
        // DEFERRED (W30/W31): Replace offset paging with keyset pagination.
        // Offset paging can skip or duplicate rows when data changes between pages.
        // Target: expenseDao.getExpensesBetweenForExportKeyset(startMs, endMs, limit, lastDate, lastId)
        // BLOCKED: Keyset pagination requires a schema migration to add a composite
        // index on (date ASC, id ASC) for efficient keyset queries. Currently deferred
        // until the next schema version bump.
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

    override suspend fun getExpensesBetweenFiltered(
        startMs: Long,
        endMs: Long,
        merchants: List<String>?,
        categories: List<String>?,
        minAmount: Double?,
        maxAmount: Double?
    ): List<NaturalLanguageExpense> {
        // DEFERRED (W30/W31): Replace offset paging with keyset pagination +
        // push filters down to DAO SQL. Same schema index dependency as
        // getExpensesBetween() — composite index on (date ASC, id ASC) required.
        // Load data from DAO (date-bounded only at the SQL level for now)
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

            // Apply filters at the repository level (closer to data) —
            // TODO: push these filters down to DAO SQL queries for efficiency
            for (expense in page) {
                // Merchant filter
                if (merchants != null && merchants.isNotEmpty()) {
                    if (merchants.none { expense.merchant.contains(it, ignoreCase = true) }) {
                        continue
                    }
                }

                // Amount filters
                if (minAmount != null && expense.effectiveAmount < minAmount) continue
                if (maxAmount != null && expense.effectiveAmount > maxAmount) continue

                result.add(
                    NaturalLanguageExpense(
                        id = expense.id,
                        amount = expense.amount,
                        effectiveAmount = expense.effectiveAmount,
                        currency = expense.currency,
                        merchant = expense.merchant,
                        date = expense.date,
                        categoryId = expense.categoryId
                    )
                )
            }

            if (page.size < PAGE_SIZE) {
                break
            }

            offset += PAGE_SIZE
        }

        Timber.d("getExpensesBetweenFiltered: loaded ${result.size} expenses with filters [merchants=%s, categories=%s, minAmt=%s, maxAmt=%s]",
            merchants, categories, minAmount, maxAmount)
        return result
    }
}
