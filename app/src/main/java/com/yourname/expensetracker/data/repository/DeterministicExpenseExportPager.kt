package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.entity.Expense
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exhaustive expense pager that uses offset-based pagination to fetch all
 * expenses in a date range for export.
 *
 * ## BAK-13: Offset-based paging (not streaming)
 * This pager uses `LIMIT ? OFFSET ?` (offset-based pagination), which loads
 * the **entire result set into memory** before returning. For very large
 * datasets (>10k expenses) this can cause:
 * - High memory pressure (OOM risk)
 * - Performance degradation as offset grows (SQLite must scan past earlier rows)
 *
 * A streaming / cursor-based (keyset) approach would be more efficient:
 * - Use `WHERE (date, id) > (:lastDate, :lastId) ORDER BY date ASC, id ASC`
 * - Each page fetches only the next N rows without re-scanning skipped rows.
 *
 * For the current use-case (accounting export) this is acceptable because:
 * - Accounting exports typically cover bounded time ranges (quarterly).
 * - The page size (2000) keeps individual queries fast.
 * - Exports are infrequent and user-triggered.
 *
 * If performance becomes an issue, migrate to keyset pagination via a new
 * DAO method that accepts a cursor pair `(lastDate, lastId)`.
 */
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
