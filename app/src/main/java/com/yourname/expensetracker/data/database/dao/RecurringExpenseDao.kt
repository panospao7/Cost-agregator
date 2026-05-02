package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import kotlinx.coroutines.flow.Flow

/**
 * Fully deprecated — do not use in new code.
 *
 * Use [ManualRecurringExpenseDao] for all recurring expense operations.
 * This interface is retained only to avoid breaking existing callers during
 * migration.  Every method in this DAO has an equivalent (or superseding)
 * counterpart in [ManualRecurringExpenseDao].
 *
 * B4 contract change: [getAllActiveFlow] and [getAllActive] now return only
 * active rows (isActive = 1).  The old unfiltered [getAllIncludingInactive]
 * methods are provided for callers that explicitly need inactive rows.
 */
@Deprecated(
    message = "Use ManualRecurringExpenseDao instead — all methods have an equivalent in ManualRecurringExpenseDao",
    replaceWith = ReplaceWith("ManualRecurringExpenseDao"),
    level = DeprecationLevel.WARNING
)
@Dao
interface RecurringExpenseDao {
    /**
     * Observe only active recurring expenses, ordered by next date.
     * This is the primary reactive read path — inactive rows are excluded.
     */
    @Query("SELECT * FROM manual_recurring_expenses WHERE isActive = 1 ORDER BY nextDate ASC")
    fun getAllActiveFlow(): Flow<List<ManualRecurringExpense>>

    /**
     * One-shot read of active recurring expenses only.
     */
    @Query("SELECT * FROM manual_recurring_expenses WHERE isActive = 1 ORDER BY nextDate ASC")
    suspend fun getAllActive(): List<ManualRecurringExpense>

    /**
     * @deprecated Renamed to [getAllActiveFlow] for clarity. Returns all rows including inactive.
     */
    @Deprecated("Use getAllActiveFlow() for active-only, or getAllIncludingInactiveFlow() for all rows",
        replaceWith = ReplaceWith("getAllActiveFlow()"))
    @Query("SELECT * FROM manual_recurring_expenses ORDER BY nextDate ASC")
    fun getAllFlow(): Flow<List<ManualRecurringExpense>>

    /**
     * @deprecated Renamed to [getAllActive] for clarity. Returns all rows including inactive.
     */
    @Deprecated("Use getAllActive() for active-only, or getAllIncludingInactive() for all rows",
        replaceWith = ReplaceWith("getAllActive()"))
    @Query("SELECT * FROM manual_recurring_expenses")
    suspend fun getAll(): List<ManualRecurringExpense>

    /** Returns all rows including inactive — use only when explicitly needed. */
    @Query("SELECT * FROM manual_recurring_expenses ORDER BY nextDate ASC")
    fun getAllIncludingInactiveFlow(): Flow<List<ManualRecurringExpense>>

    /** Returns all rows including inactive — use only when explicitly needed. */
    @Query("SELECT * FROM manual_recurring_expenses ORDER BY createdAt DESC")
    suspend fun getAllIncludingInactive(): List<ManualRecurringExpense>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(expense: ManualRecurringExpense): Long

    @Update
    suspend fun update(expense: ManualRecurringExpense)

    @Delete
    suspend fun delete(expense: ManualRecurringExpense)
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE merchant = :merchant LIMIT 1")
    suspend fun getByMerchant(merchant: String): ManualRecurringExpense?
    
    @Query("DELETE FROM manual_recurring_expenses WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ManualRecurringExpense?
}
