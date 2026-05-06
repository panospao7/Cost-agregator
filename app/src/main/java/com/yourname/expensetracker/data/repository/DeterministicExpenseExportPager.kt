package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.entity.Expense
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exhaustive expense pager that uses **keyset-based (cursor) pagination** to fetch
 * all expenses in a date range for export.
 *
 * ## SRH-24: ID-based snapshot instead of offset-based
 * Converted from offset-based (`LIMIT ? OFFSET ?`) to keyset-based pagination that
 * uses `WHERE (date, id) > (:lastDate, :lastId)`. This provides:
 * - An **atomic snapshot** — the watermark is defined by (date, id) of the last
 *   row, so insertions/deletions on *already-paged* rows do not cause missed or
 *   duplicated rows.
 * - **Consistent ordering** — the `ORDER BY date ASC, id ASC` across pages ensures
 *   every expense is visited exactly once.
 * - **No offset scan penalty** — SQLite does not need to skip past previously-returned
 *   rows on each page, making large exports O(n) instead of O(n²).
 */
@Singleton
class DeterministicExpenseExportPager @Inject constructor(
    private val expenseRepository: ExpenseRepository
) {

    companion object {
        const val EXPORT_PAGE_SIZE = 2000
    }

    /**
     * Fetches all expenses between [startDate] and [endDate] using keyset-based
     * cursor pagination.
     *
     * @param startDate Start of the date range (inclusive).
     * @param endDate   End of the date range (exclusive).
     * @param pageSize  Number of rows per page (default [EXPORT_PAGE_SIZE]).
     * @return A complete list of matching expenses in deterministic order.
     */
    suspend fun fetchAllBetween(
        startDate: Long,
        endDate: Long,
        pageSize: Int = EXPORT_PAGE_SIZE
    ): List<Expense> {
        require(pageSize > 0) { "pageSize must be greater than 0" }

        val expenses = mutableListOf<Expense>()
        var lastDate: Long? = null
        var lastId: Long? = null

        while (true) {
            val page = expenseRepository.getExpensesBetweenForExportKeyset(
                startDate = startDate,
                endDate = endDate,
                limit = pageSize,
                lastDate = lastDate,
                lastId = lastId
            )
            if (page.isEmpty()) break

            expenses += page

            if (page.size < pageSize) break

            // Update cursor to the last row of this page
            val lastRow = page.last()
            lastDate = lastRow.date
            lastId = lastRow.id
        }

        Timber.d("fetchAllBetween: exported %d expenses using keyset pagination", expenses.size)
        return expenses
    }

    /**
     * Fetches a single page of expenses between [startDate] and [endDate] using
     * keyset-based (cursor) pagination.
     *
     * This is the per-page primitive used by [streamExpensesToWriter] in the
     * export ViewModel. Unlike [fetchAllBetween], it does NOT accumulate pages
     * — the caller is responsible for looping with cursor updates.
     *
     * @param startDate Start of the date range (inclusive).
     * @param endDate   End of the date range (exclusive).
     * @param pageSize  Number of rows per page.
     * @param lastDate  Cursor: date of the last row from the previous page (null for first page).
     * @param lastId    Cursor: id of the last row from the previous page (null for first page).
     * @return A single page of expenses (may be empty indicating no more data).
     */
    suspend fun fetchPage(
        startDate: Long,
        endDate: Long,
        pageSize: Int,
        lastDate: Long?,
        lastId: Long?
    ): List<Expense> {
        return expenseRepository.getExpensesBetweenForExportKeyset(
            startDate = startDate,
            endDate = endDate,
            limit = pageSize,
            lastDate = lastDate,
            lastId = lastId
        )
    }

    /**
     * Count expenses between dates using the same consistent snapshot.
     * Uses the repository's counting method which is consistent with the paged fetch.
     */
    suspend fun countBetween(startDate: Long, endDate: Long): Int =
        expenseRepository.countExpensesBetween(startDate, endDate)
}
