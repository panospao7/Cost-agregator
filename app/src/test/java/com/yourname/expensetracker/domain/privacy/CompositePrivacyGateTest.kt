package com.yourname.expensetracker.domain.privacy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositePrivacyGateTest {

    private class RecordingAuditLogger : PrivacyAuditLogger {
        val decisions = mutableListOf<PrivacyDecision>()

        override suspend fun logDecision(
            capability: PrivacyCapability,
            decision: PrivacyDecision,
            context: Map<String, String>
        ) {
            decisions += decision
        }
    }

    @Test
    fun `delegate cancellation propagates the exact exception and is not audited`() = runTest {
        val cancellation = CancellationException("caller cancelled")
        val auditLogger = RecordingAuditLogger()
        val gate = object : PrivacyGate {
            override suspend fun check(
                capability: PrivacyCapability,
                context: Map<String, String>
            ): PrivacyDecision = throw cancellation
        }
        val composite = CompositePrivacyGate(listOf(gate), auditLogger)

        try {
            composite.check(PrivacyCapability.CLOUD_AI_GENERAL)
            throw AssertionError("cancellation must propagate")
        } catch (thrown: CancellationException) {
            assertSame(cancellation, thrown)
        }

        assertTrue(auditLogger.decisions.isEmpty())
    }

    @Test
    fun `ordinary delegate exception fails closed with controlled reason and is audited`() = runTest {
        val auditLogger = RecordingAuditLogger()
        val gate = object : PrivacyGate {
            override suspend fun check(
                capability: PrivacyCapability,
                context: Map<String, String>
            ): PrivacyDecision = throw IllegalStateException("secret failure")
        }
        val composite = CompositePrivacyGate(listOf(gate), auditLogger)

        val decision = composite.check(PrivacyCapability.CLOUD_AI_GENERAL)

        assertTrue(decision is PrivacyDecision.FailClosed)
        assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, decision.reason())
        assertEquals(
            listOf(PrivacyDecision.FailClosed(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE)),
            auditLogger.decisions
        )
    }

    @Test
    fun `allowed denied fail closed and not applicable composition remains unchanged`() = runTest {
        val auditLogger = RecordingAuditLogger()
        val allowed = gateReturning(PrivacyDecision.Allowed)
        val denied = gateReturning(PrivacyDecision.Denied("DENIED_REASON"))
        val failClosed = gateReturning(PrivacyDecision.FailClosed("FAIL_CLOSED_REASON"))
        val notApplicable = gateReturning(PrivacyDecision.NotApplicable)

        assertEquals(
            PrivacyDecision.Allowed,
            CompositePrivacyGate(listOf(notApplicable, allowed), auditLogger)
                .check(PrivacyCapability.CLOUD_AI_GENERAL)
        )
        assertEquals(
            PrivacyDecision.Denied("DENIED_REASON"),
            CompositePrivacyGate(listOf(denied, allowed), auditLogger)
                .check(PrivacyCapability.CLOUD_AI_GENERAL)
        )
        assertEquals(
            PrivacyDecision.FailClosed("FAIL_CLOSED_REASON"),
            CompositePrivacyGate(listOf(failClosed, allowed), auditLogger)
                .check(PrivacyCapability.CLOUD_AI_GENERAL)
        )
    }

    private fun gateReturning(decision: PrivacyDecision) = object : PrivacyGate {
        override suspend fun check(
            capability: PrivacyCapability,
            context: Map<String, String>
        ): PrivacyDecision = decision
    }
}
