package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent

@Dao
interface RecurringLifecycleEventDao {

    @Insert
    suspend fun insert(event: RecurringLifecycleEvent): Long

    @Query("SELECT * FROM recurring_lifecycle_events WHERE occurrenceId = :id ORDER BY occurredAt DESC")
    suspend fun getEventsForOccurrence(id: Long): List<RecurringLifecycleEvent>
}
