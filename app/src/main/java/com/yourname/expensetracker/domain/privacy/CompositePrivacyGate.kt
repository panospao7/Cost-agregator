package com.yourname.expensetracker.domain.privacy

import timber.log.Timber

/**
 * Composite [PrivacyGate] that delegates to a chain of gate implementations.
 *
 * S3-012: If a capability is in [gateHandledCapabilities] but no concrete gate
 * handles it (all return NotApplicable), the composite returns FailClosed instead
 * of the previous fail-open Allowed default.
 */
class CompositePrivacyGate(
    private val gates: List<PrivacyGate>,
    private val auditLogger: PrivacyAuditLogger,
    /** Capabilities that must be handled by at least one gate — fail-closed if not. */
    private val gateHandledCapabilities: Set<PrivacyCapability> = emptySet()
) : PrivacyGate {

    override suspend fun check(
        capability: PrivacyCapability,
        context: Map<String, String>
    ): PrivacyDecision {
        var finalDecision: PrivacyDecision = PrivacyDecision.Allowed
        var anyGateHandled = false
        for (gate in gates) {
            val decision = try {
                gate.check(capability, context)
            } catch (e: Exception) {
                Timber.e(e, "Privacy gate threw for capability %s — failing closed", capability)
                finalDecision = PrivacyDecision.FailClosed(
                    "Privacy check failed (fail-closed): ${e.message}"
                )
                anyGateHandled = true
                break
            }
            when (decision) {
                is PrivacyDecision.Denied -> {
                    finalDecision = decision
                    anyGateHandled = true
                    break
                }
                is PrivacyDecision.FailClosed -> {
                    finalDecision = decision
                    anyGateHandled = true
                    break
                }
                is PrivacyDecision.Allowed -> {
                    anyGateHandled = true
                }
                else -> { /* NotApplicable — gate does not handle this capability */ }
            }
        }
        // S3-012: Fail-closed for GATE_HANDLED capabilities with no handler
        if (!anyGateHandled) {
            if (capability in gateHandledCapabilities) {
                Timber.e("No privacy gate handled GATE_HANDLED capability %s — failing closed", capability)
                finalDecision = PrivacyDecision.FailClosed("No privacy gate handled $capability")
            } else {
                Timber.w("No privacy gate handled capability %s — defaulting to Allowed (local-only)", capability)
            }
        }
        auditLogger.logDecision(capability, finalDecision, context)
        return finalDecision
    }
}
