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

    @Query("SELECT * FROM recurring_occurrences WHERE occurrenceKey = :key LIMIT 1")
    suspend fun getByKey(key: String): RecurringOccurrence?

    @Query("SELECT * FROM recurring_occurrences WHERE sourceType = :sourceType AND sourceId = :sourceId ORDER BY dueDate")
    suspend fun getBySource(sourceType: String, sourceId: Long): List<RecurringOccurrence>

    @Query("SELECT * FROM recurring_occurrences WHERE dueDate >= :start AND dueDate < :end ORDER BY dueDate")
    suspend fun getByDateRange(start: Long, end: Long): List<RecurringOccurrence>

    @Query("SELECT * FROM recurring_occurrences WHERE status = :status ORDER BY dueDate")
    suspend fun getByStatus(status: String): List<RecurringOccurrence>

    @Query("UPDATE recurring_occurrences SET status = :newStatus, updatedAt = :now WHERE id IN (:ids)")
    suspend fun updateStatus(ids: List<Long>, newStatus: String, now: Long)
}
