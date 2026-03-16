package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType

/**
 * Persisted AI output record.
 *
 * Stores the result of any AI capability invocation (briefing, explanation, etc.)
 * separately from core financial tables so that AI data can be expired, regenerated,
 * or disabled without affecting financial ground truth.
 *
 * Key design decisions:
 * - [targetKey]      Composite string key (e.g. "dashboard_home:2026-03-16",
 *                    "pending_review:123") that identifies the logical target.
 * - [sourceHash]     Hash of the deterministic inputs used to produce this artifact.
 *                    Allows staleness detection without re-running the provider.
 * - [promptVersion]  Version tag for the prompt template. Bump to invalidate old artifacts.
 * - [payloadJson]    Optional structured payload for richer data beyond [summaryText] /
 *                    [explanationText] (serialized by the calling repository).
 */
@Entity(
    tableName = "ai_artifacts",
    indices = [
        // Used for upsert deduplication: one artifact per (target, capability, promptVersion, sourceHash)
        Index(
            value = ["targetKey", "capability", "promptVersion", "sourceHash"],
            unique = true
        ),
        // Used for latest-artifact lookup
        Index(value = ["targetKey", "capability", "updatedAt"]),
        // Used for cleanup workers
        Index(value = ["status", "updatedAt"]),
        // Used for TTL expiry sweep
        Index(value = ["expiresAt"])
    ]
)
data class AiArtifactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetType: AiTargetType,
    val targetId: Long? = null,
    val targetKey: String,
    val capability: AiCapability,
    val status: AiArtifactStatus,
    val mode: AiMode,
    val provider: String? = null,
    val modelName: String? = null,
    val promptVersion: String,
    val summaryText: String? = null,
    val explanationText: String? = null,
    val payloadJson: String? = null,
    val confidence: Float? = null,
    val sourceHash: String,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long? = null
)
