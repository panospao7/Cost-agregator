package com.yourname.expensetracker.domain.privacy

sealed interface PrivacyDecision {
    data object Allowed : PrivacyDecision
    data object NotApplicable : PrivacyDecision
    data class Denied(val reason: String) : PrivacyDecision
    data class FailClosed(val reason: String) : PrivacyDecision
}
