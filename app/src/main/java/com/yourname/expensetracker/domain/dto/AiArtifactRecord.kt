package com.yourname.expensetracker.domain.dto

import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType

/**
 * Domain-level artifact record.
 *
 * This is the domain/UI-facing representation of an AI artifact.
 * Only [com.yourname.expensetracker.data.repository.AiArtifactRepositoryImpl]
 * knows about the Room [AiArtifactEntity]; all domain and UI code uses this DTO.
 */
data class AiArtifactRecord(
    val id: Long = 0,
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
