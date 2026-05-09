package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.AiArtifactDao
import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

@Singleton
class AiArtifactRepositoryImpl @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val dao: AiArtifactDao,
    private val timeProvider: TimeProvider
) : AiArtifactRepository {

    override fun observeLatest(targetKey: String, capability: AiCapability): Flow<AiArtifactRecord?> =
        dao.observeLatest(targetKey, capability.name).map { it?.toRecord() }

    override suspend fun getLatest(targetKey: String, capability: AiCapability): AiArtifactRecord? =
        dao.getLatest(targetKey, capability.name)?.toRecord()

    override suspend fun upsert(artifact: AiArtifactRecord): Long {
        writeBarrier.checkWritesAllowed("AiArtifactRepositoryImpl.upsert")
        return dao.upsert(artifact.toEntity())
    }

    override suspend fun markDismissed(id: Long) {
        writeBarrier.checkWritesAllowed("AiArtifactRepositoryImpl.markDismissed")
        dao.markDismissed(id, now = timeProvider.now())
    }

    override suspend fun markApplied(id: Long) {
        writeBarrier.checkWritesAllowed("AiArtifactRepositoryImpl.markApplied")
        dao.markApplied(id, now = timeProvider.now())
    }

    override suspend fun deleteExpired(now: Long) {
        writeBarrier.checkWritesAllowed("AiArtifactRepositoryImpl.deleteExpired")
        dao.deleteExpired(now)
    }

    override suspend fun deleteByTargetKey(targetKey: String) {
        writeBarrier.checkWritesAllowed("AiArtifactRepositoryImpl.deleteByTargetKey")
        dao.deleteByTargetKey(targetKey)
    }

    // ── Entity ↔ Record mappers (data-layer only) ────────────────────────

    private fun AiArtifactEntity.toRecord(): AiArtifactRecord = AiArtifactRecord(
        id = id,
        targetType = targetType,
        targetId = targetId,
        targetKey = targetKey,
        capability = capability,
        status = status,
        mode = mode,
        provider = provider,
        modelName = modelName,
        promptVersion = promptVersion,
        summaryText = summaryText,
        explanationText = explanationText,
        payloadJson = payloadJson,
        confidence = confidence,
        sourceHash = sourceHash,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        expiresAt = expiresAt
    )

    private fun AiArtifactRecord.toEntity(): AiArtifactEntity = AiArtifactEntity(
        id = id,
        targetType = targetType,
        targetId = targetId,
        targetKey = targetKey,
        capability = capability,
        status = status,
        mode = mode,
        provider = provider,
        modelName = modelName,
        promptVersion = promptVersion,
        summaryText = summaryText,
        explanationText = explanationText,
        payloadJson = payloadJson,
        confidence = confidence,
        sourceHash = sourceHash,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        expiresAt = expiresAt
    )
}
