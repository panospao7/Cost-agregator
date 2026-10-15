package com.yourname.expensetracker.data.repository

import android.content.Context
import android.database.MatrixCursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.SettableFuture
import com.yourname.expensetracker.data.backup.AppOperationalState
import com.yourname.expensetracker.data.backup.CostbackupBundle
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import com.yourname.expensetracker.data.privacy.ExportAnonymizer
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.workers.PendingWorkerTestFactory
import com.yourname.expensetracker.domain.workers.ScheduleResult
import com.yourname.expensetracker.domain.workers.WorkerLease
import com.yourname.expensetracker.domain.workers.WorkerLeaseRegistry
import com.yourname.expensetracker.domain.workers.WorkerRegistry
import com.yourname.expensetracker.domain.workers.WorkerSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * D-23 (release 1.0 assurance, R4b): an in-process restore/reset aborted BEFORE any destructive
 * step must not latch a permanent CRITICAL_RECOVERY_REQUIRED just because the follow-up worker
 * resume (exit(false)) failed to reschedule/confirm.
 *
 * Intended contract after R4b: the original abort error is returned, the mode is
 * RESTORE_COMPLETE_RESTART_REQUIRED (writes denied) backed by persisted NORMAL +
 * worker_resume_pending, no CRITICAL keys/sentinel exist, the live DB bytes are unchanged, the
 * terminal failure journal is preserved, decrypted temp extraction residue is removed, scheduleAll
 * ran exactly once, the worker stop flag was not reset, and the next launch recovers to NORMAL.
 *
 * Boundaries: real [RestoreMaintenanceMode] with real SharedPreferences/sentinel persistence, real
 * [RestoreJournal], real app files. Only [WorkerRegistry.scheduleAll] (failed summary), the
 * WorkManager confirmation future for the recovery launch, [CostbackupBundle.extract] (late
 * authentication failure after partial decrypted output) and the Room handle are controlled.
 * All fixture data is synthetic.
 *
 * Controls (pass before and after the fix): PersistenceException on exit and a post-destructive
 * rollback failure still latch CRITICAL; a healthy worker resume after the abort returns to NORMAL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreAbortWorkerResumeDeferredTest {

    private lateinit var context: Context
    private val fixedTime = 1716163200000L

    private val scheduleCalls = AtomicInteger()
    private var scheduleOutcome: () -> WorkerRegistry.ScheduleAllResult = { failedSummary() }
    private val extractCalls = AtomicInteger()
    private var residueWrittenByExtract = false

    private val liveDbBytes = ByteArray(96) { (it * 7 + 3).toByte() }
    private val decryptedResidueBytes = byteArrayOf(11, 22, 33, 44, 55)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(PendingWorkerTestFactory()).build()
        )
        cleanState()

        scheduleCalls.set(0)
        extractCalls.set(0)
        residueWrittenByExtract = false
        scheduleOutcome = { failedSummary() }
        mockkObject(WorkerRegistry)
        every { WorkerRegistry.scheduleAll(any(), any(), null) } answers {
            scheduleCalls.incrementAndGet()
            scheduleOutcome()
        }
    }

    @After
    fun tearDown() {
        unmockkObject(CostbackupBundle)
        unmockkStatic(WorkManager::class)
        unmockkObject(WorkerRegistry)
        cleanState()
    }

    // -- Tests -------------------------------------------------------------------------------

    @Test
    fun wrong_password_abort_with_worker_resume_failure_defers_to_restart_not_critical() = runBlocking {
        val leases = CountingLeaseRegistry()
        val mode = newMode(leases)
        val journal = RestoreJournal(context, FakeTimeProvider(fixedTime))
        seedLiveDb()
        stubExtractionFailsAfterPartialOutput()

        val result = newRepository(mode, journal, mockk(relaxed = true)).restoreCostBackup(
            File(context.filesDir, "synthetic_bundle.costbackup"),
            "synthetic-wrong-password"
        )

        assertEquals("D-23: extraction stub must have run exactly once", 1, extractCalls.get())
        assertTrue("D-23: stub must have left decrypted residue to be cleaned", residueWrittenByExtract)
        assertTrue("D-23: restore must report failure", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "D-23: original WrongBackupPasswordException must be returned (was ${error?.javaClass?.simpleName})",
            error is CostbackupBundle.WrongBackupPasswordException
        )
        assertEquals("D-23: one scheduleAll attempt for the abort exit", 1, scheduleCalls.get())
        assertDeferredToRestart("restore wrong password", mode, leases)
        assertLiveDbUnchanged("restore wrong password")
        assertNoExtractionOrStagedResidue("restore wrong password")
        assertFailureJournalPreserved("restore wrong password")

        assertRecoversOnNextLaunch("restore wrong password", leases)
    }

    @Test
    fun reset_safety_backup_failure_with_worker_resume_failure_defers_to_restart_not_critical() = runBlocking {
        val leases = CountingLeaseRegistry()
        val mode = newMode(leases)
        val journal = RestoreJournal(context, FakeTimeProvider(fixedTime))
        seedLiveDb()
        // The safety backup's WAL checkpoint cannot open the live DB -> safety backup fails (pre-destructive).
        val database = mockk<AppDatabase>(relaxed = true)
        every { database.openHelper } throws IllegalStateException("synthetic open failure")

        val result = newRepository(mode, journal, database).resetDatabase()

        assertTrue("D-23: reset must report failure", result.isFailure)
        val error = result.exceptionOrNull()
        assertFalse(
            "D-23: reset must not surface the worker reschedule failure (was ${error?.javaClass?.simpleName})",
            error is RestoreMaintenanceMode.WorkerRescheduleException ||
                error is RestoreMaintenanceMode.PersistenceException
        )
        assertTrue(
            "D-23: original safety-backup-failed message must be returned (was ${error?.message})",
            error?.message?.contains("safety backup failed") == true
        )
        assertEquals("D-23: one scheduleAll attempt for the abort exit", 1, scheduleCalls.get())
        assertDeferredToRestart("reset safety backup", mode, leases)
        assertLiveDbUnchanged("reset safety backup")
        assertFailureJournalPreserved("reset safety backup")

        assertRecoversOnNextLaunch("reset safety backup", leases)
    }

    // -- Controls ----------------------------------------------------------------------------

    @Test
    fun persistence_failure_on_exit_still_latches_critical() = runBlocking {
        val leases = CountingLeaseRegistry()
        val mode = spyk(newMode(leases))
        every { mode.exit(any()) } throws RestoreMaintenanceMode.PersistenceException()
        val journal = RestoreJournal(context, FakeTimeProvider(fixedTime))
        seedLiveDb()
        stubExtractionFailsAfterPartialOutput()

        val result = newRepository(mode, journal, mockk(relaxed = true)).restoreCostBackup(
            File(context.filesDir, "synthetic_bundle.costbackup"),
            "synthetic-wrong-password"
        )

        assertTrue("D-23 control: restore must report failure", result.isFailure)
        assertTrue(
            "D-23 control: PersistenceException must still be returned (was ${result.exceptionOrNull()?.javaClass?.simpleName})",
            result.exceptionOrNull() is RestoreMaintenanceMode.PersistenceException
        )
        assertCriticalLatched("D-23 control persistence", mode)
        assertLiveDbUnchanged("control persistence")
    }

    @Test
    fun post_destructive_rollback_failure_still_latches_critical() = runBlocking {
        val leases = CountingLeaseRegistry()
        val mode = newMode(leases)
        val journal = RestoreJournal(context, FakeTimeProvider(fixedTime))
        val liveDb = seedLiveDb()
        // Reset reaches the destructive step: the checkpoint (first open) succeeds, the safety backup
        // is real, then the WAL path is a non-empty directory so the destructive delete throws. The
        // rollback re-open (second open) fails, so the rollback itself fails.
        File(liveDb.path + "-wal").apply { mkdirs() }.let { File(it, "blocker").writeBytes(byteArrayOf(1)) }
        val opens = AtomicInteger()
        val supportDb = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { supportDb.query("PRAGMA wal_checkpoint(TRUNCATE)") } answers {
            MatrixCursor(arrayOf("busy")).apply { addRow(arrayOf<Any>(0)) }
        }
        val helper = mockk<SupportSQLiteOpenHelper>(relaxed = true)
        every { helper.writableDatabase } answers {
            if (opens.incrementAndGet() == 1) supportDb else throw IllegalStateException("synthetic reopen failure")
        }
        val database = mockk<AppDatabase>(relaxed = true)
        every { database.openHelper } returns helper

        val result = newRepository(mode, journal, database).resetDatabase()

        assertTrue("D-23 control: reset must report failure", result.isFailure)
        assertTrue("D-23 control: destructive step must have been reached", opens.get() >= 2)
        assertCriticalLatched("D-23 control rollback", mode)
    }

    @Test
    fun wrong_password_abort_with_healthy_worker_resume_returns_to_normal() = runBlocking {
        val leases = CountingLeaseRegistry()
        val mode = newMode(leases)
        val journal = RestoreJournal(context, FakeTimeProvider(fixedTime))
        seedLiveDb()
        scheduleOutcome = { successfulSummary() }
        mockConfirmingWorkManager()
        stubExtractionFailsAfterPartialOutput()

        val result = newRepository(mode, journal, mockk(relaxed = true)).restoreCostBackup(
            File(context.filesDir, "synthetic_bundle.costbackup"),
            "synthetic-wrong-password"
        )

        assertTrue(
            "D-23 control: original WrongBackupPasswordException must be returned",
            result.exceptionOrNull() is CostbackupBundle.WrongBackupPasswordException
        )
        assertEquals("D-23 control: abort must recover to NORMAL", RestoreMaintenanceMode.Mode.NORMAL, mode.currentMode())
        assertTrue("D-23 control: writes allowed after healthy exit", mode.isWritesAllowed())
        assertEquals(AppOperationalState.Normal, mode.operationalStateFlow.value)
        assertEquals("D-23 control: stop flag reset once after confirmed resume", 1, leases.resetStopFlagCalls.get())
        assertNoCriticalState("D-23 control healthy")
        assertLiveDbUnchanged("control healthy")
        assertNoExtractionOrStagedResidue("control healthy")
        assertFailureJournalPreserved("control healthy")
    }

    // -- Fixtures ----------------------------------------------------------------------------

    private class CountingLeaseRegistry : WorkerLeaseRegistry {
        val resetStopFlagCalls = AtomicInteger()
        override suspend fun acquire(workerName: String): WorkerLease = object : WorkerLease {
            override val leaseId: String = "d23-fake-lease"
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

    private fun newMode(leases: CountingLeaseRegistry): RestoreMaintenanceMode =
        RestoreMaintenanceMode(context, dagger.Lazy { leases }, FakeTimeProvider(fixedTime))

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

    private fun newRepository(
        mode: RestoreMaintenanceMode,
        journal: RestoreJournal,
        database: AppDatabase
    ): DatabaseBackupRepositoryImpl = DatabaseBackupRepositoryImpl(
        context = context,
        database = database,
        ioDispatcher = Dispatchers.Unconfined,
        privacyGate = mockk<PrivacyGate>(relaxed = true),
        privacySettingsRepository = mockk<PrivacySettingsRepository>(relaxed = true),
        backupEncryptionService = mockk<BackupEncryptionService>(relaxed = true),
        exportAnonymizer = mockk<ExportAnonymizer>(relaxed = true),
        secureKeyStorage = mockk<SecureKeyStorage>(relaxed = true),
        receiptAssetStore = mockk(relaxed = true),
        restoreMaintenanceMode = mode,
        restoreJournal = journal,
        stagedImportVerifier = { _, _, _, _, summary -> summary },
        liveImportVerifier = { _, _, _, summary -> summary },
        timeProvider = FakeTimeProvider(fixedTime)
    )

    /**
     * Models a late authentication failure: the bundle stream has already written decrypted output
     * into the extraction workspace when the wrong-password failure surfaces.
     */
    private fun stubExtractionFailsAfterPartialOutput() {
        mockkObject(CostbackupBundle)
        every {
            CostbackupBundle.extract(any(), any(), any(), any(), any(), any())
        } answers {
            extractCalls.incrementAndGet()
            val outputDir = secondArg<File>()
            outputDir.mkdirs()
            File(outputDir, "database.sqlite").writeBytes(decryptedResidueBytes)
            residueWrittenByExtract = File(outputDir, "database.sqlite").isFile
            Result.failure<CostbackupBundle.ExtractionResult>(
                CostbackupBundle.WrongBackupPasswordException("BACKUP_AUTHENTICATION_FAILED")
            )
        }
    }

    private fun liveDbFile(): File = context.getDatabasePath(AppDatabase.DATABASE_NAME)

    private fun seedLiveDb(): File = liveDbFile().apply {
        parentFile?.mkdirs()
        writeBytes(liveDbBytes)
    }

    private fun cleanState() {
        File(context.noBackupFilesDir, SENTINEL_FILE).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        listOf(
            "restore_journal.json",
            RestoreJournal.FAILURE_JOURNAL_FILENAME,
            RestoreJournal.SUCCESS_JOURNAL_FILENAME
        ).forEach { File(context.filesDir, it).delete() }
        File(context.filesDir, "safety_backups").deleteRecursively()
        File(liveDbFile().path + "-wal").deleteRecursively()
        File(liveDbFile().path + "-shm").delete()
        liveDbFile().delete()
        context.cacheDir.listFiles { f -> f.name.startsWith(EXTRACT_PREFIX) }?.forEach { it.deleteRecursively() }
        liveDbFile().parentFile?.listFiles { f -> f.name.startsWith(STAGE_PREFIX) }?.forEach { it.deleteRecursively() }
    }

    // -- Assertions --------------------------------------------------------------------------

    private fun assertNoCriticalState(label: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        assertFalse("$label: CRITICAL reason key must not be written", prefs.contains("critical_recovery_reason"))
        assertFalse("$label: CRITICAL timestamp key must not be written", prefs.contains("critical_recovery_timestamp"))
        assertFalse("$label: CRITICAL sentinel file must not exist", File(context.noBackupFilesDir, SENTINEL_FILE).exists())
    }

    private fun assertDeferredToRestart(label: String, mode: RestoreMaintenanceMode, leases: CountingLeaseRegistry) {
        assertEquals(
            "D-23: pre-destructive abort latched CRITICAL instead of restart-required ($label)",
            RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
            mode.currentMode()
        )
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        assertEquals(
            "D-23: persisted mode must stay NORMAL ($label)",
            RestoreMaintenanceMode.Mode.NORMAL.name,
            prefs.getString("current_mode", null)
        )
        assertTrue(
            "D-23: worker_resume_pending must remain durable ($label)",
            prefs.getBoolean("worker_resume_pending", false)
        )
        assertNoCriticalState("D-23 ($label)")
        assertEquals(
            "D-23: operational state must be RestartRequiredAfterRestore ($label)",
            AppOperationalState.RestartRequiredAfterRestore,
            mode.operationalStateFlow.value
        )
        assertFalse("D-23: writes must stay denied ($label)", mode.isWritesAllowed())
        try {
            DatabaseWriteBarrier(mode).checkWritesAllowed("D-23_probe")
            fail("D-23: write barrier must deny writes ($label)")
        } catch (_: com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
            // expected
        }
        assertEquals("D-23: stop flag must not be reset before confirmed resume ($label)", 0, leases.resetStopFlagCalls.get())
    }

    private fun assertCriticalLatched(label: String, mode: RestoreMaintenanceMode) {
        assertEquals(
            "$label: mode must be CRITICAL_RECOVERY_REQUIRED",
            RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED,
            mode.currentMode()
        )
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        assertNotNull("$label: CRITICAL reason must be persisted", prefs.getString("critical_recovery_reason", null))
        assertEquals(
            "$label: persisted mode must be CRITICAL",
            RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED.name,
            prefs.getString("current_mode", null)
        )
        assertTrue(
            "$label: CRITICAL sentinel must exist",
            File(context.noBackupFilesDir, SENTINEL_FILE).exists()
        )
        assertFalse("$label: writes must be denied", mode.isWritesAllowed())
    }

    private fun assertLiveDbUnchanged(label: String) {
        val live = liveDbFile()
        assertTrue("D-23: live DB must still exist ($label)", live.isFile)
        assertArrayEquals("D-23: live DB bytes must be unchanged ($label)", liveDbBytes, live.readBytes())
    }

    private fun assertNoExtractionOrStagedResidue(label: String) {
        val extraction = context.cacheDir.listFiles { f -> f.name.startsWith(EXTRACT_PREFIX) }?.toList().orEmpty()
        assertTrue("D-23: decrypted extraction workspace leaked ($label): ${extraction.map { it.name }}", extraction.isEmpty())
        val staged = liveDbFile().parentFile?.listFiles { f -> f.name.startsWith(STAGE_PREFIX) }?.toList().orEmpty()
        assertTrue("D-23: staged DB files leaked ($label): ${staged.map { it.name }}", staged.isEmpty())
    }

    private fun assertFailureJournalPreserved(label: String) {
        // RestoreJournal.failJournal stores only an allowlisted code; the raw stage names map to UNKNOWN_ERROR.
        val failure = RestoreJournal(context, FakeTimeProvider(fixedTime)).readFailureJournal()
        assertNotNull("D-23: failure journal must be preserved ($label)", failure)
        assertEquals("D-23: failure journal state ($label)", RestoreJournal.JournalState.FAILED, failure!!.state)
        assertEquals(
            "D-23: failure journal reason must be the bounded code ($label)",
            com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.UNKNOWN_ERROR.name,
            failure.error
        )
        assertFalse(
            "D-23: active journal must have been renamed away ($label)",
            File(context.filesDir, "restore_journal.json").exists()
        )
    }

    /** Fresh process state rebuilt only from persisted prefs; one checked resume must reach NORMAL. */
    private fun assertRecoversOnNextLaunch(label: String, leases: CountingLeaseRegistry) {
        scheduleOutcome = { successfulSummary() }
        scheduleCalls.set(0)
        mockConfirmingWorkManager()
        val launch2 = newMode(leases)
        assertNull(
            "D-23: next launch must not see a CRITICAL sentinel ($label)",
            if (File(context.noBackupFilesDir, SENTINEL_FILE).exists()) "sentinel" else null
        )
        runBlocking { launch2.resetCancellable() }

        assertEquals(
            "D-23: next launch must recover to NORMAL ($label)",
            RestoreMaintenanceMode.Mode.NORMAL,
            launch2.currentMode()
        )
        assertTrue("D-23: writes allowed after recovery ($label)", launch2.isWritesAllowed())
        assertFalse(
            "D-23: resumePending must be cleared after recovery ($label)",
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("worker_resume_pending")
        )
        assertEquals("D-23: exactly one checked resume attempt ($label)", 1, scheduleCalls.get())
        assertEquals("D-23: stop flag reset exactly once after confirmation ($label)", 1, leases.resetStopFlagCalls.get())
        assertLiveDbUnchanged("$label after recovery")
    }

    private companion object {
        const val PREFS = "restore_maintenance_mode"
        const val SENTINEL_FILE = "restore_maintenance_critical"
        const val EXTRACT_PREFIX = "costbackup_extract_"
        const val STAGE_PREFIX = "expense_tracker_db_import_stage_"
    }
}
