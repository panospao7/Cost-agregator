package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.SpendingChallengeDao
import com.yourname.expensetracker.data.database.entity.SpendingChallengeEntity
import com.yourname.expensetracker.domain.challenge.ChallengeType
import com.yourname.expensetracker.domain.challenge.SpendingChallenge
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpendingChallengeRepository @Inject constructor(
    private val spendingChallengeDao: SpendingChallengeDao,
    private val timeProvider: TimeProvider
) {

    fun observeActiveChallenges(): Flow<List<SpendingChallenge>> {
        return spendingChallengeDao.observeActiveChallenges().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun getActiveChallenges(): List<SpendingChallenge> {
        return spendingChallengeDao.getActiveChallenges().map { it.toDomain() }
    }

    suspend fun saveChallenge(challenge: SpendingChallenge): SpendingChallenge {
        val entity = challenge.toEntity()
        val persistedId = spendingChallengeDao.insert(entity).takeIf { it > 0 } ?: entity.id
        return entity.copy(id = persistedId).toDomain(
            progress = challenge.progress,
            createdAtOverride = entity.createdAt,
            updatedAtOverride = entity.updatedAt
        )
    }

    suspend fun deactivateChallenges(challengeIds: List<Long>, updatedAt: Long) {
        if (challengeIds.isEmpty()) return
        spendingChallengeDao.deactivateChallenges(challengeIds, updatedAt)
    }

    private fun SpendingChallengeEntity.toDomain(
        progress: Double = 0.0,
        createdAtOverride: Long? = null,
        updatedAtOverride: Long? = null
    ): SpendingChallenge {
        return SpendingChallenge(
            id = id,
            name = name,
            type = runCatching { ChallengeType.valueOf(type) }.getOrDefault(ChallengeType.BUDGET_LIMIT),
            startDate = startDate,
            endDate = endDate,
            targetAmount = targetAmount,
            categoryId = categoryId,
            isActive = isActive,
            progress = progress,
            baselineAmount = baselineAmount,
            baselineStartDate = baselineStartDate,
            baselineEndDate = baselineEndDate,
            createdAt = createdAtOverride ?: createdAt,
            updatedAt = updatedAtOverride ?: updatedAt
        )
    }

    private fun SpendingChallenge.toEntity(): SpendingChallengeEntity {
        val timestamp = timeProvider.now()
        return SpendingChallengeEntity(
            id = id,
            name = name,
            type = type.name,
            startDate = startDate,
            endDate = endDate,
            targetAmount = targetAmount,
            categoryId = categoryId,
            isActive = isActive,
            baselineAmount = baselineAmount,
            baselineStartDate = baselineStartDate,
            baselineEndDate = baselineEndDate,
            createdAt = if (id == 0L) timestamp else createdAt,
            updatedAt = timestamp
        )
    }
}
