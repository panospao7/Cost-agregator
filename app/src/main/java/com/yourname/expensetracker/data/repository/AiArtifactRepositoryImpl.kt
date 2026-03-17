package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.AiArtifactDao
import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiArtifactRepositoryImpl @Inject constructor(
    private val dao: AiArtifactDao
) : AiArtifactRepository {

    override fun observeLatest(targetKey: String, capability: AiCapability): Flow<AiArtifactEntity?> =
        dao.observeLatest(targetKey, capability.name)

    override suspend fun getLatest(targetKey: String, capability: AiCapability): AiArtifactEntity? =
        dao.getLatest(targetKey, capability.name)

    override suspend fun upsert(artifact: AiArtifactEntity): Long =
        dao.upsert(artifact)

    override suspend fun markDismissed(id: Long) =
        dao.markDismissed(id)

    override suspend fun markApplied(id: Long) =
        dao.markApplied(id)

    override suspend fun deleteExpired(now: Long) =
        dao.deleteExpired(now)

    override suspend fun deleteByTargetKey(targetKey: String) =
        dao.deleteByTargetKey(targetKey)
}
