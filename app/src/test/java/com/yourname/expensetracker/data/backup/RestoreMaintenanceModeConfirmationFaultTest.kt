package com.yourname.expensetracker.data.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.SettableFuture
import com.yourname.expensetracker.domain.bank.BankSyncStartupRecovery
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.MonotonicTimeProvider
import com.yourname.expensetracker.domain.workers.PendingWorkerTestFactory
import com.yourname.expensetracker.domain.workers.ScheduleResult
import com.yourname.expensetracker.domain.workers.WorkerLease
import com.yourname.expensetracker.domain.workers.WorkerLeaseRegistry
import com.yourname.expensetracker.domain.workers.WorkerRegistry
import com.yourname.expensetracker.domain.workers.WorkerSpec
import com.yourname.expensetracker.startup.AppStartupCoordinator
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * RG01A-1 (release 1.0 assurance, R0-4): a worker-reschedule CONFIRMATION failure after a
 * restore must NOT durably latch CRITICAL_RECOVERY_REQUIRED.
 *
 * Intended contract (owner decision D-01 a): the mode stays persisted as NORMAL with
 * worker_resume_pending=true (effective mode RESTORE_COMPLETE_RESTART_REQUIRED, operational
 * state RestartRequiredAfterRestore), writes stay denied, the worker stop flag is not reset,
 * no CRITICAL keys / sentinel are written, and the next launch retries the checked resume
 * (one scheduleAll attempt per launch) and recovers to NORMAL.
 *
 * Real mode persistence (Robolectric filesDir/prefs/noBackupFilesDir) is used; only
 * [WorkerRegistry.scheduleAll], the WorkManager confirmation future and the monotonic clock are
 * controlled. Pre-fix every "failed launch" test fails with an RG01A-1 latched-CRITICAL message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RestoreMaintenanceModeConfirmationFaultTest {

    private lateinit var context: Context
    private val fixedTime = 1716163200000L

    private val scheduleCalls = AtomicInteger()
    private var scheduleOutcome: () -> WorkerRegistry.ScheduleAllResult = { successfulSummary() }

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setWorkerFactory(PendingWorkerTestFactory())
                .build()
        )
        File(context.noBackupFilesDir, SENTINEL_FILE).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        listOf(
            "restore_journal.json",
            RestoreJournal.FAILURE_JOURNAL_FILENAME,
            RestoreJournal.SUCCESS_JOURNAL_FILENAME
        ).forEach { File(context.filesDir, it).delete() }

        scheduleCalls.set(0)
        scheduleOutcome = { successfulSummary() }
        mockkObject(WorkerRegistry)
        every { WorkerRegistry.scheduleAll(any(), any(), null) } answers {
            scheduleCalls.incrementAndGet()
            scheduleOutcome()
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
        unmockkObject(WorkerRegistry)
    }

    // ── Fakes ──────────────────────────────────────────────────────

    private class CountingLeaseRegistry : WorkerLeaseRegistry {
        val resetStopFlagCalls = AtomicInteger()
        override suspend fun acquire(workerName: String): WorkerLease = object : WorkerLease {
            override val leaseId: String = "rg01a1-fake-lease"
            override suspend fun checkpoint(operation: String) {}
            override fun close() {}
        }

        override suspend fun requestStopAll(reason: String) {}
        override suspend fun awaitNoActiveWorkers(timeoutMs: Long) = true
        override fun isStopRequested() = false
        override fun resetStopFlag() {
            resetStopFlagCalls.incrementAndGet()
        }
    }

    /** Returns 0 on the first call (deadline origin), then 11 s so the 10 s confirm window is spent. */
    private class JumpingMonotonicTimeProvider : MonotonicTimeProvider {
        private val calls = AtomicInteger()
        override fun nowNanos(): Long =
            if (calls.getAndIncrement() == 0) 0L else 11_000_000_000L
    }

    private fun successfulSummary(): WorkerRegistry.ScheduleAllResult =
        WorkerRegistry.ScheduleAllResult(
            WorkerSpec.DEFAULTS.keys.map { name ->
                ScheduleResult(name, scheduled = true, policyUsed = "TEST", versionChanged = false)
            }
        )

    private fun failedSummary(): WorkerRegistry.ScheduleAllResult =
        WorkerRegistry.ScheduleAllResult(
            WorkerSpec.DEFAULTS.keys.mapIndexed { index, name ->
                ScheduleResult(
                    workerName = name,
                    scheduled = index != 0,
                    policyUsed = "TEST",
                    versionChanged = false,
                    error = if (index == 0) "TEST_SCHEDULE_FAILURE" else null
                )
            }
        )

    private fun newMode(
        leases: CountingLeaseRegistry,
        monotonic: MonotonicTimeProvider = com.yourname.expensetracker.domain.util.SystemMonotonicTimeProvider()
    ): RestoreMaintenanceMode = RestoreMaintenanceMode(
        context,
        dagger.Lazy { leases },
        FakeTimeProvider(fixedTime),
        monotonic
    )

    /** Mocks WorkManager so every default worker confirms exactly one active unique work. */
    private fun mockConfirmingWorkManager() {
        val manager = mockk<WorkManager>(relaxed = true)
        val active = mockk<WorkInfo>()
        every { active.state } returns WorkInfo.State.ENQUEUED
        every { manager.getWorkInfosForUniqueWork(any()) } answers {
            SettableFuture.create<List<WorkInfo>>().apply { set(listOf(active)) }
        }
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns manager
    }

    private fun newCoordinator(
        mode: RestoreMaintenanceMode,
        scope: CoroutineScope
    ): AppStartupCoordinator = AppStartupCoordinator(
        appContext = context,
        backgroundLifecycleObserver = mockk(relaxed = true),
        foregroundStateObserver = mockk(relaxed = true),
        syncProactiveBriefingWorkUseCase = mockk(relaxed = true),
        restoreJournal = RestoreJournal(context, FakeTimeProvider(fixedTime)),
        restoreMaintenanceMode = mode,
        restoreDatabaseOpener = mockk(relaxed = true),
        restoreInternalWriteScope = RestoreInternalWriteScope(mode),
        workerExecutionGuard = mockk(relaxed = true),
        restoreJournalImporter = mockk(relaxed = true),
        intakeRecoveryScheduler = mockk(relaxed = true),
        bankSyncStartupRecovery = mockk<BankSyncStartupRecovery>(relaxed = true),
        timeProvider = FakeTimeProvider(fixedTime),
        applicationScope = scope
    )

    /** Launch 1: restore finished, synchronous checked exit whose confirmation window expires. */
    private fun failFirstLaunchSynchronously(leases: CountingLeaseRegistry): RestoreMaintenanceMode {
        val mode = newMode(leases, JumpingMonotonicTimeProvider())
        mode.enter(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        try {
            mode.exit(forceRestartRequired = false)
            fail("RG01A-1: exit(false) must throw WorkerRescheduleException when confirmation fails")
        } catch (_: RestoreMaintenanceMode.WorkerRescheduleException) {
            // expected
        }
        return mode
    }

    // ── Assertions ─────────────────────────────────────────────────

    private fun assertRetainedResumePending(
        label: String,
        mode: RestoreMaintenanceMode,
        leases: CountingLeaseRegistry
    ) {
        val observed = mode.currentMode()
        assertEquals(
            "RG01A-1: latched CRITICAL after reschedule failure (mode=$observed, $label)",
            RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
            observed
        )

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        assertEquals(
            "RG01A-1: persisted mode must stay NORMAL ($label)",
            RestoreMaintenanceMode.Mode.NORMAL.name,
            prefs.getString("current_mode", null)
        )
        assertTrue(
            "RG01A-1: worker_resume_pending must remain durable ($label)",
            prefs.contains("worker_resume_pending") && prefs.getBoolean("worker_resume_pending", false)
        )
        assertFalse(
            "RG01A-1: CRITICAL reason key must not be written ($label)",
            prefs.contains("critical_recovery_reason")
        )
        assertFalse(
            "RG01A-1: CRITICAL timestamp key must not be written ($label)",
            prefs.contains("critical_recovery_timestamp")
        )
        assertFalse(
            "RG01A-1: CRITICAL sentinel file must not exist ($label)",
            File(context.noBackupFilesDir, SENTINEL_FILE).exists()
        )

        assertEquals(
            "RG01A-1: operational state must be RestartRequiredAfterRestore ($label)",
            AppOperationalState.RestartRequiredAfterRestore,
            mode.operationalStateFlow.value
        )
        assertFalse("RG01A-1: writes must stay denied ($label)", mode.isWritesAllowed())
        try {
            DatabaseWriteBarrier(mode).checkWritesAllowed("RG01A-1_probe")
            fail("RG01A-1: write barrier must deny writes ($label)")
        } catch (_: DatabaseAccessBlockedException) {
            // expected
        }
        assertEquals(
            "RG01A-1: stop flag must not be reset before confirmed resume ($label)",
            0,
            leases.resetStopFlagCalls.get()
        )
    }

    private fun assertRecoveredToNormal(label: String, mode: RestoreMaintenanceMode) {
        assertEquals(
            "RG01A-1: next launch must recover to NORMAL ($label)",
            RestoreMaintenanceMode.Mode.NORMAL,
            mode.currentMode()
        )
        assertTrue("RG01A-1: writes must be allowed after recovery ($label)", mode.isWritesAllowed())
        DatabaseWriteBarrier(mode).checkWritesAllowed("RG01A-1_recovered_probe")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        assertFalse(
            "RG01A-1: resumePending must be cleared after recovery ($label)",
            prefs.contains("worker_resume_pending")
        )
        assertEquals(AppOperationalState.Normal, mode.operationalStateFlow.value)
    }

    // ── b1: synchronous exit(false), confirmation window expires ───

    @Test
    fun b1_sync_exit_confirmation_timeout_retains_resume_pending_not_critical() {
        val leases = CountingLeaseRegistry()
        val mode = failFirstLaunchSynchronously(leases)

        assertEquals("one scheduleAll attempt per exit", 1, scheduleCalls.get())
        assertRetainedResumePending("b1", mode, leases)
    }

    // ── b2: exitCancellable, confirmation future fails ─────────────

    @Test
    fun b2_exitCancellable_failed_confirmation_future_retains_resume_pending_not_critical() = runTest {
        val leases = CountingLeaseRegistry()
        val mode = newMode(leases)
        mode.enter(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)

        val manager = mockk<WorkManager>(relaxed = true)
        every { manager.getWorkInfosForUniqueWork(any()) } answers {
            SettableFuture.create<List<WorkInfo>>().apply {
                setException(IllegalStateException("synthetic confirmation failure"))
            }
        }
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns manager

        try {
            mode.exitCancellable(forceRestartRequired = false)
            fail("RG01A-1: exitCancellable(false) must throw WorkerRescheduleException when confirmation fails")
        } catch (_: RestoreMaintenanceMode.WorkerRescheduleException) {
            // expected
        }

        assertEquals("one scheduleAll attempt per exit", 1, scheduleCalls.get())
        assertRetainedResumePending("b2", mode, leases)
    }

    // ── c: next launch recovers ────────────────────────────────────

    @Test
    fun c_next_launch_after_confirmation_failure_recovers_to_normal() = runTest {
        val leases = CountingLeaseRegistry()
        val launch1 = failFirstLaunchSynchronously(leases)
        assertRetainedResumePending("c launch1", launch1, leases)

        // Launch 2: fresh process state rebuilt from persisted prefs only.
        mockConfirmingWorkManager()
        scheduleCalls.set(0)
        val launch2 = newMode(leases)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            newCoordinator(launch2, scope).initialize(ApplicationProvider.getApplicationContext<Application>())
            advanceUntilIdle()
        } finally {
            scope.cancel()
        }

        assertRecoveredToNormal("c launch2", launch2)
        assertTrue("checked resume must have scheduled workers", scheduleCalls.get() >= 1)
        assertEquals("stop flag reset exactly once, after confirmation", 1, leases.resetStopFlagCalls.get())
    }

    /**
     * Control (passes before and after the fix): the same next-launch recovery from a directly
     * seeded NORMAL + resumePending state. Isolates recovery from the failure-retention fix.
     */
    @Test
    fun c_control_seeded_pending_state_recovers_on_next_launch() = runTest {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        assertTrue(
            prefs.edit()
                .putString("current_mode", RestoreMaintenanceMode.Mode.NORMAL.name)
                .putBoolean("worker_resume_pending", true)
                .commit()
        )
        mockConfirmingWorkManager()
        val leases = CountingLeaseRegistry()
        val mode = newMode(leases)
        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED, mode.currentMode())
        assertFalse(mode.isWritesAllowed())

        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            newCoordinator(mode, scope).initialize(ApplicationProvider.getApplicationContext<Application>())
            advanceUntilIdle()
        } finally {
            scope.cancel()
        }

        assertRecoveredToNormal("c_control", mode)
    }

    // ── d: bounded retries ─────────────────────────────────────────

    @Test
    fun d_failed_retry_is_bounded_to_one_attempt_per_launch_and_third_launch_recovers() = runTest {
        val leases = CountingLeaseRegistry()
        val launch1 = failFirstLaunchSynchronously(leases)
        assertRetainedResumePending("d launch1", launch1, leases)

        // Launch 2: scheduling fails again.
        scheduleCalls.set(0)
        scheduleOutcome = { failedSummary() }
        val launch2 = newMode(leases)
        val scope2 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            newCoordinator(launch2, scope2).initialize(ApplicationProvider.getApplicationContext<Application>())
            advanceUntilIdle()
        } finally {
            scope2.cancel()
        }
        assertEquals("RG01A-1: exactly one scheduleAll attempt in a failing launch", 1, scheduleCalls.get())
        assertRetainedResumePending("d launch2", launch2, leases)

        // Launch 3: scheduling and confirmation succeed.
        scheduleOutcome = { successfulSummary() }
        mockConfirmingWorkManager()
        val launch3 = newMode(leases)
        val scope3 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            newCoordinator(launch3, scope3).initialize(ApplicationProvider.getApplicationContext<Application>())
            advanceUntilIdle()
        } finally {
            scope3.cancel()
        }
        assertRecoveredToNormal("d launch3", launch3)
    }

    private companion object {
        const val PREFS = "restore_maintenance_mode"
        const val SENTINEL_FILE = "restore_maintenance_critical"
    }
}
