package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.SavingsGoalDao
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SavingsGoalRepository @Inject constructor(
    private val savingsGoalDao: SavingsGoalDao
) {
    fun getAllGoals(): Flow<List<SavingsGoal>> {
        return savingsGoalDao.getAllGoals()
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
}
