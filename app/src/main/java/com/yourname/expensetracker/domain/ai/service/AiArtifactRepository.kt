package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for AI output artifacts.
 *
 * All AI-generated content (briefings, explanations) is stored here, separate
 * from core financial tables, so it can be expired or deleted without side effects.
 *
 * Domain and UI callers interact exclusively with [AiArtifactRecord]; the Room
 * entity is an implementation detail of the data layer.
 */
interface AiArtifactRepository {
    /** Live stream of the most-recently updated artifact for a target + capability pair. */
    fun observeLatest(targetKey: String, capability: AiCapability): Flow<AiArtifactRecord?>

    /** One-shot fetch of the most-recently updated artifact. */
    suspend fun getLatest(targetKey: String, capability: AiCapability): AiArtifactRecord?

    /**
     * Insert or replace an artifact.
     * Returns the row id of the upserted record.
     */
    suspend fun upsert(artifact: AiArtifactRecord): Long

    /** Mark an artifact as dismissed so it is no longer surfaced in the UI. */
    suspend fun markDismissed(id: Long)

    /** Mark an artifact as applied after its suggestion is accepted into local UI state. */
    suspend fun markApplied(id: Long)

    /** Delete all artifacts whose TTL has elapsed. */
    suspend fun deleteExpired(now: Long)

    /** Delete all artifacts associated with a given target (e.g. after a review is resolved). */
    suspend fun deleteByTargetKey(targetKey: String)
}
