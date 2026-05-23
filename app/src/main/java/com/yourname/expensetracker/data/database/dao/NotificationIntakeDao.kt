package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity

@Dao
interface NotificationIntakeDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(entity: NotificationIntakeEntity): Long

    @Query("SELECT * FROM notification_intake WHERE id = :id")
    suspend fun getById(id: Long): NotificationIntakeEntity?

    @Query("""
        SELECT * FROM notification_intake
        WHERE status IN ('RECEIVED', 'FAILED_RETRYABLE')
          AND (:nowMs >= COALESCE(nextAttemptAt, 0))
        ORDER BY capturedAt ASC
        LIMIT :limit
    """)
    suspend fun getReadyForProcessing(nowMs: Long, limit: Int = 100): List<NotificationIntakeEntity>

    @Query("""
        UPDATE notification_intake
        SET status = 'PROCESSING',
            lockedAt = :nowMs,
            lockedBy = :workerId,
            lastAttemptAt = :nowMs,
            attempts = attempts + 1,
            updatedAt = :nowMs
        WHERE id = :id
          AND status IN ('RECEIVED', 'FAILED_RETRYABLE')
    """)
    suspend fun claimForProcessing(id: Long, nowMs: Long, workerId: String): Int

    @Query("""
        UPDATE notification_intake
        SET status = :status,
            rawNotificationId = :rawId,
            expenseId = :expenseId,
            pendingReviewId = :reviewId,
            finalOutcome = :finalOutcome,
            terminalAt = :nowMs,
            updatedAt = :nowMs,
            lockedAt = NULL,
            lockedBy = NULL
        WHERE id = :id
    """)
    suspend fun markTerminal(
        id: Long, status: String, rawId: Long?, expenseId: Long?,
        reviewId: Long?, finalOutcome: String?, nowMs: Long
    ): Int

    @Query("""
        UPDATE notification_intake
        SET status = 'FAILED_RETRYABLE',
            nextAttemptAt = :nextAttemptAt,
            lastFailureCode = :failureCode,
            lastFailureMessageHash = :failureHash,
            updatedAt = :nowMs,
            lockedAt = NULL,
            lockedBy = NULL
        WHERE id = :id
    """)
    suspend fun markRetryableFailure(
        id: Long, nextAttemptAt: Long, failureCode: String,
        failureHash: String?, nowMs: Long
    ): Int

    @Query("""
        UPDATE notification_intake
        SET status = 'FAILED_FINAL',
            lastFailureCode = :failureCode,
            lastFailureMessageHash = :failureHash,
            terminalAt = :nowMs,
            updatedAt = :nowMs,
            lockedAt = NULL,
            lockedBy = NULL
        WHERE id = :id
    """)
    suspend fun markFinalFailure(
        id: Long, failureCode: String, failureHash: String?, nowMs: Long
    ): Int

    @Query("""
        UPDATE notification_intake
        SET status = 'FAILED_RETRYABLE',
            lockedAt = NULL,
            lockedBy = NULL,
            nextAttemptAt = :nowMs,
            updatedAt = :nowMs
        WHERE status = 'PROCESSING'
          AND lockedAt < :staleBeforeMs
    """)
    suspend fun releaseStaleProcessing(staleBeforeMs: Long, nowMs: Long): Int

    @Query("""
        UPDATE notification_intake
        SET title = NULL, text = NULL, bigText = NULL,
            subText = NULL, extrasJson = NULL,
            rawPayloadPurgedAt = :nowMs, updatedAt = :nowMs
        WHERE id = :id
    """)
    suspend fun purgeRawPayload(id: Long, nowMs: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM notification_intake WHERE dedupeFingerprint = :fingerprint)")
    suspend fun existsByFingerprint(fingerprint: String): Boolean

    @Query("""
        UPDATE notification_intake
        SET transientPayloadCiphertext = NULL,
            transientPayloadNonce = NULL,
            transientPayloadVersion = NULL,
            transientPayloadPurgedAt = :nowMs,
            updatedAt = :nowMs
        WHERE id = :id
    """)
    suspend fun purgeTransientPayload(id: Long, nowMs: Long): Int
}
