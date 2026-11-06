package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.ExpenseRefundLink

/**
 * Refund netting (schema 153): DAO for `expense_refund_links`.
 *
 * ## Ownership
 * The write methods are reserved for the transaction lifecycle coordinator (linkRefund,
 * unlinkRefund and the in-transaction cleanup of deleteExpense), which also adds the
 * db_ownership_policy rows in a later slice. Nothing calls them yet. Reads are free for the
 * read-only use case and the backup verifier.
 *
 * ## Method naming
 * Write methods start with a verb from the DB access guard's mutation grammar (insert / delete)
 * so call sites are detected by `verify_db_access_boundaries.py`. Do not rename them to a
 * non-verb prefix.
 *
 * ## Constraints surfaced to callers
 * Plain `@Insert` (ABORT): the unique index on (refundExpenseId, purchaseExpenseId) and the
 * foreign keys raise a constraint exception inside the coordinator's transaction instead of
 * being silently ignored.
 */
@Dao
interface ExpenseRefundLinkDao {

    // -- Writes (coordinator only) --------------------------------------------------------

    @RestrictedExpenseDaoMutation
    @Insert
    suspend fun insert(link: ExpenseRefundLink): Long

    /** Removes one link. Returns affected rows (1 = removed, 0 = it did not exist). */
    @RestrictedExpenseDaoMutation
    @Query("DELETE FROM expense_refund_links WHERE id = :linkId")
    suspend fun deleteById(linkId: Long): Int

    // -- Reads ----------------------------------------------------------------------------

    @Query("SELECT * FROM expense_refund_links WHERE id = :linkId LIMIT 1")
    suspend fun getById(linkId: Long): ExpenseRefundLink?

    /** Links where [refundExpenseId] is the DEPOSIT side, oldest first. */
    @Query("SELECT * FROM expense_refund_links WHERE refundExpenseId = :refundExpenseId ORDER BY createdAt ASC, id ASC")
    suspend fun getByRefund(refundExpenseId: Long): List<ExpenseRefundLink>

    /** Links where [purchaseExpenseId] is the PURCHASE side, oldest first. */
    @Query("SELECT * FROM expense_refund_links WHERE purchaseExpenseId = :purchaseExpenseId ORDER BY createdAt ASC, id ASC")
    suspend fun getByPurchase(purchaseExpenseId: Long): List<ExpenseRefundLink>

    /** Links where [expenseId] is either side, oldest first. */
    @Query(
        "SELECT * FROM expense_refund_links " +
            "WHERE refundExpenseId = :expenseId OR purchaseExpenseId = :expenseId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun getForExpense(expenseId: Long): List<ExpenseRefundLink>

    @Query("SELECT COUNT(*) FROM expense_refund_links")
    suspend fun countAll(): Int

    /** Number of links where [expenseId] is either side. */
    @Query("SELECT COUNT(*) FROM expense_refund_links WHERE refundExpenseId = :expenseId OR purchaseExpenseId = :expenseId")
    suspend fun countForExpense(expenseId: Long): Int
}
