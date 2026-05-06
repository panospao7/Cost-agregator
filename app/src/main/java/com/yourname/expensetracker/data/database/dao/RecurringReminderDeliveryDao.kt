package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery

@Dao
interface RecurringReminderDeliveryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(delivery: RecurringReminderDelivery): Long

    @Insert
    suspend fun insertAll(deliveries: List<RecurringReminderDelivery>)

    @Update
    suspend fun update(delivery: RecurringReminderDelivery)

    @Query("SELECT * FROM recurring_reminder_deliveries WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RecurringReminderDelivery?

    @Query("SELECT * FROM recurring_reminder_deliveries WHERE occurrenceId = :occurrenceId AND reminderWindow = :window LIMIT 1")
    suspend fun getByOccurrenceAndWindow(occurrenceId: Long, window: String): RecurringReminderDelivery?

    @Query("""
        SELECT * FROM recurring_reminder_deliveries
        WHERE (status = 'SCHEDULED' AND scheduledAt <= :now)
           OR (status = 'SNOOZED' AND snoozedUntil IS NOT NULL AND snoozedUntil <= :now)
        ORDER BY COALESCE(snoozedUntil, scheduledAt)
    """)
    suspend fun getPendingDeliveries(now: Long): List<RecurringReminderDelivery>
}
