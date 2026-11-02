package com.yourname.expensetracker.domain.savings

/** Bounded rejection codes for savings-goal deletion. Never carry names, ids, amounts or messages. */
const val SAVINGS_GOAL_DELETE_HAS_BALANCE = "SAVINGS_GOAL_DELETE_HAS_BALANCE"
const val SAVINGS_GOAL_DELETE_HAS_HISTORY = "SAVINGS_GOAL_DELETE_HAS_HISTORY"
const val SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE = "SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE"
const val SAVINGS_GOAL_DELETE_UNAVAILABLE = "SAVINGS_GOAL_DELETE_UNAVAILABLE"

/** Outcome of applying [SavingsGoalDeletionPolicy] to the facts of an existing goal. */
sealed interface SavingsGoalDeletionDecision {
    data object Deletable : SavingsGoalDeletionDecision
    data class Rejected(val reasonCode: String) : SavingsGoalDeletionDecision
}

/**
 * Single owner of the "empty goal" rule (INV-1, INV-16, INV-18).
 *
 * Pure function of its inputs: no I/O, no logging, no arithmetic. A missing goal is handled by
 * the repository before this policy is consulted.
 */
object SavingsGoalDeletionPolicy {
    /**
     * Precedence for an existing goal:
     * 1. balance not exactly zero (negative, NaN, infinite or positive) -> HAS_BALANCE;
     * 2. any ledger row or contribution event for the goal -> HAS_HISTORY;
     * 3. history unreadable -> HISTORY_UNAVAILABLE (unavailable is never "no history");
     * 4. otherwise Deletable.
     *
     * Negative zero compares equal to zero and therefore counts as an empty balance.
     */
    fun evaluate(
        balance: Double,
        hasHistory: Boolean,
        historyUnavailable: Boolean
    ): SavingsGoalDeletionDecision {
        val balanceIsZero = balance == 0.0
        if (!balanceIsZero) {
            return SavingsGoalDeletionDecision.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE)
        }
        if (hasHistory) {
            return SavingsGoalDeletionDecision.Rejected(SAVINGS_GOAL_DELETE_HAS_HISTORY)
        }
        if (historyUnavailable) {
            return SavingsGoalDeletionDecision.Rejected(SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE)
        }
        return SavingsGoalDeletionDecision.Deletable
    }
}
