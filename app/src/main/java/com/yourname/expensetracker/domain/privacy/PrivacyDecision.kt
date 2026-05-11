package com.yourname.expensetracker.domain.privacy

sealed interface PrivacyDecision {
    data object Allowed : PrivacyDecision
    data object NotApplicable : PrivacyDecision
    data class Denied(val reason: String) : PrivacyDecision
    data class FailClosed(val reason: String) : PrivacyDecision

    /**
     * Returns true if this decision should block execution.
     *
     * P8-PR1: Both [Denied] and [FailClosed] block. [Allowed] proceeds.
     * [NotApplicable] does NOT block — it is for intermediate composite
     * evaluation only; callers that receive it should treat it as a
     * non-decision and either proceed or re-check at a finer grain.
     */
    fun blocksExecution(): Boolean = when (this) {
        is Allowed, is NotApplicable -> false
        is Denied, is FailClosed -> true
    }

    /**
     * Returns the reason string for [Denied] and [FailClosed] decisions,
     * or a sensible default for [Allowed] and [NotApplicable].
     *
     * Safe to call without smart-casting — no subclass access needed.
     */
    fun reason(): String = when (this) {
        is Denied -> reason
        is FailClosed -> reason
        is Allowed -> "Allowed"
        is NotApplicable -> "NotApplicable"
    }
}
