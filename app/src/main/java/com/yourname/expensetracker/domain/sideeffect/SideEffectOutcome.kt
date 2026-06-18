package com.yourname.expensetracker.domain.sideeffect

sealed interface SideEffectOutcome {
    data object Completed : SideEffectOutcome
    data class Skipped(val reason: SideEffectSkipReason) : SideEffectOutcome
    data class FailedRetryable(val reason: String, val errorClass: String? = null) : SideEffectOutcome
    data class FailedFinal(val reason: String, val errorClass: String? = null) : SideEffectOutcome
    data class Cancelled(val reason: String? = null) : SideEffectOutcome
}
