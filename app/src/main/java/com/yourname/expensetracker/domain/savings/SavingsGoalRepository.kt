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

    /**
     * Records one manual contribution: the balance update and its "manual" history row commit in one
     * database transaction, so they can never diverge. The balance moves only while the goal still
     * matches [expectedSnapshot]. [idempotencyKey] is generated once per user action: a retry of the
     * same action is an [ManualContributionResult.AlreadyRecorded] no-op, never a second add.
     * [timestamp] is the action time and is stored as the history timestamp.
     */
    suspend fun recordManualContribution(
        goalId: Long,
        delta: Double,
        expectedSnapshot: ManualContributionSnapshot,
        idempotencyKey: String,
        timestamp: Long
    ): ManualContributionResult
}

/** The goal state a manual contribution was validated against (goal currency, no conversion). */
data class ManualContributionSnapshot(
    val currentAmount: Double,
    val targetAmount: Double,
    val currency: String
)

/** Terminal outcome of [SavingsGoalRepository.recordManualContribution]. [Rejected] carries a bounded code only. */
sealed interface ManualContributionResult {
    data object Recorded : ManualContributionResult
    data object AlreadyRecorded : ManualContributionResult
    data object SnapshotMismatch : ManualContributionResult
    data class Rejected(val reasonCode: String) : ManualContributionResult
}
