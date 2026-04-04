package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.domain.model.SavingsGoal
import kotlinx.coroutines.flow.Flow

interface SavingsGoalRepository {
    fun observeSavingsGoals(): Flow<List<SavingsGoal>>
}
