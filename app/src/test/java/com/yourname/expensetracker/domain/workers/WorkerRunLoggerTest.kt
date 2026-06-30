package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.data.database.entity.BackgroundJobRun
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.SQLException

@OptIn(ExperimentalCoroutinesApi::class)
class WorkerRunLoggerTest {

    private val dao = mockk<BackgroundJobRunDao>(relaxed = true)
    private val sanitizer = mockk<EventMetadataSanitizer>()
    private val timeProvider = mockk<TimeProvider>()
    private lateinit var logger: WorkerRunLoggerImpl

    @Before
    fun setup() {
        every { timeProvider.now() } returns 1700000000000L
        every { sanitizer.sanitizeExceptionMessage(any()) } returns null
        logger = WorkerRunLoggerImpl(dao, sanitizer, timeProvider)
    }

    @Test
    fun start_creates_running_record() = runTest {
        val idSlot = slot<BackgroundJobRun>()
        coEvery { dao.insert(capture(idSlot)) } returns 42L

        val handle = logger.start("test_worker")

        assertEquals(42L, handle.runId)
        assertEquals("RUNNING", idSlot.captured.status)
        assertEquals("test_worker", idSlot.captured.workerName)
        assertTrue(idSlot.captured.correlationId?.isNotEmpty() == true)
    }

    // ── PR12B: All terminal methods now call dao.completeTerminal (CAS-based) ──

    @Test
    fun success_updates_terminal_state() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        handle.success(rowsScanned = 5, rowsUpdated = 3, notificationsSent = 1)

        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(1L),
                status = eq("SUCCESS"),
                finishedAt = eq(1700000000000L),
                rowsScanned = eq(5),
                rowsUpdated = eq(3),
                notificationsSent = eq(1),
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
    fun skipped_updates_terminal_state() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        handle.skipped("privacy_denied")

        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(1L),
                status = eq("SKIPPED"),
                finishedAt = eq(1700000000000L),
                statusReason = eq("privacy_denied"),
                rowsScanned = eq(0),
                rowsUpdated = eq(0),
                notificationsSent = eq(0),
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
    fun retry_updates_terminal_state() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        handle.retry("timeout", error = RuntimeException("boom"))

        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(1L),
                status = eq("RETRY"),
                finishedAt = eq(1700000000000L),
                retryReason = eq("timeout"),
                errorMessage = any(),
                errorClass = eq("RuntimeException"),
                statusReason = any(),
                rowsScanned = eq(0),
                rowsUpdated = eq(0),
                notificationsSent = eq(0),
                cancellationReason = any(),
                terminalReasonCode = any(),
                terminalDiagnosticCode = any(),
                partialFailureCount = any(),
                failedTargetCount = any()
            )
        }
    }

    @Test
    fun failure_updates_terminal_state() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        handle.failure("permanent", error = RuntimeException("boom"))

        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(1L),
                status = eq("FAILED"),
                finishedAt = eq(1700000000000L),
                errorMessage = any(),
                errorClass = eq("RuntimeException"),
                statusReason = any(),
                retryReason = any(),
                rowsScanned = eq(0),
                rowsUpdated = eq(0),
                notificationsSent = eq(0),
                cancellationReason = any(),
                terminalReasonCode = any(),
                terminalDiagnosticCode = any(),
                partialFailureCount = any(),
                failedTargetCount = any()
            )
        }
    }

    @Test
    fun cancelled_updates_terminal_state() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        handle.cancelled("system_shutdown")

        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(1L),
                status = eq("CANCELLED"),
                finishedAt = eq(1700000000000L),
                statusReason = eq("system_shutdown"),
                cancellationReason = eq("system_shutdown"),
                retryReason = any(),
                errorMessage = any(),
                errorClass = any(),
                rowsScanned = eq(0),
                rowsUpdated = eq(0),
                notificationsSent = eq(0),
                terminalReasonCode = any(),
                terminalDiagnosticCode = any(),
                partialFailureCount = any(),
                failedTargetCount = any()
            )
        }
    }

    // ── PR12B: Mutex ensures exactly one terminal DB write under race ──

    @Test
    fun concurrent_terminal_calls_result_in_exactly_one_write() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        val jobs = listOf(
            async { handle.success() },
            async { handle.failure("race", error = null) },
            async { handle.retry("race") },
            async { handle.skipped("race") }
        )
        jobs.awaitAll()

        coVerify(exactly = 1) { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun duplicate_success_is_noop() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        handle.success()
        handle.success()
        handle.success()

        coVerify(exactly = 1) { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun duplicate_failure_is_noop() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        handle.failure("boom")
        handle.failure("boom2")

        coVerify(exactly = 1) { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun staleAborted_is_terminal() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        handle.staleAborted()

        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(1L),
                status = eq("STALE_ABORTED"),
                finishedAt = eq(1700000000000L),
                rowsScanned = eq(0),
                rowsUpdated = eq(0),
                notificationsSent = eq(0),
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

    // ══════════════════════════════════════════════════════════════════════
    // PR12B: Durable terminal state machine tests
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun terminal_success_sets_completed_only_after_db_update() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        handle.success()   // DB returns 1 → handle marked completed
        handle.success()   // completed already true → no DB call

        coVerify(exactly = 1) { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun terminal_timeout_does_not_burn_handle() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        // First call delays beyond the 5s TERMINAL_WRITE_TIMEOUT_MS,
        // triggering a natural TimeoutCancellationException inside withTimeout().
        // Second call returns 1 normally.
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(6_000L)   // > 5_000ms timeout
            1
        } andThen 1
        val handle = logger.start("test_worker")

        handle.success()   // Timeout → handle NOT marked completed
        handle.success()   // Retry succeeds

        coVerify(exactly = 2) { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun terminal_db_exception_does_not_burn_handle() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            SQLException("db error") andThen 1
        val handle = logger.start("test_worker")

        handle.success()   // SQLException → handle NOT marked completed
        handle.success()   // Retry succeeds

        coVerify(exactly = 2) { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun second_terminal_after_first_db_failure_can_retry() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        var callCount = 0
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            callCount++
            if (callCount == 1) throw SQLException("db error")
            else 1
        }
        val handle = logger.start("test_worker")

        handle.skipped("first attempt")   // DB fails
        handle.skipped("second attempt")  // DB succeeds

        coVerify(exactly = 2) { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun affected_zero_terminal_row_already_success_marks_local_complete() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 0
        coEvery { dao.getById(1L) } returns BackgroundJobRun(
            id = 1L,
            workerName = "test_worker",
            startedAt = 1700000000000L,
            status = "SUCCESS"
        )
        val handle = logger.start("test_worker")

        handle.success()   // affected=0, getById returns SUCCESS → handle marked completed
        handle.success()   // noop (AlreadyCompletedLocal)

        coVerify(exactly = 1) { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { dao.getById(1L) }
    }

    @Test
    fun affected_zero_but_row_running_records_fallback_diagnostic() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 0
        coEvery { dao.getById(1L) } returns BackgroundJobRun(
            id = 1L,
            workerName = "test_worker",
            startedAt = 1700000000000L,
            status = "RUNNING"
        )
        val handle = logger.start("test_worker")

        handle.success()   // affected=0, getById returns RUNNING → handle NOT marked completed
        handle.success()   // retries DB since handle still not completed

        coVerify(exactly = 2) { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 2) { dao.getById(1L) }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PR12H-3: TerminalWriteOutcome return value tests
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `terminal success returns Durable when db write succeeds`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        val outcome = handle.success()
        assertTrue(outcome is TerminalWriteOutcome.Durable)
    }

    @Test
    fun `terminal skipped returns Durable when db write succeeds`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        val outcome = handle.skipped("privacy_denied")
        assertTrue(outcome is TerminalWriteOutcome.Durable)
    }

    @Test
    fun `terminal retry returns Durable when db write succeeds`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        val outcome = handle.retry("timeout", error = RuntimeException("boom"))
        assertTrue(outcome is TerminalWriteOutcome.Durable)
    }

    @Test
    fun `terminal failure returns Durable when db write succeeds`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        val outcome = handle.failure("permanent", error = RuntimeException("boom"))
        assertTrue(outcome is TerminalWriteOutcome.Durable)
    }

    @Test
    fun `terminal cancelled returns Durable when db write succeeds`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        val outcome = handle.cancelled("system_shutdown")
        assertTrue(outcome is TerminalWriteOutcome.Durable)
    }

    @Test
    fun `terminal staleAborted returns Durable when db write succeeds`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        val outcome = handle.staleAborted()
        assertTrue(outcome is TerminalWriteOutcome.Durable)
    }

    @Test
    fun `terminal success db timeout returns NotDurable`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(6_000L)  // > 5_000ms timeout
            1
        }
        val handle = logger.start("test_worker")

        val outcome = handle.success()
        assertTrue(outcome is TerminalWriteOutcome.NotDurable)
        val nd = outcome as TerminalWriteOutcome.NotDurable
        assertEquals("SUCCESS", nd.intendedStatus)
        assertEquals("TERMINAL_WRITE_TIMEOUT", nd.failureCode)
        assertEquals("TimeoutCancellationException", nd.errorClass)
    }

    @Test
    fun `terminal retry db exception returns NotDurable`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            SQLException("db error")
        val handle = logger.start("test_worker")

        val outcome = handle.retry("timeout", error = RuntimeException("boom"))
        assertTrue(outcome is TerminalWriteOutcome.NotDurable)
        val nd = outcome as TerminalWriteOutcome.NotDurable
        assertEquals("RETRY", nd.intendedStatus)
        assertEquals("timeout", nd.reasonCode)
        assertEquals("TERMINAL_WRITE_FAILED", nd.failureCode)
        assertEquals("RuntimeException", nd.errorClass)
    }

    @Test
    fun `terminal db failure keeps handle retryable`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            SQLException("db error") andThen 1
        val handle = logger.start("test_worker")

        val outcome1 = handle.success()   // DB fails → NotDurable
        assertTrue(outcome1 is TerminalWriteOutcome.NotDurable)

        val outcome2 = handle.success()   // Handle is still retryable → DB succeeds → Durable
        assertTrue(outcome2 is TerminalWriteOutcome.Durable)

        coVerify(exactly = 2) { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `duplicate terminal returns AlreadyTerminal`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        handle.success()   // first call → Durable
        val outcome = handle.success()   // second call → AlreadyTerminal (AlreadyCompletedLocal)
        assertTrue(outcome is TerminalWriteOutcome.AlreadyTerminal)
    }

    @Test
    fun `terminal zero affected but already completed in db returns AlreadyTerminal`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 0
        coEvery { dao.getById(1L) } returns BackgroundJobRun(
            id = 1L,
            workerName = "test_worker",
            startedAt = 1700000000000L,
            status = "FAILED"
        )
        val handle = logger.start("test_worker")

        val outcome = handle.success()
        assertTrue(outcome is TerminalWriteOutcome.AlreadyTerminal)
        assertEquals("FAILED", (outcome as TerminalWriteOutcome.AlreadyTerminal).status)
    }

    @Test
    fun `terminal zero affected but still running returns NotDurable`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 0
        coEvery { dao.getById(1L) } returns BackgroundJobRun(
            id = 1L,
            workerName = "test_worker",
            startedAt = 1700000000000L,
            status = "RUNNING"
        )
        val handle = logger.start("test_worker")

        val outcome = handle.success()
        assertTrue(outcome is TerminalWriteOutcome.NotDurable)
        val nd = outcome as TerminalWriteOutcome.NotDurable
        assertEquals("TERMINAL_WRITE_ZERO_AFFECTED", nd.failureCode)
    }

    @Test
    fun `handle exposes workerName workId and runAttempt`() = runTest {
        coEvery { dao.insert(any()) } returns 42L
        val handle = logger.start(
            workerName = "my_worker",
            workId = "work-uuid-123",
            runAttempt = 3
        )

        assertEquals(42L, handle.runId)
        assertEquals("my_worker", handle.workerName)
        assertEquals("work-uuid-123", handle.workId)
        assertEquals(3, handle.runAttempt)
    }
}
