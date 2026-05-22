package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.RawNotification
import kotlinx.coroutines.flow.Flow

@Dao
interface RawNotificationDao {
    
    @Insert
    suspend fun insert(notification: RawNotification): Long
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(notification: RawNotification): Long
    
    @Query("SELECT * FROM raw_notifications WHERE id = :id")
    suspend fun getById(id: Long): RawNotification?
    
    @Query("SELECT * FROM raw_notifications ORDER BY capturedAt DESC")
    fun getAllFlow(): Flow<List<RawNotification>>

    @Query("SELECT * FROM raw_notifications ORDER BY capturedAt DESC")
    suspend fun getAll(): List<RawNotification>
    
    @Query("SELECT * FROM raw_notifications ORDER BY capturedAt DESC LIMIT :limit")
    fun getRecentFlow(limit: Int): Flow<List<RawNotification>>
    
    @Query("SELECT * FROM raw_notifications WHERE packageName = :packageName ORDER BY capturedAt DESC")
    fun getByPackageFlow(packageName: String): Flow<List<RawNotification>>
    
    @Query("SELECT DISTINCT packageName FROM raw_notifications ORDER BY packageName")
    fun getAllPackagesFlow(): Flow<List<String>>
    
    @Query("SELECT COUNT(*) FROM raw_notifications")
    fun getCountFlow(): Flow<Int>
    
    @Query("DELETE FROM raw_notifications")
    suspend fun deleteAll()
    
    @Delete
    suspend fun delete(notification: RawNotification)

    /**
     * Insert all notifications. Uses IGNORE because raw notifications are audit/event
     * data — duplicates (same auto-generated PK) should never replace existing records.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(notifications: List<RawNotification>)
    
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM raw_notifications
            WHERE packageName = :packageName
            AND timestamp = :timestamp
            AND (title = :title OR (:title IS NULL AND title IS NULL))
            AND (text = :text OR (:text IS NULL AND text IS NULL))
            AND (bigText = :bigText OR (:bigText IS NULL AND bigText IS NULL))
        )
    """)
    suspend fun exists(packageName: String, timestamp: Long, title: String?, text: String?, bigText: String?): Boolean

    /**
     * Fast duplicate pre-check using the canonical [dedupeFingerprint].
     * Works correctly under all [RawStorageMode] values because the fingerprint
     * is based on the original notification content, not sanitized stored fields.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM raw_notifications
            WHERE dedupeFingerprint = :fingerprint
        )
    """)
    suspend fun existsByDedupeFingerprint(fingerprint: String): Boolean

    /**
     * Find an existing raw notification by its dedupe fingerprint.
     * Returns the raw ID if found, null otherwise.
     */
    @Query("""
        SELECT id FROM raw_notifications
        WHERE dedupeFingerprint = :fingerprint
        LIMIT 1
    """)
    suspend fun findIdByDedupeFingerprint(fingerprint: String): Long?

    @Query("UPDATE raw_notifications SET isRelevant = :isRelevant WHERE id = :id")
    suspend fun markRelevance(id: Long, isRelevant: Boolean)

    /**
     * Mark a raw notification as processed after a terminal pipeline outcome.
     *
     * @return Number of rows updated (should be 1).
     */
    @Query("UPDATE raw_notifications SET isProcessed = 1 WHERE id = :rawId")
    suspend fun markProcessed(rawId: Long): Int

    // ── Raw data retention (Phase 6, Batch 3) ──────────────────────────────────

    @Query("""
        UPDATE raw_notifications
        SET rawContentPurgedAt = :nowMs
        WHERE capturedAt < :beforeMs
          AND rawContentPurgedAt IS NULL
    """)
    suspend fun purgeRawContent(beforeMs: Long, nowMs: Long): Int

    // ── Data retention worker support ──────────────────────────────────────────

    @Query("""
        SELECT * FROM raw_notifications
        WHERE capturedAt < :cutoffMs
          AND rawContentPurgedAt IS NULL
    """)
    suspend fun getUnpurgedRawNotificationsOlderThan(cutoffMs: Long): List<RawNotification>

    @Query("""
        SELECT * FROM raw_notifications
        WHERE capturedAt < :cutoffMs
          AND rawContentPurgedAt IS NULL
        LIMIT :limit
    """)
    suspend fun getUnpurgedRawNotificationsOlderThan(cutoffMs: Long, limit: Int): List<RawNotification>

    @Query("""
        UPDATE raw_notifications
        SET rawContentPurgedAt = :rawContentPurgedAt,
            title = :title,
            text = :text,
            bigText = :bigText,
            subText = :subText,
            extrasJson = :extrasJson,
            parseResult = :parseResult
        WHERE id = :id
    """)
    suspend fun updateRawContentPurged(
        id: Long,
        rawContentPurgedAt: Long,
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        extrasJson: String?,
        parseResult: String?
    )
}
