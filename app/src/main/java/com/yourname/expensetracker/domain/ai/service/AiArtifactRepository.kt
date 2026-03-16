package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.domain.ai.model.AiCapability
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for AI output artifacts.
 *
 * All AI-generated content (briefings, explanations) is stored here, separate
 * from core financial tables, so it can be expired or deleted without side effects.
 */
interface AiArtifactRepository {
    /** Live stream of the most-recently updated artifact for a target + capability pair. */
    fun observeLatest(targetKey: String, capability: AiCapability): Flow<AiArtifactEntity?>

    /** One-shot fetch of the most-recently updated artifact. */
    suspend fun getLatest(targetKey: String, capability: AiCapability): AiArtifactEntity?

    /**
     * Insert or replace an artifact.
     * Returns the row id of the upserted record.
     */
    suspend fun upsert(artifact: AiArtifactEntity): Long

    /** Mark an artifact as dismissed so it is no longer surfaced in the UI. */
    suspend fun markDismissed(id: Long)

    /** Delete all artifacts whose TTL has elapsed. */
    suspend fun deleteExpired(now: Long)

    /** Delete all artifacts associated with a given target (e.g. after a review is resolved). */
    suspend fun deleteByTargetKey(targetKey: String)
}
