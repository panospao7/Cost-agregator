package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.SavingsGoalDao
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
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
                com.yourname.expensetracker.domain.model.SavingsGoal(
                    id = entity.id,
                    name = entity.name,
                    targetAmount = entity.targetAmount,
                    currentAmount = entity.currentAmount,
                    targetDate = entity.targetDate,
                    protectionLevel = when (entity.protectionLevel) {
                        com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.STRICT -> GoalProtectionLevel.STRICT
                        com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.WARNING -> GoalProtectionLevel.WARNING
                        com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.TRACKING -> GoalProtectionLevel.TRACKING
                    },
                    createdAt = entity.createdAt
                )
            }
        }
    }

    fun getAllGoalEntities(): Flow<List<SavingsGoal>> {
        return savingsGoalDao.getAllGoals()
    }

    fun getAllGoals(): Flow<List<SavingsGoal>> {
        return getAllGoalEntities()
    }

    suspend fun addGoal(goal: SavingsGoal): Long {
        return savingsGoalDao.insertGoal(goal)
    }

    suspend fun deleteGoal(goal: SavingsGoal) {
        savingsGoalDao.deleteGoal(goal)
    }

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
    suspend fun addToGoalAmount(goalId: Long, delta: Double): Boolean {
        return savingsGoalDao.addToGoalAmount(goalId, delta) > 0
    }
}
