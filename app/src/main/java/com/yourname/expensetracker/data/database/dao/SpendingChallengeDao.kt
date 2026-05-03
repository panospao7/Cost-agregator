package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.SpendingChallengeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpendingChallengeDao {

    @Query(
        """
        SELECT * FROM spending_challenges
        WHERE isActive = 1
        ORDER BY endDate ASC, startDate DESC, id DESC
        """
    )
    fun observeActiveChallenges(): Flow<List<SpendingChallengeEntity>>

    @Query(
        """
        SELECT * FROM spending_challenges
        WHERE isActive = 1
        ORDER BY endDate ASC, startDate DESC, id DESC
        """
    )
    suspend fun getActiveChallenges(): List<SpendingChallengeEntity>

    /**
     * Uses IGNORE to prevent silent data loss on conflict. Callers should check return value (0 = skipped).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(challenge: SpendingChallengeEntity): Long

    @Query(
        """
        UPDATE spending_challenges
        SET isActive = 0,
            updatedAt = :updatedAt
        WHERE id IN (:challengeIds)
        """
    )
    suspend fun deactivateChallenges(challengeIds: List<Long>, updatedAt: Long)
}
