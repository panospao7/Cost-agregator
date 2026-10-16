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

    private class ThrowingAuditLogger(private val error: Exception) : PrivacyAuditLogger {
        override suspend fun logDecision(
            capability: PrivacyCapability,
            decision: PrivacyDecision,
            context: Map<String, String>
        ) {
            throw error
        }
    }

    @Test
    fun `R26 audit write failure on an allowed decision fails closed instead of throwing`() = runTest {
        val composite = CompositePrivacyGate(
            listOf(gateReturning(PrivacyDecision.Allowed)),
            ThrowingAuditLogger(IllegalStateException("database is locked"))
        )

        val decision = composite.check(PrivacyCapability.NOTIFICATION_CAPTURE)

        assertEquals(PrivacyDecision.FailClosed(PrivacyGateReasonCodes.PRIVACY_AUDIT_WRITE_FAILED), decision)
    }

    @Test
    fun `G3 barrier blocked audit write on an allowed decision fails closed with the audit reason`() = runTest {
        val blocked = com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException(
            accessType = com.yourname.expensetracker.data.backup.DatabaseAccessType.WRITE,
            operation = com.yourname.expensetracker.data.backup.DatabaseAccessOperation("PrivacyAuditLoggerImpl.logDecision"),
            mode = com.yourname.expensetracker.data.backup.RestoreMaintenanceMode.Mode.BACKUP_EXPORTING
        )
        val composite = CompositePrivacyGate(
            listOf(gateReturning(PrivacyDecision.Allowed)),
            ThrowingAuditLogger(blocked)
        )

        val decision = composite.check(PrivacyCapability.RAWBACKUP_EXPORT)

        assertEquals(PrivacyDecision.FailClosed(PrivacyGateReasonCodes.PRIVACY_AUDIT_WRITE_FAILED), decision)
    }

    @Test
    fun `R26 audit write failure preserves an already blocking decision`() = runTest {
        val denied = PrivacyDecision.Denied("DENIED_REASON")
        val composite = CompositePrivacyGate(
            listOf(gateReturning(denied)),
            ThrowingAuditLogger(IllegalStateException("audit failure"))
        )

        assertEquals(denied, composite.check(PrivacyCapability.CLOUD_AI_GENERAL))
    }

    @Test
    fun `R26 audit write cancellation propagates the exact exception`() = runTest {
        val cancellation = CancellationException("audit cancelled")
        val composite = CompositePrivacyGate(
            listOf(gateReturning(PrivacyDecision.Allowed)),
            ThrowingAuditLogger(cancellation)
        )

        try {
            composite.check(PrivacyCapability.NOTIFICATION_CAPTURE)
            throw AssertionError("cancellation must propagate")
        } catch (thrown: CancellationException) {
            assertSame(cancellation, thrown)
        }
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
