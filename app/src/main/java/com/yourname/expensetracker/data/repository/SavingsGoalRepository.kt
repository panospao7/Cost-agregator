package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.SavingsGoalDao
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SavingsGoalRepository @Inject constructor(
    private val savingsGoalDao: SavingsGoalDao
) : com.yourname.expensetracker.domain.savings.SavingsGoalRepository {
    override fun observeSavingsGoals(): Flow<List<com.yourname.expensetracker.domain.model.SavingsGoal>> {
        return savingsGoalDao.getAllGoals().map { entities ->
            entities.map { entity ->
                entity.toDomain()
            }
        }
    }

    override suspend fun getSavingsGoals(): List<com.yourname.expensetracker.domain.model.SavingsGoal> {
        return savingsGoalDao.getAllGoals().first().map { it.toDomain() }
    }

    override suspend fun createSavingsGoal(goal: com.yourname.expensetracker.domain.model.SavingsGoal): Long {
        return savingsGoalDao.insertGoal(goal.toEntity())
    }

    override suspend fun deleteSavingsGoal(goal: com.yourname.expensetracker.domain.model.SavingsGoal) {
        savingsGoalDao.deleteGoal(goal.toEntity())
    }

    override suspend fun updateSavingsGoalAmount(goalId: Long, amount: Double) {
        savingsGoalDao.updateGoalAmount(goalId, amount)
    }

    override suspend fun incrementSavingsGoalAmount(goalId: Long, delta: Double): Boolean {
        return savingsGoalDao.addToGoalAmount(goalId, delta) > 0
    }

    @Deprecated("Use observeSavingsGoals() for domain-safe access")
    fun getAllGoalEntities(): Flow<List<SavingsGoal>> {
        return savingsGoalDao.getAllGoals()
    }

    @Deprecated("Use observeSavingsGoals() for domain-safe access")
    fun getAllGoals(): Flow<List<SavingsGoal>> {
        return getAllGoalEntities()
    }

    @Deprecated("Use createSavingsGoal() with domain model")
    suspend fun addGoal(goal: SavingsGoal): Long {
        return savingsGoalDao.insertGoal(goal)
    }

    @Deprecated("Use deleteSavingsGoal() with domain model")
    suspend fun deleteGoal(goal: SavingsGoal) {
        savingsGoalDao.deleteGoal(goal)
    }

    @Deprecated("Use updateSavingsGoalAmount()")
    suspend fun updateGoalAmount(goalId: Long, amount: Double) {
        savingsGoalDao.updateGoalAmount(goalId, amount)
    }

    /**
     * Atomically add [delta] to the current saved amount for the given goal.
     *
     * Unlike [updateGoalAmount], this operation does **not** require the caller
     * to first read the current value — the increment is applied inside a
     * single SQL UPDATE, eliminating the read-modify-write race that can lose
     * concurrent contributions.
     *
     * @return `true` if the goal existed and was updated.
     */
    @Deprecated("Use incrementSavingsGoalAmount()")
    suspend fun addToGoalAmount(goalId: Long, delta: Double): Boolean {
        return savingsGoalDao.addToGoalAmount(goalId, delta) > 0
    }

    private fun com.yourname.expensetracker.data.database.entity.SavingsGoal.toDomain(): com.yourname.expensetracker.domain.model.SavingsGoal {
        return com.yourname.expensetracker.domain.model.SavingsGoal(
            id = id,
            name = name,
            targetAmount = targetAmount,
            currentAmount = currentAmount,
            targetDate = targetDate,
            protectionLevel = when (protectionLevel) {
                com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.STRICT -> GoalProtectionLevel.STRICT
                com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.WARNING -> GoalProtectionLevel.WARNING
                com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.TRACKING -> GoalProtectionLevel.TRACKING
            },
            createdAt = createdAt
        )
    }

    private fun com.yourname.expensetracker.domain.model.SavingsGoal.toEntity(): com.yourname.expensetracker.data.database.entity.SavingsGoal {
        return com.yourname.expensetracker.data.database.entity.SavingsGoal(
            id = id,
            name = name,
            targetAmount = targetAmount,
            currentAmount = currentAmount,
            targetDate = targetDate,
            protectionLevel = when (protectionLevel) {
                GoalProtectionLevel.STRICT -> com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.STRICT
                GoalProtectionLevel.WARNING -> com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.WARNING
                GoalProtectionLevel.TRACKING -> com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.TRACKING
            },
            createdAt = createdAt
        )
    }
}
