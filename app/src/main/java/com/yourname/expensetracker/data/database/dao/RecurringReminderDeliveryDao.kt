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
     * Cancels all open/retryable reminder deliveries for the given occurrence.
     *
     * Called when an occurrence transitions to PAID so that outstanding reminder
     * notifications are not dispatched for a bill the user has already paid.
     * Now includes CLAIMED and FAILED_TRANSIENT to prevent notification-after-payment races.
     */
    @Query("""
        UPDATE recurring_reminder_deliveries
        SET status = 'CANCELLED',
            updatedAt = :now,
            failureReason = :reason
        WHERE occurrenceId = :occurrenceId
          AND status IN ('SCHEDULED', 'SNOOZED', 'CLAIMED', 'FAILED_TRANSIENT')
    """)
    suspend fun suppressOpenDeliveriesForOccurrence(occurrenceId: Long, now: Long, reason: String): Int

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
     * Only succeeds if the delivery is SCHEDULED and due, or SNOOZED and its snoozedUntil has passed.
     * Sets claimedAt, lastAttemptAt, increments attemptCount, and clears failureReason.
     * Returns 1 if the claim was successful, 0 if another worker already claimed it.
     *
     * P4-CURRENT-006: Uses status-specific due conditions — SCHEDULED checks scheduledAt,
     * SNOOZED checks snoozedUntil. scheduledAt is NOT used for SNOOZED rows to prevent
     * early claims of future-snoozed deliveries with old scheduledAt.
     */
    @Query("""
        UPDATE recurring_reminder_deliveries
        SET status = 'CLAIMED',
            claimedAt = :now,
            lastAttemptAt = :now,
            attemptCount = attemptCount + 1,
            failureReason = NULL,
            updatedAt = :now
        WHERE id = :id
          AND (
            (status = 'SCHEDULED' AND scheduledAt <= :now)
            OR
            (status = 'SNOOZED' AND snoozedUntil IS NOT NULL AND snoozedUntil <= :now)
          )
    """)
    suspend fun claimDelivery(id: Long, now: Long): Int

    /**
     * P4-CURRENT-005: Reset stale CLAIMED deliveries back to SCHEDULED.
     * Called to recover deliveries that were claimed but never completed
     * (e.g. worker crashed after claiming).
     *
     * Fixed: Uses claimedAt instead of scheduledAt to determine staleness.
     * An overdue reminder's scheduledAt can be far in the past, but a freshly
     * claimed delivery should not be immediately recovered — only deliveries
     * whose claim is genuinely old (claimedAt <= staleClaimThreshold) are reset.
     */
    @Query("""
        UPDATE recurring_reminder_deliveries
        SET status = 'SCHEDULED',
            claimedAt = NULL,
            updatedAt = :now
        WHERE status = 'CLAIMED'
          AND claimedAt IS NOT NULL
          AND claimedAt <= :staleClaimThreshold
    """)
    suspend fun recoverStaleClaimedDeliveries(staleClaimThreshold: Long, now: Long): Int

    /**
     * Atomically marks a CLAIMED delivery as SENT, persisting the notificationId.
     * Only succeeds if the delivery is currently CLAIMED — if payment suppression
     * cancelled the claim between claim and send, this returns 0.
     */
    @Query("""
        UPDATE recurring_reminder_deliveries
        SET status = 'SENT',
            lastSentAt = :now,
            notificationId = :notificationId,
            updatedAt = :now
        WHERE id = :id
          AND status = 'CLAIMED'
    """)
    suspend fun markSentFromClaimed(id: Long, notificationId: Int, now: Long): Int

    /**
     * Atomically marks a CLAIMED delivery as failed.
     * Only succeeds if the delivery is currently CLAIMED.
     */
    @Query("""
        UPDATE recurring_reminder_deliveries
        SET status = :status,
            failureReason = :reason,
            updatedAt = :now
        WHERE id = :id
          AND status = 'CLAIMED'
    """)
    suspend fun markFailedFromClaimed(id: Long, status: String, reason: String, now: Long): Int

    /**
     * Cancels a CLAIMED delivery (used when occurrence is no longer PLANNED after claim).
     */
    @Query("""
        UPDATE recurring_reminder_deliveries
        SET status = 'CANCELLED',
            failureReason = :reason,
            updatedAt = :now
        WHERE id = :id
          AND status = 'CLAIMED'
    """)
    suspend fun cancelClaimedDelivery(id: Long, reason: String, now: Long): Int

    @Query("DELETE FROM recurring_reminder_deliveries WHERE occurrenceId IN (:occurrenceIds)")
    suspend fun deleteByOccurrenceIds(occurrenceIds: List<Long>)

    /**
     * Reopens a previously cancelled/failed reminder delivery for an occurrence+window,
     * resetting it back to SCHEDULED. Used when an expense is unlinked and the
     * occurrence becomes PLANNED again.
     *
     * Does NOT reopen SENT rows (user already saw the notification).
     */
    @Query("""
        UPDATE recurring_reminder_deliveries
        SET status = 'SCHEDULED',
            scheduledAt = :scheduledAt,
            snoozedUntil = NULL,
            dismissedAt = NULL,
            failureReason = NULL,
            updatedAt = :now
        WHERE occurrenceId = :occurrenceId
          AND reminderWindow = :window
          AND status IN ('CANCELLED', 'FAILED_TRANSIENT')
    """)
    suspend fun reopenDeliveryForOccurrenceWindow(
        occurrenceId: Long,
        window: String,
        scheduledAt: Long,
        now: Long
    ): Int

    /**
     * P4-CURRENT-003: Suppress open deliveries for an occurrence by ID.
     * Used when materializer auto-PAIDs an occurrence.
     */
    @Query("""
        UPDATE recurring_reminder_deliveries
        SET status = 'CANCELLED'
        WHERE occurrenceId = :occurrenceId
          AND status IN ('SCHEDULED', 'SNOOZED', 'CLAIMED')
    """)
    suspend fun suppressByOccurrenceId(occurrenceId: Long): Int
}
