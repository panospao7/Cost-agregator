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

    /**
     * Cancels all SCHEDULED or SNOOZED reminder deliveries for the given occurrence.
     *
     * Called when an occurrence transitions to PAID so that outstanding reminder
     * notifications are not dispatched for a bill the user has already paid.
     */
    @Query("""
        UPDATE recurring_reminder_deliveries
        SET status = 'CANCELLED'
        WHERE occurrenceId = :occurrenceId
          AND status IN ('SCHEDULED', 'SNOOZED')
    """)
    suspend fun suppressOpenDeliveriesForOccurrence(occurrenceId: Long)

    /**
     * Returns pending deliveries whose associated occurrence is still PLANNED.
     * Safer than [getPendingDeliveries] because it excludes reminders for
     * occurrences that have been PAID, SKIPPED, or CANCELLED.
     */
    @Query("""
        SELECT * FROM recurring_reminder_deliveries
        WHERE ((status = 'SCHEDULED' AND scheduledAt <= :now)
            OR (status = 'SNOOZED' AND snoozedUntil IS NOT NULL AND snoozedUntil <= :now))
          AND occurrenceId IN (SELECT id FROM recurring_occurrences WHERE status = 'PLANNED')
        ORDER BY COALESCE(snoozedUntil, scheduledAt)
    """)
    suspend fun getPendingDeliveriesForPlannedOccurrences(now: Long): List<RecurringReminderDelivery>

    /**
     * Atomically claim a reminder delivery for processing.
     * Only succeeds if the delivery is currently SCHEDULED or SNOOZED.
     * Returns 1 if the claim was successful, 0 if another worker already claimed it.
     *
     * Retry policy:
     * FAILED_PERMISSION = terminal until permission changes
     * FAILED_TRANSIENT = manual retry only (no automatic retry yet)
     */
    @Query("""
        UPDATE recurring_reminder_deliveries
        SET status = 'CLAIMED'
        WHERE id = :id
          AND status IN ('SCHEDULED', 'SNOOZED')
    """)
    suspend fun claimDelivery(id: Long): Int
}
