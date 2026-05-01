package com.yourname.expensetracker.domain.privacy

/**
 * Composite [PrivacyGate] that delegates to a chain of gate implementations.
 *
 * Each [check] call iterates through [gates] in order. The first gate that
 * returns a [PrivacyDecision.Denied] short-circuits and returns that decision.
 * If all gates return [PrivacyDecision.Allowed] (or "Allow" for a capability
 * they do not handle), the composite returns [PrivacyDecision.Allowed].
 */
class CompositePrivacyGate(
    private val gates: List<PrivacyGate>
) : PrivacyGate {

    override suspend fun check(
        capability: PrivacyCapability,
        context: Map<String, String>
    ): PrivacyDecision {
        for (gate in gates) {
            val decision = gate.check(capability, context)
            if (decision is PrivacyDecision.Denied) {
                return decision
            }
        }
        return PrivacyDecision.Allowed
    }
}
