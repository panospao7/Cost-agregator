package com.yourname.expensetracker.domain.privacy

sealed interface PrivacyDecision {
    data object Allowed : PrivacyDecision
    data class Denied(val reason: String) : PrivacyDecision
}
