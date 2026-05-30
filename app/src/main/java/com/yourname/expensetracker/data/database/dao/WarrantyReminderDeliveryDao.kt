package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.WarrantyReminderDelivery

/**
 * DAO for [WarrantyReminderDelivery] — the durable, claim-before-notify sent-state for
 * the warranty expiration worker.
 *
 * Mirrors [RecurringReminderDeliveryDao]'s atomic-claim pattern:
 *  - [insertOrIgnore] seeds a row idempotently via the (warrantyId, windowDays, expiryDate) unique key.
 *  - [claim] atomically flips SCHEDULED/FAILED → CLAIMED (returns 1 once; a racing run gets 0).
 *  - [markSentFromClaimed] only succeeds from CLAIMED (so a re-notify cannot occur).
 *  - [markFailed] only succeeds from CLAIMED (records the transient reason, leaves it re-claimable).
 *  - [recoverStaleClaimed] resets crash-orphaned CLAIMED rows keyed on [WarrantyReminderDelivery.claimedAt].
 */
@Dao
interface WarrantyReminderDeliveryDao {

    /**
     * Seed a delivery row idempotently. If a row with the same
     * (warrantyId, windowDays, expiryDate) already exists, the insert is ignored
     * (returns -1) and the existing row's state is preserved.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(delivery: WarrantyReminderDelivery): Long

    @Update
    suspend fun update(delivery: WarrantyReminderDelivery)

    @Query("SELECT * FROM warranty_reminder_deliveries WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): WarrantyReminderDelivery?

    @Query("""
        SELECT * FROM warranty_reminder_deliveries
        WHERE warrantyId = :warrantyId AND windowDays = :windowDays AND expiryDate = :expiryDate
        LIMIT 1
    """)
    suspend fun getByKey(warrantyId: Long, windowDays: Int, expiryDate: Long): WarrantyReminderDelivery?

    @Query("SELECT * FROM warranty_reminder_deliveries WHERE warrantyId = :warrantyId")
    suspend fun getByWarrantyId(warrantyId: Long): List<WarrantyReminderDelivery>

    /**
     * Atomically claim a reminder delivery for processing by its unique key.
     *
     * Only succeeds if the row is currently SCHEDULED or FAILED (a FAILED row is a
     * previous transient failure and is safe to retry; a SENT row is terminal and is
     * never re-claimed). Sets status='CLAIMED', stamps claimedAt/lastAttemptAt,
     * increments attemptCount, and clears failureReason.
     *
     * Returns 1 if the claim was acquired, 0 if another run already claimed/sent it
     * (durable, cross-run dedup + double-notify protection).
     */
    @Query("""
        UPDATE warranty_reminder_deliveries
        SET status = 'CLAIMED',
            claimedAt = :now,
            lastAttemptAt = :now,
            attemptCount = attemptCount + 1,
            failureReason = NULL,
            updatedAt = :now
        WHERE warrantyId = :warrantyId
          AND windowDays = :windowDays
          AND expiryDate = :expiryDate
          AND status IN ('SCHEDULED', 'FAILED')
    """)
    suspend fun claim(warrantyId: Long, windowDays: Int, expiryDate: Long, now: Long): Int

    /**
     * Atomically claim a reminder delivery by primary key. Same semantics as the
     * key-based [claim] overload; useful once a row id is already known.
     */
    @Query("""
        UPDATE warranty_reminder_deliveries
        SET status = 'CLAIMED',
            claimedAt = :now,
            lastAttemptAt = :now,
            attemptCount = attemptCount + 1,
            failureReason = NULL,
            updatedAt = :now
        WHERE id = :id
          AND status IN ('SCHEDULED', 'FAILED')
    """)
    suspend fun claimById(id: Long, now: Long): Int

    /**
     * Atomically marks a CLAIMED delivery as SENT, persisting the notificationId.
     * Only succeeds if the delivery is currently CLAIMED — returns 0 otherwise, so a
     * row can never transition to SENT without having been claimed first.
     */
    @Query("""
        UPDATE warranty_reminder_deliveries
        SET status = 'SENT',
            notificationId = :notificationId,
            updatedAt = :now
        WHERE id = :id
          AND status = 'CLAIMED'
    """)
    suspend fun markSentFromClaimed(id: Long, notificationId: Int, now: Long): Int

    /**
     * Marks a CLAIMED delivery as FAILED, recording the reason. Only succeeds from
     * CLAIMED. A FAILED row remains re-claimable on a later run (transient failure).
     */
    @Query("""
        UPDATE warranty_reminder_deliveries
        SET status = 'FAILED',
            failureReason = :reason,
            updatedAt = :now
        WHERE id = :id
          AND status = 'CLAIMED'
    """)
    suspend fun markFailed(id: Long, reason: String, now: Long): Int

    /**
     * Reset stale CLAIMED deliveries back to SCHEDULED so a delivery that was claimed
     * but never completed (e.g. the worker crashed after claiming) can be retried.
     *
     * Keyed on [WarrantyReminderDelivery.claimedAt]: only deliveries whose claim is
     * genuinely old (claimedAt <= :staleClaimThreshold) are recovered.
     */
    @Query("""
        UPDATE warranty_reminder_deliveries
        SET status = 'SCHEDULED',
            claimedAt = NULL,
            updatedAt = :now
        WHERE status = 'CLAIMED'
          AND claimedAt IS NOT NULL
          AND claimedAt <= :staleClaimThreshold
    """)
    suspend fun recoverStaleClaimed(staleClaimThreshold: Long, now: Long): Int

    /**
     * Deletes delivery rows whose expiry is older than [cutoff]. Used to prune
     * long-past reminders (replaces the SharedPreferences 90-day key sweep).
     */
    @Query("DELETE FROM warranty_reminder_deliveries WHERE expiryDate < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
