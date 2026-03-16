package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import kotlinx.coroutines.flow.Flow

@Dao
interface AiArtifactDao {

    /**
     * Insert or replace an artifact.
     * The unique index on (targetKey, capability, promptVersion, sourceHash) ensures
     * that re-running a provider with the same inputs overwrites the previous record
     * rather than accumulating duplicates.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artifact: AiArtifactEntity): Long

    /**
     * Observe the most-recently updated artifact for a given target and capability.
     * Emits null when no record exists.
     */
    @Query("""
        SELECT * FROM ai_artifacts
        WHERE targetKey = :targetKey AND capability = :capability
        ORDER BY updatedAt DESC
        LIMIT 1
    """)
    fun observeLatest(targetKey: String, capability: String): Flow<AiArtifactEntity?>

    /**
     * One-shot fetch of the most-recently updated artifact.
     */
    @Query("""
        SELECT * FROM ai_artifacts
        WHERE targetKey = :targetKey AND capability = :capability
        ORDER BY updatedAt DESC
        LIMIT 1
    """)
    suspend fun getLatest(targetKey: String, capability: String): AiArtifactEntity?

    /**
     * Mark an artifact as dismissed so the UI does not surface it again,
     * while preserving the record for diagnostics.
     */
    @Query("UPDATE ai_artifacts SET status = :dismissed, updatedAt = :now WHERE id = :id")
    suspend fun markDismissed(id: Long, dismissed: String = AiArtifactStatus.DISMISSED.name, now: Long = System.currentTimeMillis())

    /**
     * Delete all artifacts whose TTL has expired.
     * Called by a cleanup worker; pass [now] as epoch-millis.
     */
    @Query("DELETE FROM ai_artifacts WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun deleteExpired(now: Long)

    /**
     * Delete all artifacts for a given target (e.g. when a PendingReview is approved/rejected).
     */
    @Query("DELETE FROM ai_artifacts WHERE targetKey = :targetKey")
    suspend fun deleteByTargetKey(targetKey: String)

    /** For testing / debug only. */
    @Query("SELECT * FROM ai_artifacts ORDER BY updatedAt DESC")
    suspend fun getAll(): List<AiArtifactEntity>
}
