package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.domain.model.SavingsGoal
import kotlinx.coroutines.flow.Flow

interface SavingsGoalRepository {
    fun observeSavingsGoals(): Flow<List<SavingsGoal>>

    suspend fun getSavingsGoals(): List<SavingsGoal>

    suspend fun createSavingsGoal(goal: SavingsGoal): Long

    suspend fun deleteSavingsGoal(goal: SavingsGoal)

    suspend fun updateSavingsGoalAmount(goalId: Long, amount: Double)

    suspend fun incrementSavingsGoalAmount(goalId: Long, delta: Double): Boolean
}
