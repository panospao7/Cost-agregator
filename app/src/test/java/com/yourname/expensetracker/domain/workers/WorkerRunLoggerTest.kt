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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

    @Test
    fun success_updates_terminal_state() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val handle = logger.start("test_worker")
        handle.success(rowsScanned = 5, rowsUpdated = 3, notificationsSent = 1)

        coVerify(exactly = 1) { dao.update(any()) }
        val slot = slot<BackgroundJobRun>()
        coVerify { dao.update(capture(slot)) }
        assertEquals("SUCCESS", slot.captured.status)
        assertEquals(5, slot.captured.rowsScanned)
        assertEquals(3, slot.captured.rowsUpdated)
        assertEquals(1, slot.captured.notificationsSent)
    }

    @Test
    fun skipped_updates_terminal_state() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val handle = logger.start("test_worker")
        handle.skipped("privacy_denied")

        val slot = slot<BackgroundJobRun>()
        coVerify { dao.update(capture(slot)) }
        assertEquals("SKIPPED", slot.captured.status)
        assertEquals("privacy_denied", slot.captured.statusReason)
    }

    @Test
    fun retry_updates_terminal_state() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val handle = logger.start("test_worker")
        handle.retry("timeout", error = RuntimeException("boom"))

        val slot = slot<BackgroundJobRun>()
        coVerify { dao.update(capture(slot)) }
        assertEquals("RETRY", slot.captured.status)
        assertEquals("timeout", slot.captured.retryReason)
    }

    @Test
    fun failure_updates_terminal_state() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val handle = logger.start("test_worker")
        handle.failure("permanent", error = RuntimeException("boom"))

        val slot = slot<BackgroundJobRun>()
        coVerify { dao.update(capture(slot)) }
        assertEquals("FAILED", slot.captured.status)
    }

    @Test
    fun cancelled_updates_terminal_state() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val handle = logger.start("test_worker")
        handle.cancelled("system_shutdown")

        val slot = slot<BackgroundJobRun>()
        coVerify { dao.update(capture(slot)) }
        assertEquals("CANCELLED", slot.captured.status)
        assertEquals("system_shutdown", slot.captured.cancellationReason)
    }

    // ── PR3: Atomic terminal write (CAS) prevents double write under race ──

    @Test
    fun concurrent_terminal_calls_result_in_exactly_one_update() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val handle = logger.start("test_worker")

        // Fire success() and failure() concurrently from different coroutines
        val jobs = listOf(
            async { handle.success() },
            async { handle.failure("race", error = null) },
            async { handle.retry("race") },
            async { handle.skipped("race") }
        )
        jobs.awaitAll()

        // Exactly one update must have been written
        coVerify(exactly = 1) { dao.update(any()) }
    }

    @Test
    fun duplicate_success_is_noop() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val handle = logger.start("test_worker")

        handle.success()
        handle.success() // duplicate
        handle.success() // triplicate

        coVerify(exactly = 1) { dao.update(any()) }
    }

    @Test
    fun duplicate_failure_is_noop() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val handle = logger.start("test_worker")

        handle.failure("boom")
        handle.failure("boom2")

        coVerify(exactly = 1) { dao.update(any()) }
    }

    @Test
    fun staleAborted_is_terminal() = runTest {
        coEvery { dao.insert(any()) } returns 1L
        val handle = logger.start("test_worker")
        handle.staleAborted()

        val slot = slot<BackgroundJobRun>()
        coVerify { dao.update(capture(slot)) }
        assertEquals("STALE_ABORTED", slot.captured.status)
    }
}
