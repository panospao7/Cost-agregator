package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.SQLException

/**
 * RP-16 16-B (D9): durable terminal counter tests.
 *
 * Covers: success / retry after partial work / permanent failure / cancellation /
 * stale abort terminal persistence with a [WorkerRunContext] snapshot, old-caller
 * scalar defaults, null-vs-zero compatibility for partialFailureCount and
 * failedTargetCount, terminal-write failure leaving a safe non-durable state,
 * and NO_WORK measured skipped/errors counters.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkerRunCountersTerminalTest {

    private val dao = mockk<BackgroundJobRunDao>(relaxed = true)
    private val sanitizer = mockk<EventMetadataSanitizer>()
    private val timeProvider = mockk<TimeProvider>()
    private lateinit var logger: WorkerRunLoggerImpl

    @Before
    fun setup() {
        every { timeProvider.now() } returns 1_700_000_000_000L
        every { sanitizer.sanitizeExceptionMessage(any()) } returns null
        logger = WorkerRunLoggerImpl(dao, sanitizer, timeProvider)
    }

    private fun newContext(): WorkerRunContext = WorkerRunContext { /* no-op checkpoint */ }

    @Test
    fun `success with snapshot persists measured counters as scalars`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("worker_a")
        val ctx = newContext()
        ctx.addRowsScanned(7)
        ctx.addRowsUpdated(4)
        ctx.addNotificationsSent(2)
        ctx.addRowsSkipped(3)
        ctx.addErrors(1)

        val outcome = handle.success(snapshotProvider = { ctx.snapshot() })

        assertTrue(outcome is TerminalWriteOutcome.Durable)
        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(handle.runId),
                status = eq("SUCCESS"),
                finishedAt = any(),
                rowsScanned = eq(7),
                rowsUpdated = eq(4),
                notificationsSent = eq(2),
                rowsSkipped = eq(3),
                errors = eq(1),
                statusReason = any(),
                retryReason = any(),
                errorMessage = any(),
                errorClass = any(),
                cancellationReason = any(),
                terminalReasonCode = any(),
                terminalDiagnosticCode = any(),
                partialFailureCount = isNull(),
                failedTargetCount = isNull()
            )
        }
    }

    @Test
    fun `retry after partial work persists the measured snapshot`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("worker_a")
        val ctx = newContext()
        ctx.addRowsScanned(10)
        ctx.addRowsUpdated(6)
        ctx.addErrors(2)
        ctx.setPartialFailureCount(1)
        ctx.setFailedTargetCount(1)

        val outcome = handle.retry(DiagnosticFriendlyCode.RETRYABLE, snapshotProvider = { ctx.snapshot() })

        assertTrue(outcome is TerminalWriteOutcome.Durable)
        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(handle.runId),
                status = eq("RETRY"),
                finishedAt = any(),
                rowsScanned = eq(10),
                rowsUpdated = eq(6),
                rowsSkipped = eq(0),
                errors = eq(2),
                statusReason = any(),
                retryReason = any(),
                errorMessage = any(),
                errorClass = any(),
                cancellationReason = any(),
                terminalReasonCode = any(),
                terminalDiagnosticCode = any(),
                partialFailureCount = eq(1),
                failedTargetCount = eq(1)
            )
        }
    }

    @Test
    fun `permanent failure persists snapshot and null-vs-zero semantics`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("worker_a")
        val ctx = newContext()
        ctx.addRowsScanned(5)
        ctx.addRowsSkipped(2)
        ctx.setPartialFailureCount(0) // measured zero
        // failedTargetCount left null → unknown / not supplied

        val outcome = handle.failure(DiagnosticFriendlyCode.FINAL, snapshotProvider = { ctx.snapshot() })

        assertTrue(outcome is TerminalWriteOutcome.Durable)
        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(handle.runId),
                status = eq("FAILED"),
                finishedAt = any(),
                rowsScanned = eq(5),
                rowsSkipped = eq(2),
                errors = eq(0),
                statusReason = any(),
                retryReason = any(),
                errorMessage = any(),
                errorClass = any(),
                cancellationReason = any(),
                terminalReasonCode = any(),
                terminalDiagnosticCode = any(),
                partialFailureCount = eq(0),
                failedTargetCount = isNull()
            )
        }
    }

    @Test
    fun `cancellation persists snapshot`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("worker_a")
        val ctx = newContext()
        ctx.addRowsUpdated(3)
        ctx.addRowsSkipped(1)

        val outcome = handle.cancelled("WORKER_CANCELLED", snapshotProvider = { ctx.snapshot() })

        assertTrue(outcome is TerminalWriteOutcome.Durable)
        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(handle.runId),
                status = eq("CANCELLED"),
                finishedAt = any(),
                rowsScanned = any(),
                rowsUpdated = eq(3),
                notificationsSent = any(),
                rowsSkipped = eq(1),
                errors = any(),
                statusReason = any(),
                retryReason = any(),
                errorMessage = any(),
                errorClass = any(),
                cancellationReason = any(),
                terminalReasonCode = any(),
                terminalDiagnosticCode = any(),
                partialFailureCount = any(),
                failedTargetCount = any()
            )
        }
    }

    @Test
    fun `stale abort without snapshot uses old scalar defaults`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("worker_a")

        val outcome = handle.staleAborted()

        assertTrue(outcome is TerminalWriteOutcome.Durable)
        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(handle.runId),
                status = eq("STALE_ABORTED"),
                finishedAt = any(),
                rowsScanned = eq(0),
                rowsUpdated = eq(0),
                notificationsSent = eq(0),
                rowsSkipped = eq(0),
                errors = eq(0),
                statusReason = any(),
                retryReason = any(),
                errorMessage = any(),
                errorClass = any(),
                cancellationReason = any(),
                terminalReasonCode = any(),
                terminalDiagnosticCode = any(),
                partialFailureCount = isNull(),
                failedTargetCount = isNull()
            )
        }
    }

    @Test
    fun `old callers without snapshot keep legacy defaults`() = runTest {
        coEvery { dao.insert(any()) } returns 1L andThen 2L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("worker_a")

        val outcome = handle.success(rowsScanned = 9, rowsUpdated = 8, notificationsSent = 7)
        assertTrue(outcome is TerminalWriteOutcome.Durable)

        // A terminal handle is once-only (PR12B): a second terminal call on the
        // same handle is a local no-op, so the SKIPPED legacy-default leg uses
        // a fresh run.
        val skippedHandle = logger.start("worker_a")
        val skippedOutcome = skippedHandle.skipped("PRIVACY_DENIED")
        assertTrue(skippedOutcome is TerminalWriteOutcome.Durable)

        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(1L),
                status = eq("SUCCESS"),
                finishedAt = any(),
                rowsScanned = eq(9),
                rowsUpdated = eq(8),
                notificationsSent = eq(7),
                rowsSkipped = eq(0),
                errors = eq(0),
                statusReason = any(),
                retryReason = any(),
                errorMessage = any(),
                errorClass = any(),
                cancellationReason = any(),
                terminalReasonCode = any(),
                terminalDiagnosticCode = any(),
                partialFailureCount = isNull(),
                failedTargetCount = isNull()
            )
        }
        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(2L),
                status = eq("SKIPPED"),
                finishedAt = any(),
                rowsScanned = eq(0),
                rowsUpdated = eq(0),
                notificationsSent = eq(0),
                rowsSkipped = eq(0),
                errors = eq(0),
                statusReason = any(),
                retryReason = any(),
                errorMessage = any(),
                errorClass = any(),
                cancellationReason = any(),
                terminalReasonCode = any(),
                terminalDiagnosticCode = any(),
                partialFailureCount = isNull(),
                failedTargetCount = isNull()
            )
        }
    }

    @Test
    fun `terminal write failure leaves safe non-durable state and handle stays retryable`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery {
            dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws SQLException("db error") andThen 1
        val handle = logger.start("worker_a")
        val ctx = newContext()
        ctx.addRowsSkipped(4)

        val first = handle.failure(DiagnosticFriendlyCode.FINAL, snapshotProvider = { ctx.snapshot() })
        assertTrue(first is TerminalWriteOutcome.NotDurable)

        // Retry of the terminal write still reports the same measured counters.
        val second = handle.success(snapshotProvider = { ctx.snapshot() })
        assertTrue(second is TerminalWriteOutcome.Durable)
        coVerify(atLeast = 2) {
            dao.completeTerminal(
                id = eq(handle.runId),
                status = any(),
                finishedAt = any(),
                rowsScanned = any(),
                rowsUpdated = any(),
                notificationsSent = any(),
                rowsSkipped = eq(4),
                errors = any(),
                statusReason = any(),
                retryReason = any(),
                errorMessage = any(),
                errorClass = any(),
                cancellationReason = any(),
                terminalReasonCode = any(),
                terminalDiagnosticCode = any(),
                partialFailureCount = any(),
                failedTargetCount = any()
            )
        }
    }

    @Test
    fun `failing snapshot provider is treated as not supplied, never as fake zeros`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("worker_a")

        val outcome = handle.success(snapshotProvider = { throw IllegalStateException("snapshot unavailable") })

        assertTrue(outcome is TerminalWriteOutcome.Durable)
        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(handle.runId),
                status = eq("SUCCESS"),
                finishedAt = any(),
                rowsScanned = eq(0),
                rowsUpdated = eq(0),
                notificationsSent = eq(0),
                rowsSkipped = eq(0),
                errors = eq(0),
                statusReason = any(),
                retryReason = any(),
                errorMessage = any(),
                errorClass = any(),
                cancellationReason = any(),
                terminalReasonCode = any(),
                terminalDiagnosticCode = any(),
                partialFailureCount = isNull(),
                failedTargetCount = isNull()
            )
        }
    }

    @Test
    fun `snapshot is captured exactly once per terminal call`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("worker_a")
        val ctx = newContext()
        var invocations = 0

        handle.success(snapshotProvider = { invocations++; ctx.snapshot() })

        assertEquals(1, invocations)
    }

    @Test
    fun `context snapshot is immutable after mutation`() {
        val ctx = newContext()
        ctx.addRowsScanned(1)
        ctx.addRowsSkipped(2)
        ctx.setFailedTargetCount(3)
        val snapshot = ctx.snapshot()

        ctx.addRowsScanned(100)
        ctx.addRowsSkipped(200)
        ctx.setFailedTargetCount(300)

        assertEquals(1, snapshot.rowsScanned)
        assertEquals(2, snapshot.rowsSkipped)
        assertEquals(3, snapshot.failedTargetCount)
        // Mutations after capture affect only the live context, never the
        // already-captured immutable snapshot.
        assertEquals(101, ctx.rowsScanned)
        assertEquals(202, ctx.rowsSkipped)
    }

    @Test
    fun `NO_WORK measured counters include skipped and errors`() {
        val ctx = newContext()
        ctx.addRowsSkipped(5)
        ctx.addErrors(2)
        val snapshot = ctx.snapshot()

        assertEquals(5, snapshot.rowsSkipped)
        assertEquals(2, snapshot.errors)
        assertNull(snapshot.partialFailureCount)
        assertNull(snapshot.failedTargetCount)
    }

    @Test
    fun `dao completeTerminal maps only scalar counter arguments`() {
        // Guard against re-introducing a data-object parameter (D9: scalar mapping only).
        val params = BackgroundJobRunDao::class.java.methods
            .filter { !it.isSynthetic } // ignore compiler bridges; pin the declared method
            .first { it.name == "completeTerminal" }
            .parameterTypes
        assertEquals(
            "completeTerminal must map only scalar arguments (no Room data object)",
            listOf(
                Long::class.java, String::class.java, Long::class.java, // id, status, finishedAt
                Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java, // rowsScanned, rowsUpdated, notificationsSent, rowsSkipped, errors
                String::class.java, String::class.java, String::class.java, String::class.java, String::class.java, // statusReason, retryReason, errorMessage, errorClass, cancellationReason
                String::class.java, String::class.java, // terminalReasonCode, terminalDiagnosticCode
                // D9 null-vs-zero: partial/failed counts are nullable scalars (Int?)
                // so they reflect as boxed java.lang.Integer — still scalar-only.
                Integer::class.java, Integer::class.java // partialFailureCount, failedTargetCount
            ),
            // Reflection appends the trailing kotlin.coroutines.Continuation of the
            // suspend function — compare the declared parameters only.
            params.toList().dropLast(1)
        )
    }
}

/** Controlled reason codes for test invocations (passed through sanitizer as valid). */
private object DiagnosticFriendlyCode {
    const val RETRYABLE = "WORKER_RETRYABLE_ERROR"
    const val FINAL = "WORKER_UNHANDLED_EXCEPTION"
}
