package com.yourname.expensetracker.domain.privacy

import kotlinx.coroutines.CancellationException
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // RP-15 (15-B): the failure reason is a bounded controlled code.
                // The exception text is NEVER included; diagnostics use the
                // exception CLASS NAME only (no stack trace, no message).
                Timber.e(
                    "Privacy gate threw %s for capability %s — failing closed",
                    e::class.java.name, capability.name
                )
                finalDecision = PrivacyDecision.FailClosed(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE)
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
                finalDecision = PrivacyDecision.FailClosed(PrivacyGateReasonCodes.NO_GATE_HANDLER)
            } else {
                Timber.w("No privacy gate handled capability %s — defaulting to Allowed (local-only)", capability)
            }
        }
        // R26 (owner-approved): an audit-write failure (e.g. SQLITE_BUSY, write barrier) must never
        // escape to the caller and crash it. A decision that already blocks is returned unchanged;
        // otherwise the gate fails closed, because an unaudited allow is not permitted. No retry.
        try {
            auditLogger.logDecision(capability, finalDecision, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(
                "Privacy audit write failed with %s for capability %s — failing closed",
                e::class.java.name, capability.name
            )
            if (finalDecision is PrivacyDecision.Denied || finalDecision is PrivacyDecision.FailClosed) {
                return finalDecision
            }
            return PrivacyDecision.FailClosed(PrivacyGateReasonCodes.PRIVACY_AUDIT_WRITE_FAILED)
        }
        return finalDecision
    }
}
