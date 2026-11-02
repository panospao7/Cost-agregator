package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.domain.model.SavingsGoal
import kotlinx.coroutines.flow.Flow

interface SavingsGoalRepository {
    fun observeSavingsGoals(): Flow<List<SavingsGoal>>

    suspend fun getSavingsGoals(): List<SavingsGoal>

    suspend fun createSavingsGoal(goal: SavingsGoal): Long

    /**
     * Deletes the goal only when it is empty (zero balance, no ledger rows, no contribution events).
     * Only [SavingsGoal.id] is used; the passed amounts and name are ignored and the decision is
     * taken against the rows re-read inside one transaction.
     */
    suspend fun deleteSavingsGoal(goal: SavingsGoal): SavingsGoalDeleteResult

    /** Read-only eligibility check using the same facts and policy as [deleteSavingsGoal]. */
    suspend fun getSavingsGoalDeletionEligibility(goalId: Long): SavingsGoalDeleteEligibility

    suspend fun incrementSavingsGoalAmount(goalId: Long, delta: Double): Boolean
}
