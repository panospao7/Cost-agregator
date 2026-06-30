package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity
import com.yourname.expensetracker.data.database.entity.NotificationIntakeStatus

// ── Projection DTOs ─────────────────────────────────────────────────────────

/**
 * Metadata-only projection used for maxAttempts checks, claim/status tracking,
 * and non-sensitive routing fields — does NOT load raw title/text/extras or
 * transient ciphertext (PR12H-2).
 */
data class NotificationIntakeProcessingMetadata(
    val id: Long,
    val status: String,
    val attempts: Int,
    val maxAttempts: Int,
    val payloadMode: String,
    val rawStorageMode: String,
    val packageName: String,
    val appName: String?,
    val postTime: Long,
    val capturedAt: Long,
    val source: String,
    val correlationId: String,
    val dedupeFingerprint: String
)

/**
 * Payload-only projection that isolates every column holding notification
 * content so it is never loaded before the mid-run privacy recheck (PR12H-2).
 */
data class NotificationIntakePayloadForProcessing(
    val id: Long,
    val payloadMode: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val extrasJson: String?,
    val transientPayloadCiphertext: String?,
    val transientPayloadNonce: String?,
    val transientPayloadVersion: Int?
)

@Dao
interface NotificationIntakeDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(entity: NotificationIntakeEntity): Long

    @Query("SELECT * FROM notification_intake WHERE id = :id")
    suspend fun getById(id: Long): NotificationIntakeEntity?

    @Query("""
        SELECT id, status, attempts, maxAttempts, payloadMode, rawStorageMode,
               packageName, appName, postTime, capturedAt, source,
               correlationId, dedupeFingerprint
        FROM notification_intake
        WHERE id = :id
        LIMIT 1
    """)
    suspend fun getProcessingMetadataById(id: Long): NotificationIntakeProcessingMetadata?

    @Query("""
        SELECT id, payloadMode, title, text, bigText, subText, extrasJson,
               transientPayloadCiphertext, transientPayloadNonce,
               transientPayloadVersion
        FROM notification_intake
        WHERE id = :id
        LIMIT 1
    """)
    suspend fun getPayloadForProcessing(id: Long): NotificationIntakePayloadForProcessing?

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

    @Query("""
        SELECT * FROM notification_intake
        WHERE payloadMode = 'TRANSIENT'
          AND transientPayloadCiphertext IS NULL
          AND rawPayloadPurgedAt IS NULL
        LIMIT :limit
    """)
    suspend fun getLegacyPlaintextTransientRows(limit: Int = 100): List<NotificationIntakeEntity>

    @Query("""
        UPDATE notification_intake
        SET title = NULL, text = NULL, bigText = NULL,
            subText = NULL, extrasJson = NULL,
            rawPayloadPurgedAt = :nowMs, updatedAt = :nowMs
        WHERE id = :id
    """)
    suspend fun purgeVisiblePayload(id: Long, nowMs: Long): Int

    @Query("""
        UPDATE notification_intake
        SET transientPayloadCiphertext = :ciphertext,
            transientPayloadNonce = :nonce,
            transientPayloadVersion = :version,
            title = NULL, text = NULL, bigText = NULL,
            subText = NULL, extrasJson = NULL, updatedAt = :nowMs
        WHERE id = :id
    """)
    suspend fun encryptAndClearVisiblePayload(
        id: Long, ciphertext: String, nonce: String, version: Int, nowMs: Long
    ): Int

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

    @Query("""
        UPDATE notification_intake
        SET status = :status,
            finalOutcome = :finalOutcome,
            rawNotificationId = NULL,
            expenseId = NULL,
            pendingReviewId = NULL,
            title = NULL,
            text = NULL,
            bigText = NULL,
            subText = NULL,
            extrasJson = NULL,
            transientPayloadCiphertext = NULL,
            transientPayloadNonce = NULL,
            transientPayloadVersion = NULL,
            updatedAt = :nowMs,
            terminalAt = :nowMs,
            rawPayloadPurgedAt = :nowMs,
            transientPayloadPurgedAt = :nowMs
        WHERE id = :id
    """)
    suspend fun markPrivacyDeniedAndPurgeAllPayload(
        id: Long,
        status: String = NotificationIntakeStatus.PRIVACY_DENIED.name,
        finalOutcome: String = "PRIVACY_DENIED",
        nowMs: Long
    ): Int

    @Query("""
        UPDATE notification_intake
        SET title = NULL,
            text = NULL,
            bigText = NULL,
            subText = NULL,
            extrasJson = NULL,
            transientPayloadCiphertext = NULL,
            transientPayloadNonce = NULL,
            transientPayloadVersion = NULL,
            updatedAt = :nowMs,
            rawPayloadPurgedAt = :nowMs,
            transientPayloadPurgedAt = :nowMs
        WHERE id = :id
    """)
    suspend fun purgeAllPayload(id: Long, nowMs: Long): Int

    // ── Data retention worker support (P8F-01) ─────────────────────────────────

    @Query("""
        SELECT * FROM notification_intake
        WHERE capturedAt < :cutoffMs
          AND rawPayloadPurgedAt IS NULL
        LIMIT :limit
    """)
    suspend fun getUnpurgedIntakeOlderThan(cutoffMs: Long, limit: Int): List<NotificationIntakeEntity>
}
