package com.yourname.expensetracker.domain.privacy

import timber.log.Timber

/**
 * Composite [PrivacyGate] that delegates to a chain of gate implementations.
 *
 * Each [check] call iterates through [gates] in order. The first gate that
 * returns a [PrivacyDecision.Denied] or [PrivacyDecision.FailClosed]
 * short-circuits and returns that decision. If all gates return
 * [PrivacyDecision.Allowed] or [PrivacyDecision.NotApplicable], the composite
 * returns [PrivacyDecision.Allowed].
 *
 * P8-P1-03: Only the composite gate writes the final audit event. Concrete
 * gates no longer log decisions for capabilities they don't handle (they
 * return [PrivacyDecision.NotApplicable] instead of [PrivacyDecision.Allowed]).
 *
 * P8-P1-03: Fail-closed exceptions now produce [PrivacyDecision.FailClosed]
 * which carries a distinguishing type for durable audit recording.
 */
class CompositePrivacyGate(
    private val gates: List<PrivacyGate>,
    private val auditLogger: PrivacyAuditLogger
) : PrivacyGate {

    override suspend fun check(
        capability: PrivacyCapability,
        context: Map<String, String>
    ): PrivacyDecision {
        var finalDecision: PrivacyDecision = PrivacyDecision.Allowed
        for (gate in gates) {
            val decision = try {
                gate.check(capability, context)
            } catch (e: Exception) {
                Timber.e(e, "Privacy gate threw for capability %s — failing closed", capability)
                finalDecision = PrivacyDecision.FailClosed(
                    "Privacy check failed (fail-closed): ${e.message}"
                )
                break
            }
            when (decision) {
                is PrivacyDecision.Denied -> {
                    finalDecision = decision
                    break
                }
                is PrivacyDecision.FailClosed -> {
                    finalDecision = decision
                    break
                }
                else -> { }
            }
        }
        auditLogger.logDecision(capability, finalDecision, context)
        return finalDecision
    }
}
