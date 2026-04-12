package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import kotlinx.coroutines.flow.Flow

@Dao
interface SavingsGoalDao {
    @Query("SELECT * FROM savings_goals")
    fun getAllGoals(): Flow<List<SavingsGoal>>

    @Query("SELECT * FROM savings_goals WHERE id = :goalId")
    suspend fun getById(goalId: Long): SavingsGoal?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoal(goal: SavingsGoal): Long

    @Delete
    suspend fun deleteGoal(goal: SavingsGoal)

    @Query("UPDATE savings_goals SET currentAmount = :amount WHERE id = :goalId")
    suspend fun updateGoalAmount(goalId: Long, amount: Double)

    /**
     * Atomically add [delta] to the current amount for a savings goal.
     *
     * This avoids the read-then-modify-then-write race that occurs when the
     * caller reads [currentAmount], adds locally, and then calls
     * [updateGoalAmount].  Two concurrent contributions can no longer
     * overwrite each other.
     *
     * @return the number of rows updated (0 if the goal does not exist).
     */
    @Query("UPDATE savings_goals SET currentAmount = currentAmount + :delta WHERE id = :goalId")
    suspend fun addToGoalAmount(goalId: Long, delta: Double): Int
}
