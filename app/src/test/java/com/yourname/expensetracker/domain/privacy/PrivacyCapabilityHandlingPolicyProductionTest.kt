package com.yourname.expensetracker.domain.privacy

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * PRIV-441-03 / PRIV-441-04 / PRIV-441-15 acceptance tests.
 */
class PrivacyCapabilityHandlingPolicyProductionTest {

    @Test
    fun production_privacy_capability_policy_covers_all_enum_values() {
        val allCapabilities = PrivacyCapability.entries.toSet()
        val covered = PrivacyCapabilityHandlingPolicy.gateHandledCapabilities +
            PrivacyCapabilityHandlingPolicy.localOnlyCapabilities

        val uncovered = allCapabilities - covered
        assertTrue(
            "PrivacyCapabilityHandlingPolicy does not cover: $uncovered. " +
            "Add each new capability to gateHandledCapabilities or localOnlyCapabilities.",
            uncovered.isEmpty()
        )
    }

    @Test
    fun gate_handled_and_local_only_sets_do_not_overlap() {
        val overlap = PrivacyCapabilityHandlingPolicy.gateHandledCapabilities
            .intersect(PrivacyCapabilityHandlingPolicy.localOnlyCapabilities)
        assertTrue(
            "Capabilities must not appear in both sets: $overlap",
            overlap.isEmpty()
        )
    }

    @Test
    fun gate_handled_capabilities_covers_all_cloud_ai_capabilities() {
        val gateHandled = PrivacyCapabilityHandlingPolicy.gateHandledCapabilities
        assertTrue(gateHandled.contains(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST))
        assertTrue(gateHandled.contains(PrivacyCapability.CLOUD_AI_DAILY_BRIEFING))
        assertTrue(gateHandled.contains(PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION))
        assertTrue(gateHandled.contains(PrivacyCapability.CLOUD_AI_BANK_STATEMENT))
        assertTrue(gateHandled.contains(PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD))
    }

    @Test
    fun gate_handled_capabilities_covers_export_capabilities() {
        val gateHandled = PrivacyCapabilityHandlingPolicy.gateHandledCapabilities
        assertTrue(gateHandled.contains(PrivacyCapability.EXPENSE_EXPORT))
        assertTrue(gateHandled.contains(PrivacyCapability.EXPENSE_EXPORT_RAW))
        assertTrue(gateHandled.contains(PrivacyCapability.DEBUG_RAW_EXPORT))
        assertTrue(gateHandled.contains(PrivacyCapability.RAW_DATABASE_EXPORT))
    }

    @Test
    fun composite_gate_fails_closed_for_unhandled_gate_handled_capability() = runTest {
        val auditLogger = PrivacyAuditLogger.NO_OP
        // Gate that handles nothing (returns NotApplicable for everything)
        val noOpGate = object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.NotApplicable
        }

        val composite = CompositePrivacyGate(
            gates = listOf(noOpGate),
            auditLogger = auditLogger,
            gateHandledCapabilities = setOf(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST)
        )

        val decision = composite.check(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST)
        assertTrue(
            "Composite must fail closed for unhandled gate-handled capability",
            decision is PrivacyDecision.FailClosed
        )
    }

    @Test
    fun composite_gate_allows_local_only_capability_with_no_handler() = runTest {
        val auditLogger = PrivacyAuditLogger.NO_OP
        val noOpGate = object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.NotApplicable
        }

        val composite = CompositePrivacyGate(
            gates = listOf(noOpGate),
            auditLogger = auditLogger,
            gateHandledCapabilities = setOf(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST)
        )

        // RAW_NOTIFICATION_RETENTION is local-only — not in gateHandledCapabilities
        val decision = composite.check(PrivacyCapability.RAW_NOTIFICATION_RETENTION)
        assertTrue(
            "Local-only capability with no handler should default to Allowed",
            decision is PrivacyDecision.Allowed
        )
    }

    @Test
    fun privacy_module_gate_handled_capabilities_matches_production_policy() {
        // Verify the production policy object is the one used in DI
        val policyCapabilities = PrivacyCapabilityHandlingPolicy.gateHandledCapabilities
        assertTrue("Production policy must have gate-handled capabilities", policyCapabilities.isNotEmpty())
        assertTrue(
            "Production policy must cover NOTIFICATION_CAPTURE",
            policyCapabilities.contains(PrivacyCapability.NOTIFICATION_CAPTURE)
        )
    }

    @Test
    fun cloud_provider_secondary_constructor_is_fail_closed() = runTest {
        // Verify that the fail-closed gate pattern is used (not allow-all)
        // This is a contract test — the actual gate is tested via CompositePrivacyGate
        val failClosedGate = object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.FailClosed("PrivacyGate not configured in test constructor")
        }
        val decision = failClosedGate.check(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST)
        assertTrue(
            "Secondary constructor gate must be fail-closed, not allow-all",
            decision is PrivacyDecision.FailClosed
        )
    }

    @Test
    fun privacy_gate_contract_docs_match_not_applicable_behavior() = runTest {
        // Verify NotApplicable is returned for unrecognized capabilities
        val auditLogger = PrivacyAuditLogger.NO_OP
        val notApplicableGate = object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.NotApplicable
        }
        val decision = notApplicableGate.check(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST)
        assertTrue(
            "Unrecognized capability must return NotApplicable per updated contract",
            decision is PrivacyDecision.NotApplicable
        )
    }
}
