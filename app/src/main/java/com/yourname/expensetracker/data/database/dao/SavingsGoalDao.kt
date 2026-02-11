package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import kotlinx.coroutines.flow.Flow

@Dao
interface SavingsGoalDao {
    @Query("SELECT * FROM savings_goals")
    fun getAllGoals(): Flow<List<SavingsGoal>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoal(goal: SavingsGoal): Long

    @Delete
    suspend fun deleteGoal(goal: SavingsGoal)
}
