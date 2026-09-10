package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.data.database.entity.BackgroundJobRun
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
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
        handle.skipped("PRIVACY_DENIED")

        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(1L),
                status = eq("SKIPPED"),
                finishedAt = eq(1700000000000L),
                statusReason = eq("PRIVACY_DENIED"),
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
        handle.retry("TIMEOUT", error = RuntimeException("boom"))

        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(1L),
                status = eq("RETRY"),
                finishedAt = eq(1700000000000L),
                retryReason = eq("TIMEOUT"),
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
        handle.failure("PERMANENT", error = RuntimeException("boom"))

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
        handle.cancelled("SYSTEM_SHUTDOWN")

        coVerify(exactly = 1) {
            dao.completeTerminal(
                id = eq(1L),
                status = eq("CANCELLED"),
                finishedAt = eq(1700000000000L),
                statusReason = eq("SYSTEM_SHUTDOWN"),
                cancellationReason = eq("SYSTEM_SHUTDOWN"),
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
            async { handle.failure("RACE_CONDITION", error = null) },
            async { handle.retry("RACE_CONDITION") },
            async { handle.skipped("RACE_CONDITION") }
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

        handle.failure("BOOM")
        handle.failure("BOOM2")

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

        handle.skipped("FIRST_ATTEMPT")   // DB fails
        handle.skipped("SECOND_ATTEMPT")  // DB succeeds

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
        assertEquals(DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name, nd.reasonCode)
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

    // ══════════════════════════════════════════════════════════════════════
    // PR12I-2: Terminal reason-code persistence for all states
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `skipped_persists_terminal_reason_code`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        handle.skipped("PRIVACY_DENIED")

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("SKIPPED"), any(),
                any(), any(), any(),
                eq("PRIVACY_DENIED"), any(), any(), any(), any(),
                eq("PRIVACY_DENIED"), eq("PRIVACY_DENIED"), any(), any()
            )
        }
    }

    @Test
    fun `retry_persists_terminal_reason_code_and_classifies_diagnostic`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        val timeoutEx = kotlinx.coroutines.runBlocking {
            try {
                kotlinx.coroutines.withTimeout(1L) { kotlinx.coroutines.delay(10L) }
                throw IllegalStateException("Expected TimeoutCancellationException")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) { e }
        }
        handle.retry("pipeline timeout", error = timeoutEx)

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("RETRY"), any(),
                any(), any(), any(),
                any(), eq("WORKER_UNHANDLED_EXCEPTION"), any(), eq("TimeoutCancellationException"), any(),
                eq("WORKER_UNHANDLED_EXCEPTION"), eq("TIMEOUT"), any(), any()
            )
        }
    }

    @Test
    fun `failure_persists_terminal_reason_code_and_classifies_diagnostic`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        handle.failure("WORKER_UNHANDLED_EXCEPTION", error = IllegalStateException("boom"))

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("FAILED"), any(),
                any(), any(), any(),
                eq("WORKER_UNHANDLED_EXCEPTION"), any(), any(), eq("IllegalStateException"), any(),
                eq("WORKER_UNHANDLED_EXCEPTION"), eq("WORKER_UNHANDLED_EXCEPTION"), any(), any()
            )
        }
    }

    @Test
    fun `cancelled_persists_terminal_reason_code`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        handle.cancelled("CANCELLED_BY_SYSTEM")

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("CANCELLED"), any(),
                any(), any(), any(),
                eq("CANCELLED_BY_SYSTEM"), any(), any(), any(), eq("CANCELLED_BY_SYSTEM"),
                eq("CANCELLED_BY_SYSTEM"), eq("CANCELLED_BY_SYSTEM"), any(), any()
            )
        }
    }

    @Test
    fun `stale_aborted_persists_terminal_reason_code`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        handle.staleAborted()

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("STALE_ABORTED"), any(),
                any(), any(), any(),
                eq("STALE_RUNNING_ABORTED"), any(), any(), any(), any(),
                eq("STALE_RUNNING_ABORTED"), eq("STALE_RUNNING_ABORTED"), any(), any()
            )
        }
    }

    @Test
    fun `failure_does_not_store_reason_inside_error_message`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        handle.failure("UNHANDLED", error = IllegalStateException("sensitive db path"))

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("FAILED"), any(),
                any(), any(), any(),
                eq("UNHANDLED"), any(), any(), any(), any(),
                any(), any(), any(), any()
            )
        }
        // verify sanitizer was called with raw exception message only (not "reason: message")
        coVerify(exactly = 1) {
            sanitizer.sanitizeExceptionMessage("sensitive db path")
        }
    }

    @Test
    fun `classifyDiagnostic_timeout_returns_TIMEOUT`() {
        val timeoutEx = kotlinx.coroutines.runBlocking {
            try {
                kotlinx.coroutines.withTimeout(1L) { kotlinx.coroutines.delay(10L) }
                throw IllegalStateException("Expected TimeoutCancellationException")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) { e }
        }
        assertEquals("TIMEOUT", WorkerRunLoggerImpl.classifyDiagnostic("any", timeoutEx))
    }

    @Test
    fun `classifyDiagnostic_retryable_returns_message`() {
        assertEquals("TIMEOUT", WorkerRunLoggerImpl.classifyDiagnostic("any", RetryableWorkerException(DiagnosticReasonCode.WORKER_TIMEOUT.name)))
    }

    @Test
    fun `classifyDiagnostic_checkpoint_returns_reasonCode`() {
        val ex = WorkerCheckpointBlockedException("STOP_REQUESTED", "msg")
        assertEquals("STOP_REQUESTED", WorkerRunLoggerImpl.classifyDiagnostic("any", ex))
    }

    @Test
    fun `classifyDiagnostic_security_with_notification_context_returns_permission_denied`() {
        assertEquals("NOTIFICATION_PERMISSION_DENIED", WorkerRunLoggerImpl.classifyDiagnostic("notification permission revoked", SecurityException()))
    }

    @Test
    fun `classifyDiagnostic_reason_contains_TIMEOUT_returns_TIMEOUT`() {
        assertEquals("TIMEOUT", WorkerRunLoggerImpl.classifyDiagnostic("pipeline TIMEOUT occurred", null))
    }

    @Test
    fun `classifyDiagnostic_security_without_notification_returns_security_exception`() {
        assertEquals("SECURITY_EXCEPTION", WorkerRunLoggerImpl.classifyDiagnostic("generic failure", SecurityException()))
    }

    @Test
    fun `classifyDiagnostic_reason_contains_BLOCKED_returns_BLOCKED`() {
        assertEquals("BLOCKED", WorkerRunLoggerImpl.classifyDiagnostic("WRITE_BARRIER_BLOCKED", null))
    }

    @Test
    fun `classifyDiagnostic_reason_contains_PRIVACY_returns_PRIVACY`() {
        assertEquals("PRIVACY", WorkerRunLoggerImpl.classifyDiagnostic("PRIVACY_DENIED", null))
    }

    @Test
    fun `classifyDiagnostic_reason_contains_RESTORE_returns_RESTORE_BLOCKED`() {
        assertEquals("RESTORE_BLOCKED", WorkerRunLoggerImpl.classifyDiagnostic("RESTORE_PREPARING", null))
    }

    @Test
    fun `classifyDiagnostic_reason_contains_NETWORK_returns_NETWORK_UNAVAILABLE`() {
        assertEquals("NETWORK_UNAVAILABLE", WorkerRunLoggerImpl.classifyDiagnostic("NETWORK_UNAVAILABLE", null))
    }

    @Test
    fun `classifyDiagnostic_fallback_returns_reason`() {
        assertEquals("UNKNOWN_ERROR", WorkerRunLoggerImpl.classifyDiagnostic("UNKNOWN_ERROR", null))
    }

    // ══════════════════════════════════════════════════════════════════════
    // PR12J-1: Safe structured worker reason codes
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `exception_message_is_not_terminal_reason_code`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        // Simulate what happens when WorkerExecutionGuard passes safe codes:
        // terminalReasonCode must be a safe constant, never a raw exception path/PII.
        handle.failure(
            reason = DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name,
            error = RuntimeException("/data/app/bad/path")
        )

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("FAILED"), any(),
                any(), any(), any(),
                statusReason = eq("WORKER_UNHANDLED_EXCEPTION"), any(), any(), any(), any(),
                terminalReasonCode = eq("WORKER_UNHANDLED_EXCEPTION"),
                terminalDiagnosticCode = eq("WORKER_UNHANDLED_EXCEPTION"),
                any(), any()
            )
        }
    }

    @Test
    fun `exception_message_is_not_terminal_diagnostic_code`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        // Even if a raw path/PII string is passed as reason, terminalDiagnosticCode
        // must be sanitized to a safe code — never the raw string.
        handle.failure(
            reason = "/data/user/0/com.app/files/private_key.pem",
            error = RuntimeException("sensitive notification body")
        )

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("FAILED"), any(),
                any(), any(), any(),
                any(), any(), any(), any(), any(),
                terminalReasonCode = eq("WORKER_UNHANDLED_EXCEPTION"),
                terminalDiagnosticCode = eq("WORKER_UNHANDLED_EXCEPTION"),
                any(), any()
            )
        }
    }

    @Test
    fun `retryable_exception_uses_safe_reason_code`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        val retryableEx = RetryableWorkerException("WORKER_RETRYABLE_ERROR", "DB was locked")

        handle.retry(retryableEx.reasonCode, error = retryableEx)

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("RETRY"), any(),
                any(), any(), any(),
                any(), eq("WORKER_RETRYABLE_ERROR"), any(), any(), any(),
                terminalReasonCode = eq("WORKER_RETRYABLE_ERROR"),
                terminalDiagnosticCode = eq("RETRYABLE"),
                any(), any()
            )
        }
    }

    @Test
    fun `invalid_reason_code_is_sanitized`() {
        // Path-like strings must be rejected by the sanitizer
        assertEquals(
            DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name,
            WorkerReasonCodes.sanitizeReasonCode("bad/path")
        )
        // Lowercase is rejected
        assertEquals(
            DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name,
            WorkerReasonCodes.sanitizeReasonCode("something")
        )
        // Null is rejected
        assertEquals(
            DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name,
            WorkerReasonCodes.sanitizeReasonCode(null)
        )
    }

    @Test
    fun `success_persists_terminal_diagnostic_code`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        handle.success(
            rowsScanned = 10,
            rowsUpdated = 3,
            notificationsSent = 2,
            reasonCode = DiagnosticReasonCode.WORKER_SUCCESS.name
        )

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("SUCCESS"), any(),
                rowsScanned = eq(10), rowsUpdated = eq(3), notificationsSent = eq(2),
                any(), any(), any(), any(), any(),
                terminalReasonCode = eq("WORKER_SUCCESS"),
                terminalDiagnosticCode = eq("WORKER_SUCCESS"),
                any(), any()
            )
        }
    }

    @Test
    fun `no_work_persists_terminal_diagnostic_code`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")
        handle.success(
            rowsScanned = 0,
            rowsUpdated = 0,
            notificationsSent = 0,
            message = "NO_WORK",
            reasonCode = DiagnosticReasonCode.WORKER_NO_WORK.name
        )

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("SUCCESS"), any(),
                rowsScanned = eq(0), rowsUpdated = eq(0), notificationsSent = eq(0),
                statusReason = eq("WORKER_NO_WORK"), any(), any(), any(), any(),
                terminalReasonCode = eq("WORKER_NO_WORK"),
                terminalDiagnosticCode = eq("WORKER_NO_WORK"),
                any(), any()
            )
        }
    }

    @Test
    fun `classify_diagnostic_never_returns_raw_message`() {
        // classifyDiagnostic must never echo raw path/PII strings
        val result1 = WorkerRunLoggerImpl.classifyDiagnostic(
            "/data/user/0/com.app/cache",
            RuntimeException("sensitive")
        )
        assertEquals("WORKER_UNHANDLED_EXCEPTION", result1)

        val result2 = WorkerRunLoggerImpl.classifyDiagnostic(
            "user@example.com personal data",
            null
        )
        assertEquals("WORKER_UNHANDLED_EXCEPTION", result2)
    }

    // ══════════════════════════════════════════════════════════════════════
    // PR12K-4: Retryable reason-code boundary hardening
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `logger_sanitizes_retry_reason_code`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        handle.retry("bad/path", error = RuntimeException("boom"))

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("RETRY"), any(),
                any(), any(), any(),
                any(), any(), any(), any(), any(),
                terminalReasonCode = eq(DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name),
                terminalDiagnosticCode = any(),
                any(), any()
            )
        }
    }

    @Test
    fun `logger_sanitizes_failure_reason_code`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        handle.failure("../../../etc/passwd", error = RuntimeException("boom"))

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("FAILED"), any(),
                any(), any(), any(),
                any(), any(), any(), any(), any(),
                terminalReasonCode = eq(DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name),
                terminalDiagnosticCode = eq(DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name),
                any(), any()
            )
        }
    }

    @Test
    fun `logger_sanitizes_skipped_reason_code`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        handle.skipped("user data leak")

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("SKIPPED"), any(),
                any(), any(), any(),
                any(), any(), any(), any(), any(),
                terminalReasonCode = eq(DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name),
                terminalDiagnosticCode = eq(DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name),
                any(), any()
            )
        }
    }

    @Test
    fun `path_like_reason_code_not_persisted`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        handle.retry("/data/app/com.example/cache/tmp", error = RuntimeException("sensitive"))

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("RETRY"), any(),
                any(), any(), any(),
                any(), any(), any(), any(), any(),
                terminalReasonCode = eq(DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name),
                terminalDiagnosticCode = any(),
                any(), any()
            )
        }
    }

    @Test
    fun `valid_reason_code_is_preserved`() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val handle = logger.start("test_worker")

        handle.retry(DiagnosticReasonCode.WORKER_TIMEOUT.name, error = null)

        coVerify(exactly = 1) {
            dao.completeTerminal(
                eq(1L), eq("RETRY"), any(),
                any(), any(), any(),
                any(), any(), any(), any(), any(),
                terminalReasonCode = eq(DiagnosticReasonCode.WORKER_TIMEOUT.name),
                terminalDiagnosticCode = any(),
                any(), any()
            )
        }
    }
}
