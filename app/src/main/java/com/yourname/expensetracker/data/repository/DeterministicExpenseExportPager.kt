package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.entity.Expense
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exhaustive expense pager that uses **keyset-based (cursor) pagination** to fetch
 * all expenses in a date range for export.
 *
 * ## SRH-24: Keyset-based pagination
 * Converted from offset-based (`LIMIT ? OFFSET ?`) to keyset-based pagination that
 * uses `WHERE (date, id) > (:lastDate, :lastId)`.
 * - **Keyset-based pagination** — the watermark is defined by (date, id) of the last
 *   row. Rows inserted with (date, id) higher than the current cursor will be seen;
 *   rows inserted behind the cursor will be missed (this is NOT a true atomic snapshot).
 *   Deletions of already-paged rows do not cause duplicates.
 * - **Consistent ordering** — the `ORDER BY date ASC, id ASC` across pages ensures
 *   every expense is visited exactly once.
 * - **No offset scan penalty** — SQLite does not need to skip past previously-returned
 *   rows on each page, making large exports O(n) instead of O(n²).
 *
 * ## Snapshot limitation
 * Keyset pagination is not a true atomic snapshot — concurrent inserts may be
 * partially visible depending on when they commit relative to the cursor position.
 * A true snapshot table (CREATE TABLE export_snapshot AS SELECT ...) is planned
 * for future export passes to guarantee point-in-time consistency.
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
     * cursor pagination, up to [maxRows] rows.
     *
     * @param startDate Start of the date range (inclusive).
     * @param endDate   End of the date range (exclusive).
     * @param pageSize  Number of rows per page (default [EXPORT_PAGE_SIZE]).
     * @param maxRows   Maximum number of rows to return (default Int.MAX_VALUE).
     *                  Used to prevent OOM when only validation/sampling is needed.
     * @return A list of matching expenses in deterministic order, limited to [maxRows].
     */
    suspend fun fetchAllBetween(
        startDate: Long,
        endDate: Long,
        pageSize: Int = EXPORT_PAGE_SIZE,
        maxRows: Int = Int.MAX_VALUE
    ): List<Expense> {
        require(pageSize > 0) { "pageSize must be greater than 0" }
        require(maxRows > 0) { "maxRows must be greater than 0" }

        val expenses = mutableListOf<Expense>()
        var lastDate: Long? = null
        var lastId: Long? = null

        while (expenses.size < maxRows) {
            val remaining = maxRows - expenses.size
            val fetchLimit = minOf(pageSize, remaining)
            val page = expenseRepository.getExpensesBetweenForExportKeyset(
                startDate = startDate,
                endDate = endDate,
                limit = fetchLimit,
                lastDate = lastDate,
                lastId = lastId
            )
            if (page.isEmpty()) break

            // Only take up to the remaining allowance from this page
            val pageChunk = if (page.size <= remaining) page else page.take(remaining)
            expenses += pageChunk
            if (pageChunk.size < fetchLimit || expenses.size >= maxRows) break

            // Update cursor to the last row of this page
            val lastRow = pageChunk.last()
            lastDate = lastRow.date
            lastId = lastRow.id
        }

        if (expenses.size >= maxRows) {
            Timber.w("fetchAllBetween: hit maxRows=%d limit (may be incomplete)", maxRows)
        } else {
            Timber.d("fetchAllBetween: fetched %d expenses using keyset pagination", expenses.size)
        }
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
     * Count expenses between dates.
     * Uses the repository's counting method which is consistent with the paged fetch.
     *
     * NOTE: This count is NOT snapshot-anchored. If rows are inserted between when
     * the count is taken and when paging begins, the count and exported rows may disagree.
     */
    suspend fun countBetween(startDate: Long, endDate: Long): Int =
        expenseRepository.countExpensesBetween(startDate, endDate)
}
