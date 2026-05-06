package com.yourname.expensetracker.domain.privacy

import timber.log.Timber

/**
 * Composite [PrivacyGate] that delegates to a chain of gate implementations.
 *
 * Each [check] call iterates through [gates] in order. The first gate that
 * returns a [PrivacyDecision.Denied] short-circuits and returns that decision.
 * If all gates return [PrivacyDecision.Allowed] (or "Allow" for a capability
 * they do not handle), the composite returns [PrivacyDecision.Allowed].
 *
 * Fail-closed: if any gate throws an exception (e.g. DataStore corruption),
 * the composite returns [PrivacyDecision.Denied] rather than propagating.
 */
class CompositePrivacyGate(
    private val gates: List<PrivacyGate>
) : PrivacyGate {

    override suspend fun check(
        capability: PrivacyCapability,
        context: Map<String, String>
    ): PrivacyDecision {
        for (gate in gates) {
            val decision = try {
                gate.check(capability, context)
            } catch (e: Exception) {
                Timber.e(e, "Privacy gate threw for capability %s — failing closed", capability)
                return PrivacyDecision.Denied("Privacy check failed (fail-closed): ${e.message}")
            }
            if (decision is PrivacyDecision.Denied) {
                return decision
            }
        }
        return PrivacyDecision.Allowed
    }
}
