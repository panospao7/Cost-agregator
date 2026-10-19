package com.yourname.expensetracker.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.SettableFuture
import com.yourname.expensetracker.domain.bank.BankSyncStartupRecovery
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.workers.PendingWorkerTestFactory
import com.yourname.expensetracker.domain.workers.ScheduleResult
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * HZ-03 F1 (Q-R32-1): a restore cancelled after the destructive swap now behaves exactly like a
 * process crash. The cancellation cleanup leaves the journal in its post-swap state (the production
 * handler calls `restoreMaintenanceMode.exit(forceRestartRequired = true)` and only removes the
 * staged DB trio), so the NEXT app start runs the existing journal recovery:
 *  - SWAPPING/VERIFYING  -> rollback from the safety backup, failure journal FAILED with the bounded
 *    code UNKNOWN_ERROR (RestoreJournal.failureReasonCode sanitizes the non-allowlisted internal
 *    STARTUP_SWAP_ROLLED_BACK label by design; the allowlist is intentionally not widened)
 *  - ASSETS_RESTORING    -> asset resume, journal finalized forward
 * and no CRITICAL_RECOVERY_REQUIRED latch is involved at any point.
 *
 * Real [RestoreJournal] (files on disk) and real [RestoreMaintenanceMode] (SharedPreferences);
 * the "next start" is a fresh mode/journal/coordinator trio, as in AppStartupCoordinatorRecoveryTest.
 * The post-cancel double-fault case (exit throws -> RESTORE_CANCELLED_CLEANUP_FAILED) needs the
 * repository fixture and lives in DatabaseBackupRepositoryImplTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreCancelAfterSwapRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private val time = FakeTimeProvider(1716163200000L)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.noBackupFilesDir, "restore_maintenance_critical").delete()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(PendingWorkerTestFactory()).build()
        )
        context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE).edit().clear().commit()
        RestoreMaintenanceMode(context, time).reset()
        listOf(
            "restore_journal.json",
            RestoreJournal.FAILURE_JOURNAL_FILENAME,
            RestoreJournal.SUCCESS_JOURNAL_FILENAME
        ).forEach { File(context.filesDir, it).delete() }
    }

    @Test
    fun cancel_during_VERIFYING_then_startup_rolls_back_from_safety_backup_without_critical() {
        val live = tmp.newFile("expense_tracker_db").apply { writeText("TEST_SWAPPED_NOT_SQLITE") }
        val safety = tmp.newFile("safety.db")
        createValidSqliteFile(safety)
        val safetyBytes = safety.readBytes()
        // Evidence written just before the swap; the rollback must consume it (RP-03).
        val preRestore = File(live.path + ".pre_restore").apply { writeText("PRE_RESTORE_SNAPSHOT") }

        val journal = RestoreJournal(context, time)
        val entry = journal.beginJournal(File(context.cacheDir, "src.costbackup").absolutePath, "", live.absolutePath)
        journal.transitionTo(entry, RestoreJournal.JournalState.VERIFYING, safetyBackupPath = safety.absolutePath)

        // The production cancellation cleanup after the swap: restart-required exit only.
        val cancelledSessionMode = RestoreMaintenanceMode(context, time)
        cancelledSessionMode.enter(RestoreMaintenanceMode.Mode.RESTORE_VERIFYING)
        cancelledSessionMode.exit(forceRestartRequired = true)

        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED, cancelledSessionMode.currentMode())
        assertFalse("Writes stay blocked until restart", cancelledSessionMode.isWritesAllowed())
        assertTrue(cancelledSessionMode.operationalStateFlow.value is AppOperationalState.RestartRequiredAfterRestore)
        assertTrue("Journal must remain active for next-start recovery", journal.hasJournal())
        assertEquals(RestoreJournal.JournalState.VERIFYING, journal.readJournal()?.state)
        assertTrue("Safety backup must be kept", safety.exists())
        assertTrue(".pre_restore evidence must survive the cancellation cleanup", preRestore.exists())

        // Next app start.
        val freshMode = RestoreMaintenanceMode(context, time)
        val freshJournal = RestoreJournal(context, time)
        withCheckedResumeStubs { newCoordinator(freshMode, freshJournal).checkRestoreJournal() }

        assertEquals("Live DB must be rolled back to the safety backup", safetyBytes.toList(), live.readBytes().toList())
        assertFalse("Active journal must be consumed", freshJournal.hasJournal())
        val failure = freshJournal.readFailureJournal()
        assertNotNull(failure)
        assertEquals(
            com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.UNKNOWN_ERROR.name,
            failure?.error
        )
        assertEquals(RestoreJournal.JournalState.FAILED, failure?.state)
        assertFalse(
            "Stale .pre_restore must be consumed after a successful safety-backup rollback",
            preRestore.exists()
        )
        assertFalse(freshMode.currentMode() == RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED)
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, freshMode.currentMode())
        assertTrue(freshMode.isWritesAllowed())
    }

    @Test
    fun cancel_during_ASSETS_RESTORING_then_startup_resumes_assets_and_finalizes_journal() {
        val live = tmp.newFile("expense_tracker_db")
        createValidSqliteFile(live)
        val safety = tmp.newFile("safety.db")
        createValidSqliteFile(safety)
        val extractDir = tmp.newFolder("extract")

        val journal = RestoreJournal(context, time)
        var entry = journal.beginJournal(File(context.cacheDir, "src.costbackup").absolutePath, "", live.absolutePath)
        entry = entry.copy(extractTempDirPath = extractDir.absolutePath)
        journal.writeJournal(entry)
        journal.transitionTo(entry, RestoreJournal.JournalState.ASSETS_RESTORING, safetyBackupPath = safety.absolutePath)

        val cancelledSessionMode = RestoreMaintenanceMode(context, time)
        cancelledSessionMode.enter(RestoreMaintenanceMode.Mode.ASSETS_RESTORING)
        cancelledSessionMode.exit(forceRestartRequired = true)

        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED, cancelledSessionMode.currentMode())
        assertFalse(cancelledSessionMode.isWritesAllowed())
        assertTrue(journal.hasJournal())
        assertEquals(RestoreJournal.JournalState.ASSETS_RESTORING, journal.readJournal()?.state)
        assertTrue("Extraction workspace (asset-resume source) must be kept", extractDir.exists())

        // Next app start: asset resume finalizes the journal forward (no rollback, no CRITICAL).
        val startMode = RestoreMaintenanceMode(context, time)
        val startJournal = RestoreJournal(context, time)
        newCoordinator(startMode, startJournal).checkRestoreJournal()

        assertFalse("Journal must be consumed by the asset resume", startJournal.hasJournal())
        assertNotNull("Success journal must preserve the operation trail", startJournal.readSuccessJournal())
        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED, startMode.currentMode())

        // The following clean start auto-resets the transient mode: not blocked.
        val laterMode = RestoreMaintenanceMode(context, time)
        withCheckedResumeStubs { newCoordinator(laterMode, RestoreJournal(context, time)).checkRestoreJournal() }
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, laterMode.currentMode())
        assertTrue(laterMode.isWritesAllowed())
    }

    private fun newCoordinator(mode: RestoreMaintenanceMode, journal: RestoreJournal): AppStartupCoordinator =
        AppStartupCoordinator(
            appContext = context,
            backgroundLifecycleObserver = mockk(relaxed = true),
            syncProactiveBriefingWorkUseCase = mockk(relaxed = true),
            restoreJournal = journal,
            restoreMaintenanceMode = mode,
            restoreDatabaseOpener = mockk(relaxed = true),
            restoreInternalWriteScope = RestoreInternalWriteScope(mode),
            workerExecutionGuard = mockk(relaxed = true),
            restoreJournalImporter = mockk(relaxed = true),
            intakeRecoveryScheduler = mockk(relaxed = true),
            bankSyncStartupRecovery = mockk<BankSyncStartupRecovery>(relaxed = true),
            timeProvider = time,
            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )

    /** Same stubbing as AppStartupCoordinatorRecoveryTest's checked-resume positive control. */
    private fun withCheckedResumeStubs(block: () -> Unit) {
        val activeWork = mockk<WorkInfo>()
        every { activeWork.state } returns WorkInfo.State.ENQUEUED
        val manager = mockk<WorkManager>(relaxed = true)
        every { manager.getWorkInfosForUniqueWork(any()) } answers {
            SettableFuture.create<List<WorkInfo>>().apply { set(listOf(activeWork)) }
        }
        mockkObject(WorkerRegistry)
        mockkStatic(WorkManager::class)
        try {
            every { WorkerRegistry.scheduleAll(any(), any(), null) } returns
                WorkerRegistry.ScheduleAllResult(
                    WorkerSpec.DEFAULTS.keys.map { name ->
                        ScheduleResult(name, scheduled = true, policyUsed = "TEST", versionChanged = false)
                    }
                )
            every { WorkManager.getInstance(any<Context>()) } returns manager
            block()
        } finally {
            unmockkStatic(WorkManager::class)
            unmockkObject(WorkerRegistry)
        }
    }

    private fun createValidSqliteFile(file: File) {
        file.parentFile?.mkdirs()
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS recovery_probe (id INTEGER PRIMARY KEY, note TEXT)")
        } finally {
            db.close()
        }
    }
}
