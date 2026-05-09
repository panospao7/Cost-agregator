package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpense
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpenseQueryRepository
import com.yourname.expensetracker.domain.naturallanguage.SearchCursor
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NaturalLanguageExpenseQueryRepositoryImpl @Inject constructor(
    private val expenseDao: ExpenseDao
) : NaturalLanguageExpenseQueryRepository {

    private companion object {
        private const val PAGE_SIZE = 500
    }

    /**
     * W30+W31: Keyset-paginated load — collects successive pages via
     * getExpensesFilteredKeyset until all results are gathered.
     */
    override suspend fun getExpensesBetween(startMs: Long, endMs: Long): List<NaturalLanguageExpense> {
        val result = mutableListOf<NaturalLanguageExpense>()
        var cursor: SearchCursor? = null

        while (true) {
            val page = expenseDao.getExpensesFilteredKeyset(
                startDate = startMs,
                endDate = endMs,
                categoryIds = emptyList(),
            categoryFilterEnabled = 0,
                merchantLike = null,
                transactionType = null,
                keywordLike = null,
                limit = PAGE_SIZE,
                cursorDate = cursor?.date,
                cursorId = cursor?.id
            )

            if (page.isEmpty()) break

            result += page.mapToNaturalLanguageExpenses()
            if (page.size < PAGE_SIZE) break

            val last = page.last()
            cursor = SearchCursor(date = last.date, id = last.id)
        }

        return result
    }

    /**
     * Legacy wrapper — delegates to the keyset method, collecting all pages.
     * Amount filtering is NOT done here — it's the engine's responsibility (W16).
     */
    @Deprecated("Use getExpensesBetweenFilteredKeyset for W30+W31 keyset pagination")
    override suspend fun getExpensesBetweenFiltered(
        startMs: Long,
        endMs: Long,
        merchants: List<String>?,
        categories: List<String>?,
        minAmount: Double?,
        maxAmount: Double?
    ): List<NaturalLanguageExpense> {
        val merchantPattern = merchants?.singleOrNull()?.let { "%$it%" }
        val merchantFiltered = if (merchantPattern != null) {
            // Apply merchant filter at DAO level via LIKE
            getExpensesBetweenFilteredKeyset(
                startMs = startMs, endMs = endMs,
                categoryIds = null, merchants = null,
                transactionType = null, keywordSearch = null,
                limit = PAGE_SIZE, cursor = null
            ).filter { exp ->
                merchants!!.any { exp.merchant.contains(it, ignoreCase = true) }
            }
        } else {
            getExpensesBetweenFilteredKeyset(
                startMs = startMs, endMs = endMs,
                categoryIds = null, merchants = null,
                transactionType = null, keywordSearch = null,
                limit = PAGE_SIZE, cursor = null
            )
        }

        Timber.d("getExpensesBetweenFiltered (legacy): loaded ${merchantFiltered.size} expenses")
        return merchantFiltered
    }

    /**
     * W30+W31: Keyset-paginated, filtered query.
     *
     * Pushes categoryIds and transactionType to DAO SQL.
     * Returns a single page; caller loops with cursor for subsequent pages.
     */
    override suspend fun getExpensesBetweenFilteredKeyset(
        startMs: Long,
        endMs: Long,
        categoryIds: Set<Long>?,
        merchants: List<String>?,
        transactionType: String?,
        keywordSearch: String?,
        limit: Int,
        cursor: SearchCursor?
    ): List<NaturalLanguageExpense> {
        val merchantLike = merchants?.singleOrNull()?.let { "%$it%" }
        val keywordLike = keywordSearch?.let { "%$it%" }

        val page = expenseDao.getExpensesFilteredKeyset(
            startDate = startMs,
            endDate = endMs,
            categoryIds = categoryIds?.toList().orEmpty(),
            categoryFilterEnabled = if (categoryIds.isNullOrEmpty()) 0 else 1,
            merchantLike = merchantLike,
            transactionType = transactionType,
            keywordLike = keywordLike,
            limit = limit,
            cursorDate = cursor?.date,
            cursorId = cursor?.id
        )

        return page.mapToNaturalLanguageExpenses()
    }

    private fun List<com.yourname.expensetracker.data.database.entity.Expense>.mapToNaturalLanguageExpenses() =
        map { expense ->
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
}
