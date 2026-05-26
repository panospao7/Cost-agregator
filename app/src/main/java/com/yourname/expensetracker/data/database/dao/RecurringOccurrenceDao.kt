package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence

@Dao
interface RecurringOccurrenceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(occurrence: RecurringOccurrence): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(occurrences: List<RecurringOccurrence>)

    @Update
    suspend fun update(occurrence: RecurringOccurrence)

    @Query("SELECT * FROM recurring_occurrences WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RecurringOccurrence?

    @Query("SELECT * FROM recurring_occurrences WHERE occurrenceKey = :key LIMIT 1")
    suspend fun getByKey(key: String): RecurringOccurrence?

    @Query("SELECT * FROM recurring_occurrences WHERE sourceType = :sourceType AND sourceId = :sourceId ORDER BY dueDate")
    suspend fun getBySource(sourceType: String, sourceId: Long): List<RecurringOccurrence>

    @Query("SELECT * FROM recurring_occurrences WHERE dueDate >= :start AND dueDate < :end ORDER BY dueDate")
    suspend fun getByDateRange(start: Long, end: Long): List<RecurringOccurrence>

    @Query("SELECT * FROM recurring_occurrences WHERE linkedExpenseId = :expenseId LIMIT 1")
    suspend fun getByLinkedExpenseId(expenseId: Long): RecurringOccurrence?

    @Query("SELECT * FROM recurring_occurrences WHERE status = :status ORDER BY dueDate")
    suspend fun getByStatus(status: String): List<RecurringOccurrence>

    @Query("UPDATE recurring_occurrences SET status = :newStatus, updatedAt = :now WHERE id IN (:ids)")
    suspend fun updateStatus(ids: List<Long>, newStatus: String, now: Long)

    @Query("DELETE FROM recurring_occurrences WHERE sourceType = :sourceType AND sourceId = :sourceId")
    suspend fun deleteBySource(sourceType: String, sourceId: Long)

    @Query("SELECT id FROM recurring_occurrences WHERE sourceType = :sourceType AND sourceId = :sourceId")
    suspend fun getIdsBySource(sourceType: String, sourceId: Long): List<Long>

    @Query("SELECT id FROM recurring_occurrences WHERE sourceType = :sourceType AND sourceId = :sourceId AND status = 'PLANNED'")
    suspend fun getPlannedIdsBySource(sourceType: String, sourceId: Long): List<Long>

    /**
     * P4-CURRENT-001: Atomically claim an occurrence for expense linkage.
     * Only succeeds if the occurrence is still PLANNED and unlinked.
     * Returns the number of affected rows (1 = success, 0 = already claimed).
     */
    @Query("""
        UPDATE recurring_occurrences
        SET status = 'PAID',
            linkedExpenseId = :expenseId,
            paidAmount = :amount,
            paidCurrency = :currency,
            paidAt = :paidAt
        WHERE id = :occurrenceId
          AND status = 'PLANNED'
          AND linkedExpenseId IS NULL
    """)
    suspend fun claimForExpense(occurrenceId: Long, expenseId: Long, amount: Double, currency: String, paidAt: Long): Int

    /** Deletes open PLANNED occurrences for a source (used during rule update regeneration). */
    @Query("DELETE FROM recurring_occurrences WHERE sourceType = :sourceType AND sourceId = :sourceId AND status = 'PLANNED'")
    suspend fun deleteOpenPlannedBySource(sourceType: String, sourceId: Long): Int
}
