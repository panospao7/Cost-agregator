package com.yourname.expensetracker.domain.privacy

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RP-15 (15-B) — Capability policy and safe reason codes.
 *
 * Covers:
 * 1. Every current cloud capability is allowed when its input columns permit.
 * 2. Global, image, and bank denial paths.
 * 3. Non-cloud / unknown capabilities fail closed (CAPABILITY_NOT_CLOUD).
 * 4. Exact-one enum coverage of the cloud capability registry.
 * 5. Controlled reasons only: requireAllowed messages, composite FailClosed
 *    decisions, and PrivacyBlocked.Custom reasons are bounded codes — exception
 *    text can never reach a decision reason or a blocked state.
 */
class PrivacyCapabilityPolicyTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun policy(
        cloudAllowed: Boolean = true,
        redactBeforeCloud: Boolean = false,
        image: Boolean = true,
        bank: Boolean = true
    ) = EffectiveCloudAiPolicy(
        cloudAllowed = cloudAllowed,
        reason = if (cloudAllowed) null else "Privacy settings: cloud AI disabled",
        redactBeforeCloud = redactBeforeCloud,
        receiptImageUploadAllowed = image,
        bankStatementCloudAllowed = bank
    )

    private val cloudCapabilities = listOf(
        PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST,
        PrivacyCapability.CLOUD_AI_RECEIPT_OCR,
        PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD,
        PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION,
        PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION,
        PrivacyCapability.CLOUD_AI_DAILY_BRIEFING,
        PrivacyCapability.CLOUD_AI_BANK_STATEMENT,
        PrivacyCapability.AI_BANK_STATEMENT_PARSING,
        PrivacyCapability.CLOUD_AI_GENERAL
    )

    /** A "future" capability name is simulated by all currently non-cloud values. */
    private val nonCloudCapabilities = PrivacyCapability.entries.filter { it !in cloudCapabilities }

    private class CapturingAuditLogger : PrivacyAuditLogger {
        val decisions = mutableListOf<PrivacyDecision>()
        override suspend fun logDecision(
            capability: PrivacyCapability,
            decision: PrivacyDecision,
            context: Map<String, String>
        ) {
            decisions += decision
        }
    }

    // ── 1. Every current capability allowed when inputs permit ────────────────

    @Test
    fun `every_registered_cloud_capability_is_allowed_with_all_inputs_permitted`() {
        val allAllowed = policy(cloudAllowed = true, redactBeforeCloud = false, image = true, bank = true)
        for (capability in cloudCapabilities) {
            allAllowed.requireAllowed(capability) // must not throw
        }
    }

    @Test
    fun `global_only_capabilities_require_nothing_beyond_global_flag`() {
        val globalOnly = listOf(
            PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST,
            PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION,
            PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION,
            PrivacyCapability.CLOUD_AI_DAILY_BRIEFING,
            PrivacyCapability.CLOUD_AI_GENERAL
        )
        // Dedicated settings off (image/bank false) — global-only capabilities still allowed.
        val p = policy(cloudAllowed = true, redactBeforeCloud = true, image = false, bank = false)
        for (capability in globalOnly) {
            p.requireAllowed(capability) // global-only is the explicit product decision
        }
    }

    // ── 2. Global / image / bank denial ───────────────────────────────────────

    @Test
    fun `global_denial_blocks_every_registered_capability_with_controlled_code`() {
        val denied = policy(cloudAllowed = false)
        for (capability in cloudCapabilities) {
            val e = try {
                denied.requireAllowed(capability)
                null
            } catch (e: SecurityException) {
                e
            }
            assertTrue("capability $capability must be denied", e != null)
            assertEquals(
                "capability $capability must use the controlled global code",
                PrivacyGateReasonCodes.CLOUD_AI_DISABLED,
                e!!.message
            )
        }
    }

    @Test
    fun `image_denial_blocks_image_column_with_controlled_code`() {
        val p = policy(cloudAllowed = true, redactBeforeCloud = false, image = false, bank = true)
        // Image column: OCR and image upload denied.
        for (capability in listOf(
            PrivacyCapability.CLOUD_AI_RECEIPT_OCR,
            PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD
        )) {
            val e = try {
                p.requireAllowed(capability); null
            } catch (e: SecurityException) {
                e
            }
            assertEquals(
                "capability $capability must use the image code",
                PrivacyGateReasonCodes.RECEIPT_IMAGE_UPLOAD_DISABLED,
                e?.message
            )
        }
        // Non-image columns still allowed.
        p.requireAllowed(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST)
        p.requireAllowed(PrivacyCapability.CLOUD_AI_BANK_STATEMENT)
    }

    @Test
    fun `image_upload_suppressed_while_redaction_required_with_controlled_code`() {
        val p = policy(cloudAllowed = true, redactBeforeCloud = true, image = true, bank = true)
        val e = try {
            p.requireAllowed(PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD); null
        } catch (e: SecurityException) {
            e
        }
        assertEquals(PrivacyGateReasonCodes.IMAGE_UPLOAD_REDACTION_REQUIRED, e?.message)
        // OCR uses the plain IMAGE column and is unaffected by the redaction flag.
        p.requireAllowed(PrivacyCapability.CLOUD_AI_RECEIPT_OCR)
    }

    @Test
    fun `bank_denial_blocks_bank_column_with_controlled_code`() {
        val p = policy(cloudAllowed = true, image = true, bank = false)
        for (capability in listOf(
            PrivacyCapability.CLOUD_AI_BANK_STATEMENT,
            PrivacyCapability.AI_BANK_STATEMENT_PARSING
        )) {
            val e = try {
                p.requireAllowed(capability); null
            } catch (e: SecurityException) {
                e
            }
            assertEquals(
                "capability $capability must use the bank code",
                PrivacyGateReasonCodes.BANK_STATEMENT_AI_DISABLED,
                e?.message
            )
        }
        p.requireAllowed(PrivacyCapability.CLOUD_AI_GENERAL)
    }

    // ── 3. Non-cloud / future capabilities fail closed ────────────────────────

    @Test
    fun `non_cloud_and_future_capabilities_fail_closed`() {
        val p = policy() // everything permitted — the only reason to fail is non-registration
        assertTrue("fixture must exercise at least the known non-cloud values", nonCloudCapabilities.isNotEmpty())
        for (capability in nonCloudCapabilities) {
            val e = try {
                p.requireAllowed(capability); null
            } catch (e: SecurityException) {
                e
            }
            assertTrue("non-cloud capability $capability must be denied", e != null)
            assertEquals(
                "capability $capability must use CAPABILITY_NOT_CLOUD",
                PrivacyGateReasonCodes.CAPABILITY_NOT_CLOUD,
                e!!.message
            )
        }
    }

    // ── 4. Exact-one enum coverage ────────────────────────────────────────────

    @Test
    fun `registry_covers_each_capability_at_most_once_and_all_nine_cloud_values`() {
        // A map cannot hold duplicate keys; assert the registry's observable
        // coverage is exactly the nine cloud capabilities.
        assertEquals(9, cloudCapabilities.size)
        assertEquals(cloudCapabilities.size, cloudCapabilities.toSet().size)

        // Behavioral exact-one proof: with all inputs ON, every cloud value passes
        // and every other value fails — the classification is total and disjoint.
        val p = policy()
        for (capability in PrivacyCapability.entries) {
            val allowed = try {
                p.requireAllowed(capability); true
            } catch (e: SecurityException) {
                assertEquals(
                    "the only failure mode with everything permitted is non-registration",
                    PrivacyGateReasonCodes.CAPABILITY_NOT_CLOUD,
                    e.message
                )
                false
            }
            assertEquals("capability $capability classification", capability in cloudCapabilities, allowed)
        }
    }

    // ── 5. Controlled reasons only ────────────────────────────────────────────

    @Test
    fun `composite_gate_turns_gate_exception_into_controlled_code_without_exception_text`() = runTest {
        val secret = "SECRET_DB_CONNECTION_STRING"
        val throwingGate = object : PrivacyGate {
            override suspend fun check(
                capability: PrivacyCapability,
                context: Map<String, String>
            ): PrivacyDecision = throw IllegalStateException("leaky failure: $secret")
        }
        val audit = CapturingAuditLogger()
        val composite = CompositePrivacyGate(
            gates = listOf(throwingGate),
            auditLogger = audit,
            gateHandledCapabilities = setOf(PrivacyCapability.CLOUD_AI_GENERAL)
        )
        val decision = composite.check(PrivacyCapability.CLOUD_AI_GENERAL)
        assertTrue(decision is PrivacyDecision.FailClosed)
        assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, decision.reason())
        assertFalse("exception text must not leak into the decision", decision.reason().contains(secret))
        assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, (audit.decisions.single() as PrivacyDecision.FailClosed).reason)
    }

    @Test
    fun `composite_gate_unhandled_gate_handled_capability_uses_controlled_code`() = runTest {
        val emptyGate = object : PrivacyGate {
            override suspend fun check(
                capability: PrivacyCapability,
                context: Map<String, String>
            ): PrivacyDecision = PrivacyDecision.NotApplicable
        }
        val composite = CompositePrivacyGate(
            gates = listOf(emptyGate),
            auditLogger = PrivacyAuditLogger.NO_OP,
            gateHandledCapabilities = setOf(PrivacyCapability.CLOUD_AI_GENERAL)
        )
        val decision = composite.check(PrivacyCapability.CLOUD_AI_GENERAL)
        assertEquals(PrivacyGateReasonCodes.NO_GATE_HANDLER, decision.reason())
    }

    @Test
    fun `fail_closed_decision_maps_to_custom_with_controlled_code_only`() {
        val leaky = PrivacyDecision.FailClosed("Privacy check failed: SECRET_INTERNAL_DETAIL")
        val blocked = leaky.toPrivacyBlocked(PrivacyCapability.CLOUD_AI_GENERAL)
        assertTrue(blocked is PrivacyBlocked.Custom)
        assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, blocked!!.reason)
    }

    @Test
    fun `denied_reason_text_is_not_carried_into_blocked_state`() {
        val denied = PrivacyDecision.Denied("Bank statement AI is disabled; internal detail X")
        val blocked = denied.toPrivacyBlocked(PrivacyCapability.CLOUD_AI_BANK_STATEMENT)
        assertTrue(blocked is PrivacyBlocked.BankStatementAiDisabled)
        assertEquals(
            "typed blocked reasons are static defaults only",
            PrivacyBlocked.BankStatementAiDisabled().reason,
            blocked!!.reason
        )
    }

    @Test
    fun `fallback_mapping_uses_controlled_code_for_permissive_decisions`() {
        for (decision in listOf(PrivacyDecision.Allowed, PrivacyDecision.NotApplicable)) {
            val blocked = decision.toPrivacyBlockedOrFallback(PrivacyCapability.CLOUD_AI_GENERAL)
            assertTrue(blocked is PrivacyBlocked.Custom)
            assertEquals(PrivacyGateReasonCodes.PRIVACY_BLOCKED, blocked.reason)
        }
        // Denied/FailClosed still map through the primary path.
        assertTrue(
            PrivacyDecision.Denied("bank off")
                .toPrivacyBlockedOrFallback(PrivacyCapability.CLOUD_AI_BANK_STATEMENT)
                is PrivacyBlocked.BankStatementAiDisabled
        )
    }

    @Test
    fun `allowed_and_not_applicable_map_to_null`() {
        assertNull(PrivacyDecision.Allowed.toPrivacyBlocked(PrivacyCapability.CLOUD_AI_GENERAL))
        assertNull(PrivacyDecision.NotApplicable.toPrivacyBlocked(PrivacyCapability.CLOUD_AI_GENERAL))
    }

    @Test
    fun `reason_code_registry_is_bounded_and_all_messages_are_members`() {
        // Every message requireAllowed can throw must be a member of the registry.
        val observed = mutableSetOf<String>()
        for (cloudAllowed in listOf(true, false)) {
            for (redact in listOf(true, false)) {
                for (image in listOf(true, false)) {
                    for (bank in listOf(true, false)) {
                        val p = policy(cloudAllowed, redact, image, bank)
                        for (capability in PrivacyCapability.entries) {
                            try {
                                p.requireAllowed(capability)
                            } catch (e: SecurityException) {
                                observed += e.message ?: "null"
                            }
                        }
                    }
                }
            }
        }
        assertTrue(observed.isNotEmpty())
        assertTrue(
            "all observed codes must be bounded registry members, got $observed",
            observed.all { it in PrivacyGateReasonCodes.ALL }
        )
    }
}
