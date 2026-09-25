package com.yourname.expensetracker.domain.workers

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.SettableFuture
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Restore → Worker regression tests (PR11).
 *
 * Covers:
 * 1. Restore completion unblocks lease registry and WorkerRegistry re-scheduling.
 * 2. writeBarrier blocks worker operations during restore.
 * 3. stopRequested flag prevents new lease acquisition during restore.
 * 4. Terminal logging idempotency (CAS) across restore/restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkerRestoreRegressionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val writeBarrier = mockk<DatabaseWriteBarrier>()
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = System.currentTimeMillis()
    }

    private lateinit var leaseRegistry: WorkerLeaseRegistryImpl

    @Before
    fun setUp() {
        every { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        every { writeBarrier.checkWritesAllowed(any<DatabaseAccessOperation>()) } returns Unit
        leaseRegistry = WorkerLeaseRegistryImpl(writeBarrier, timeProvider)
    }

    // ─────────────────────────────────────────────────────────────────
    // 1. Restore completion unblocks all workers and reschedules them
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `restore_complete unblocks leases and workers can re-acquire`() = runTest {
        // Simulate: restore begins → stop all workers
        leaseRegistry.requestStopAll("restore started")
        assertTrue("Stop should be requested", leaseRegistry.isStopRequested())

        // New worker leases must be rejected during restore
        assertThrows(LeaseAcquisitionBlockedException::class.java) {
            kotlinx.coroutines.runBlocking { leaseRegistry.acquire("data_retention") }
        }

        // Simulate: restore completes → reset stop flag
        leaseRegistry.resetStopFlag()
        assertFalse("Stop flag should be reset", leaseRegistry.isStopRequested())

        // Now workers can acquire leases again
        val lease = leaseRegistry.acquire("data_retention")
        assertNotNull("Lease should be acquired after restore", lease)
        assertEquals(1, leaseRegistry.activeLeaseCount())
        lease.close()
        assertEquals(0, leaseRegistry.activeLeaseCount())
    }

    @Test
    fun `WorkerRegistry entries are all present and cover all known worker names`() {
        // Verify WorkerRegistry has entries for every WorkerSpec.DEFAULTS key
        val specNames = WorkerSpec.DEFAULTS.keys
        val registryNames = WorkerRegistry.entries.map { it.specName }.toSet()

        for (name in specNames) {
            assertTrue(
                "Worker '$name' must be registered in WorkerRegistry",
                registryNames.contains(name)
            )
        }

        // And verify the scheduler's own list matches
        val schedulerNames = WorkerSpecScheduler.listAllWorkerNames()
        for (name in registryNames) {
            assertTrue(
                "Worker '$name' in Registry must be known to Scheduler",
                schedulerNames.contains(name) || name == "ai_daily_briefing"
            )
        }
    }

    @Test
    fun `rollback rescheduling contract covers every default worker exactly once`() {
        val defaults = WorkerSpec.DEFAULTS.keys
        val scheduled = WorkerRegistry.entries.map { it.specName }
        assertEquals("Worker registry must not omit or duplicate rollback schedules", defaults, scheduled.toSet())
        assertEquals("Each registry worker must have one unique schedule", scheduled.size, scheduled.toSet().size)
    }

    @Test
    fun `checked resumption releases real guarded workers only after NORMAL and stop reset`() = runTest {
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        val values = mutableMapOf<String, Any?>()
        every { context.applicationContext } returns context
        every { context.noBackupFilesDir } returns temporaryFolder.root
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.all } answers { values.toMap() }
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg<String?>(); editor }
        every { editor.putBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>(); editor }
        every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
        every { editor.commit() } returns true
        lateinit var actualLeases: WorkerLeaseRegistryImpl
        val mode = RestoreMaintenanceMode(context, dagger.Lazy { actualLeases }, timeProvider)
        val barrier = DatabaseWriteBarrier(mode)
        actualLeases = WorkerLeaseRegistryImpl(barrier, timeProvider)
        val logger = mockk<WorkerRunLogger>()
        val handle = mockk<WorkerRunHandle>(relaxed = true)
        coEvery { logger.start(any(), any(), any(), any(), any(), any()) } returns handle
        coEvery { handle.success(reasonCode = any()) } returns TerminalWriteOutcome.Durable
        val guard = WorkerExecutionGuard(
            writeBarrier = barrier,
            readBarrier = mockk(relaxed = true),
            restoreMaintenanceMode = mode,
            workerRunLogger = logger,
            privacyGate = mockk(relaxed = true),
            leaseRegistry = actualLeases,
            diagnosticSink = mockk(relaxed = true),
            workerTerminalDiagnosticSink = mockk(relaxed = true),
            backgroundJobRunDao = mockk(relaxed = true),
            notificationPermissionChecker = mockk(relaxed = true),
            timeProvider = timeProvider
        )
        val manager = mockk<WorkManager>(relaxed = true)
        val active = mockk<WorkInfo>()
        every { active.state } returns WorkInfo.State.ENQUEUED
        val confirmed = SettableFuture.create<List<WorkInfo>>().apply { set(listOf(active)) }
        every { manager.getWorkInfosForUniqueWork(any()) } returns confirmed
        mockkStatic(WorkManager::class)
        mockkObject(WorkerRegistry)
        try {
            every { WorkManager.getInstance(any<Context>()) } returns manager
            mode.enter(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
            actualLeases.requestStopAll("TEST_RESTORE")
            every { WorkerRegistry.scheduleAll(any(), any(), null) } answers {
                assertEquals(RestoreMaintenanceMode.Mode.NORMAL.name, values["current_mode"])
                assertEquals(true, values["worker_resume_pending"])
                assertTrue(actualLeases.isStopRequested())
                for (name in WorkerSpec.DEFAULTS.keys) {
                    val blocked = kotlinx.coroutines.runBlocking {
                        guard.runGuarded(WorkerGuardRequest(name)) {
                            fail("Worker body must not run before confirmed resumption")
                        }
                    }
                    assertTrue(blocked is WorkerGuardResult.BlockedRetry)
                }
                WorkerRegistry.ScheduleAllResult(WorkerSpec.DEFAULTS.keys.map {
                    ScheduleResult(it, true, "TEST", false)
                })
            }
            mode.exitCancellable(false)
            val executed = mutableListOf<String>()
            for (name in WorkerSpec.DEFAULTS.keys) {
                val result = guard.runGuarded(WorkerGuardRequest(name)) {
                    assertEquals(RestoreMaintenanceMode.Mode.NORMAL, mode.currentMode())
                    assertFalse(actualLeases.isStopRequested())
                    barrier.checkWritesAllowed("TEST_GUARDED_RESUME")
                    executed += name
                }
                assertTrue(result is WorkerGuardResult.Success)
            }
            assertEquals(WorkerSpec.DEFAULTS.keys, executed.toSet())
            assertEquals(WorkerSpec.DEFAULTS.size, executed.size)
            assertEquals(0, actualLeases.activeLeaseCount())
        } finally {
            unmockkObject(WorkerRegistry)
            unmockkStatic(WorkManager::class)
        }
    }

    @Test
    fun scheduleEntries_returns_one_successful_result_for_every_requested_worker() {
        val context = mockk<Context>()
        val calls = mutableListOf<String>()
        val entries = listOf(
            WorkerRegistry.Entry("worker_one") { _, _ ->
                calls += "worker_one"
                ScheduleResult("worker_one", true, "TEST", false)
            },
            WorkerRegistry.Entry("worker_two") { _, _ ->
                calls += "worker_two"
                ScheduleResult("worker_two", true, "TEST", false)
            }
        )

        val result = WorkerRegistry.scheduleEntries(context, timeProvider, entries)

        assertEquals(listOf("worker_one", "worker_two"), calls)
        assertTrue(result.confirms(setOf("worker_one", "worker_two")))
        assertTrue(result.failedWorkerNames.isEmpty())
    }

    @Test
    fun scheduleEntries_records_ordinary_failures_and_continues_later_workers() {
        val context = mockk<Context>()
        val laterWorkerRan = AtomicBoolean(false)
        val entries = listOf(
            WorkerRegistry.Entry("returned_failure") { _, _ ->
                ScheduleResult("returned_failure", false, "TEST", false, "TEST_FAILURE")
            },
            WorkerRegistry.Entry("thrown_failure") { _, _ ->
                throw IllegalStateException("must not escape")
            },
            WorkerRegistry.Entry("later_worker") { _, _ ->
                laterWorkerRan.set(true)
                ScheduleResult("later_worker", true, "TEST", false)
            }
        )

        val result = WorkerRegistry.scheduleEntries(context, timeProvider, entries)

        assertTrue("A later worker must still be attempted", laterWorkerRan.get())
        assertEquals(listOf("returned_failure", "thrown_failure"), result.failedWorkerNames)
        assertEquals("WORKER_SCHEDULE_EXCEPTION", result.results[1].error)
        assertFalse(result.confirms(entries.map { it.specName }.toSet()))
    }

    @Test
    fun scheduleEntries_rethrows_the_same_cancellation_and_stops_scheduling() {
        val context = mockk<Context>()
        val cancellation = CancellationException("test cancellation")
        val laterWorkerRan = AtomicBoolean(false)
        val entries = listOf(
            WorkerRegistry.Entry("cancelled_worker") { _, _ -> throw cancellation },
            WorkerRegistry.Entry("later_worker") { _, _ ->
                laterWorkerRan.set(true)
                ScheduleResult("later_worker", true, "TEST", false)
            }
        )

        val thrown = assertThrows(CancellationException::class.java) {
            WorkerRegistry.scheduleEntries(context, timeProvider, entries)
        }

        assertSame(cancellation, thrown)
        assertFalse("Cancellation must stop subsequent scheduling", laterWorkerRan.get())
    }

    @Test
    fun `all default workers have valid spec and are enabled post-restore`() = runTest {
        // After restore resetStopFlag(), the lease registry allows new workers.
        // Verify every worker spec is valid and enabled so WorkerRegistry can schedule.
        leaseRegistry.resetStopFlag()

        for ((name, spec) in WorkerSpec.DEFAULTS) {
            assertTrue("Worker '$name' must be enabled", spec.enabled)
            assertTrue("Worker '$name' name must not be blank", spec.name.isNotBlank())

            // Verify a lease can be acquired for each worker after restore
            val lease = leaseRegistry.acquire(name)
            assertNotNull("Lease should be acquired for '$name'", lease)
            assertEquals(1, leaseRegistry.activeLeaseCount())
            lease.close()
            assertEquals(0, leaseRegistry.activeLeaseCount())
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. Workers honor writeBarrier during restore
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `writeBarrier blocks worker writes during RESTORE_PREPARING`() = runTest {
        val mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        val op = DatabaseAccessOperation("receipt_matching_worker")

        every { writeBarrier.checkWritesAllowed(any<String>()) } throws
            DatabaseAccessBlockedException(DatabaseAccessType.WRITE, op, mode)

        // Worker trying to checkpoint should hit the blocked exception
        val lease = leaseRegistry.acquire("receipt_matching")
        assertThrows(DatabaseAccessBlockedException::class.java) {
            kotlinx.coroutines.runBlocking { lease.checkpoint("process_receipt") }
        }
        lease.close()
    }

    @Test
    fun `writeBarrier blocks worker writes during RESTORE_STAGING`() = runTest {
        val mode = RestoreMaintenanceMode.Mode.RESTORE_STAGING
        val op = DatabaseAccessOperation("data_retention_worker")

        every { writeBarrier.checkWritesAllowed(any<String>()) } throws
            DatabaseAccessBlockedException(DatabaseAccessType.WRITE, op, mode)

        val lease = leaseRegistry.acquire("data_retention")
        assertThrows(DatabaseAccessBlockedException::class.java) {
            kotlinx.coroutines.runBlocking { lease.checkpoint("delete_old_data") }
        }
        lease.close()
    }

    @Test
    fun `writeBarrier blocks worker writes during RESTORE_SWAPPING`() = runTest {
        val mode = RestoreMaintenanceMode.Mode.RESTORE_SWAPPING
        val op = DatabaseAccessOperation("warranty_expiration_worker")

        every { writeBarrier.checkWritesAllowed(any<String>()) } throws
            DatabaseAccessBlockedException(DatabaseAccessType.WRITE, op, mode)

        val lease = leaseRegistry.acquire("warranty_expiration_check")
        assertThrows(DatabaseAccessBlockedException::class.java) {
            kotlinx.coroutines.runBlocking { lease.checkpoint("check_warranties") }
        }
        lease.close()
    }

    @Test
    fun `workers can write after restore returns to NORMAL`() = runTest {
        // After restore, the barrier should allow writes
        every { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit

        val lease = leaseRegistry.acquire("location_backfill")
        // Should NOT throw
        lease.checkpoint("update_location")
        lease.close()
    }

    @Test
    fun `writeBarrier blocks all eight worker names during restore`() = runTest {
        val mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        val workerNames = WorkerSpecScheduler.listAllWorkerNames()

        every { writeBarrier.checkWritesAllowed(any<String>()) } throws
            DatabaseAccessBlockedException(
                DatabaseAccessType.WRITE,
                DatabaseAccessOperation("any_worker"),
                mode
            )

        for (name in workerNames) {
            val lease = leaseRegistry.acquire(name)
            assertThrows(DatabaseAccessBlockedException::class.java) {
                kotlinx.coroutines.runBlocking { lease.checkpoint("work") }
            }
            lease.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. leaseRegistry stopRequested prevents new leases during restore
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `stopRequested prevents new lease acquisition`() = runTest {
        leaseRegistry.requestStopAll("maintenance")

        // Any attempt to acquire a lease must be rejected
        val allWorkerNames = listOf(
            "notification_intake", "location_backfill", "merchant_key_backfill",
            "receipt_matching", "warranty_expiration_check", "data_retention",
            "ai_daily_briefing", "bill_reminder_periodic"
        )

        for (name in allWorkerNames) {
            val ex = assertThrows(LeaseAcquisitionBlockedException::class.java) {
                kotlinx.coroutines.runBlocking { leaseRegistry.acquire(name) }
            }
            assertTrue(
                "Exception message should contain 'stop requested': ${ex.message}",
                ex.message?.contains("stop requested", ignoreCase = true) == true
            )
        }

        assertEquals("No leases should be active", 0, leaseRegistry.activeLeaseCount())
    }

    @Test
    fun `resetStopFlag after drain allows new leases in all workers`() = runTest {
        // Simulate full restore lifecycle
        leaseRegistry.requestStopAll("restore")
        assertTrue(leaseRegistry.isStopRequested())

        // Drain completes
        assertTrue(leaseRegistry.awaitNoActiveWorkers(timeoutMs = 200))

        // Reset after maintenance exits
        leaseRegistry.resetStopFlag()
        assertFalse(leaseRegistry.isStopRequested())

        // All workers should be able to re-acquire
        for ((name, _) in WorkerSpec.DEFAULTS) {
            val lease = leaseRegistry.acquire(name)
            assertNotNull("Lease for '$name' should be acquirable after reset", lease)
            lease.close()
        }
    }

    @Test
    fun `concurrent stop request and acquire is safe`() = runTest {
        // Simulate: stop is requested while workers are starting
        var lease1: WorkerLease? = null
        var lease2: WorkerLease? = null
        var acquiredCount = 0
        var blockedCount = 0

        val job1 = launch {
            try {
                lease1 = leaseRegistry.acquire("worker_a")
                acquiredCount++
            } catch (_: LeaseAcquisitionBlockedException) {
                blockedCount++
            }
        }
        val job2 = launch {
            try {
                lease2 = leaseRegistry.acquire("worker_b")
                acquiredCount++
            } catch (_: LeaseAcquisitionBlockedException) {
                blockedCount++
            }
        }
        val job3 = launch {
            leaseRegistry.requestStopAll("concurrent restore")
        }

        job1.join(); job2.join(); job3.join()

        val total = acquiredCount + blockedCount
        assertEquals("All 2 acquire attempts must resolve", 2, total)
        assertTrue("Registry must be in consistent state",
            leaseRegistry.activeLeaseCount() in 0..2)

        lease1?.close()
        lease2?.close()
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. Worker terminal logging is idempotent across restore restart
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `terminal logging CAS prevents double-success across restore restart`() = runTest {
        // Create a mock DAO that verifies terminal is only called once
        val dao = mockk<BackgroundJobRunDao>(relaxed = true)
        val idSlot = slot<com.yourname.expensetracker.data.database.entity.BackgroundJobRun>()
        coEvery { dao.insert(capture(idSlot)) } returns 1L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1

        val sanitizer = mockk<com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer>()
        every { sanitizer.sanitizeExceptionMessage(any()) } returns null
        val tp = mockk<TimeProvider>()
        every { tp.now() } returns 1700000000000L

        val logger = WorkerRunLoggerImpl(dao, sanitizer, tp)

        val handle = logger.start("test_worker")
        val id = handle.runId
        val corrId = handle.correlationId

        // First terminal write — should succeed
        handle.success(rowsScanned = 10, rowsUpdated = 5, notificationsSent = 1)

        // Duplicate terminal write (e.g. after restore restart re-delivers)
        handle.success(rowsScanned = 10, rowsUpdated = 5, notificationsSent = 1)

        // Verify DAO.completeTerminal was called exactly once
        coVerify(exactly = 1) {
            dao.completeTerminal(any(), "SUCCESS", any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `terminal logging CAS prevents double-retry across restore restart`() = runTest {
        val dao = mockk<BackgroundJobRunDao>(relaxed = true)
        coEvery { dao.insert(any()) } returns 42L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1

        val sanitizer = mockk<com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer>()
        every { sanitizer.sanitizeExceptionMessage(any()) } returns null
        val tp = mockk<TimeProvider>()
        every { tp.now() } returns 1700000000000L

        val logger = WorkerRunLoggerImpl(dao, sanitizer, tp)
        val handle = logger.start("test_worker")

        // Retry (first): simulate worker blocked by restore
        handle.retry("Blocked by restore barrier")

        // Retry (duplicate): after restore restart, same run gets re-terminated
        handle.retry("Blocked by restore barrier")

        coVerify(exactly = 1) {
            dao.completeTerminal(any(), "RETRY", any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `terminal logging CAS prevents double-failure`() = runTest {
        val dao = mockk<BackgroundJobRunDao>(relaxed = true)
        coEvery { dao.insert(any()) } returns 99L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1

        val sanitizer = mockk<com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer>()
        every { sanitizer.sanitizeExceptionMessage(any()) } returns null
        val tp = mockk<TimeProvider>()
        every { tp.now() } returns 1700000000000L

        val logger = WorkerRunLoggerImpl(dao, sanitizer, tp)
        val handle = logger.start("test_worker")

        val err = RuntimeException("Permanent failure")
        handle.failure("DB corrupt", err)
        handle.failure("DB corrupt", err)

        coVerify(exactly = 1) {
            dao.completeTerminal(any(), "FAILED", any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `terminal logging CAS race between retry and skipped is safe`() = runTest {
        // Simulate race: one path records RETRY, another tries SKIPPED
        val dao = mockk<BackgroundJobRunDao>(relaxed = true)
        coEvery { dao.insert(any()) } returns 77L
        coEvery { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1

        val sanitizer = mockk<com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer>()
        every { sanitizer.sanitizeExceptionMessage(any()) } returns null
        val tp = mockk<TimeProvider>()
        every { tp.now() } returns 1700000000000L

        val logger = WorkerRunLoggerImpl(dao, sanitizer, tp)
        val handle = logger.start("test_worker")

        // First write wins
        handle.retry("Transient error")
        // Second write should be no-op
        handle.skipped("Too late — already retried")

        // Only one terminal call
        coVerify(exactly = 1) {
            dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        // The sole call should be RETRY (first writer wins)
        coVerify(exactly = 1) {
            dao.completeTerminal(any(), "RETRY", any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────

    /** Exposes active lease count for test assertions. */
    private fun WorkerLeaseRegistryImpl.activeLeaseCount(): Int =
        this.activeLeases.size
}
