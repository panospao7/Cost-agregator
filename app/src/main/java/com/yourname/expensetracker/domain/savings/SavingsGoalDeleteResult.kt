package com.yourname.expensetracker.domain.savings

/** Terminal outcome of deleting a savings goal. [Rejected] carries a bounded reason code only. */
sealed interface SavingsGoalDeleteResult {
    data object Deleted : SavingsGoalDeleteResult
    data object AlreadyAbsent : SavingsGoalDeleteResult
    data class Rejected(val reasonCode: String) : SavingsGoalDeleteResult
}

/** Read-only answer to "could this goal be deleted right now". Mutates nothing. */
sealed interface SavingsGoalDeleteEligibility {
    data object Deletable : SavingsGoalDeleteEligibility
    data object AlreadyAbsent : SavingsGoalDeleteEligibility
    data class Rejected(val reasonCode: String) : SavingsGoalDeleteEligibility
}
