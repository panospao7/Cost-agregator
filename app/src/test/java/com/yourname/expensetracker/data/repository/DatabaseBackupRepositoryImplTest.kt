package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.BuildConfig

import android.content.Context
import android.content.ContentResolver
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.APP_DATABASE_SCHEMA_VERSION
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.DatabaseSchemaPolicy
import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import com.yourname.expensetracker.data.privacy.ExportAnonymizer
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.backup.DatabaseImportSummary
import com.yourname.expensetracker.domain.privacy.CompositePrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import io.mockk.mockkObject
import io.mockk.unmockkObject
import androidx.room.testing.MigrationTestHelper
import com.yourname.expensetracker.data.backup.BackupVerifier
import com.yourname.expensetracker.data.backup.CostbackupBundle
import com.yourname.expensetracker.data.backup.RealSchemaFixture
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.backup.SemanticAggregateId
import com.yourname.expensetracker.data.backup.SemanticAggregateSet
import com.yourname.expensetracker.data.backup.SemanticAggregates
import com.yourname.expensetracker.data.backup.SemanticCaptureStatus
import com.yourname.expensetracker.data.backup.SemanticCheckResult
import com.yourname.expensetracker.data.backup.SemanticCheckpoint
import com.yourname.expensetracker.data.backup.SemanticCode
import com.yourname.expensetracker.data.backup.SemanticNotCapturedReason
import com.yourname.expensetracker.domain.backup.SemanticNotVerifiableReason
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPreBackupReconciler
import com.yourname.expensetracker.domain.installment.lifecycle.PreBackupReconcileResult
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.testfixtures.backup.lowIterationBackupEncryptionService
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.OperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDeniedException
import com.yourname.expensetracker.domain.privacy.PrivacyGateReasonCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Tests for [DatabaseBackupRepositoryImpl] covering backup/restore lifecycle.
 *
 * ## Test gaps (not yet covered):
 * - Privacy gate enforcement during export: verify that when [PrivacyGate] denies
 *   an export action (e.g. export of location data when location privacy is off),
 *   the repository honours the decision and does not leak protected data.
 * - Privacy gate enforcement during import: test that import respects privacy
 *   settings and does not restore settings/profiles that the user has opted out of.
 * - Multi-profile privacy boundary: test that restoring a backup from a different
 *   device/profile does not import privacy-sensitive data that conflicts with
 *   current device settings.
 * - Concurrent backup/restore conflicts: verify that starting a restore while
 *   a backup is in progress correctly cancels/queues the conflicting operation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseBackupRepositoryImplTest {

    /** UF-46 S2: builds the export fixtures on the real exported Room schema (see [createFullSchemaDatabase]). */
    @get:Rule
    val realSchemaHelper: MigrationTestHelper = RealSchemaFixture.newHelper()

    /** Short unique fixture DB names (long names overflow the Windows path limit under Robolectric). */
    private var fixtureSeq = 0

    private val context = mockk<Context>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val openHelper = mockk<SupportSQLiteOpenHelper>(relaxed = true)
    private val supportDb = mockk<SupportSQLiteDatabase>(relaxed = true)
    private val privacyGate = mockk<PrivacyGate>(relaxed = true)
    private val privacySettingsRepository = mockk<PrivacySettingsRepository>(relaxed = true)
    private val backupEncryptionService = mockk<BackupEncryptionService>(relaxed = true)
    private val exportAnonymizer = mockk<ExportAnonymizer>(relaxed = true)
    private val secureKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
    private val mockRestoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true)
    private val mockRestoreJournal = mockk<RestoreJournal>(relaxed = true)

    // S6c-4: these tests exercise the backup pipeline itself; the installment pre-reconcile
    // is covered by DatabaseBackupRepositoryPreReconcileTest and reports a clean reconcile here.
    private val mockPreBackupReconciler = mockk<InstallmentPreBackupReconciler> {
        coEvery { reconcileBeforeBackup() } returns PreBackupReconcileResult.Reconciled
    }

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: DatabaseBackupRepositoryImpl

    /** Deterministic epoch-millis injected via FakeTimeProvider into the repository. */
    private val fixedTime = 1716163200000L // 2024-05-20 00:00 UTC

    private lateinit var tempDir: File
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        tempDir = createTempDir(prefix = "db-backup-repo-test-")
        dbFile = File(tempDir, "expense_tracker_db")

        every { context.filesDir } returns tempDir
        every { context.getDatabasePath(any()) } answers { File(tempDir, firstArg<String>()) }

        every { database.openHelper } returns openHelper
        every { openHelper.writableDatabase } returns supportDb
        every { supportDb.query("PRAGMA wal_checkpoint(TRUNCATE)") } answers { checkpointCursor(busyCode = 0) }

        // Privacy gate defaults — allow everything by default
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed
        coEvery { privacySettingsRepository.getSettings() } returns PrivacySettings(encryptedBackupEnabled = false)
        every { privacySettingsRepository.observeSettings() } returns kotlinx.coroutines.flow.flowOf(PrivacySettings(encryptedBackupEnabled = false))

        repository = createRepository(
            stagedVerifier = { _, _, _, _, summary -> summary },
            liveVerifier = { _, _, _, summary -> summary }
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `allowed file export reaches the file destination writer`() = runTest(testDispatcher) {
        createFullSchemaDatabase(file = dbFile)
        every { context.cacheDir } returns File(tempDir, "cache").apply { mkdirs() }

        val repo = createRepository(encryptionService = lowIterationBackupEncryptionService())
        val result = repo.createCostBackup(
            password = "allowed_file_password",
            includeReceiptImages = false,
            redacted = true,
            privacyMode = null
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.exists() == true)
        verify(exactly = 1) { mockRestoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING) }
        verify(exactly = 1) { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) }
    }

    @Test
    fun `allowed SAF export reaches the SAF destination writer`() = runTest(testDispatcher) {
        createFullSchemaDatabase(file = dbFile)
        every { context.cacheDir } returns File(tempDir, "cache").apply { mkdirs() }
        val resolver = mockk<ContentResolver>()
        val output = ByteArrayOutputStream()
        val destination = android.net.Uri.parse("content://backup/export")
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(destination, "wt") } returns output

        val repo = createRepository(
            encryptionService = lowIterationBackupEncryptionService()
        )
        val result = repo.createCostBackup(
            destination = destination,
            password = "allowed_saf_password",
            includeReceiptImages = false,
            redacted = true,
            privacyMode = null
        )

        assertTrue(result.isSuccess)
        assertTrue(output.size() > 0)
        verify { resolver.openOutputStream(destination, "wt") }
        verify(exactly = 1) { mockRestoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING) }
        verify(exactly = 1) { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) }
    }

    @Test
    fun `denied blocks both export overloads before maintenance and destination`() = runTest(testDispatcher) {
        assertBlockingPrivacyDecisionStopsExport(PrivacyDecision.Denied("ignored"))
    }

    @Test
    fun `fail closed blocks both export overloads before maintenance and destination`() = runTest(testDispatcher) {
        assertBlockingPrivacyDecisionStopsExport(PrivacyDecision.FailClosed("ignored"))
    }

    @Test
    fun `denied exports preserve an existing recovery barrier`() = runTest(testDispatcher) {
        assertBlockingPrivacyDecisionStopsExport(
            PrivacyDecision.Denied("ignored"),
            RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED
        )
    }

    @Test
    fun `fail closed exports preserve an existing recovery barrier`() = runTest(testDispatcher) {
        assertBlockingPrivacyDecisionStopsExport(
            PrivacyDecision.FailClosed("ignored"),
            RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED
        )
    }

    @Test
    fun `gate cancellation propagates from both export overloads without side effects`() = runTest(testDispatcher) {
        assertGateCancellationStopsExport(RestoreMaintenanceMode.Mode.NORMAL)
    }

    @Test
    fun `gate cancellation preserves an existing recovery barrier`() = runTest(testDispatcher) {
        assertGateCancellationStopsExport(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED)
    }

    @Test
    fun `cancellation after maintenance entry still releases the export barrier`() = runTest(testDispatcher) {
        val cancellation = IdentityCancellationException("maintenance entry cancelled")
        var mode = RestoreMaintenanceMode.Mode.NORMAL
        every { mockRestoreMaintenanceMode.currentMode() } answers { mode }
        every { mockRestoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING) } answers {
            mode = RestoreMaintenanceMode.Mode.BACKUP_EXPORTING
            throw cancellation
        }
        every { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) } answers {
            mode = RestoreMaintenanceMode.Mode.NORMAL
        }
        val cacheDir = File(tempDir, "preflight-cache").apply { mkdirs() }
        every { context.cacheDir } returns cacheDir
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver

        assertCancellationFromBothExports(repository, cancellation)

        verify(exactly = 2) { mockRestoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING) }
        verify(exactly = 2) { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) }
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, mockRestoreMaintenanceMode.currentMode())
        assertNoSnapshotOrDestinationWork(resolver, cacheDir)
    }

    @Test
    fun `privacy denial operation failure uses only the controlled reason code`() = runTest(testDispatcher) {
        val operationRun = mockk<OperationRunHandle>(relaxed = true)
        val operationRecorder = mockk<OperationRunRecorder>(relaxed = true)
        coEvery { operationRecorder.start(any(), any(), any()) } returns operationRun
        coEvery { privacyGate.check(PrivacyCapability.ENCRYPTED_BACKUP, any()) } returns PrivacyDecision.Denied("secret reason")
        val repo = createRepository(operationRunRecorder = operationRecorder)

        val result = repo.createCostBackup(
            password = "password",
            includeReceiptImages = false,
            redacted = true,
            privacyMode = null
        )

        assertTrue(result.isFailure)
        val reason = slot<String>()
        coVerify(exactly = 1) { operationRun.failedFinal(capture(reason)) }
        assertEquals(DiagnosticReasonCode.PRIVACY_DENIED.name, reason.captured)
        val denial = result.exceptionOrNull() as PrivacyDeniedException
        assertEquals(PrivacyCapability.ENCRYPTED_BACKUP, denial.capability)
        assertEquals(PrivacyGateReasonCodes.ENCRYPTED_BACKUP_DISABLED, denial.reasonCode)
        assertEquals(denial.reasonCode, denial.message)
        assertFalse(denial.toString().contains("secret reason"))
    }

    @Test
    fun `privacy fail closed operation failure uses only the controlled reason code`() = runTest(testDispatcher) {
        val operationRun = mockk<OperationRunHandle>(relaxed = true)
        val operationRecorder = mockk<OperationRunRecorder>(relaxed = true)
        coEvery { operationRecorder.start(any(), any(), any()) } returns operationRun
        coEvery { privacyGate.check(PrivacyCapability.ENCRYPTED_BACKUP, any()) } returns PrivacyDecision.FailClosed("secret reason")
        val repo = createRepository(operationRunRecorder = operationRecorder)

        val result = repo.createCostBackup(
            password = "password",
            includeReceiptImages = false,
            redacted = true,
            privacyMode = null
        )

        assertTrue(result.isFailure)
        val reason = slot<String>()
        coVerify(exactly = 1) { operationRun.failedFinal(capture(reason)) }
        assertEquals(DiagnosticReasonCode.PRIVACY_FAIL_CLOSED.name, reason.captured)
        val denial = result.exceptionOrNull() as PrivacyDeniedException
        assertEquals(PrivacyCapability.ENCRYPTED_BACKUP, denial.capability)
        assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, denial.reasonCode)
        assertEquals(denial.reasonCode, denial.message)
        assertFalse(denial.toString().contains("secret reason"))
    }

    @Test
    fun `audit failure returns failure for both export overloads without touching recovery`() = runTest(testDispatcher) {
        val failure = IllegalStateException("private audit failure")
        val auditLogger = object : PrivacyAuditLogger {
            override suspend fun logDecision(
                capability: PrivacyCapability,
                decision: PrivacyDecision,
                context: Map<String, String>
            ) {
                throw failure
            }
        }
        val operationRun = mockk<OperationRunHandle>(relaxed = true)
        val operationRecorder = mockk<OperationRunRecorder>()
        coEvery { operationRecorder.start(any(), any(), any()) } returns operationRun
        val repo = createRepository(
            operationRunRecorder = operationRecorder,
            gate = CompositePrivacyGate(listOf(privacyGate), auditLogger)
        )
        val recoveryMode = RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED
        every { mockRestoreMaintenanceMode.currentMode() } returns recoveryMode
        val cacheDir = File(tempDir, "preflight-cache").apply { mkdirs() }
        every { context.cacheDir } returns cacheDir
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver

        val fileResult = repo.createCostBackup(
            password = "password", includeReceiptImages = false, redacted = true, privacyMode = null
        )
        val safResult = repo.createCostBackup(
            destination = android.net.Uri.parse("content://backup/audit-failure"),
            password = "password", includeReceiptImages = false, redacted = true, privacyMode = null
        )

        // R26 (owner-approved): the composite gate no longer propagates the audit exception; it
        // fails closed; the repository surfaces any fail-closed gate decision as the generic bounded
        // PRIVACY_GATE_FAILURE denial (DatabaseBackupRepositoryImpl :419) and never leaks the audit message.
        for (result in listOf(fileResult, safResult)) {
            val denial = result.exceptionOrNull() as PrivacyDeniedException
            assertEquals(PrivacyCapability.ENCRYPTED_BACKUP, denial.capability)
            assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, denial.reasonCode)
            assertFalse(denial.toString().contains(failure.message!!))
        }
        coVerify(exactly = 2) { operationRun.failedFinal(DiagnosticReasonCode.PRIVACY_FAIL_CLOSED.name, null) }
        coVerify(exactly = 2) { operationRun.failedFinal(any(), any()) }
        coVerify(exactly = 0) { operationRun.success() }
        assertEquals(recoveryMode, mockRestoreMaintenanceMode.currentMode())
        assertNoExportSideEffects(resolver, cacheDir)
    }

    @Test
    fun `operation recorder start failure does not break the export and no locking mode pragma is issued`() = runTest(testDispatcher) {
        createFullSchemaDatabase(file = dbFile)
        every { context.cacheDir } returns File(tempDir, "cache").apply { mkdirs() }
        val operationRecorder = mockk<OperationRunRecorder>()
        coEvery { operationRecorder.start(any(), any(), any()) } throws
            IllegalStateException("database is locked /data/secret-path")

        val repo = createRepository(
            encryptionService = lowIterationBackupEncryptionService(),
            operationRunRecorder = operationRecorder
        )
        val result = repo.createCostBackup(
            password = "recorder_failure_password",
            includeReceiptImages = false,
            redacted = true,
            privacyMode = null
        )

        assertTrue(
            "recorder failure must fall back to the no-op handle, got ${result.exceptionOrNull()}",
            result.isSuccess
        )
        coVerify(exactly = 1) { operationRecorder.start(any(), any(), any()) }
        // R26: connection-level locking-mode changes blocked later writes (SQLITE_BUSY).
        // Any spelling, via query or execSQL.
        verify(exactly = 0) { supportDb.query(match<String> { it.contains("locking_mode", ignoreCase = true) }) }
        verify(exactly = 0) { supportDb.execSQL(match<String> { it.contains("locking_mode", ignoreCase = true) }) }
    }

    @Test
    fun `operation recorder start cancellation propagates from the export`() = runTest(testDispatcher) {
        val cancellation = IdentityCancellationException("recorder start cancelled")
        val operationRecorder = mockk<OperationRunRecorder>()
        coEvery { operationRecorder.start(any(), any(), any()) } throws cancellation
        every { context.cacheDir } returns File(tempDir, "cache").apply { mkdirs() }

        val repo = createRepository(operationRunRecorder = operationRecorder)

        assertCancellationFromBothExports(repo, cancellation)
    }

    @Test
    fun `failing wal checkpoint returns failure without creating a snapshot`() = runTest(testDispatcher) {
        createFullSchemaDatabase(file = dbFile)
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        every { context.cacheDir } returns cacheDir
        // Checkpoint stays busy on every attempt.
        every { supportDb.query("PRAGMA wal_checkpoint(TRUNCATE)") } answers { checkpointCursor(busyCode = 1) }

        val repo = createRepository(encryptionService = lowIterationBackupEncryptionService())
        val result = repo.createCostBackup(
            password = "checkpoint_failure_password",
            includeReceiptImages = false,
            redacted = true,
            privacyMode = null
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("busy", ignoreCase = true) == true)
        assertTrue(
            "no snapshot file may remain after a failed checkpoint",
            cacheDir.listFiles().orEmpty().none { it.name.startsWith("costbackup_snapshot_") }
        )
    }

    @Test
    fun `successful export finalizes the run only after maintenance exit`() = runTest(testDispatcher) {
        createFullSchemaDatabase(file = dbFile)
        every { context.cacheDir } returns File(tempDir, "cache").apply { mkdirs() }
        val callLog = mutableListOf<String>()
        val operationRun = mockk<OperationRunHandle>(relaxed = true)
        coEvery { operationRun.success() } coAnswers { callLog.add("success"); Unit }
        val operationRecorder = mockk<OperationRunRecorder>(relaxed = true)
        coEvery { operationRecorder.start(any(), any(), any()) } returns operationRun
        every { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) } answers { callLog.add("exit"); Unit }

        val repo = createRepository(
            encryptionService = lowIterationBackupEncryptionService(),
            operationRunRecorder = operationRecorder
        )
        val result = repo.createCostBackup(
            password = "deferred_success_password",
            includeReceiptImages = false,
            redacted = true,
            privacyMode = null
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("exit", "success"), callLog)
        coVerify(exactly = 1) { operationRun.success() }
        coVerify(exactly = 0) { operationRun.failedFinal(any(), any()) }
    }

    @Test
    fun `in window failure finalizes the run as failed only after maintenance exit`() = runTest(testDispatcher) {
        createFullSchemaDatabase(file = dbFile)
        every { context.cacheDir } returns File(tempDir, "cache").apply { mkdirs() }
        every { supportDb.query("PRAGMA wal_checkpoint(TRUNCATE)") } answers { checkpointCursor(busyCode = 1) }
        val callLog = mutableListOf<String>()
        val operationRun = mockk<OperationRunHandle>(relaxed = true)
        coEvery { operationRun.failedFinal(any(), any()) } coAnswers { callLog.add("failedFinal"); Unit }
        val operationRecorder = mockk<OperationRunRecorder>(relaxed = true)
        coEvery { operationRecorder.start(any(), any(), any()) } returns operationRun
        every { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) } answers { callLog.add("exit"); Unit }

        val repo = createRepository(
            encryptionService = lowIterationBackupEncryptionService(),
            operationRunRecorder = operationRecorder
        )
        val result = repo.createCostBackup(
            password = "deferred_failure_password",
            includeReceiptImages = false,
            redacted = true,
            privacyMode = null
        )

        assertTrue(result.isFailure)
        assertEquals(listOf("exit", "failedFinal"), callLog)
        coVerify(exactly = 1) { operationRun.failedFinal("WAL checkpoint failed", any()) }
        coVerify(exactly = 0) { operationRun.success() }
    }

    // G3-S2: the privacy audit logger honours the write barrier, so the debug raw export must run
    // both privacy checks before the BACKUP_EXPORTING window is entered.
    @Test
    fun `exportDatabase runs both privacy checks before entering maintenance`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertRawExportDeniedInRelease()
            return@runTest
        }
        createFullSchemaDatabase(file = dbFile)

        repository.exportDatabase()

        io.mockk.coVerifyOrder {
            privacyGate.check(PrivacyCapability.RAW_DATABASE_EXPORT, any())
            privacyGate.check(PrivacyCapability.RAWBACKUP_EXPORT, any())
            mockRestoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING)
        }
    }

    @Test
    fun `exportDatabase denied privacy check never enters maintenance`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertRawExportDeniedInRelease()
            return@runTest
        }
        createFullSchemaDatabase(file = dbFile)
        coEvery { privacyGate.check(PrivacyCapability.RAWBACKUP_EXPORT, any()) } returns PrivacyDecision.Denied("ignored")

        val result = repository.exportDatabase()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PrivacyDeniedException)
        verify(exactly = 0) { mockRestoreMaintenanceMode.enter(any()) }
        verify(exactly = 0) { mockRestoreMaintenanceMode.exit(any()) }
    }

    // G2b: terminal run calls must be replayed after the maintenance exit, because the write
    // barrier blocks Room run finalization while the window is active.
    @Test
    fun `restore pre-swap abort finalizes the run only after maintenance exit`() = runTest(testDispatcher) {
        stubCacheDir()
        val callLog = mutableListOf<String>()
        val operationRun = mockk<OperationRunHandle>(relaxed = true)
        coEvery { operationRun.failedFinal(any(), any()) } coAnswers { callLog.add("failedFinal"); Unit }
        val operationRecorder = mockk<OperationRunRecorder>(relaxed = true)
        coEvery { operationRecorder.start(any(), any(), any()) } returns operationRun
        every { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) } answers { callLog.add("exit"); Unit }
        val repo = createRepository(journal = newRealJournal(), operationRunRecorder = operationRecorder)
        val garbageDb = File(tempDir, "garbage_extracted.sqlite").apply { writeText("not a database") }
        stubExtractionResult(
            tier1Manifest(BackupVerifier.requiredManifestTables(1).associateWith { 1 }),
            dbFile = garbageDb
        )

        try {
            val result = repo.restoreCostBackup(File(tempDir, "bundle.costbackup"), "pw")

            assertTrue(result.isFailure)
            assertEquals(listOf("exit", "failedFinal"), callLog)
            coVerify(exactly = 1) { operationRun.failedFinal("Staged verification failed", any()) }
        } finally {
            unmockkObject(CostbackupBundle)
        }
    }

    @Test
    fun `legacy import abort finalizes the run only after maintenance exit`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        val callLog = mutableListOf<String>()
        val operationRun = mockk<OperationRunHandle>(relaxed = true)
        coEvery { operationRun.failedFinal(any(), any()) } coAnswers { callLog.add("failedFinal"); Unit }
        val operationRecorder = mockk<OperationRunRecorder>(relaxed = true)
        coEvery { operationRecorder.start(any(), any(), any()) } returns operationRun
        every { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) } answers { callLog.add("exit"); Unit }
        val repo = createRepository(operationRunRecorder = operationRecorder)
        val emptySource = File(tempDir, "empty_source.db").apply { writeBytes(ByteArray(0)) }

        val result = repo.importDatabase(emptySource)

        assertTrue(result.isFailure)
        assertEquals(listOf("exit", "failedFinal"), callLog)
        coVerify(exactly = 1) { operationRun.failedFinal("Source database file is empty", any()) }
    }

    @Test
    fun `reset abort finalizes the run only after maintenance exit`() = runTest(testDispatcher) {
        // No live database file exists, so the pre-destructive safety backup fails and the reset aborts.
        val callLog = mutableListOf<String>()
        val operationRun = mockk<OperationRunHandle>(relaxed = true)
        coEvery { operationRun.failedFinal(any(), any()) } coAnswers { callLog.add("failedFinal"); Unit }
        val operationRecorder = mockk<OperationRunRecorder>(relaxed = true)
        coEvery { operationRecorder.start(any(), any(), any()) } returns operationRun
        every { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) } answers { callLog.add("exit"); Unit }
        val repo = createRepository(journal = newRealJournal(), operationRunRecorder = operationRecorder)

        val result = repo.resetDatabase()

        assertTrue(result.isFailure)
        assertEquals(listOf("exit", "failedFinal"), callLog)
        coVerify(exactly = 1) { operationRun.failedFinal(DiagnosticReasonCode.UNKNOWN_ERROR.name, any()) }
    }

    private fun assertNoExportSideEffects(resolver: ContentResolver, cacheDir: File) {
        verify(exactly = 0) { mockRestoreMaintenanceMode.enter(any()) }
        verify(exactly = 0) { mockRestoreMaintenanceMode.exit(any()) }
        assertNoSnapshotOrDestinationWork(resolver, cacheDir)
    }

    private fun assertNoSnapshotOrDestinationWork(resolver: ContentResolver, cacheDir: File) {
        verify(exactly = 0) { supportDb.query("PRAGMA wal_checkpoint(TRUNCATE)") }
        verify(exactly = 0) { context.getDatabasePath(any()) }
        verify(exactly = 0) { context.cacheDir }
        verify(exactly = 0) { exportAnonymizer.sanitizeExport(any()) }
        verify(exactly = 0) { backupEncryptionService.encrypt(any<ByteArray>(), any()) }
        verify(exactly = 0) { backupEncryptionService.encrypt(any<File>(), any(), any()) }
        verify(exactly = 0) { resolver.openOutputStream(any(), any()) }
        assertTrue(cacheDir.isDirectory)
        assertTrue(cacheDir.listFiles()!!.isEmpty())
        assertFalse(File(tempDir, "costbackups").exists())
    }

    private suspend fun assertGateCancellationStopsExport(initialMode: RestoreMaintenanceMode.Mode) {
        val cancellation = IdentityCancellationException("caller cancelled")
        coEvery { privacyGate.check(PrivacyCapability.ENCRYPTED_BACKUP, any()) } throws cancellation
        val auditLogger = mockk<PrivacyAuditLogger>(relaxed = true)
        val operationRun = mockk<OperationRunHandle>(relaxed = true)
        val operationRecorder = mockk<OperationRunRecorder>()
        coEvery { operationRecorder.start(any(), any(), any()) } returns operationRun
        val repo = createRepository(
            operationRunRecorder = operationRecorder,
            gate = CompositePrivacyGate(listOf(privacyGate), auditLogger)
        )
        every { mockRestoreMaintenanceMode.currentMode() } returns initialMode
        val cacheDir = File(tempDir, "preflight-cache").apply { mkdirs() }
        every { context.cacheDir } returns cacheDir
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver

        assertCancellationFromBothExports(repo, cancellation)

        assertEquals(initialMode, mockRestoreMaintenanceMode.currentMode())
        coVerify(exactly = 0) {
            auditLogger.logDecision(any(), any(), any<Map<String, String>>())
        }
        coVerify(exactly = 0) { operationRun.failedFinal(any(), any()) }
        coVerify(exactly = 0) { operationRun.success() }
        assertNoExportSideEffects(resolver, cacheDir)
    }

    // Coroutine debug stacktrace recovery may copy exceptions at withContext.
    // Opt this sentinel out so assertSame detects application wrapping, not debug copying.
    private class IdentityCancellationException(message: String) :
        CancellationException(message), CopyableThrowable<IdentityCancellationException> {
        override fun createCopy(): IdentityCancellationException? = null
    }

    private suspend fun assertCancellationFromBothExports(
        repo: DatabaseBackupRepositoryImpl,
        cancellation: CancellationException
    ) {
        for (useSaf in listOf(false, true)) {
            try {
                if (useSaf) {
                    repo.createCostBackup(
                        destination = android.net.Uri.parse("content://backup/cancelled"),
                        password = "password", includeReceiptImages = false, redacted = true, privacyMode = null
                    )
                } else {
                    repo.createCostBackup(
                        password = "password", includeReceiptImages = false, redacted = true, privacyMode = null
                    )
                }
                throw AssertionError("cancellation must propagate")
            } catch (thrown: CancellationException) {
                assertSame(cancellation, thrown)
            }
        }
    }

    private suspend fun assertBlockingPrivacyDecisionStopsExport(
        decision: PrivacyDecision,
        initialMode: RestoreMaintenanceMode.Mode = RestoreMaintenanceMode.Mode.NORMAL
    ) {
        coEvery { privacyGate.check(PrivacyCapability.ENCRYPTED_BACKUP, any()) } returns decision
        every { mockRestoreMaintenanceMode.currentMode() } returns initialMode
        val cacheDir = File(tempDir, "preflight-cache").apply { mkdirs() }
        every { context.cacheDir } returns cacheDir
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        val destination = android.net.Uri.parse("content://backup/blocked")

        val fileResult = repository.createCostBackup(
            password = "password",
            includeReceiptImages = false,
            redacted = true,
            privacyMode = null
        )
        val safResult = repository.createCostBackup(
            destination = destination,
            password = "password",
            includeReceiptImages = false,
            redacted = true,
            privacyMode = null
        )

        assertTrue(fileResult.isFailure)
        assertTrue(safResult.isFailure)
        assertTrue(fileResult.exceptionOrNull() is PrivacyDeniedException)
        assertTrue(safResult.exceptionOrNull() is PrivacyDeniedException)
        assertEquals(PrivacyCapability.ENCRYPTED_BACKUP, (fileResult.exceptionOrNull() as PrivacyDeniedException).capability)
        assertEquals(PrivacyCapability.ENCRYPTED_BACKUP, (safResult.exceptionOrNull() as PrivacyDeniedException).capability)
        assertEquals(initialMode, mockRestoreMaintenanceMode.currentMode())
        assertNoExportSideEffects(resolver, cacheDir)
    }

    @Test
    fun `backup creates file successfully`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertRawExportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 2,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )

        val result = repository.exportDatabase()

        assertTrue(result.isSuccess)
        val backupFile = result.getOrNull()
        assertTrue(backupFile != null)
        assertTrue(backupFile!!.exists())
        assertTrue(backupFile.length() > 0L)
        verify(atLeast = 1) { supportDb.query("PRAGMA wal_checkpoint(TRUNCATE)") }
    }

    @Test
    fun `createCostBackup checks barrier doubleCheck before snapshot`() = runTest(testDispatcher) {
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 2,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )

        // createCostBackup will likely fail later due to missing tables in BackupVerifier
        // (which expects every tracked table), but the write barrier double-check happens early in
        // the flow — right after WAL checkpoint, before snapshot creation.
        runCatching {
            repository.createCostBackup(
                password = "test_password",
                includeReceiptImages = false,
                redacted = true,
                privacyMode = null
            )
        }

        // P7-P1-03: Verify write barrier is checked before snapshot
        verify { mockRestoreMaintenanceMode.isWritesAllowed() }
    }

    @Test
    fun `createCostBackup aborts when write barrier is breached during snapshot`() = runTest(testDispatcher) {
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 2,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )

        // Simulate barrier defeated — writes are allowed during snapshot
        every { mockRestoreMaintenanceMode.isWritesAllowed() } returns true

        // The barrier double-check's require() is caught by the export flow and
        // converted into a failed Result — the call does not throw.
        val result = repository.createCostBackup(
            password = "test_password",
            includeReceiptImages = false,
            redacted = true,
            privacyMode = null
        )

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("write barrier was exited") == true
        )
    }

    @Test
    fun `createCostBackup proceeds when write barrier protects snapshot`() = runTest(testDispatcher) {
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 2,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )

        // Normal case — writes are not allowed, barrier is intact
        every { mockRestoreMaintenanceMode.isWritesAllowed() } returns false

        val result = runCatching {
            repository.createCostBackup(
                password = "test_password",
                includeReceiptImages = false,
                redacted = true,
                privacyMode = null
            )
        }

        // The backup may fail later at verification (every tracked table expected), but it should
        // not fail at the write barrier check — i.e. barrier check passes.
        if (result.isFailure) {
            assertTrue(
                result.exceptionOrNull()?.message?.contains("write barrier was exited") != true
            )
        }
    }

    @Test
    fun `createCostBackup full export does not scrub the snapshot`() = runTest(testDispatcher) {
        createFullSchemaDatabase(file = dbFile)
        every { context.cacheDir } returns File(tempDir, "cache").apply { mkdirs() }

        val repo = createRepository(encryptionService = lowIterationBackupEncryptionService())
        val result = repo.createCostBackup(
            password = "full_export_password",
            includeReceiptImages = false,
            redacted = false,
            privacyMode = null
        )

        // Success proves the export ran past the snapshot step where the scrub gate lives.
        assertTrue(result.isSuccess)
        verify(exactly = 0) { exportAnonymizer.sanitizeExport(any()) }
    }

    @Test
    fun `createCostBackup redacted export scrubs the snapshot exactly once`() = runTest(testDispatcher) {
        createFullSchemaDatabase(file = dbFile)
        every { context.cacheDir } returns File(tempDir, "cache").apply { mkdirs() }

        val repo = createRepository(encryptionService = lowIterationBackupEncryptionService())
        val result = repo.createCostBackup(
            password = "redacted_export_password",
            includeReceiptImages = false,
            redacted = true,
            privacyMode = null
        )

        assertTrue(result.isSuccess)
        verify(exactly = 1) { exportAnonymizer.sanitizeExport(any()) }
    }

    @Test
    fun `export sweeps only stale temp_export regular files with real files`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertRawExportDeniedInRelease()
            return@runTest
        }
        createFullSchemaDatabase(file = dbFile)
        val exportDir = File(tempDir, "exports").apply { mkdirs() }
        val oneHourMs = 60L * 60L * 1000L
        // Whole-second timestamps; "now" is the repository's injected FakeTimeProvider(fixedTime).
        fun seed(dir: File, name: String, lastModifiedMs: Long): File {
            val f = File(dir, name)
            f.writeText("x")
            assertTrue(f.setLastModified(lastModifiedMs))
            return f
        }
        val stale = seed(exportDir, "temp_export_x.db", fixedTime - 2 * oneHourMs)
        val atBoundary = seed(exportDir, "temp_export_boundary.db", fixedTime - oneHourMs)
        val justUnder = seed(exportDir, "temp_export_under.db", fixedTime - oneHourMs + 1_000L)
        val fresh = seed(exportDir, "temp_export_fresh.db", fixedTime - 60_000L)
        val oldBackupDb = seed(exportDir, "expense_tracker_backup_1.db", fixedTime - 2 * oneHourMs)
        val oldBackupEnc = seed(exportDir, "expense_tracker_backup_1.enc", fixedTime - 2 * oneHourMs)
        val tempNamedDir = File(exportDir, "temp_export_d").apply { mkdirs() }
        val innerFile = seed(tempNamedDir, "temp_export_inner.db", fixedTime - 2 * oneHourMs)
        tempNamedDir.setLastModified(fixedTime - 2 * oneHourMs)

        val result = repository.exportDatabase()

        assertTrue("sweep must not break the export: ${result.exceptionOrNull()}", result.isSuccess)
        assertFalse("stale temp export is deleted", stale.exists())
        assertFalse("exactly one hour old is deleted (>= boundary)", atBoundary.exists())
        assertTrue("one second under the boundary is kept", justUnder.exists())
        assertTrue("fresh temp export is kept", fresh.exists())
        assertTrue("old backup .db never matches", oldBackupDb.exists())
        assertTrue("old backup .enc never matches", oldBackupEnc.exists())
        assertTrue("directory named with the prefix is kept", tempNamedDir.isDirectory)
        assertTrue("no recursion into a directory", innerFile.exists())
    }

    @Test
    fun `restore from backup works`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )
        val sourceBackup = File(tempDir, "source_backup.db")
        createSqliteDatabase(
            file = sourceBackup,
            expenseCount = 3,
            categoryCount = 2,
            merchantCount = 4,
            pendingCount = 1,
            budgetCount = 2
        )

        val result = repository.importDatabase(sourceBackup)

        assertTrue(result.isSuccess)
        assertEquals(3, countRows(dbFile, "expenses"))
        assertEquals(2, countRows(dbFile, "categories"))
        assertEquals(4, countRows(dbFile, "merchant_categories"))
    }

    @Test
    fun `journal created before maintenance mode entry during import`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )
        val sourceBackup = File(tempDir, "source_backup.db")
        createSqliteDatabase(
            file = sourceBackup,
            expenseCount = 3,
            categoryCount = 2,
            merchantCount = 4,
            pendingCount = 1,
            budgetCount = 2
        )

        repository.importDatabase(sourceBackup)

        // P7-P0-01: Verify journal is created before maintenance mode enters RESTORE_PREPARING
        verifyOrder {
            mockRestoreJournal.beginJournal(any(), any(), any())
            mockRestoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        }
    }

    @Test
    fun `rollback safety if restore fails original db preserved`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )
        val sourceBackup = File(tempDir, "source_backup_for_failure.db")
        createSqliteDatabase(
            file = sourceBackup,
            expenseCount = 5,
            categoryCount = 2,
            merchantCount = 2,
            pendingCount = 1,
            budgetCount = 1
        )

        val failingRepository = createRepository(
            stagedVerifier = { _, _, _, _, summary -> summary },
            liveVerifier = { _, _, _, _ -> throw RuntimeException("forced reopen failure") }
        )

        val result = failingRepository.importDatabase(sourceBackup)

        assertTrue(result.isFailure)
        assertEquals(1, countRows(dbFile, "expenses"))
        assertEquals(1, countRows(dbFile, "categories"))
        // Post-swap failures roll back from the safety backup and surface the
        // controlled reason code IMPORT_FAILED_ROLLED_BACK, not prose.
        assertTrue(
            result.exceptionOrNull()?.message?.contains("IMPORT_FAILED_ROLLED_BACK") == true
        )
    }

    @Test
    fun `wal checkpoint helper works`() = runTest(testDispatcher) {
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )

        val result = repository.createSafetyBackup()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.exists() == true)
        verify(atLeast = 1) { supportDb.query("PRAGMA wal_checkpoint(TRUNCATE)") }
    }

    @Test
    fun `import rejects schema86 stale budgets backup as legacy and leaves live db unchanged`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )
        val sourceBackup = File(tempDir, "source_schema86_stale_budgets.db")
        createSchema86DatabaseWithStaleBudgets(file = sourceBackup, budgetCount = 2)

        assertImportRejectedBeforeSwap(sourceBackup, BackupSchemaUnsupportedException.LEGACY)
    }

    @Test
    fun `import allows same lineage backup missing later non core tables`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )

        val sourceBackup = File(tempDir, "source_missing_optional_tables.db")
        createLegacyCompatibleDatabaseMissingOptionalTables(
            file = sourceBackup,
            schemaVersion = DatabaseSchemaPolicy.MIGRATION_BASELINE,
            expenseCount = 2,
            categoryCount = 1
        )

        val result = repository.importDatabase(sourceBackup)

        assertTrue(result.isSuccess)
        assertEquals(2, countRows(dbFile, "expenses"))
        assertEquals(1, countRows(dbFile, "categories"))
    }

    @Test
    fun `import does not reject backup with no expenses or categories when other tracked data exists`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )

        val sourceBackup = File(tempDir, "source_non_expense_data_only.db")
        createSqliteDatabase(
            file = sourceBackup,
            expenseCount = 0,
            categoryCount = 0,
            merchantCount = 2,
            pendingCount = 1,
            budgetCount = 1
        )

        val result = repository.importDatabase(sourceBackup)

        assertTrue(result.isSuccess)
        assertEquals(0, countRows(dbFile, "expenses"))
        assertEquals(0, countRows(dbFile, "categories"))
        assertEquals(2, countRows(dbFile, "merchant_categories"))
        assertEquals(1, countRows(dbFile, "pending_reviews"))
        assertEquals(1, countRows(dbFile, "budgets"))
    }

    @Test
    fun `import rejects schema86 backups with non repairable budgets mismatch`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )

        val sourceBackup = File(tempDir, "source_schema86_invalid_budgets.db")
        createSchema86DatabaseWithInvalidBudgetsColumns(sourceBackup)

        // R25-C: v86 is below the supported floor, so it is rejected early as legacy.
        assertImportRejectedBeforeSwap(sourceBackup, BackupSchemaUnsupportedException.LEGACY)
        assertEquals(1, countRows(dbFile, "expenses"))
    }

    @Test
    fun `import rejects schema86 backups with valid defaults but invalid budgets index uniqueness`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )

        val sourceBackup = File(tempDir, "source_schema86_invalid_budgets_index_uniqueness.db")
        createSchema86DatabaseWithBadBudgetsIndexUniqueness(sourceBackup)

        // R25-C: v86 is below the supported floor, so it is rejected early as legacy;
        // the internal budgets-index detail is never part of the public error.
        assertImportRejectedBeforeSwap(sourceBackup, BackupSchemaUnsupportedException.LEGACY)
        assertEquals(1, countRows(dbFile, "expenses"))
    }

    @Test
    fun `import rejects legacy v144 backup before any swap with bounded code`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(file = dbFile, expenseCount = 1, categoryCount = 1, merchantCount = 1, pendingCount = 1, budgetCount = 1)
        val sourceBackup = File(tempDir, "source_v144.db")
        createSqliteDatabase(
            file = sourceBackup,
            schemaVersion = DatabaseSchemaPolicy.MIGRATION_BASELINE - 1,
            expenseCount = 3, categoryCount = 1, merchantCount = 1, pendingCount = 0, budgetCount = 0
        )

        assertImportRejectedBeforeSwap(sourceBackup, BackupSchemaUnsupportedException.LEGACY)
    }

    @Test
    fun `import rejects newer than current schema backup before any swap with bounded code`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(file = dbFile, expenseCount = 1, categoryCount = 1, merchantCount = 1, pendingCount = 1, budgetCount = 1)
        val sourceBackup = File(tempDir, "source_newer.db")
        createSqliteDatabase(
            file = sourceBackup,
            schemaVersion = APP_DATABASE_SCHEMA_VERSION + 1,
            expenseCount = 3, categoryCount = 1, merchantCount = 1, pendingCount = 0, budgetCount = 0
        )

        assertImportRejectedBeforeSwap(sourceBackup, BackupSchemaUnsupportedException.NEWER)
    }

    @Test
    fun `import accepts backups at the supported floor and at the current schema`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        for (version in listOf(DatabaseSchemaPolicy.MIGRATION_BASELINE, APP_DATABASE_SCHEMA_VERSION)) {
            createSqliteDatabase(file = dbFile, expenseCount = 1, categoryCount = 1, merchantCount = 1, pendingCount = 1, budgetCount = 1)
            val sourceBackup = File(tempDir, "source_accepted_$version.db")
            createSqliteDatabase(
                file = sourceBackup,
                schemaVersion = version,
                expenseCount = 4, categoryCount = 2, merchantCount = 1, pendingCount = 0, budgetCount = 0
            )

            // Fresh repository per version: a successful import hot-swaps the repository's
            // live Room instance onto the (fake-schema) file, so reusing it would fail the
            // next import's safety backup for reasons unrelated to the schema floor.
            val result = createRepository().importDatabase(sourceBackup)

            assertTrue("schema $version must be accepted", result.isSuccess)
            assertEquals(4, countRows(dbFile, "expenses"))
            dbFile.delete()
        }
    }

    @Test
    fun `costbackup manifest outside supported schema range aborts before staging and cleans up`() = runTest(testDispatcher) {
        for ((version, expectedCode) in listOf(
            DatabaseSchemaPolicy.MIGRATION_BASELINE - 1 to BackupSchemaUnsupportedException.LEGACY,
            APP_DATABASE_SCHEMA_VERSION + 1 to BackupSchemaUnsupportedException.NEWER
        )) {
            stubCacheDir()
            val callLog = mutableListOf<String>()
            val operationRun = mockk<OperationRunHandle>(relaxed = true)
            coEvery { operationRun.failedFinal(any(), any()) } coAnswers { callLog.add("failedFinal"); Unit }
            val operationRecorder = mockk<OperationRunRecorder>(relaxed = true)
            coEvery { operationRecorder.start(any(), any(), any()) } returns operationRun
            every { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) } answers { callLog.add("exit"); Unit }
            val journal = newRealJournal()
            val repo = createRepository(journal = journal, operationRunRecorder = operationRecorder)
            val counts = BackupVerifier.requiredManifestTables(1).associateWith { 1 }
            stubExtractionResult(
                CostbackupBundle.BackupManifest(databaseVersion = version, createdAt = fixedTime, tableCounts = counts),
                dbFile = null
            )

            try {
                val result = repo.restoreCostBackup(File(tempDir, "bundle.costbackup"), "pw")

                val error = result.exceptionOrNull()
                assertTrue("v$version expected typed rejection", error is BackupSchemaUnsupportedException)
                assertEquals(expectedCode, (error as BackupSchemaUnsupportedException).code)
                // Mode exits without restart; run is finalized only after the exit.
                verify { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) }
                assertEquals(listOf("exit", "failedFinal"), callLog)
                coVerify(exactly = 1) { operationRun.failedFinal(expectedCode, any()) }
                assertEquals(expectedCode, journal.readFailureJournal()?.error)
                verify(exactly = 0) { database.close() }
                assertTrue(stagedResidueFiles().isEmpty())
                assertTrue("extraction dir must be deleted", extractionResidueFiles().isEmpty())
            } finally {
                unmockkObject(CostbackupBundle)
            }
        }
    }

    private suspend fun assertImportRejectedBeforeSwap(source: File, expectedCode: String) {
        val liveHashBefore = sha256Hex(dbFile)
        val result = repository.importDatabase(source)

        val error = result.exceptionOrNull()
        assertTrue("expected typed rejection, got ${error?.javaClass?.simpleName}", error is BackupSchemaUnsupportedException)
        assertEquals(expectedCode, (error as BackupSchemaUnsupportedException).code)
        assertEquals(liveHashBefore, sha256Hex(dbFile))
        verify(exactly = 0) { database.close() }
        assertTrue(stagedResidueFiles().isEmpty())
    }

    private fun sha256Hex(file: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `temp migration open failure leaves live db untouched`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 2,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )
        val sourceBackup = File(tempDir, "source_stage_failure.db")
        createSqliteDatabase(
            file = sourceBackup,
            schemaVersion = DatabaseSchemaPolicy.MIGRATION_BASELINE,
            expenseCount = 5,
            categoryCount = 2,
            merchantCount = 3,
            pendingCount = 1,
            budgetCount = 2
        )

        val failingRepository = createRepository(
            stagedVerifier = { _, _, _, _, _ ->
                throw IllegalStateException("forced staged Room open failure")
            }
        )

        val result = failingRepository.importDatabase(sourceBackup)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("forced staged Room open failure") == true)
        assertEquals(2, countRows(dbFile, "expenses"))
        assertEquals(1, countRows(dbFile, "categories"))
        assertFalse(File(tempDir, "safety_backups").exists())
    }

    @Test
    fun `successful staged import swaps only after verification`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )
        val sourceBackup = File(tempDir, "source_stage_success.db")
        createSqliteDatabase(
            file = sourceBackup,
            schemaVersion = DatabaseSchemaPolicy.MIGRATION_BASELINE,
            expenseCount = 6,
            categoryCount = 3,
            merchantCount = 2,
            pendingCount = 2,
            budgetCount = 4
        )

        var liveCountSeenDuringStage: Int? = null
        val stagedRepository = createRepository(
            stagedVerifier = { _, _, stagedFile, _, summary ->
                liveCountSeenDuringStage = countRows(dbFile, "expenses")
                assertEquals(6, countRows(stagedFile, "expenses"))
                summary
            },
            liveVerifier = { _, liveFile, _, summary ->
                assertEquals(6, countRows(liveFile, "expenses"))
                summary
            }
        )

        val result = stagedRepository.importDatabase(sourceBackup)

        assertTrue(result.isSuccess)
        assertEquals(1, liveCountSeenDuringStage)
        assertEquals(6, countRows(dbFile, "expenses"))
        assertEquals(3, countRows(dbFile, "categories"))
    }

    private suspend fun importRawDbAndReadOutcome(
        liveVerifier: suspend (AppDatabase, File, Int, DatabaseImportSummary) -> DatabaseImportSummary
    ): com.yourname.expensetracker.domain.backup.SemanticVerificationOutcome {
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )
        val sourceBackup = File(tempDir, "source_no_manifest.db")
        createSqliteDatabase(
            file = sourceBackup,
            schemaVersion = DatabaseSchemaPolicy.MIGRATION_BASELINE,
            expenseCount = 3,
            categoryCount = 2,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )
        val result = createRepository(liveVerifier = liveVerifier).importDatabase(sourceBackup)
        assertTrue(result.isSuccess)
        return result.getOrThrow().semanticVerification
    }

    @Test
    fun `UF46 S4 AE12 INV11 importDatabase of a raw db is NotVerifiable NO_MANIFEST`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        // A raw .db has no manifest to compare against.
        assertEquals(
            com.yourname.expensetracker.domain.backup.SemanticVerificationOutcome.NotVerifiable(
                SemanticNotVerifiableReason.NO_MANIFEST
            ),
            importRawDbAndReadOutcome { _, _, _, summary -> summary }
        )
    }

    @Test
    fun `UF46 S4 AE12 INV11 importDatabase is never Verified even when the live verifier claims Verified`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        val outcome = importRawDbAndReadOutcome { _, _, _, summary ->
            summary.copy(
                semanticVerification = com.yourname.expensetracker.domain.backup.SemanticVerificationOutcome.Verified
            )
        }
        assertEquals(
            com.yourname.expensetracker.domain.backup.SemanticVerificationOutcome.NotVerifiable(
                SemanticNotVerifiableReason.NO_MANIFEST
            ),
            outcome
        )
    }

    @Test
    fun `verification rejects partial count loss for core tables`() {
        val sourceSummary = DatabaseImportSummary(
            transactionCount = 128,
            categoryCount = 19,
            merchantCount = 41,
            pendingReviewCount = 7,
            budgetCount = 5
        )

        val error = runCatching {
            DatabaseBackupRepositoryImpl.verifySummaryPreservedForVerification(
                sourceSummary = sourceSummary,
                actualSummary = sourceSummary.copy(transactionCount = 127),
                sourceSchemaVersion = 37
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error?.message?.contains("Verified import changed expenses from 128 to 127") == true)
    }

    @Test
    fun `schema37 fixture import preserves exact core counts through staged pipeline seam`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        val fixture = Schema37FixtureCounts(
            expenseCount = 128,
            categoryCount = 19,
            merchantCount = 41,
            pendingReviewCount = 7,
            budgetCount = 5
        )
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )
        val sourceBackup = File(tempDir, "source_schema37_fixture.db")
        createSqliteDatabase(
            file = sourceBackup,
            schemaVersion = DatabaseSchemaPolicy.MIGRATION_BASELINE,
            expenseCount = fixture.expenseCount,
            categoryCount = fixture.categoryCount,
            merchantCount = fixture.merchantCount,
            pendingCount = fixture.pendingReviewCount,
            budgetCount = fixture.budgetCount
        )

        val expectedSummary = fixture.toImportSummary()
        val seamRepository = createRepository(
            stagedVerifier = { _, _, stagedFile, sourceSchemaVersion, summary ->
                assertEquals(DatabaseSchemaPolicy.MIGRATION_BASELINE, sourceSchemaVersion)
                assertEquals(expectedSummary, summary)
                val stagedSummary = trackedSummary(stagedFile)
                assertEquals(expectedSummary, stagedSummary)
                DatabaseBackupRepositoryImpl.verifySummaryPreservedForVerification(
                    sourceSummary = summary,
                    actualSummary = stagedSummary,
                    sourceSchemaVersion = sourceSchemaVersion
                )
                stagedSummary
            },
            liveVerifier = { _, liveFile, sourceSchemaVersion, summary ->
                assertEquals(DatabaseSchemaPolicy.MIGRATION_BASELINE, sourceSchemaVersion)
                assertEquals(expectedSummary, summary)
                val liveSummary = trackedSummary(liveFile)
                assertEquals(expectedSummary, liveSummary)
                liveSummary
            }
        )

        val result = seamRepository.importDatabase(sourceBackup)

        assertTrue(result.isSuccess)
        assertEquals(expectedSummary, result.getOrNull())
        assertEquals(expectedSummary, trackedSummary(dbFile))
    }

    @Test
    fun `rollback on post swap reopen failure`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 2,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )
        val sourceBackup = File(tempDir, "source_post_swap_failure.db")
        createSqliteDatabase(
            file = sourceBackup,
            schemaVersion = DatabaseSchemaPolicy.MIGRATION_BASELINE,
            expenseCount = 7,
            categoryCount = 2,
            merchantCount = 2,
            pendingCount = 1,
            budgetCount = 1
        )

        val repositoryWithFailingReopen = createRepository(
            stagedVerifier = { _, _, stagedFile, _, summary ->
                assertEquals(7, countRows(stagedFile, "expenses"))
                summary
            },
            liveVerifier = { _, _, _, _ ->
                throw IllegalStateException("forced live reopen failure")
            }
        )

        val result = repositoryWithFailingReopen.importDatabase(sourceBackup)

        assertTrue(result.isFailure)
        assertNotNull(File(tempDir, "safety_backups").listFiles()?.firstOrNull())
        assertEquals(2, countRows(dbFile, "expenses"))
        // Post-swap failures roll back from the safety backup and surface the
        // controlled reason code IMPORT_FAILED_ROLLED_BACK, not prose.
        assertTrue(result.exceptionOrNull()?.message?.contains("IMPORT_FAILED_ROLLED_BACK") == true)
    }

    // ── RP-03B batch 3b: restoreCostBackup state machine tests ─────

    private val realAppContext: Context by lazy { ApplicationProvider.getApplicationContext() }

    private fun stubCacheDir() {
        every { context.cacheDir } answers { File(tempDir, "cache").apply { mkdirs() } }
        every { context.applicationContext } returns realAppContext
    }

    /** Fresh on-disk journal (real file-backed instance over the stubbed filesDir). */
    private fun newRealJournal(): RestoreJournal {
        listOf(
            "restore_journal.json",
            "restore_journal_last_failure.json",
            RestoreJournal.SUCCESS_JOURNAL_FILENAME
        ).forEach { File(tempDir, it).delete() }
        return RestoreJournal(context, FakeTimeProvider(fixedTime))
    }

    private fun tier1Manifest(counts: Map<String, Int>): CostbackupBundle.BackupManifest =
        CostbackupBundle.BackupManifest(
            databaseVersion = APP_DATABASE_SCHEMA_VERSION,
            createdAt = fixedTime,
            tableCounts = counts
        )

    private fun stubExtractionResult(
        manifest: CostbackupBundle.BackupManifest,
        dbFile: File?,
        withReceiptAsset: Boolean = false
    ) {
        mockkObject(CostbackupBundle)
        // The production call site passes nowEpochMs and relies on defaults for
        // encryptionService/limits — the defaults are filled before dispatch, so the
        // stub must match the FULL parameter list or the real method runs.
        every {
            CostbackupBundle.extract(any(), any(), any(), any(), any(), any())
        } answers {
            val outputDir = secondArg<File>()
            outputDir.mkdirs()
            if (withReceiptAsset) {
                // S2: real bundle layout (CostbackupBundle.create writes files/receipts/{id}_{name}).
                val receiptsDir = File(outputDir, "files/receipts")
                receiptsDir.mkdirs()
                File(receiptsDir, "5_photo.jpg").writeBytes(byteArrayOf(9, 8, 7))
            }
            Result.success(
                CostbackupBundle.ExtractionResult(
                    manifest = manifest,
                    dbFile = dbFile ?: File(outputDir, "database.sqlite"),
                    assetsDir = null,
                    checksumsVerified = true,
                    warnings = emptyList(),
                    extractedFiles = emptyMap()
                )
            )
        }
    }

    private fun stagedResidueFiles(): List<File> =
        tempDir.listFiles { f -> f.name.startsWith("expense_tracker_db_import_stage_") }?.toList() ?: emptyList()

    private fun extractionResidueFiles(): List<File> =
        File(tempDir, "cache").listFiles { f -> f.name.startsWith("costbackup_extract_") }?.toList() ?: emptyList()

    @Test
    fun `staged verification failure cleans staged trio and extraction dir and exits without restart`() = runTest(testDispatcher) {
        stubCacheDir()
        val journal = newRealJournal()
        val repo = createRepository(journal = journal)
        val garbageDb = File(tempDir, "garbage_extracted.sqlite").apply { writeText("not a database") }
        stubExtractionResult(
            tier1Manifest(BackupVerifier.requiredManifestTables(1).associateWith { 1 }),
            dbFile = garbageDb
        )

        try {
            val result = repo.restoreCostBackup(File(tempDir, "bundle.costbackup"), "pw")

            assertTrue(result.isFailure)
            assertEquals("STAGED_VERIFICATION_FAILED", result.exceptionOrNull()?.message)
            verify { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) }
            // P7-006: staged trio and extraction workspace must not leak.
            assertTrue("staged residue leaked: ${stagedResidueFiles()}", stagedResidueFiles().isEmpty())
            assertTrue("extraction residue leaked: ${extractionResidueFiles()}", extractionResidueFiles().isEmpty())
            val failureJournal = journal.readFailureJournal()
            assertEquals(
                "Pre-swap failure must leave a terminal failed journal",
                RestoreJournal.JournalState.FAILED,
                failureJournal?.state
            )
            assertEquals(
                "Persisted failure must use a controlled diagnostic reason code",
                DiagnosticReasonCode.UNKNOWN_ERROR.name,
                failureJournal?.error
            )
        } finally {
            unmockkObject(CostbackupBundle)
        }
    }

    @Test
    fun `UF46 S3 C1 semantic mismatch passes the reused STAGED_VERIFICATION_FAILED literal and persists the unchanged error`() = runTest(testDispatcher) {
        stubCacheDir()
        val journal = io.mockk.spyk(newRealJournal())
        val failReasons = mutableListOf<String>()
        every { journal.failJournal(any(), any()) } answers {
            failReasons += secondArg<String>()
            callOriginal()
        }
        val source = File(tempDir, "semantic_c1_source.sqlite")
        createFullSchemaDatabase(source)
        val section = RealSchemaFixture.capture(source)
        // E3 DEPOSIT 1000.0 -> 1001.0: no row count changes, only the aggregates can notice.
        RealSchemaFixture.mutate(source, "UPDATE expenses SET amount = 1001.0 WHERE id = 3")
        stubExtractionResult(
            CostbackupBundle.BackupManifest(
                databaseVersion = APP_DATABASE_SCHEMA_VERSION,
                createdAt = fixedTime,
                tableCounts = RealSchemaFixture.manifestCounts(source),
                semanticAggregates = section
            ),
            dbFile = source
        )

        try {
            val result = createRepository(journal = journal)
                .restoreCostBackup(File(tempDir, "bundle.costbackup"), "pw")

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull() as BackupSemanticVerificationException
            assertEquals(SemanticCode.SEMANTIC_MISMATCH, error.code)
            assertEquals(SemanticCheckpoint.C1_STAGED_SOURCE, error.checkpoint)
            assertEquals("the message is the bounded code only", "SEMANTIC_MISMATCH", error.message)
            // F2: the journal literal is the reused one, never a new semantic literal.
            assertEquals(listOf("STAGED_VERIFICATION_FAILED"), failReasons)
            val failureJournal = journal.readFailureJournal()
            assertEquals(RestoreJournal.JournalState.FAILED, failureJournal?.state)
            // The persisted `error` is unchanged: not in FAILURE_REASON_CODES, so it stays UNKNOWN_ERROR.
            assertEquals(DiagnosticReasonCode.UNKNOWN_ERROR.name, failureJournal?.error)
            verify { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) }
            assertTrue("staged residue leaked: ${stagedResidueFiles()}", stagedResidueFiles().isEmpty())
            assertTrue("extraction residue leaked: ${extractionResidueFiles()}", extractionResidueFiles().isEmpty())
        } finally {
            unmockkObject(CostbackupBundle)
        }
    }

    @Test
    fun `UF46 S3 malformed semantic section passes the reused MANIFEST_INCOMPLETE literal and persists the unchanged error`() = runTest(testDispatcher) {
        stubCacheDir()
        val journal = io.mockk.spyk(newRealJournal())
        val failReasons = mutableListOf<String>()
        every { journal.failJournal(any(), any()) } answers {
            failReasons += secondArg<String>()
            callOriginal()
        }
        // The extracted database is garbage: if the restore got as far as staging it, the failure would be
        // STAGED_VERIFICATION_FAILED instead, so the literal also proves the block happens before staging.
        val garbageDb = File(tempDir, "garbage_semantic_extracted.sqlite").apply { writeText("not a database") }
        stubExtractionResult(
            CostbackupBundle.BackupManifest(
                databaseVersion = APP_DATABASE_SCHEMA_VERSION,
                createdAt = fixedTime,
                tableCounts = BackupVerifier.requiredManifestTables(1).associateWith { 1 },
                semanticAggregates = null,
                semanticSectionMalformed = true
            ),
            dbFile = garbageDb
        )

        try {
            val result = createRepository(journal = journal)
                .restoreCostBackup(File(tempDir, "bundle.costbackup"), "pw")

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull() as BackupSemanticVerificationException
            assertEquals(SemanticCode.SEMANTIC_MANIFEST_MALFORMED, error.code)
            assertNull(error.checkpoint)
            assertEquals("SEMANTIC_MANIFEST_MALFORMED", error.message)
            assertEquals(listOf("MANIFEST_INCOMPLETE"), failReasons)
            val failureJournal = journal.readFailureJournal()
            assertEquals(RestoreJournal.JournalState.FAILED, failureJournal?.state)
            assertEquals(DiagnosticReasonCode.UNKNOWN_ERROR.name, failureJournal?.error)
            verify { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) }
            assertTrue("staged residue leaked: ${stagedResidueFiles()}", stagedResidueFiles().isEmpty())
            assertTrue("extraction residue leaked: ${extractionResidueFiles()}", extractionResidueFiles().isEmpty())
        } finally {
            unmockkObject(CostbackupBundle)
        }
    }

    // ── INV-11 / m1: the nine coverage-gated table counts depend on the semantic marker ──

    /** The nine Tier 1 tables first classified at schema 152 (spec INV-11); independent of production constants. */
    private val m1GatedTables = listOf(
        "source_stats_events",
        "warranty_lifecycle_events",
        "investment_transactions",
        "group_settlements",
        "group_lifecycle_events",
        "entity_source_links",
        "bank_statement_import_runs",
        "bank_statement_import_items",
        "negotiation_outcomes"
    )

    @Test
    fun `INV11 m1 v152 manifest with no semantic section and no gated counts is accepted as NotVerifiable LEGACY`() =
        runTest(testDispatcher) {
            val fixture = restoreFixture()
            try {
                val counts = BackupVerifier.allTableNames().toSet().minus(m1GatedTables.toSet())
                    .associateWith { if (it == "expenses") 1 else 0 }
                assertEquals(
                    "precondition: exactly the nine gated counts are absent",
                    m1GatedTables.toSet(),
                    BackupVerifier.allTableNames().toSet() - counts.keys
                )
                // tier1Manifest is a v152 manifest with semanticAggregates = null and no malformed marker
                // (a pre-lane v152 development backup).
                val manifest = tier1Manifest(counts)
                assertEquals(APP_DATABASE_SCHEMA_VERSION, manifest.databaseVersion)
                assertNull(manifest.semanticAggregates)
                assertFalse(manifest.semanticSectionMalformed)
                stubExtractionResult(manifest, fixture.source, withReceiptAsset = true)

                val result = createRepository(journal = fixture.journal)
                    .restoreCostBackup(File(tempDir, "bundle.costbackup"), "pw")

                assertTrue("a section-less v152 manifest must be accepted: ${result.exceptionOrNull()}", result.isSuccess)
                val summary = (result.getOrThrow() as com.yourname.expensetracker.domain.backup.DatabaseImportResult.SuccessNeedsRestart).summary
                assertEquals(
                    com.yourname.expensetracker.domain.backup.SemanticVerificationOutcome.NotVerifiable(
                        SemanticNotVerifiableReason.LEGACY
                    ),
                    summary.semanticVerification
                )
                assertEquals(RestoreJournal.JournalState.COMPLETE, fixture.journal.readSuccessJournal()?.state)
            } finally {
                releaseRestoreFixture()
            }
        }

    // ── FL-07: home currency applied after the restore journal commit ──

    /**
     * Runs a restore that reaches the post-commit point (same accepted section-less v152 shape as the
     * test above) with [manifestHomeCurrency] in the manifest, and returns the result.
     */
    private suspend fun restoreWithManifestHomeCurrency(
        manifestHomeCurrency: String?,
        settings: com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
    ): Result<com.yourname.expensetracker.domain.backup.DatabaseImportResult> {
        val fixture = restoreFixture()
        try {
            val counts = BackupVerifier.allTableNames().toSet().minus(m1GatedTables.toSet())
                .associateWith { if (it == "expenses") 1 else 0 }
            val manifest = tier1Manifest(counts).copy(homeCurrency = manifestHomeCurrency)
            stubExtractionResult(manifest, fixture.source, withReceiptAsset = true)

            val result = createRepository(journal = fixture.journal, currencySettings = settings)
                .restoreCostBackup(File(tempDir, "bundle.costbackup"), "pw")

            // Guard: every assertion below is only meaningful if the restore reached COMPLETE.
            assertTrue("restore must reach the post-commit point: ${result.exceptionOrNull()}", result.isSuccess)
            assertEquals(RestoreJournal.JournalState.COMPLETE, fixture.journal.readSuccessJournal()?.state)
            return result
        } finally {
            releaseRestoreFixture()
        }
    }

    private fun relaxedCurrencySettings(): com.yourname.expensetracker.domain.currency.CurrencySettingsRepository =
        mockk(relaxed = true) {
            every { homeCurrency() } returns flowOf("EUR")
        }

    @Test
    fun `FL07 restore applies a supported manifest home currency after the journal commit`() =
        runTest(testDispatcher) {
            val settings = relaxedCurrencySettings()

            restoreWithManifestHomeCurrency("USD", settings)

            coVerify(exactly = 1) { settings.setHomeCurrency("USD") }
            // Applied before maintenance mode is released, never after the restore epilogue.
            io.mockk.coVerifyOrder {
                settings.setHomeCurrency("USD")
                mockRestoreMaintenanceMode.exit(forceRestartRequired = true)
            }
        }

    @Test
    fun `FL07 restore applies the normalized form of a lowercase manifest code`() =
        runTest(testDispatcher) {
            // The manifest parser normalizes before restore; a manifest built in memory with a
            // lowercase value must still reach setHomeCurrency as the upper-case supported code.
            val settings = relaxedCurrencySettings()

            restoreWithManifestHomeCurrency("gbp", settings)

            coVerify(exactly = 1) { settings.setHomeCurrency("GBP") }
        }

    @Test
    fun `FL07 restore never applies an unsupported manifest home currency`() =
        runTest(testDispatcher) {
            val settings = relaxedCurrencySettings()

            restoreWithManifestHomeCurrency("ZZZ", settings)

            coVerify(exactly = 0) { settings.setHomeCurrency(any()) }
        }

    @Test
    fun `FL07 restore still succeeds when applying the home currency throws`() =
        runTest(testDispatcher) {
            val settings = relaxedCurrencySettings()
            coEvery { settings.setHomeCurrency(any()) } throws IllegalStateException("synthetic failure")

            val result = restoreWithManifestHomeCurrency("USD", settings)

            coVerify(exactly = 1) { settings.setHomeCurrency("USD") }
            assertTrue(
                result.getOrThrow() is com.yourname.expensetracker.domain.backup.DatabaseImportResult.SuccessNeedsRestart
            )
            verify { mockRestoreMaintenanceMode.exit(forceRestartRequired = true) }
        }

    @Test
    fun `FL07 legacy manifest without a home currency never calls setHomeCurrency`() =
        runTest(testDispatcher) {
            val settings = relaxedCurrencySettings()

            restoreWithManifestHomeCurrency(null, settings)

            coVerify(exactly = 0) { settings.setHomeCurrency(any()) }
        }

    /**
     * Drives a restore whose v152 manifest lacks the nine gated counts and carries the semantic marker (a present
     * section or a present-but-malformed one). It must fail closed BEFORE staging with the reused bounded
     * MANIFEST_INCOMPLETE literal and name exactly the nine missing tables.
     */
    private suspend fun assertMarkedManifestWithoutGatedCountsIsRejected(
        buildManifest: (counts: Map<String, Int>, section: SemanticAggregateSet) -> CostbackupBundle.BackupManifest
    ) {
        val realSource = File(tempDir, "m1-src.sqlite")
        createFullSchemaDatabase(realSource)
        val section = RealSchemaFixture.capture(realSource)
        val fullCounts = RealSchemaFixture.manifestCounts(realSource)
        m1GatedTables.forEach { assertTrue("precondition: fixture counts include $it", fullCounts.containsKey(it)) }
        val counts = fullCounts - m1GatedTables.toSet()

        val fixture = restoreFixture()
        try {
            val before = dbFile.readBytes()
            val failReasons = mutableListOf<String>()
            every { fixture.journal.failJournal(any(), any()) } answers {
                failReasons += secondArg<String>()
                callOriginal()
            }
            // The extracted database is garbage: reaching staging would give STAGED_VERIFICATION_FAILED instead,
            // so MANIFEST_INCOMPLETE also proves the block happens before staging.
            val garbage = File(tempDir, "m1-garbage.sqlite").apply { writeText("not a database") }
            stubExtractionResult(buildManifest(counts, section), garbage)

            val result = createRepository(journal = fixture.journal)
                .restoreCostBackup(File(tempDir, "bundle.costbackup"), "pw")

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull() as BackupVerifier.IncompleteManifestException
            assertEquals(m1GatedTables.sorted(), error.missingTables.sorted())
            assertEquals(listOf("MANIFEST_INCOMPLETE"), failReasons)
            assertEquals(RestoreJournal.JournalState.FAILED, fixture.journal.readFailureJournal()?.state)
            verify { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) }
            verify(exactly = 0) { database.close() }
            assertFalse("live database must not be swapped", fixture.trace.contains("CLOSE_LIVE"))
            org.junit.Assert.assertArrayEquals(before, dbFile.readBytes())
        } finally {
            releaseRestoreFixture()
        }
    }

    @Test
    fun `INV11 m1 v152 manifest WITH a semantic section lacking the gated counts still fails MANIFEST_INCOMPLETE`() =
        runTest(testDispatcher) {
            assertMarkedManifestWithoutGatedCountsIsRejected { counts, section ->
                CostbackupBundle.BackupManifest(
                    databaseVersion = APP_DATABASE_SCHEMA_VERSION,
                    createdAt = fixedTime,
                    tableCounts = counts,
                    semanticAggregates = section
                )
            }
        }

    @Test
    fun `INV11 m1 v152 manifest WITH a malformed semantic section lacking the gated counts still fails MANIFEST_INCOMPLETE`() =
        runTest(testDispatcher) {
            assertMarkedManifestWithoutGatedCountsIsRejected { counts, _ ->
                CostbackupBundle.BackupManifest(
                    databaseVersion = APP_DATABASE_SCHEMA_VERSION,
                    createdAt = fixedTime,
                    tableCounts = counts,
                    semanticAggregates = null,
                    semanticSectionMalformed = true
                )
            }
        }

    @Test
    fun `UF46 S3 BackupSemanticVerificationException maps to VALIDATION_FAILED and an unrelated exception does not`() {
        val mapper = DatabaseBackupRepositoryImpl::class.java
            .getDeclaredMethod("restoreFailureReasonCode", Throwable::class.java)
            .apply { isAccessible = true }
        fun reasonOf(error: Throwable): DiagnosticReasonCode = mapper.invoke(repository, error) as DiagnosticReasonCode

        SemanticCode.values().forEach { code ->
            val error = BackupSemanticVerificationException(code)
            assertEquals(code.name, error.message)
            assertEquals(DiagnosticReasonCode.VALIDATION_FAILED, reasonOf(error))
        }
        // Boundary: the mapping is by type, so an unrelated failure keeps the generic code.
        assertEquals(DiagnosticReasonCode.UNKNOWN_ERROR, reasonOf(IllegalStateException("SEMANTIC_MISMATCH")))
    }

    @Test
    fun `UF46 S3 DatabaseImportSummary semanticVerification default is NotVerifiable NO_MANIFEST and never Verified`() {
        val summary = DatabaseImportSummary(
            transactionCount = 3,
            categoryCount = 2,
            merchantCount = 1,
            pendingReviewCount = 0,
            budgetCount = 0
        )

        assertEquals(
            com.yourname.expensetracker.domain.backup.SemanticVerificationOutcome.NotVerifiable(
                SemanticNotVerifiableReason.NO_MANIFEST
            ),
            summary.semanticVerification
        )
        assertFalse(
            "a summary that never set the outcome must not claim Verified",
            summary.semanticVerification is com.yourname.expensetracker.domain.backup.SemanticVerificationOutcome.Verified
        )
        // The default survives copy and the legacy tracked-table constructor shape.
        assertEquals(summary.semanticVerification, summary.copy(transactionCount = 9).semanticVerification)
        assertEquals(summary.semanticVerification, trackedSummaryDefaultOutcome())
        // Boundary: Verified is only ever an explicit choice of the producer.
        assertEquals(
            com.yourname.expensetracker.domain.backup.SemanticVerificationOutcome.Verified,
            summary.copy(
                semanticVerification = com.yourname.expensetracker.domain.backup.SemanticVerificationOutcome.Verified
            ).semanticVerification
        )
    }

    private fun trackedSummaryDefaultOutcome() =
        DatabaseImportSummary(
            transactionCount = 0,
            categoryCount = 0,
            merchantCount = 0,
            pendingReviewCount = 0,
            budgetCount = 0,
            allTableCounts = mapOf("expenses" to 0),
            receiptAssetWarnings = listOf("W")
        ).semanticVerification

    @Test
    fun `pre-swap generic failure writes terminal failed journal cleans residue and exits normally`() = runTest(testDispatcher) {
        stubCacheDir()
        val journal = newRealJournal()
        val repo = createRepository(journal = journal)
        // Extraction "succeeds" but the extracted DB file is missing — the staging copy
        // throws and lands in the OUTER catch with a pre-swap (STAGED) journal.
        // (stubExtractionResult never creates the db file when dbFile=null.)
        stubExtractionResult(
            tier1Manifest(BackupVerifier.requiredManifestTables(1).associateWith { 1 }),
            dbFile = null
        )

        try {
            val result = repo.restoreCostBackup(File(tempDir, "bundle.costbackup"), "pw")

            assertTrue(result.isFailure)
            // P7-008 (pre-swap branch): nothing destructive happened — exit to NORMAL.
            verify { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) }
            assertNotNull("Outer catch must write a terminal failed journal", journal.readFailureJournal())
            assertTrue(stagedResidueFiles().isEmpty())
            assertTrue(extractionResidueFiles().isEmpty())
        } finally {
            unmockkObject(CostbackupBundle)
        }
    }

    // -- RP-03 fix: restoreReceiptAssets identity-derived names + fail-closed guards --

    /**
     * Runs [DatabaseBackupRepositoryImpl.restoreReceiptAssets] directly (test seam)
     * against a mocked Room DB and a real file-backed journal, with [sourceFiles]
     * placed in the extracted bundle's receipts dir.
     */
    private suspend fun runDirectAssetRestore(
        journal: RestoreJournal,
        vararg sourceFiles: String,
        priorTask: RestoreJournal.AssetRestoreTask? = null,
        withProof: Boolean = true,
        extractedRoot: File? = null,
        configureDao: (com.yourname.expensetracker.data.database.dao.ScannedReceiptDao) -> Unit = {}
    ): Pair<DatabaseBackupRepositoryImpl.ReceiptAssetRestoreOutcome, com.yourname.expensetracker.data.database.dao.ScannedReceiptDao> {
        val assetsDir = extractedRoot ?: File(tempDir, "asset_extract")
        if (extractedRoot == null) assetsDir.deleteRecursively()
        // S2: the bundle layout is files/receipts/{id}_{name} plus checksums.json proofs.
        val receiptsSrc = File(assetsDir, "files/receipts")
        receiptsSrc.mkdirs()
        sourceFiles.forEach { name -> File(receiptsSrc, name).writeBytes(ASSET_BYTES) }
        if (withProof && extractedRoot == null) {
            val entries = sourceFiles.associate { "files/receipts/$it" to ASSET_SHA256 }
            File(assetsDir, "checksums.json").writeText(
                CostbackupBundle.ChecksumsManifest(entries).toJson().toString()
            )
        }

        val dao = mockk<com.yourname.expensetracker.data.database.dao.ScannedReceiptDao>(relaxed = true)
        var receipt = com.yourname.expensetracker.data.database.entity.ScannedReceipt(
            id = 5L,
            imagePath = "/legacy/photo.jpg",
            rawOcrText = "seed",
            parsedTotal = null,
            parsedMerchant = null,
            parsedDate = null,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.5f
        )
        coEvery { dao.getById(5L) } coAnswers { receipt }
        coEvery { dao.updateImagePathIfUnchanged(any(), any(), any()) } coAnswers {
            if (firstArg<Long>() == receipt.id && secondArg<String?>() == receipt.imagePath) {
                receipt = receipt.copy(imagePath = thirdArg<String>())
                1
            } else 0
        }
        val assetDb = mockk<AppDatabase>(relaxed = true)
        every { assetDb.scannedReceiptDao() } returns dao

        // RestoreInternalWriteScope permits writes only in restore modes.
        every { mockRestoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.ASSETS_RESTORING

        var entry = journal.beginJournal(
            sourceBackupPath = File(tempDir, "bundle.costbackup").absolutePath,
            stagedDbPath = File(tempDir, "staged_restore.db").absolutePath,
            liveDbPath = dbFile.absolutePath
        )
        entry = journal.transitionTo(entry, RestoreJournal.JournalState.ASSETS_RESTORING)

        if (priorTask != null) {
            entry = entry.copy(assetTasks = listOf(priorTask))
            journal.writeJournal(entry)
        }
        configureDao(dao)

        val repo = createRepository(journal = journal)
        val outcome = repo.restoreReceiptAssets(
            assetsDir = assetsDir,
            manifest = tier1Manifest(BackupVerifier.requiredManifestTables(1).associateWith { 1 }),
            db = assetDb,
            journalEntry = entry
        )
        return outcome to dao
    }

    private val ASSET_BYTES = byteArrayOf(1, 2, 3)
    private val ASSET_SHA256: String =
        java.security.MessageDigest.getInstance("SHA-256").digest(ASSET_BYTES).joinToString("") { "%02x".format(it) }

    /** S2: no operation-owned temp (any `*.tmp`) may survive in the receipts dir. */
    private fun noAssetTemps(): Boolean =
        File(tempDir, "receipts").listFiles { f -> f.name.endsWith(".tmp") }.isNullOrEmpty()

    @Test
    fun `restoreReceiptAssets restores under identity-derived name and returns ledger for the caller`() = runTest(testDispatcher) {
        val journal = newRealJournal()
        val (outcome, dao) = runDirectAssetRestore(journal, "5_photo.jpg")

        val finalFile = File(File(tempDir, "receipts"), "restored_5.jpg")
        assertTrue("Asset must be restored under the identity-derived final name", finalFile.exists())
        assertEquals("Asset bytes must be preserved", 3L, finalFile.length())
        assertTrue("Temp copy must be gone after the durable publish", noAssetTemps())

        // The returned ledger (RP-03 fix: caller carries it into the committed journal).
        val ledger = outcome.journalEntry!!.assetTasks
        assertEquals(1, ledger.size)
        assertEquals(RestoreJournal.AssetRestoreStatus.COMPLETED, ledger[0].status)
        assertTrue("Ledger target must point at the derived final name", ledger[0].targetPath!!.endsWith("restored_5.jpg"))
        // The journal persisted during the loop round-trips basename-only targets.
        val onDisk = journal.readJournal()!!
        assertEquals(RestoreJournal.AssetRestoreStatus.COMPLETED, onDisk.assetTasks.single().status)
        assertEquals("restored_5.jpg", onDisk.assetTasks.single().targetPath)

        coVerify(exactly = 1) {
            dao.updateImagePathIfUnchanged(5L, "/legacy/photo.jpg", finalFile.absolutePath)
        }
        coVerify(exactly = 0) { dao.update(any()) }
        assertEquals(finalFile.absolutePath, dao.getById(5L)!!.imagePath)
        assertTrue(onDisk.assetTasks.single().expectedImagePathRecorded)
        assertEquals("/legacy/photo.jpg", onDisk.assetTasks.single().expectedImagePath)
    }

    @Test
    fun `restoreReceiptAssets never overwrites an existing final file on collision`() = runTest(testDispatcher) {
        val journal = newRealJournal()
        val foreignBytes = byteArrayOf(7, 7, 7, 7)
        val finalFile = File(File(tempDir, "receipts"), "restored_5.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(foreignBytes)
        }

        val (outcome, dao) = runDirectAssetRestore(journal, "5_photo.jpg")

        val task = outcome.journalEntry!!.assetTasks.single()
        assertEquals(RestoreJournal.AssetRestoreStatus.FAILED, task.status)
        assertEquals(RestoreJournal.ASSET_REASON_TARGET_COLLISION, task.error)
        assertTrue("Foreign final file bytes must be untouched", finalFile.readBytes().contentEquals(foreignBytes))
        assertTrue("No temp residue may linger", noAssetTemps())
        coVerify(exactly = 0) { dao.update(any()) }
        coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
    }

    @Test
    fun `restoreReceiptAssets fails duplicate receipt-id tasks closed`() = runTest(testDispatcher) {
        val journal = newRealJournal()

        val (outcome, dao) = runDirectAssetRestore(journal, "5_a.jpg", "5_b.jpg")

        val tasks = outcome.journalEntry!!.assetTasks
        assertEquals(2, tasks.size)
        assertTrue(
            "ALL duplicate receipt-id tasks must fail (no first-wins)",
            tasks.all { it.status == RestoreJournal.AssetRestoreStatus.FAILED && it.error == RestoreJournal.ASSET_REASON_DUPLICATE_TASK }
        )
        coVerify(exactly = 0) { dao.update(any()) }
        coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
        assertFalse(
            "No final file may be created for duplicate tasks",
            File(File(tempDir, "receipts"), "restored_5.jpg").exists()
        )
    }

    @Test
    fun `restoreReceiptAssets rejects non-allowlisted source extension`() = runTest(testDispatcher) {
        val journal = newRealJournal()

        val (outcome, dao) = runDirectAssetRestore(journal, "5_payload.exe")

        val task = outcome.journalEntry!!.assetTasks.single()
        assertEquals(RestoreJournal.AssetRestoreStatus.FAILED, task.status)
        assertEquals(RestoreJournal.ASSET_REASON_INVALID_TARGET, task.error)
        assertFalse("No file may be produced for a rejected extension", File(File(tempDir, "receipts"), "restored_5.exe").exists())
        coVerify(exactly = 0) { dao.update(any()) }
        coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
    }

    @Test
    fun asset_restore_zero_affected_rows_never_completes_the_task() = runTest(testDispatcher) {
        val journal = newRealJournal()
        val (outcome, dao) = runDirectAssetRestore(journal, "5_photo.jpg", configureDao = { dao ->
            coEvery { dao.updateImagePathIfUnchanged(any(), any(), any()) } returns 0
        })
        val task = outcome.journalEntry!!.assetTasks.single()
        assertEquals(RestoreJournal.AssetRestoreStatus.FAILED, task.status)
        assertEquals(RestoreJournal.ASSET_REASON_IMAGE_PATH_CONFLICT, task.error)
        assertEquals("/legacy/photo.jpg", dao.getById(5L)!!.imagePath)
        assertTrue(outcome.warnings.isNotEmpty())
        assertEquals(task.status, journal.readJournal()!!.assetTasks.single().status)
        coVerify(exactly = 0) { dao.update(any()) }
        // S2: readback proves the row never referenced the published final - it is operation-owned residue.
        assertFalse(File(File(tempDir, "receipts"), "restored_5.jpg").exists())
        assertTrue(noAssetTemps())
    }

    @Test
    fun asset_restore_requires_pointer_readback_even_after_one_affected_row() = runTest(testDispatcher) {
        val journal = newRealJournal()
        val (outcome, dao) = runDirectAssetRestore(journal, "5_photo.jpg", configureDao = { dao ->
            coEvery { dao.updateImagePathIfUnchanged(any(), any(), any()) } returns 1
        })
        assertEquals(RestoreJournal.AssetRestoreStatus.FAILED, outcome.journalEntry!!.assetTasks.single().status)
        assertEquals(RestoreJournal.ASSET_REASON_IMAGE_PATH_CONFLICT, outcome.journalEntry!!.assetTasks.single().error)
        assertEquals("/legacy/photo.jpg", dao.getById(5L)!!.imagePath)
        coVerify(exactly = 0) { dao.update(any()) }
        // S2: an unconfirmed CAS (row count vs readback disagree) keeps the file - the DB may reference it.
        assertTrue(File(File(tempDir, "receipts"), "restored_5.jpg").exists())
    }

    @Test
    fun asset_restore_preserves_the_original_journal_snapshot_instead_of_rebasing_it() = runTest(testDispatcher) {
        val journal = newRealJournal()
        val prior = RestoreJournal.AssetRestoreTask(
            5L, "5_photo.jpg", RestoreJournal.AssetRestoreStatus.PENDING,
            expectedImagePath = "/older/photo.jpg", expectedImagePathRecorded = true,
            expectedSha256 = ASSET_SHA256, expectedSize = ASSET_BYTES.size.toLong()
        )
        val (outcome, dao) = runDirectAssetRestore(journal, "5_photo.jpg", priorTask = prior)
        val task = outcome.journalEntry!!.assetTasks.single()
        assertEquals(RestoreJournal.ASSET_REASON_IMAGE_PATH_CONFLICT, task.error)
        assertEquals(prior.expectedImagePath, journal.readJournal()!!.assetTasks.single().expectedImagePath)
        assertEquals("/legacy/photo.jpg", dao.getById(5L)!!.imagePath)
        assertFalse(File(File(tempDir, "receipts"), "restored_5.jpg").exists())
        coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
    }

    @Test
    fun asset_restore_does_not_invent_a_snapshot_for_a_legacy_pending_task() = runTest(testDispatcher) {
        val journal = newRealJournal()
        val prior = RestoreJournal.AssetRestoreTask(5L, "5_photo.jpg", RestoreJournal.AssetRestoreStatus.PENDING)
        val (outcome, dao) = runDirectAssetRestore(journal, "5_photo.jpg", priorTask = prior)
        val task = outcome.journalEntry!!.assetTasks.single()
        assertEquals(RestoreJournal.AssetRestoreStatus.FAILED, task.status)
        assertEquals(RestoreJournal.ASSET_REASON_EXPECTED_PATH_MISSING, task.error)
        assertFalse(task.expectedImagePathRecorded)
        coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
    }

    @Test
    fun asset_restore_pointer_cancellation_propagates_without_completing_the_ledger() = runTest(testDispatcher) {
        val journal = newRealJournal()
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            runDirectAssetRestore(journal, "5_photo.jpg", configureDao = { dao ->
                coEvery { dao.updateImagePathIfUnchanged(any(), any(), any()) } throws
                    kotlinx.coroutines.CancellationException("TEST_CANCELLED")
            })
        }
        // S2 contract: the published final is journaled FINAL_DURABLE before the CAS, so a
        // cancelled CAS resumes from FINAL_DURABLE (never COMPLETED) on the next launch.
        val onDisk = journal.readJournal()!!.assetTasks.single()
        assertEquals(RestoreJournal.AssetRestoreStatus.FINAL_DURABLE, onDisk.status)
        assertTrue(File(File(tempDir, "receipts"), "restored_5.jpg").exists())
    }


    @Test
    fun asset_restore_requires_durable_snapshot_before_copy_or_mutation() = runTest(testDispatcher) {
        val journal = newRealJournal()
        var capturedDao: com.yourname.expensetracker.data.database.dao.ScannedReceiptDao? = null
        try {
            kotlin.test.assertFailsWith<RestoreJournal.JournalDurabilityException> {
                runDirectAssetRestore(journal, "5_photo.jpg", configureDao = { dao ->
                    capturedDao = dao
                    journal.beforeIo = { _, stage ->
                        if (stage == RestoreJournal.IoStage.WRITE) throw java.io.IOException("TEST_JOURNAL_WRITE_FAILED")
                    }
                })
            }
        } finally {
            journal.beforeIo = null
        }
        val dao = requireNotNull(capturedDao)
        coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
        coVerify(exactly = 0) { dao.update(any()) }
        assertFalse(File(File(tempDir, "receipts"), "restored_5.jpg").exists())
        assertTrue(journal.readJournal()!!.assetTasks.isEmpty())
    }

    @Test
    fun asset_snapshot_read_failure_is_an_asset_warning_not_a_verified_database_failure() = runTest(testDispatcher) {
        val journal = newRealJournal()
        val (outcome, dao) = runDirectAssetRestore(journal, "5_photo.jpg", configureDao = { dao ->
            coEvery { dao.getById(5L) } throws IllegalStateException("TEST_READ_FAILED")
        })
        val task = outcome.journalEntry!!.assetTasks.single()
        assertEquals(RestoreJournal.AssetRestoreStatus.FAILED, task.status)
        assertEquals("ASSET_RESTORE_FAILED", task.error)
        assertFalse(task.expectedImagePathRecorded)
        assertTrue(outcome.warnings.isNotEmpty())
        assertFalse(File(File(tempDir, "receipts"), "restored_5.jpg").exists())
        coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun asset_snapshot_read_cancellation_is_not_converted_to_an_asset_warning() = runTest(testDispatcher) {
        val journal = newRealJournal()
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            runDirectAssetRestore(journal, "5_photo.jpg", configureDao = { dao ->
                coEvery { dao.getById(5L) } throws kotlinx.coroutines.CancellationException("TEST_CANCELLED")
            })
        }
        assertTrue(journal.readJournal()!!.assetTasks.isEmpty())
        assertFalse(File(File(tempDir, "receipts"), "restored_5.jpg").exists())
    }

    // -- S2 (RP-03 asset recovery): proof, durability, crash-replay and residue matrix --

    private val receiptsOut: File get() = File(tempDir, "receipts")
    private val finalAsset: File get() = File(receiptsOut, "restored_5.jpg")

    private fun seededReceipt(imagePath: String?) = com.yourname.expensetracker.data.database.entity.ScannedReceipt(
        id = 5L, imagePath = imagePath, rawOcrText = "seed", parsedTotal = null, parsedMerchant = null,
        parsedDate = null, parsedItems = null, parsedTaxAmount = null, confidence = 0.5f
    )

    private fun provenTask(status: RestoreJournal.AssetRestoreStatus, targetPath: String? = null) =
        RestoreJournal.AssetRestoreTask(
            5L, "5_photo.jpg", status, targetPath = targetPath,
            expectedImagePath = "/legacy/photo.jpg", expectedImagePathRecorded = true,
            expectedSha256 = ASSET_SHA256, expectedSize = ASSET_BYTES.size.toLong()
        )

    @Test
    fun s2_asset_without_bundle_proof_fails_closed_before_any_side_effect() = runTest(testDispatcher) {
        val journal = newRealJournal()
        val (outcome, dao) = runDirectAssetRestore(journal, "5_photo.jpg", withProof = false)
        val task = outcome.journalEntry!!.assetTasks.single()
        assertEquals(RestoreJournal.AssetRestoreStatus.FAILED, task.status)
        assertEquals(RestoreJournal.ASSET_REASON_PROOF_MISSING, task.error)
        assertFalse(finalAsset.exists())
        coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
    }

    @Test
    fun s2_checksum_mismatch_never_publishes_or_moves_the_pointer() = runTest(testDispatcher) {
        val journal = newRealJournal()
        val (outcome, dao) = runDirectAssetRestore(journal, "5_photo.jpg", configureDao = {
            // Same size, different bytes than the checksums.json proof.
            File(tempDir, "asset_extract/files/receipts/5_photo.jpg").writeBytes(byteArrayOf(3, 2, 1))
        })
        val task = outcome.journalEntry!!.assetTasks.single()
        assertEquals(RestoreJournal.ASSET_REASON_INTEGRITY_MISMATCH, task.error)
        assertFalse(finalAsset.exists())
        assertTrue(noAssetTemps())
        coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
        assertEquals("/legacy/photo.jpg", dao.getById(5L)!!.imagePath)
    }

    @Test
    fun s2_oversized_source_is_an_integrity_mismatch() = runTest(testDispatcher) {
        val journal = newRealJournal()
        val (outcome, _) = runDirectAssetRestore(journal, "5_photo.jpg", configureDao = {
            File(tempDir, "asset_extract/files/receipts/5_photo.jpg").writeBytes(byteArrayOf(1, 2, 3, 4))
        })
        assertEquals(RestoreJournal.ASSET_REASON_INTEGRITY_MISMATCH, outcome.journalEntry!!.assetTasks.single().error)
        assertFalse(finalAsset.exists())
        assertTrue(noAssetTemps())
    }

    @Test
    fun s2_asset_fsync_failure_fails_the_task_and_discards_the_temp() = runTest(testDispatcher) {
        val journal = newRealJournal()
        journal.testAssetIo = { _, stage ->
            if (stage == RestoreJournal.IoStage.SYNC) throw java.io.IOException("TEST_FSYNC_FAILED")
        }
        try {
            val (outcome, dao) = runDirectAssetRestore(journal, "5_photo.jpg")
            val task = outcome.journalEntry!!.assetTasks.single()
            assertEquals(RestoreJournal.AssetRestoreStatus.FAILED, task.status)
            assertEquals(RestoreJournal.ASSET_REASON_WRITE_FAILED, task.error)
            assertFalse(finalAsset.exists())
            assertTrue(noAssetTemps())
            coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
        } finally {
            journal.testAssetIo = null
        }
    }

    @Test
    fun s2_publish_failure_fails_the_task_and_discards_the_temp() = runTest(testDispatcher) {
        val journal = newRealJournal()
        journal.testAssetIo = { _, stage ->
            if (stage == RestoreJournal.IoStage.MOVE) throw java.io.IOException("TEST_MOVE_FAILED")
        }
        try {
            val (outcome, dao) = runDirectAssetRestore(journal, "5_photo.jpg")
            assertEquals(RestoreJournal.ASSET_REASON_WRITE_FAILED, outcome.journalEntry!!.assetTasks.single().error)
            assertFalse(finalAsset.exists())
            assertTrue(noAssetTemps())
            coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
        } finally {
            journal.testAssetIo = null
        }
    }

    @Test
    fun s2_ledger_rebuild_keeps_a_task_whose_source_disappeared() = runTest(testDispatcher) {
        val journal = newRealJournal()
        val orphan = RestoreJournal.AssetRestoreTask(
            9L, "9_gone.jpg", RestoreJournal.AssetRestoreStatus.PENDING,
            expectedImagePath = null, expectedImagePathRecorded = true,
            expectedSha256 = ASSET_SHA256, expectedSize = ASSET_BYTES.size.toLong()
        )
        val (outcome, _) = runDirectAssetRestore(journal, "5_photo.jpg", priorTask = orphan)
        val tasks = outcome.journalEntry!!.assetTasks
        assertEquals(2, tasks.size)
        assertEquals(RestoreJournal.AssetRestoreStatus.COMPLETED, tasks.single { it.receiptId == 5L }.status)
        val lost = tasks.single { it.receiptId == 9L }
        assertEquals(RestoreJournal.AssetRestoreStatus.FAILED, lost.status)
        assertEquals(RestoreJournal.ASSET_REASON_SOURCE_MISSING, lost.error)
        assertEquals(2, journal.readJournal()!!.assetTasks.size)
    }

    @Test
    fun s2_warnings_never_echo_bundle_file_names() = runTest(testDispatcher) {
        val journal = newRealJournal()
        val (outcome, _) = runDirectAssetRestore(journal, "secretname.jpg", "5_privatephoto.exe")
        assertTrue(outcome.warnings.isNotEmpty())
        assertTrue(outcome.warnings.none { it.contains("secretname") || it.contains("privatephoto") })
    }

    @Test
    fun s2_final_durable_with_pointer_already_moved_replays_without_a_second_cas() = runTest(testDispatcher) {
        val journal = newRealJournal()
        receiptsOut.mkdirs()
        finalAsset.writeBytes(ASSET_BYTES)
        val (outcome, dao) = runDirectAssetRestore(
            journal, "5_photo.jpg",
            priorTask = provenTask(RestoreJournal.AssetRestoreStatus.FINAL_DURABLE, finalAsset.absolutePath),
            configureDao = { dao -> coEvery { dao.getById(5L) } returns seededReceipt(finalAsset.absolutePath) }
        )
        assertEquals(RestoreJournal.AssetRestoreStatus.COMPLETED, outcome.journalEntry!!.assetTasks.single().status)
        coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
        assertTrue(finalAsset.readBytes().contentEquals(ASSET_BYTES))
    }

    @Test
    fun s2_crash_after_publish_adopts_the_proven_final_instead_of_colliding() = runTest(testDispatcher) {
        val journal = newRealJournal()
        receiptsOut.mkdirs()
        finalAsset.writeBytes(ASSET_BYTES)
        val (outcome, dao) = runDirectAssetRestore(
            journal, "5_photo.jpg",
            priorTask = provenTask(RestoreJournal.AssetRestoreStatus.TEMP_WRITTEN, finalAsset.absolutePath)
        )
        assertEquals(RestoreJournal.AssetRestoreStatus.COMPLETED, outcome.journalEntry!!.assetTasks.single().status)
        coVerify(exactly = 1) { dao.updateImagePathIfUnchanged(5L, "/legacy/photo.jpg", finalAsset.absolutePath) }
        assertEquals(finalAsset.absolutePath, dao.getById(5L)!!.imagePath)
    }

    @Test
    fun s2_crash_after_publish_never_adopts_a_foreign_final() = runTest(testDispatcher) {
        val journal = newRealJournal()
        receiptsOut.mkdirs()
        val foreign = byteArrayOf(7, 7, 7)
        finalAsset.writeBytes(foreign)
        val (outcome, dao) = runDirectAssetRestore(
            journal, "5_photo.jpg",
            priorTask = provenTask(RestoreJournal.AssetRestoreStatus.TEMP_WRITTEN, finalAsset.absolutePath)
        )
        assertEquals(RestoreJournal.ASSET_REASON_TARGET_COLLISION, outcome.journalEntry!!.assetTasks.single().error)
        assertTrue(finalAsset.readBytes().contentEquals(foreign))
        coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
    }

    @Test
    fun s2_completed_task_replay_verifies_the_final_bytes() = runTest(testDispatcher) {
        val journal = newRealJournal()
        receiptsOut.mkdirs()
        finalAsset.writeBytes(byteArrayOf(6, 6, 6))
        val (outcome, dao) = runDirectAssetRestore(
            journal, "5_photo.jpg",
            priorTask = provenTask(RestoreJournal.AssetRestoreStatus.COMPLETED, finalAsset.absolutePath),
            configureDao = { dao -> coEvery { dao.getById(5L) } returns seededReceipt(finalAsset.absolutePath) }
        )
        assertEquals(RestoreJournal.ASSET_REASON_INTEGRITY_MISMATCH, outcome.journalEntry!!.assetTasks.single().error)
        coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
        assertTrue("Verification never rewrites files", finalAsset.readBytes().contentEquals(byteArrayOf(6, 6, 6)))
    }

    @Test
    fun s2_required_transition_journal_failure_propagates_before_the_cas() = runTest(testDispatcher) {
        val journal = newRealJournal()
        var capturedDao: com.yourname.expensetracker.data.database.dao.ScannedReceiptDao? = null
        try {
            kotlin.test.assertFailsWith<RestoreJournal.JournalDurabilityException> {
                runDirectAssetRestore(journal, "5_photo.jpg", configureDao = { dao ->
                    capturedDao = dao
                    var writes = 0
                    // Allow the ledger pre-population write, fail the TEMP_WRITTEN transition.
                    journal.beforeIo = { file, stage ->
                        if (stage == RestoreJournal.IoStage.WRITE && file.name.startsWith("restore_journal") && ++writes == 2) {
                            throw java.io.IOException("TEST_JOURNAL_WRITE_FAILED")
                        }
                    }
                })
            }
        } finally {
            journal.beforeIo = null
        }
        val dao = requireNotNull(capturedDao)
        coVerify(exactly = 0) { dao.updateImagePathIfUnchanged(any(), any(), any()) }
        assertFalse(finalAsset.exists())
        assertTrue(noAssetTemps())
        assertEquals(RestoreJournal.AssetRestoreStatus.PENDING, journal.readJournal()!!.assetTasks.single().status)
    }

    @Test
    fun s2_real_bundle_round_trip_restores_the_receipt_image() = runTest(testDispatcher) {
        val source = File(tempDir, "bundle_src").apply { mkdirs() }
        val image = File(source, "photo.jpg").apply { writeBytes(ASSET_BYTES) }
        val dbSource = File(source, "db.bin").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val bundle = File(tempDir, "roundtrip.costbackup")
        // One low-iteration instance for BOTH create and extract (count is not stored in the envelope).
        val encryption = lowIterationBackupEncryptionService()
        val created = CostbackupBundle.create(
            outputFile = bundle,
            databaseFile = dbSource,
            receiptFiles = mapOf("files/receipts/5_photo.jpg" to image),
            password = "pw-roundtrip",
            nowEpochMs = 1716163200000L,
            tableCounts = mapOf("expenses" to 1),
            databaseVersion = 1,
            redacted = false,
            includeReceiptImages = true,
            encryptionService = encryption
        )
        assertTrue(created.isSuccess)
        val extractRoot = File(tempDir, "roundtrip_extract")
        assertTrue(
            CostbackupBundle.extract(
                bundle, extractRoot, "pw-roundtrip", nowEpochMs = 1716163200000L,
                encryptionService = encryption
            ).isSuccess
        )

        val journal = newRealJournal()
        val (outcome, dao) = runDirectAssetRestore(journal, extractedRoot = extractRoot)
        val task = outcome.journalEntry!!.assetTasks.single()
        assertEquals(RestoreJournal.AssetRestoreStatus.COMPLETED, task.status)
        assertEquals(ASSET_SHA256, task.expectedSha256)
        assertTrue(finalAsset.readBytes().contentEquals(ASSET_BYTES))
        assertEquals(finalAsset.absolutePath, dao.getById(5L)!!.imagePath)
    }

    private enum class RestoreOperation { COSTBACKUP, IMPORT, RESET }

    private suspend fun assertLegacyImportDeniedInRelease() {
        val before = dbFile.takeIf { it.exists() }?.readBytes()
        val result = repository.importDatabase(File(tempDir, "release-raw-import.db"))
        assertTrue(result.exceptionOrNull() is UnsupportedOperationException)
        verify(exactly = 0) { mockRestoreJournal.beginJournal(any(), any(), any()) }
        verify(exactly = 0) { mockRestoreMaintenanceMode.enter(any()) }
        verify(exactly = 0) { database.close() }
        if (before == null) assertFalse(dbFile.exists())
        else org.junit.Assert.assertArrayEquals(before, dbFile.readBytes())
    }

    private suspend fun assertRawExportDeniedInRelease() {
        assertTrue(runCatching { repository.exportDatabase() }.exceptionOrNull() is UnsupportedOperationException)
        verify(exactly = 0) { mockRestoreMaintenanceMode.enter(any()) }
        verify(exactly = 0) { supportDb.query("PRAGMA wal_checkpoint(TRUNCATE)") }
        assertFalse(File(tempDir, "exports").exists())
    }

    private suspend fun supportedRestoreOperations(): List<RestoreOperation> {
        if (!BuildConfig.DEBUG) assertLegacyImportDeniedInRelease()
        return RestoreOperation.values().filter { BuildConfig.DEBUG || it != RestoreOperation.IMPORT }
    }

    private data class RestoreFixture(
        val directory: File,
        val source: File,
        val journal: RestoreJournal,
        val trace: MutableList<String>
    )

    /** Real file/journal bytes, with only Room opening and existing verification seams substituted. */
    private fun restoreFixture(): RestoreFixture {
        io.mockk.clearMocks(database, openHelper, supportDb, mockRestoreMaintenanceMode,
            answers = false, childMocks = false)
        val directory = File(tempDir, UUID.randomUUID().toString()).apply { mkdirs() }
        every { context.filesDir } returns directory
        every { context.cacheDir } returns File(directory, "cache").apply { mkdirs() }
        every { context.applicationContext } returns realAppContext
        every { context.getDatabasePath(any()) } answers { File(directory, firstArg<String>()) }
        dbFile = File(directory, AppDatabase.DATABASE_NAME)
        // S2: the restore ordering fixture keeps the hand-rolled id-only shape (one expense row, a rewritten
        // user_version and mocked count answers depend on it); it never reaches the export capture.
        createIdOnlySchemaDatabase(dbFile)
        val source = File(directory, "source.db")
        createIdOnlySchemaDatabase(source)
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            // R25-C: oldest still-supported schema (legacy < 145 is rejected before any swap).
            it.execSQL("PRAGMA user_version = ${DatabaseSchemaPolicy.MIGRATION_BASELINE}")
        }
        val trace = mutableListOf<String>()
        var mode = RestoreMaintenanceMode.Mode.NORMAL
        every { mockRestoreMaintenanceMode.currentMode() } answers { mode }
        every { mockRestoreMaintenanceMode.isWritesAllowed() } answers { mode == RestoreMaintenanceMode.Mode.NORMAL }
        every { mockRestoreMaintenanceMode.enter(any()) } answers {
            mode = firstArg()
            trace += "MODE_" + mode.name
        }
        every { mockRestoreMaintenanceMode.enterCriticalRecoveryRequired(any()) } answers {
            mode = RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED
            trace += "CRITICAL"
        }
        every { mockRestoreMaintenanceMode.exit(any()) } answers {
            mode = if (firstArg<Boolean>()) RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED
                else RestoreMaintenanceMode.Mode.NORMAL
            trace += "EXIT_" + mode.name
        }
        every { database.close() } answers { trace += "CLOSE_LIVE" }
        every { openHelper.close() } returns Unit
        every { openHelper.writableDatabase } returns supportDb
        every { supportDb.query(match<String> { it.startsWith("SELECT COUNT(*)") }) } answers {
            val count = if (firstArg<String>().contains("\"expenses\"")) 1 else 0
            MatrixCursor(arrayOf("count")).apply { addRow(arrayOf(count)) }
        }
        every { supportDb.query("PRAGMA wal_checkpoint(TRUNCATE)") } answers { checkpointCursor(0) }
        mockkObject(AppDatabase.Companion)
        val liveBuilder = mockk<androidx.room.RoomDatabase.Builder<AppDatabase>>()
        val stageBuilder = mockk<androidx.room.RoomDatabase.Builder<AppDatabase>>()
        val stagedDatabase = mockk<AppDatabase>(relaxed = true)
        every { stagedDatabase.openHelper } returns openHelper
        every { liveBuilder.build() } returns database
        every { stageBuilder.build() } returns stagedDatabase
        every { AppDatabase.fileBuilder(context, any()) } answers {
            if (secondArg<String>() == AppDatabase.DATABASE_NAME) liveBuilder else stageBuilder
        }
        val counts = BackupVerifier.allTableNames().associateWith { if (it == "expenses") 1 else 0 }
        stubExtractionResult(tier1Manifest(counts), source, withReceiptAsset = true)
        val journal = io.mockk.spyk(RestoreJournal(context, FakeTimeProvider(fixedTime)))
        every { journal.transitionTo(any(), any(), any(), any()) } answers {
            val result = callOriginal()
            trace += "JOURNAL_" + secondArg<RestoreJournal.JournalState>().name
            result
        }
        every { journal.failJournal(any(), any()) } answers {
            val result = callOriginal()
            trace += "FINALIZED_FAILURE"
            result
        }
        every { journal.commitJournal(any()) } answers {
            val result = callOriginal()
            trace += "FINALIZED_SUCCESS"
            result
        }
        return RestoreFixture(directory, source, journal, trace)
    }

    private fun releaseRestoreFixture() {
        unmockkObject(CostbackupBundle)
        unmockkObject(AppDatabase.Companion)
    }

    private suspend fun invokeRestore(
        operation: RestoreOperation,
        repo: DatabaseBackupRepositoryImpl,
        source: File
    ): Result<*> = when (operation) {
        RestoreOperation.COSTBACKUP -> repo.restoreCostBackup(source, "pw")
        RestoreOperation.IMPORT -> repo.importDatabase(source)
        RestoreOperation.RESET -> repo.resetDatabase()
    }

    private fun assertNoDestructiveStep(fixture: RestoreFixture, before: ByteArray) {
        org.junit.Assert.assertArrayEquals(before, dbFile.readBytes())
        assertFalse(fixture.trace.contains("CLOSE_LIVE"))
        verify(exactly = 0) { database.close() }
        verify(exactly = 0) { mockRestoreMaintenanceMode.exit(any()) }
        assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mockRestoreMaintenanceMode.currentMode())
    }

    @Test
    fun all_restore_APIs_contain_begin_open_write_sync_and_corrupt_journal_failures() = runTest(testDispatcher) {
        for (operation in supportedRestoreOperations()) {
            for (fault in listOf("OPEN", "WRITE", "SYNC", "CORRUPT")) {
                val fixture = restoreFixture()
                try {
                    val before = dbFile.readBytes()
                    val wal = File(dbFile.path + "-wal").apply { writeBytes(byteArrayOf(1, 2)) }
                    val shm = File(dbFile.path + "-shm").apply { writeBytes(byteArrayOf(3, 4)) }
                    val active = File(fixture.directory, "restore_journal.json")
                    if (fault == "CORRUPT") active.writeText("{BROKEN")
                    else fixture.journal.beforeIo = { file, stage ->
                        if (file.name == "restore_journal.json.tmp" && stage.name == fault)
                            throw java.io.IOException("TEST_JOURNAL_IO_FAILED")
                    }
                    val result = invokeRestore(operation, createRepository(journal = fixture.journal), fixture.source)
                    assertTrue(result.exceptionOrNull() is RestoreJournal.JournalDurabilityException)
                    assertNoDestructiveStep(fixture, before)
                    org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2), wal.readBytes())
                    org.junit.Assert.assertArrayEquals(byteArrayOf(3, 4), shm.readBytes())
                    verify(exactly = 0) { mockRestoreMaintenanceMode.enter(any()) }
                    if (fault == "CORRUPT") assertEquals("{BROKEN", active.readText())
                } finally { releaseRestoreFixture() }
            }
        }
    }

    @Test
    fun all_restore_APIs_contain_entry_and_pre_destructive_transition_failures() = runTest(testDispatcher) {
        for (operation in supportedRestoreOperations()) {
            for (fault in listOf("ENTRY", "STAGED", "SAFETY_BACKUP_CREATED", "SWAPPING")) {
                val fixture = restoreFixture()
                try {
                    val before = dbFile.readBytes()
                    if (fault == "ENTRY") {
                        every { mockRestoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.RESTORE_PREPARING) } throws
                            RestoreMaintenanceMode.PersistenceException()
                    } else {
                        val state = RestoreJournal.JournalState.valueOf(fault)
                        every { fixture.journal.transitionTo(any(), state, any(), any()) } throws
                            RestoreJournal.JournalDurabilityException()
                    }
                    val result = invokeRestore(operation, createRepository(journal = fixture.journal), fixture.source)
                    assertTrue(result.isFailure)
                    assertTrue(result.exceptionOrNull() is RestoreJournal.JournalDurabilityException ||
                        result.exceptionOrNull() is RestoreMaintenanceMode.PersistenceException)
                    assertNoDestructiveStep(fixture, before)
                    assertTrue(fixture.journal.hasJournal())
                } finally { releaseRestoreFixture() }
            }
        }
    }

    @Test
    fun all_restore_APIs_contain_abort_finalization_and_critical_persistence_failures() = runTest(testDispatcher) {
        for (operation in supportedRestoreOperations()) for (criticalFails in listOf(false, true)) {
            val fixture = restoreFixture()
            try {
                val before = dbFile.readBytes()
                every { mockRestoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.RESTORE_STAGING) } throws
                    IllegalStateException("TEST_PRE_SWAP_FAILED")
                every { fixture.journal.failJournal(any(), any()) } throws RestoreJournal.JournalDurabilityException()
                if (criticalFails) every { mockRestoreMaintenanceMode.enterCriticalRecoveryRequired(any()) } answers {
                    fixture.trace += "CRITICAL"
                    every { mockRestoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED
                    throw RestoreMaintenanceMode.PersistenceException()
                }
                val result = invokeRestore(operation, createRepository(journal = fixture.journal), fixture.source)
                assertTrue(if (criticalFails) result.exceptionOrNull() is RestoreMaintenanceMode.PersistenceException
                    else result.exceptionOrNull() is RestoreJournal.JournalDurabilityException)
                assertNoDestructiveStep(fixture, before)
                assertTrue(fixture.journal.hasJournal())
            } finally { releaseRestoreFixture() }
        }
    }

    @Test
    fun original_cancellation_survives_journal_and_critical_cleanup_failure_in_all_APIs() = runTest(testDispatcher) {
        val criticalFailures = listOf<Exception?>(
            null,
            RestoreMaintenanceMode.PersistenceException(),
            kotlinx.coroutines.CancellationException("TEST_CRITICAL_PERSISTENCE_CANCELLED")
        )
        for (operation in supportedRestoreOperations()) for (journalCancellation in listOf(false, true)) {
            for (criticalFailure in criticalFailures) {
                val fixture = restoreFixture()
                try {
                    val before = dbFile.readBytes()
                    val original = IdentityCancellationException("TEST_RESTORE_CANCELLED")
                    var criticalAttempts = 0
                    every { mockRestoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.RESTORE_STAGING) } throws original
                    every { fixture.journal.failJournal(any(), any()) } throws (
                        if (journalCancellation) kotlinx.coroutines.CancellationException(
                            "TEST_JOURNAL_CLEANUP_CANCELLED"
                        ) else RestoreJournal.JournalDurabilityException()
                    )
                    every { mockRestoreMaintenanceMode.enterCriticalRecoveryRequired(any()) } answers {
                        criticalAttempts++
                        every { mockRestoreMaintenanceMode.currentMode() } returns
                            RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED
                        if (criticalFailure != null) throw criticalFailure
                        Unit
                    }
                    var observed: Throwable? = null
                    try { invokeRestore(operation, createRepository(journal = fixture.journal), fixture.source) }
                    catch (e: kotlinx.coroutines.CancellationException) { observed = e }
                    org.junit.Assert.assertSame(original, observed)
                    assertEquals(1, criticalAttempts)
                    assertEquals(
                        RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED,
                        mockRestoreMaintenanceMode.currentMode()
                    )
                    assertNoDestructiveStep(fixture, before)
                    assertTrue(fixture.journal.hasJournal())
                } finally { releaseRestoreFixture() }
            }
        }
    }

    @Test
    fun swap_and_verification_failures_cross_rollback_and_terminal_durability() = runTest(testDispatcher) {
        for (operation in supportedRestoreOperations().filter { it != RestoreOperation.RESET })
            for (swapFailure in listOf(false, true)) for (rollbackFails in listOf(false, true))
                for (terminalFails in listOf(false, true)) {
                    val fixture = restoreFixture()
                    try {
                        val before = dbFile.readBytes()
                        var rollingBack = false
                        every { fixture.journal.transitionTo(any(), RestoreJournal.JournalState.ROLLING_BACK, any(), any()) } answers {
                            val result = callOriginal()
                            rollingBack = true
                            fixture.trace += "JOURNAL_ROLLING_BACK"
                            result
                        }
                        every { openHelper.writableDatabase } answers {
                            if (rollingBack && rollbackFails) throw IllegalStateException("TEST_ROLLBACK_VERIFY_FAILED")
                            supportDb
                        }
                        if (terminalFails) fixture.journal.beforeIo = { file, stage ->
                            if (file.name == "restore_journal_last_failure.json.tmp" && stage == RestoreJournal.IoStage.SYNC)
                                throw java.io.IOException("TEST_FAILURE_ARCHIVE_FAILED")
                        }
                        if (swapFailure) {
                            if (operation == RestoreOperation.COSTBACKUP) {
                                val failRename = object : File(dbFile.path) { override fun renameTo(dest: File) = false }
                                every { context.getDatabasePath(AppDatabase.DATABASE_NAME) } returns failRename
                            } else every { database.close() } answers {
                                fixture.trace += "CLOSE_LIVE"
                                if (!rollingBack) fixture.journal.readJournal()?.stagedDbPath?.let { File(it).delete() }
                            }
                        } else {
                            every { supportDb.query(match<String> { it.startsWith("SELECT COUNT(*)") }) } throws
                                IllegalStateException("TEST_LIVE_VERIFY_FAILED")
                        }
                        val repo = createRepository(journal = fixture.journal, liveVerifier = { _, _, _, _ ->
                            throw IllegalStateException("TEST_LIVE_VERIFY_FAILED")
                        })
                        val result = invokeRestore(operation, repo, fixture.source)
                        assertTrue(result.isFailure)
                        assertTrue(rollingBack)
                        verify(exactly = 0) { mockRestoreMaintenanceMode.exit(false) }
                        if (rollbackFails || terminalFails) {
                            assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mockRestoreMaintenanceMode.currentMode())
                            verify(exactly = 0) { mockRestoreMaintenanceMode.exit(true) }
                            assertTrue(fixture.journal.hasJournal())
                            assertTrue(File(fixture.directory, "safety_backups").listFiles().orEmpty().isNotEmpty())
                            if (operation == RestoreOperation.COSTBACKUP) {
                                val entry = fixture.journal.readJournal()!!
                                org.junit.Assert.assertArrayEquals(byteArrayOf(9, 8, 7),
                                    File(entry.extractTempDirPath!!, "files/receipts/5_photo.jpg").readBytes())
                            }
                        } else {
                            assertEquals(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED, mockRestoreMaintenanceMode.currentMode())
                            assertFalse(fixture.journal.hasJournal())
                            assertEquals(RestoreJournal.JournalState.FAILED, fixture.journal.readFailureJournal()?.state)
                            org.junit.Assert.assertArrayEquals(before, dbFile.readBytes())
                            assertTrue(fixture.trace.indexOf("FINALIZED_FAILURE") < fixture.trace.indexOf("EXIT_RESTORE_COMPLETE_RESTART_REQUIRED"))
                        }
                    } finally { releaseRestoreFixture() }
                }
    }

    @Test
    fun assets_transition_failure_retains_extraction_safety_and_pre_restore_bytes() = runTest(testDispatcher) {
        val fixture = restoreFixture()
        try {
            val before = dbFile.readBytes()
            every { fixture.journal.transitionTo(any(), RestoreJournal.JournalState.ASSETS_RESTORING, any(), any()) } throws
                RestoreJournal.JournalDurabilityException()
            val result = createRepository(journal = fixture.journal).restoreCostBackup(fixture.source, "pw")
            assertTrue(result.exceptionOrNull() is RestoreJournal.JournalDurabilityException)
            val entry = fixture.journal.readJournal()!!
            assertEquals(RestoreJournal.JournalState.VERIFYING, entry.state)
            org.junit.Assert.assertArrayEquals(byteArrayOf(9, 8, 7), File(entry.extractTempDirPath!!, "files/receipts/5_photo.jpg").readBytes())
            org.junit.Assert.assertArrayEquals(before, File(entry.safetyBackupPath!!).readBytes())
            org.junit.Assert.assertArrayEquals(before, File(dbFile.path + ".pre_restore").readBytes())
            verify(exactly = 0) { mockRestoreMaintenanceMode.exit(any()) }
            assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mockRestoreMaintenanceMode.currentMode())
        } finally { releaseRestoreFixture() }
    }

    @Test
    fun reset_delete_false_or_exception_requires_verified_rollback_and_checked_finalization() = runTest(testDispatcher) {
        for (throws in listOf(false, true)) for (rollbackFails in listOf(false, true))
            for (terminalFails in listOf(false, true)) {
                val fixture = restoreFixture()
                try {
                    val before = dbFile.readBytes()
                    var rollingBack = false
                    val failingFile = object : File(dbFile.path) {
                        override fun delete(): Boolean {
                            if (rollingBack) return super.delete()
                            if (throws) throw SecurityException("TEST_RESET_DELETE_FAILED")
                            return false
                        }
                    }
                    every { context.getDatabasePath(AppDatabase.DATABASE_NAME) } returns failingFile
                    every { fixture.journal.transitionTo(any(), RestoreJournal.JournalState.ROLLING_BACK, any(), any()) } answers {
                        val result = callOriginal()
                        rollingBack = true
                        fixture.trace += "JOURNAL_ROLLING_BACK"
                        result
                    }
                    every { openHelper.writableDatabase } answers {
                        if (rollingBack && rollbackFails) throw IllegalStateException("TEST_ROLLBACK_VERIFY_FAILED")
                        supportDb
                    }
                    if (terminalFails) fixture.journal.beforeIo = { file, stage ->
                        if (file.name == "restore_journal_last_failure.json.tmp" && stage == RestoreJournal.IoStage.SYNC)
                            throw java.io.IOException("TEST_RESET_FINALIZE_FAILED")
                    }
                    val result = createRepository(journal = fixture.journal).resetDatabase()
                    assertTrue(result.isFailure)
                    assertTrue(rollingBack)
                    verify(exactly = 0) { mockRestoreMaintenanceMode.exit(false) }
                    if (rollbackFails || terminalFails) {
                        assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mockRestoreMaintenanceMode.currentMode())
                        assertTrue(fixture.journal.hasJournal())
                        verify(exactly = 0) { mockRestoreMaintenanceMode.exit(true) }
                    } else {
                        org.junit.Assert.assertArrayEquals(before, dbFile.readBytes())
                        assertEquals(RestoreJournal.JournalState.FAILED, fixture.journal.readFailureJournal()?.state)
                        verify { mockRestoreMaintenanceMode.exit(true) }
                    }
                } finally { releaseRestoreFixture() }
            }
    }

    @Test
    fun successful_operations_publish_approved_stages_before_close_and_finalize_before_restart() = runTest(testDispatcher) {
        for (operation in supportedRestoreOperations()) for (terminalFails in listOf(false, true)) {
            val fixture = restoreFixture()
            try {
                if (terminalFails) fixture.journal.beforeIo = { file, stage ->
                    if (file.name == RestoreJournal.SUCCESS_JOURNAL_FILENAME + ".tmp" && stage == RestoreJournal.IoStage.SYNC)
                        throw java.io.IOException("TEST_SUCCESS_ARCHIVE_FAILED")
                }
                val result = invokeRestore(operation, createRepository(journal = fixture.journal), fixture.source)
                if (terminalFails) {
                    assertTrue(result.exceptionOrNull() is RestoreJournal.JournalDurabilityException)
                    assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mockRestoreMaintenanceMode.currentMode())
                    assertEquals(RestoreJournal.JournalState.COMPLETE, fixture.journal.readJournal()?.state)
                    verify(exactly = 0) { mockRestoreMaintenanceMode.exit(any()) }
                } else {
                    assertTrue(result.isSuccess)
                    assertEquals(RestoreJournal.JournalState.COMPLETE, fixture.journal.readSuccessJournal()?.state)
                    assertFalse(fixture.journal.hasJournal())
                    assertTrue(fixture.trace.indexOf("FINALIZED_SUCCESS") < fixture.trace.indexOf("EXIT_RESTORE_COMPLETE_RESTART_REQUIRED"))
                    if (operation == RestoreOperation.RESET) assertFalse(dbFile.exists())
                    else assertEquals(1, countRows(dbFile, "expenses"))
                }
                val expected = listOf("MODE_RESTORE_PREPARING", "MODE_RESTORE_STAGING", "JOURNAL_STAGED",
                    "JOURNAL_SAFETY_BACKUP_CREATED", "JOURNAL_SWAPPING",
                    if (operation == RestoreOperation.RESET) "MODE_RESETTING_DATABASE" else "MODE_RESTORE_SWAPPING", "CLOSE_LIVE")
                var previous = -1
                expected.forEach { event ->
                    val index = fixture.trace.indexOf(event)
                    assertTrue("TEST_TRANSITION_ORDER_" + event, index > previous)
                    previous = index
                }
                if (operation != RestoreOperation.RESET) assertTrue(fixture.trace.contains("MODE_RESTORE_VERIFYING"))
                verify(exactly = 0) { mockRestoreMaintenanceMode.exit(false) }
            } finally { releaseRestoreFixture() }
        }
    }


    @Test
    fun drain_timeout_never_releases_writes_before_checked_abort_finalization() = runTest(testDispatcher) {
        for (operation in supportedRestoreOperations()) for (archiveFails in listOf(false, true)) {
            val fixture = restoreFixture()
            io.mockk.mockkConstructor(com.yourname.expensetracker.domain.workers.NoOpWorkerDrainController::class)
            try {
                val before = dbFile.readBytes()
                coEvery {
                    anyConstructed<com.yourname.expensetracker.domain.workers.NoOpWorkerDrainController>()
                        .requestStopAndAwaitDrain(any(), any())
                } returns false
                if (archiveFails) every { fixture.journal.failJournal(any(), any()) } throws RestoreJournal.JournalDurabilityException()
                val result = invokeRestore(operation, createRepository(journal = fixture.journal), fixture.source)
                assertTrue(result.isFailure)
                org.junit.Assert.assertArrayEquals(before, dbFile.readBytes())
                verify(exactly = 0) { database.close() }
                if (archiveFails) {
                    assertNoDestructiveStep(fixture, before)
                } else {
                    assertEquals(RestoreJournal.JournalState.FAILED, fixture.journal.readFailureJournal()?.state)
                    assertEquals(RestoreMaintenanceMode.Mode.NORMAL, mockRestoreMaintenanceMode.currentMode())
                    assertTrue(fixture.trace.indexOf("FINALIZED_FAILURE") < fixture.trace.indexOf("EXIT_NORMAL"))
                }
            } finally {
                io.mockk.unmockkConstructor(com.yourname.expensetracker.domain.workers.NoOpWorkerDrainController::class)
                releaseRestoreFixture()
            }
        }
    }

    @Test
    fun write_drain_timeout_aborts_every_restore_API_with_bounded_failure_and_exits_after_finalization() = runTest(testDispatcher) {
        for (operation in supportedRestoreOperations()) {
            val fixture = restoreFixture()
            try {
                val before = dbFile.readBytes()
                // The repository and the stuck writer share ONE barrier over the same mode mock.
                val barrier = com.yourname.expensetracker.data.backup.DatabaseWriteBarrier(mockRestoreMaintenanceMode)
                val repo = DatabaseBackupRepositoryImpl(
                    context = context,
                    database = database,
                    ioDispatcher = testDispatcher,
                    privacyGate = privacyGate,
                    privacySettingsRepository = privacySettingsRepository,
                    backupEncryptionService = backupEncryptionService,
                    exportAnonymizer = exportAnonymizer,
                    secureKeyStorage = secureKeyStorage,
                    receiptAssetStore = mockk(relaxed = true),
                    restoreMaintenanceMode = mockRestoreMaintenanceMode,
                    restoreJournal = fixture.journal,
                    workerDrain = com.yourname.expensetracker.domain.workers.NoOpWorkerDrainController(),
                    restoreDatabaseOpener = object : com.yourname.expensetracker.data.backup.RestoreDatabaseOpener {
                        override fun openFreshDatabase() = database
                    },
                    maintenanceOperationRunner = com.yourname.expensetracker.data.backup.MaintenanceOperationRunner(
                        mockRestoreMaintenanceMode,
                        com.yourname.expensetracker.domain.workers.NoOpWorkerDrainController(),
                        barrier
                    ),
                    snapshotCreator = com.yourname.expensetracker.data.backup.SqliteSnapshotCreator(),
                    restoreInternalWriteScope =
                        com.yourname.expensetracker.data.backup.RestoreInternalWriteScope(mockRestoreMaintenanceMode),
                    operationRunRecorder = mockk(relaxed = true),
                    diagnosticSink = com.yourname.expensetracker.data.backup.TimberMaintenanceSafeDiagnosticSink(),
                    timeProvider = FakeTimeProvider(fixedTime),
                    currencySettingsRepository = io.mockk.mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository>(relaxed = true) {
                io.mockk.every { homeCurrency() } returns kotlinx.coroutines.flow.flowOf("EUR")
            },
            installmentPreBackupReconciler = dagger.Lazy { mockPreBackupReconciler },
                    databaseWriteBarrier = barrier
                )

                val gate = CompletableDeferred<Unit>()
                val writerStarted = CompletableDeferred<Unit>()
                val writer = launch {
                    barrier.runWrite(com.yourname.expensetracker.data.backup.DatabaseAccessOperation("stuck_write")) {
                        writerStarted.complete(Unit)
                        gate.await()
                    }
                }
                writerStarted.await()

                val restore = async { invokeRestore(operation, repo, fixture.source) }
                runCurrent()
                advanceTimeBy(com.yourname.expensetracker.data.backup.DatabaseWriteBarrier.WRITE_DRAIN_TIMEOUT_MS + 1)
                runCurrent()
                val result = restore.await()

                assertTrue(result.isFailure)
                assertTrue(
                    result.exceptionOrNull() is com.yourname.expensetracker.data.backup.WriteDrainTimeoutException
                )
                assertEquals("MAINTENANCE_WRITE_DRAIN_TIMEOUT", result.exceptionOrNull()?.message)
                org.junit.Assert.assertArrayEquals(before, dbFile.readBytes())
                verify(exactly = 0) { database.close() }
                assertEquals(RestoreJournal.JournalState.FAILED, fixture.journal.readFailureJournal()?.state)
                assertEquals(RestoreMaintenanceMode.Mode.NORMAL, mockRestoreMaintenanceMode.currentMode())
                assertTrue(fixture.trace.indexOf("FINALIZED_FAILURE") in 0 until fixture.trace.indexOf("EXIT_NORMAL"))
                assertTrue(
                    fixture.directory.listFiles { f -> f.name.startsWith("expense_tracker_db_import_stage_") }
                        .orEmpty().isEmpty()
                )
                assertTrue(
                    File(fixture.directory, "cache").listFiles { f -> f.name.startsWith("costbackup_extract_") }
                        .orEmpty().isEmpty()
                )

                gate.complete(Unit)
                writer.join()
            } finally {
                releaseRestoreFixture()
            }
        }
    }


    @Test
    fun post_destructive_cancellation_keeps_evidence_and_original_identity_in_all_APIs() = runTest(testDispatcher) {
        for (operation in supportedRestoreOperations()) {
            val fixture = restoreFixture()
            try {
                val cancellation = IdentityCancellationException("TEST_POST_SWAP_CANCELLED")
                if (operation == RestoreOperation.RESET) {
                    val cancelledDelete = object : File(dbFile.path) {
                        override fun delete(): Boolean = throw cancellation
                    }
                    every { context.getDatabasePath(AppDatabase.DATABASE_NAME) } returns cancelledDelete
                } else {
                    every { mockRestoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.RESTORE_VERIFYING) } throws cancellation
                }
                if (operation != RestoreOperation.COSTBACKUP) {
                    every { mockRestoreMaintenanceMode.enterCriticalRecoveryRequired(any()) } answers {
                        every { mockRestoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED
                        throw RestoreMaintenanceMode.PersistenceException()
                    }
                }
                var observed: Throwable? = null
                try { invokeRestore(operation, createRepository(journal = fixture.journal), fixture.source) }
                catch (e: kotlinx.coroutines.CancellationException) { observed = e }
                org.junit.Assert.assertSame(cancellation, observed)
                val retained = fixture.journal.readJournal()!!
                assertTrue(File(retained.safetyBackupPath!!).exists())
                if (operation == RestoreOperation.COSTBACKUP) {
                    // HZ-03 F1 (Q-R32-1): post-swap restore cancellation behaves like a crash -
                    // restart-required exit, no CRITICAL latch, journal kept in its post-swap
                    // state so the next startup runs journal recovery.
                    verify(exactly = 1) { mockRestoreMaintenanceMode.exit(true) }
                    verify(exactly = 0) { mockRestoreMaintenanceMode.exit(false) }
                    verify(exactly = 0) { mockRestoreMaintenanceMode.enterCriticalRecoveryRequired(any()) }
                    assertFalse(fixture.trace.contains("CRITICAL"))
                    assertEquals(
                        RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
                        mockRestoreMaintenanceMode.currentMode()
                    )
                    assertEquals(RestoreJournal.JournalState.VERIFYING, retained.state)
                    assertFalse(fixture.trace.contains("FINALIZED_FAILURE"))
                    // The cancellation cleanup removes only the staged DB trio (db/-wal/-shm).
                    assertTrue(
                        "staged DB trio leaked: ${fixture.directory.list()?.toList()}",
                        fixture.directory.listFiles { f -> f.name.startsWith("expense_tracker_db_import_stage_") }
                            .orEmpty().isEmpty()
                    )
                } else {
                    assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mockRestoreMaintenanceMode.currentMode())
                    verify(exactly = 0) { mockRestoreMaintenanceMode.exit(any()) }
                }
                if (operation == RestoreOperation.COSTBACKUP) {
                    org.junit.Assert.assertArrayEquals(byteArrayOf(9, 8, 7),
                        File(retained.extractTempDirPath!!, "files/receipts/5_photo.jpg").readBytes())
                    assertTrue(File(dbFile.path + ".pre_restore").exists())
                }
            } finally { releaseRestoreFixture() }
        }
    }

    @Test
    fun costbackup_cancellation_during_assets_restoring_exits_restart_required_and_keeps_journal_and_workspace() = runTest(testDispatcher) {
        val fixture = restoreFixture()
        try {
            val cancellation = IdentityCancellationException("TEST_ASSETS_PHASE_CANCELLED")
            every { mockRestoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.ASSETS_RESTORING) } throws cancellation
            var observed: Throwable? = null
            try { invokeRestore(RestoreOperation.COSTBACKUP, createRepository(journal = fixture.journal), fixture.source) }
            catch (e: kotlinx.coroutines.CancellationException) { observed = e }
            org.junit.Assert.assertSame(cancellation, observed)
            verify(exactly = 1) { mockRestoreMaintenanceMode.exit(true) }
            verify(exactly = 0) { mockRestoreMaintenanceMode.exit(false) }
            verify(exactly = 0) { mockRestoreMaintenanceMode.enterCriticalRecoveryRequired(any()) }
            assertEquals(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED, mockRestoreMaintenanceMode.currentMode())
            val retained = fixture.journal.readJournal()!!
            assertEquals(RestoreJournal.JournalState.ASSETS_RESTORING, retained.state)
            assertTrue(File(retained.safetyBackupPath!!).exists())
            assertTrue(File(retained.extractTempDirPath!!).exists())
            assertTrue(File(dbFile.path + ".pre_restore").exists())
        } finally { releaseRestoreFixture() }
    }

    @Test
    fun costbackup_post_swap_cancellation_cleanup_failure_latches_cleanup_failed_and_rethrows_cancellation() = runTest(testDispatcher) {
        val fixture = restoreFixture()
        try {
            val cancellation = IdentityCancellationException("TEST_POST_SWAP_CANCELLED")
            every { mockRestoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.RESTORE_VERIFYING) } throws cancellation
            every { mockRestoreMaintenanceMode.exit(any()) } throws RestoreMaintenanceMode.PersistenceException()
            var observed: Throwable? = null
            try { invokeRestore(RestoreOperation.COSTBACKUP, createRepository(journal = fixture.journal), fixture.source) }
            catch (e: kotlinx.coroutines.CancellationException) { observed = e }
            org.junit.Assert.assertSame(cancellation, observed)
            verify(exactly = 1) { mockRestoreMaintenanceMode.exit(true) }
            verify(exactly = 1) { mockRestoreMaintenanceMode.enterCriticalRecoveryRequired("RESTORE_CANCELLED_CLEANUP_FAILED") }
            assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mockRestoreMaintenanceMode.currentMode())
            assertTrue(fixture.journal.hasJournal())
            assertTrue(File(fixture.journal.readJournal()!!.safetyBackupPath!!).exists())
        } finally { releaseRestoreFixture() }
    }

    @Test
    fun restart_required_mode_blocks_every_restore_API_and_leaves_active_post_swap_journal_untouched() = runTest(testDispatcher) {
        for (operation in supportedRestoreOperations()) {
            val fixture = restoreFixture()
            try {
                val journal = fixture.journal
                var entry = journal.beginJournal("src", "", dbFile.absolutePath)
                entry = journal.transitionTo(entry, RestoreJournal.JournalState.VERIFYING, safetyBackupPath = "safety.db")
                val journalFile = File(fixture.directory, "restore_journal.json")
                val journalBytes = journalFile.readBytes()
                val before = dbFile.readBytes()
                mockRestoreMaintenanceMode.exit(true) // fixture mode -> RESTORE_COMPLETE_RESTART_REQUIRED
                io.mockk.clearMocks(mockRestoreMaintenanceMode, answers = false, childMocks = false)

                val result = invokeRestore(operation, createRepository(journal = journal), fixture.source)

                val error = result.exceptionOrNull()
                assertTrue("$operation: $error", error is com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException)
                assertEquals(
                    DiagnosticReasonCode.RESTORE_BLOCKED.name,
                    (error as com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException).operation.reason
                )
                org.junit.Assert.assertArrayEquals(journalBytes, journalFile.readBytes())
                assertEquals(RestoreJournal.JournalState.VERIFYING, journal.readJournal()?.state)
                assertEquals(entry.operationId, journal.readJournal()?.operationId)
                org.junit.Assert.assertArrayEquals(before, dbFile.readBytes())
                verify(exactly = 0) { mockRestoreMaintenanceMode.enter(any()) }
                verify(exactly = 0) { mockRestoreMaintenanceMode.exit(any()) }
                verify(exactly = 0) { mockRestoreMaintenanceMode.enterCriticalRecoveryRequired(any()) }
                verify(exactly = 0) { database.close() }
                assertEquals(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED, mockRestoreMaintenanceMode.currentMode())
            } finally { releaseRestoreFixture() }
        }
    }

    @Test
    fun active_valid_journal_blocks_every_restore_API_even_in_NORMAL_mode() = runTest(testDispatcher) {
        for (operation in supportedRestoreOperations()) {
            val fixture = restoreFixture()
            try {
                val journal = fixture.journal
                val entry = journal.beginJournal("src", "", dbFile.absolutePath)
                val journalFile = File(fixture.directory, "restore_journal.json")
                val journalBytes = journalFile.readBytes()

                val result = invokeRestore(operation, createRepository(journal = journal), fixture.source)

                assertTrue("$operation", result.exceptionOrNull() is com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException)
                org.junit.Assert.assertArrayEquals(journalBytes, journalFile.readBytes())
                assertEquals(entry.operationId, journal.readJournal()?.operationId)
                verify(exactly = 0) { mockRestoreMaintenanceMode.enter(any()) }
                verify(exactly = 0) { database.close() }
                assertEquals(RestoreMaintenanceMode.Mode.NORMAL, mockRestoreMaintenanceMode.currentMode())
            } finally { releaseRestoreFixture() }
        }
    }

    @Test
    fun restart_required_mode_without_a_journal_blocks_every_restore_API_and_mutates_nothing() = runTest(testDispatcher) {
        assertRestoreBlockedByModeAlone(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED) {
            mockRestoreMaintenanceMode.exit(true) // fixture mode -> RESTORE_COMPLETE_RESTART_REQUIRED
        }
    }

    @Test
    fun critical_recovery_mode_without_a_journal_blocks_every_restore_API_and_mutates_nothing() = runTest(testDispatcher) {
        assertRestoreBlockedByModeAlone(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED) {
            mockRestoreMaintenanceMode.enterCriticalRecoveryRequired("TEST_CRITICAL_MODE")
        }
    }

    /**
     * R32 N1 gate (restoreEntryBlockedOrNull): the blocking mode alone, with NO active journal,
     * must yield RESTORE_BLOCKED and mutate nothing: no journal begun, no mode change, no run recorded.
     */
    private suspend fun assertRestoreBlockedByModeAlone(
        expectedMode: RestoreMaintenanceMode.Mode,
        enterMode: () -> Unit
    ) {
        for (operation in supportedRestoreOperations()) {
            val fixture = restoreFixture()
            try {
                val journal = fixture.journal
                val journalFile = File(fixture.directory, "restore_journal.json")
                enterMode()
                assertEquals(expectedMode, mockRestoreMaintenanceMode.currentMode())
                assertFalse("$operation: precondition, no journal", journalFile.exists())
                assertTrue(journal.readJournalResult() is RestoreJournal.JournalReadResult.Absent)
                val before = dbFile.readBytes()
                val recorder = mockk<OperationRunRecorder>(relaxed = true)
                io.mockk.clearMocks(mockRestoreMaintenanceMode, answers = false, childMocks = false)

                val result = invokeRestore(operation, createRepository(journal = journal, operationRunRecorder = recorder), fixture.source)

                val error = result.exceptionOrNull()
                assertTrue("$operation: $error", error is com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException)
                assertEquals(
                    DiagnosticReasonCode.RESTORE_BLOCKED.name,
                    (error as com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException).operation.reason
                )
                verify(exactly = 0) { journal.beginJournal(any(), any(), any()) }
                assertFalse("$operation: no journal may be created", journalFile.exists())
                org.junit.Assert.assertArrayEquals(before, dbFile.readBytes())
                verify(exactly = 0) { mockRestoreMaintenanceMode.enter(any()) }
                verify(exactly = 0) { mockRestoreMaintenanceMode.exit(any()) }
                verify(exactly = 0) { mockRestoreMaintenanceMode.enterCriticalRecoveryRequired(any()) }
                verify(exactly = 0) { database.close() }
                coVerify(exactly = 0) { recorder.start(any(), any(), any()) }
                assertEquals(expectedMode, mockRestoreMaintenanceMode.currentMode())
            } finally { releaseRestoreFixture() }
        }
    }

    @Test
    fun reset_sidecar_delete_failures_and_failed_reopen_do_not_claim_success() = runTest(testDispatcher) {
        for (fault in listOf("WAL", "SHM", "REOPEN")) {
            val fixture = restoreFixture()
            try {
                var rollingBack = false
                every { fixture.journal.transitionTo(any(), RestoreJournal.JournalState.ROLLING_BACK, any(), any()) } answers {
                    val result = callOriginal()
                    rollingBack = true
                    result
                }
                if (fault == "REOPEN") {
                    every { AppDatabase.fileBuilder(context).build() } throws IllegalStateException("TEST_REOPEN_FAILED")
                } else every { database.close() } answers {
                    fixture.trace += "CLOSE_LIVE"
                    if (!rollingBack) {
                        val suffix = if (fault == "WAL") "-wal" else "-shm"
                        File(dbFile.path + suffix).apply { mkdirs(); File(this, "blocker").writeText("TEST_DELETE_BLOCKED") }
                    }
                }
                every { openHelper.writableDatabase } answers {
                    if (rollingBack) throw IllegalStateException("TEST_RECOVERY_VERIFY_FAILED")
                    supportDb
                }
                val result = createRepository(journal = fixture.journal).resetDatabase()
                assertTrue(result.isFailure)
                assertTrue(rollingBack)
                assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mockRestoreMaintenanceMode.currentMode())
                verify(exactly = 0) { mockRestoreMaintenanceMode.exit(any()) }
                assertTrue(fixture.journal.hasJournal())
                assertTrue(File(fixture.journal.readJournal()!!.safetyBackupPath!!).exists())
            } finally { releaseRestoreFixture() }
        }
    }

    @Test
    fun databaseStatsBlockedModesDoNotReachAnyDao() = runTest(testDispatcher) {
        for (mode in RestoreMaintenanceMode.Mode.values().filter { it != RestoreMaintenanceMode.Mode.NORMAL }) {
            every { mockRestoreMaintenanceMode.currentMode() } returns mode
            val failure = runCatching { repository.getDatabaseStats() }.exceptionOrNull()
            assertTrue(failure is com.yourname.expensetracker.domain.backup.DatabaseStatsUnavailableException)
            assertEquals(
                com.yourname.expensetracker.domain.backup.DatabaseStatsFailureReason.READ_BLOCKED,
                (failure as com.yourname.expensetracker.domain.backup.DatabaseStatsUnavailableException).reason
            )
        }
        io.mockk.verify(exactly = 0) { database.expenseDao() }
        io.mockk.verify(exactly = 0) { database.categoryDao() }
        io.mockk.verify(exactly = 0) { database.merchantCategoryDao() }
        io.mockk.verify(exactly = 0) { database.pendingReviewDao() }
    }

    @Test
    fun databaseStatsQueryFailureIsNotSuccessfulZeroCounts() = runTest(testDispatcher) {
        every { mockRestoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        coEvery { database.expenseDao().getTotalCount() } throws
            android.database.sqlite.SQLiteException("SECRET database path and financial payload")
        val failure = runCatching { repository.getDatabaseStats() }.exceptionOrNull()
        assertTrue(failure is com.yourname.expensetracker.domain.backup.DatabaseStatsUnavailableException)
        assertEquals("QUERY_FAILED", failure?.message)
        assertNull(failure?.cause)
        assertEquals(
            com.yourname.expensetracker.domain.backup.DatabaseStatsFailureReason.QUERY_FAILED,
            (failure as com.yourname.expensetracker.domain.backup.DatabaseStatsUnavailableException).reason
        )
    }

    @Test
    fun databaseStatsPreservesCancellationIdentity() = runTest(testDispatcher) {
        every { mockRestoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        val cancellation = IdentityCancellationException("test cancellation")
        coEvery { database.expenseDao().getTotalCount() } throws cancellation
        org.junit.Assert.assertSame(cancellation, runCatching { repository.getDatabaseStats() }.exceptionOrNull())
    }

    @Test
    fun databaseStatsValidEmptyAndNonemptyReadsRemainSuccessful() = runTest(testDispatcher) {
        every { mockRestoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        for (count in listOf(0, 7)) {
            coEvery { database.expenseDao().getTotalCount() } returns count
            coEvery { database.categoryDao().getCount() } returns count
            coEvery { database.merchantCategoryDao().getCount() } returns count
            coEvery { database.pendingReviewDao().getPendingCount() } returns count
            val stats = repository.getDatabaseStats()
            assertEquals(count, stats.transactionCount)
            assertEquals(count, stats.categoryCount)
            assertEquals(count, stats.merchantCount)
            assertEquals(count, stats.pendingReviewCount)
        }
    }

    private fun createRepository(
        stagedVerifier: suspend (Context, String, File, Int, DatabaseImportSummary) -> DatabaseImportSummary = { _, _, _, _, summary -> summary },
        liveVerifier: suspend (AppDatabase, File, Int, DatabaseImportSummary) -> DatabaseImportSummary = { _, _, _, summary -> summary },
        encryptionService: BackupEncryptionService = backupEncryptionService,
        timeProvider: TimeProvider = FakeTimeProvider(fixedTime),
        journal: RestoreJournal = mockRestoreJournal,
        operationRunRecorder: OperationRunRecorder = mockk(relaxed = true),
        gate: PrivacyGate = privacyGate,
        currencySettings: com.yourname.expensetracker.domain.currency.CurrencySettingsRepository =
            io.mockk.mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository>(relaxed = true) {
                io.mockk.every { homeCurrency() } returns kotlinx.coroutines.flow.flowOf("EUR")
            }
    ): DatabaseBackupRepositoryImpl {
        return DatabaseBackupRepositoryImpl(
            context = context,
            database = database,
            ioDispatcher = testDispatcher,
            privacyGate = gate,
            privacySettingsRepository = privacySettingsRepository,
            backupEncryptionService = encryptionService,
            exportAnonymizer = exportAnonymizer,
            secureKeyStorage = secureKeyStorage,
            receiptAssetStore = mockk(relaxed = true),
            restoreMaintenanceMode = mockRestoreMaintenanceMode,
            restoreJournal = journal,
            stagedImportVerifier = stagedVerifier,
            liveImportVerifier = liveVerifier,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettings,
            installmentPreBackupReconciler = dagger.Lazy { mockPreBackupReconciler },
            operationRunRecorder = operationRunRecorder
        )
    }

    private fun checkpointCursor(busyCode: Int): MatrixCursor {
        return MatrixCursor(arrayOf("busy")).apply {
            addRow(arrayOf(busyCode))
        }
    }

    private fun createSqliteDatabase(
        file: File,
        schemaVersion: Int = APP_DATABASE_SCHEMA_VERSION,
        expenseCount: Int,
        categoryCount: Int,
        merchantCount: Int,
        pendingCount: Int,
        budgetCount: Int
    ) {
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("PRAGMA user_version = $schemaVersion")
            db.execSQL("CREATE TABLE IF NOT EXISTS expenses (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS merchant_categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_reviews (id INTEGER PRIMARY KEY AUTOINCREMENT, status TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS budgets (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL)")

            repeat(expenseCount) { db.execSQL("INSERT INTO expenses(amount) VALUES (${it + 1}.0)") }
            repeat(categoryCount) { db.execSQL("INSERT INTO categories(name) VALUES ('cat_$it')") }
            repeat(merchantCount) { db.execSQL("INSERT INTO merchant_categories(name) VALUES ('merchant_$it')") }
            repeat(pendingCount) { db.execSQL("INSERT INTO pending_reviews(status) VALUES ('PENDING')") }
            repeat(budgetCount) { db.execSQL("INSERT INTO budgets(amount) VALUES (${it + 10}.0)") }
        } finally {
            db.close()
        }
    }

    /**
     * UF-46 S2: creates [file] on the REAL exported Room schema (152.json) through [RealSchemaFixture], seeded with
     * fixture F (6 expenses, 2 recurring rules; plus a flagged PURCHASE when [withFlaggedPurchase]). Every table
     * tracked by [BackupVerifier] exists, so the strict backup pipeline (`collectTableCountsStrict` +
     * `BackupVerifier.verify`) passes, `createCostBackup` reaches `CostbackupBundle.create`, and the semantic
     * capture sees real columns (CAPTURED). Use [createIdOnlySchemaDatabase] only where the id-only shape is required.
     */
    private fun createFullSchemaDatabase(file: File, withFlaggedPurchase: Boolean = false) {
        createRealSchemaDatabase(file) { db ->
            RealSchemaFixture.seedFixtureF(db, withFlaggedPurchase = withFlaggedPurchase)
        }
    }

    /** Real-schema database at [file] whose rows are written by [seed]; the helper-owned original is removed. */
    private fun createRealSchemaDatabase(file: File, seed: (SupportSQLiteDatabase) -> Unit) {
        file.parentFile?.mkdirs()
        val created = RealSchemaFixture.createSeeded(
            realSchemaHelper, "rsv-${fixtureSeq++}", seed = seed
        )
        created.copyTo(file, overwrite = true)
        created.delete()
    }

    /**
     * The pre-S2 hand-rolled fixture, kept unchanged under an honest name: every table tracked by [BackupVerifier]
     * (one per AppDatabase entity, see TABLE_TIERS) with only an `id` column (plus the few reference columns the
     * verifier and anonymizer read) and one `expenses` row. The pipeline passes, but the expenses and recurring
     * tables have no amount, currency or type columns, so the semantic capture cannot read them: this is the
     * deliberate "capture query fails" shape for AE-5.
     */
    private fun createIdOnlySchemaDatabase(file: File) {
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("PRAGMA user_version = $APP_DATABASE_SCHEMA_VERSION")
            for (tableName in BackupVerifier.allTableNames()) {
                val referenceColumn = when (tableName) {
                    "receipt_expense_links" -> ", expenseId INTEGER"
                    "recurring_occurrences" -> ", sourceId INTEGER"
                    "budget_forecasts" -> ", budgetId INTEGER"
                    // Columns read by the loan installment orphan checks (BackupVerifier) and the
                    // plan label sanitizer (ExportAnonymizer).
                    "expenses" -> ", spendingExclusionReason TEXT, refundNettedAmount REAL NOT NULL DEFAULT 0.0"
                    "expense_refund_links" -> ", refundExpenseId INTEGER, purchaseExpenseId INTEGER, appliedAmount REAL"
                    "installment_plans" -> ", purchaseExpenseId INTEGER, displayName TEXT, lenderLabel TEXT"
                    "installment_entries" -> ", planId INTEGER, linkedExpenseId INTEGER, status TEXT"
                    else -> ""
                }
                db.execSQL("CREATE TABLE IF NOT EXISTS \"$tableName\" (id INTEGER PRIMARY KEY$referenceColumn)")
            }
            // One expense row so the manifest tableCounts carries real data.
            db.execSQL("INSERT INTO expenses (id) VALUES (1)")
        } finally {
            db.close()
        }
    }

    private fun createLegacyCompatibleDatabaseMissingOptionalTables(
        file: File,
        schemaVersion: Int,
        expenseCount: Int,
        categoryCount: Int
    ) {
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("PRAGMA user_version = $schemaVersion")
            db.execSQL("CREATE TABLE IF NOT EXISTS expenses (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")

            repeat(expenseCount) { db.execSQL("INSERT INTO expenses(amount) VALUES (${it + 1}.0)") }
            repeat(categoryCount) { db.execSQL("INSERT INTO categories(name) VALUES ('cat_$it')") }
        } finally {
            db.close()
        }
    }

    private fun countRows(file: File, table: String): Int {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } finally {
            db.close()
        }
    }

    private fun trackedSummary(file: File): DatabaseImportSummary {
        return DatabaseImportSummary(
            transactionCount = countRows(file, "expenses"),
            categoryCount = countRows(file, "categories"),
            merchantCount = countRows(file, "merchant_categories"),
            pendingReviewCount = countRows(file, "pending_reviews"),
            budgetCount = countRows(file, "budgets")
        )
    }

    private fun createSchema86DatabaseWithStaleBudgets(file: File, budgetCount: Int) {
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("PRAGMA user_version = 86")
            db.execSQL("CREATE TABLE IF NOT EXISTS expenses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, amount REAL NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS merchant_categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_reviews (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, status TEXT)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS budgets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    categoryId INTEGER,
                    amount REAL NOT NULL,
                    period TEXT NOT NULL,
                    periodMode TEXT NOT NULL DEFAULT 'MONTHLY',
                    startDate INTEGER NOT NULL,
                    isActive INTEGER NOT NULL DEFAULT 0,
                    notifyAtWarning REAL NOT NULL DEFAULT 0.80,
                    notifyAtCritical REAL NOT NULL DEFAULT 0.95,
                    rollover INTEGER NOT NULL DEFAULT 1,
                    createdAt INTEGER NOT NULL,
                    lastWarningNotifiedAt INTEGER,
                    lastCriticalNotifiedAt INTEGER,
                    lastExceededNotifiedAt INTEGER,
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId)")

            db.execSQL("INSERT INTO expenses(amount) VALUES (15.0)")
            db.execSQL("INSERT INTO expenses(amount) VALUES (20.0)")
            db.execSQL("INSERT INTO categories(id, name) VALUES (1, 'cat_1')")
            db.execSQL("INSERT INTO merchant_categories(name) VALUES ('merchant_1')")
            db.execSQL("INSERT INTO pending_reviews(status) VALUES ('PENDING')")
            repeat(budgetCount) { i ->
                db.execSQL(
                    """
                    INSERT INTO budgets (
                        categoryId, amount, period, periodMode, startDate, isActive,
                        notifyAtWarning, notifyAtCritical, rollover, createdAt,
                        lastWarningNotifiedAt, lastCriticalNotifiedAt, lastExceededNotifiedAt
                    ) VALUES (1, ${100 + i}.0, 'MONTHLY', 'ROLLING', 1700000000000, 1, 0.75, 0.9, 0, 1700000000000, NULL, NULL, NULL)
                    """.trimIndent()
                )
            }
        } finally {
            db.close()
        }
    }

    private fun createSchema86DatabaseWithInvalidBudgetsColumns(file: File) {
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("PRAGMA user_version = 86")
            db.execSQL("CREATE TABLE IF NOT EXISTS expenses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, amount REAL NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS merchant_categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_reviews (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, status TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS budgets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, amount REAL NOT NULL)")

            db.execSQL("INSERT INTO expenses(amount) VALUES (10.0)")
            db.execSQL("INSERT INTO categories(name) VALUES ('cat_1')")
            db.execSQL("INSERT INTO merchant_categories(name) VALUES ('merchant_1')")
            db.execSQL("INSERT INTO pending_reviews(status) VALUES ('PENDING')")
            db.execSQL("INSERT INTO budgets(amount) VALUES (50.0)")
        } finally {
            db.close()
        }
    }

    private fun createSchema86DatabaseWithBadBudgetsIndexUniqueness(file: File) {
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("PRAGMA user_version = 86")
            db.execSQL("CREATE TABLE IF NOT EXISTS expenses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, amount REAL NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS merchant_categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_reviews (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, status TEXT)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS budgets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    categoryId INTEGER,
                    amount REAL NOT NULL,
                    period TEXT NOT NULL,
                    periodMode TEXT NOT NULL DEFAULT 'ROLLING',
                    startDate INTEGER NOT NULL,
                    isActive INTEGER NOT NULL DEFAULT 1,
                    notifyAtWarning REAL NOT NULL DEFAULT 0.75,
                    notifyAtCritical REAL NOT NULL DEFAULT 0.9,
                    rollover INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    lastWarningNotifiedAt INTEGER,
                    lastCriticalNotifiedAt INTEGER,
                    lastExceededNotifiedAt INTEGER,
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive)")

            db.execSQL("INSERT INTO expenses(amount) VALUES (10.0)")
            db.execSQL("INSERT INTO categories(id, name) VALUES (1, 'cat_1')")
            db.execSQL("INSERT INTO merchant_categories(name) VALUES ('merchant_1')")
            db.execSQL("INSERT INTO pending_reviews(status) VALUES ('PENDING')")
            db.execSQL(
                """
                INSERT INTO budgets (
                    categoryId, amount, period, periodMode, startDate, isActive,
                    notifyAtWarning, notifyAtCritical, rollover, createdAt,
                    lastWarningNotifiedAt, lastCriticalNotifiedAt, lastExceededNotifiedAt
                ) VALUES (1, 100.0, 'MONTHLY', 'ROLLING', 1700000000000, 1, 0.75, 0.9, 0, 1700000000000, NULL, NULL, NULL)
                """.trimIndent()
            )
        } finally {
            db.close()
        }
    }

    private fun isSchema86WithStaleBudgets(file: File): Boolean {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            val schemaVersion = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            if (schemaVersion != 86) return false

            val defaults = mutableMapOf<String, String?>()
            db.rawQuery("PRAGMA table_info('budgets')", null).use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                val defaultIdx = cursor.getColumnIndexOrThrow("dflt_value")
                while (cursor.moveToNext()) {
                    defaults[cursor.getString(nameIdx)] = cursor.getString(defaultIdx)
                }
            }

            val hasExpectedDefaults =
                defaults["periodMode"] == "'ROLLING'" &&
                    defaults["isActive"] == "1" &&
                    defaults["notifyAtWarning"] == "0.75" &&
                    defaults["notifyAtCritical"] == "0.9" &&
                    defaults["rollover"] == "0"

            val hasCategoryIndex = indexExists(file, "index_budgets_categoryId")
            val hasIsActiveIndex = indexExists(file, "index_budgets_isActive")

            !(hasExpectedDefaults && hasCategoryIndex && hasIsActiveIndex)
        } finally {
            db.close()
        }
    }

    private fun indexExists(file: File, indexName: String): Boolean {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name=?",
                arrayOf(indexName)
            ).use { cursor ->
                cursor.moveToFirst()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `database is hot-swapped after import via AppDatabase fileBuilder`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        // Setup: create destination and source databases
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )
        val sourceBackup = File(tempDir, "source_backup.db")
        createSqliteDatabase(
            file = sourceBackup,
            expenseCount = 3,
            categoryCount = 2,
            merchantCount = 4,
            pendingCount = 1,
            budgetCount = 2
        )

        // Mock AppDatabase.fileBuilder so we can verify it's called for the P7-P1-01 hot-swap
        val freshDatabase = mockk<AppDatabase>(relaxed = true)
        mockkObject(AppDatabase.Companion)
        try {
            every { AppDatabase.fileBuilder(any()).build() } returns freshDatabase

            val result = repository.importDatabase(sourceBackup)

            assertTrue(result.isSuccess)

            // P7-P1-01: Verify hot-swap occurred — AppDatabase.fileBuilder was called
            // to create a fresh Room instance after the file swap
            verify(atLeast = 1) { AppDatabase.fileBuilder(any()) }
        } finally {
            unmockkObject(AppDatabase.Companion)
        }
    }

    @Test
    fun `export backup filename timestamp is derived from timeProvider`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertRawExportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 2,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )

        val result = repository.exportDatabase()

        assertTrue(result.isSuccess)
        val backupFile = result.getOrNull()
        assertNotNull(backupFile)
        // T2B: the user-visible timestamped backup name must come from the injected
        // TimeProvider (FakeTimeProvider fixedTime), never LocalDateTime.now().
        val expectedTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .format(Instant.ofEpochMilli(fixedTime).atZone(ZoneId.systemDefault()).toLocalDateTime())
        assertEquals("expense_tracker_backup_${expectedTimestamp}.db", backupFile!!.name)
    }

    @Test
    fun `createCostBackup stamps CountingTimeProvider timestamp into manifest createdAt`() = runTest(testDispatcher) {
        // Full table schema (every TABLE_TIERS table) so the strict backup pipeline (collectTableCountsStrict +
        // BackupVerifier.verify) passes and the flow actually reaches CostbackupBundle.create.
        createFullSchemaDatabase(file = dbFile)
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        every { context.cacheDir } returns cacheDir

        // Dedicated counting provider: its counter reflects ONLY createCostBackup's
        // snapshot/timestamped bundle path, so no unrelated TimeProvider call from
        // other repository operations can pollute the "called exactly once" assertion.
        val countingTime = CountingTimeProvider(startTime = fixedTime)

        // Real crypto so the produced bundle is extractable and its manifest can be read back.
        // One low-iteration instance serves both the repository's create and this test's extract.
        val encryption = lowIterationBackupEncryptionService()
        val repo = createRepository(
            stagedVerifier = { _, _, _, _, summary -> summary },
            encryptionService = encryption,
            timeProvider = countingTime
        )

        val result = repo.createCostBackup(
            password = "t2b_timestamp_password",
            includeReceiptImages = false,
            redacted = true,
            privacyMode = null
        )

        assertTrue("createCostBackup should produce a real bundle", result.isSuccess)
        val bundleFile = result.getOrNull()!!

        // T2B: the snapshot/timestamped bundle path must call timeProvider.now() exactly once.
        assertEquals(
            "timeProvider.now() must be called exactly once for the snapshot/timestamped bundle path",
            1,
            countingTime.callCount
        )
        val snapshotEpochMs = countingTime.firstValue

        // T2B: the single snapshotEpochMs = timeProvider.now() capture must drive BOTH the
        // user-visible bundle name and the nowEpochMs argument to CostbackupBundle.create.
        val expectedTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .format(Instant.ofEpochMilli(snapshotEpochMs).atZone(ZoneId.systemDefault()).toLocalDateTime())
        assertTrue(
            "bundle name must embed the snapshot capture timestamp (name uses the same capture)",
            bundleFile.name.startsWith("expense_tracker_backup_${expectedTimestamp}_")
        )
        assertTrue(bundleFile.name.endsWith(".costbackup"))

        // Extract with an unrelated nowEpochMs sentinel (0L): a stored createdAt must win,
        // proving the manifest was stamped from CountingTimeProvider, never a second/uncontrolled clock.
        val extractDir = File(tempDir, "extract_created_at")
        val extraction = CostbackupBundle.extract(
            bundleFile, extractDir, "t2b_timestamp_password",
            nowEpochMs = 0L,
            encryptionService = encryption
        )
        assertTrue("bundle should extract with the same password", extraction.isSuccess)
        assertEquals(
            "manifest createdAt must be the exact snapshot capture value",
            snapshotEpochMs,
            extraction.getOrNull()?.manifest?.createdAt
        )

        runCatching { bundleFile.delete() }
    }

    // ── FL-07: home currency written into the export manifest ──

    private suspend fun exportAndReadManifest(
        settings: com.yourname.expensetracker.domain.currency.CurrencySettingsRepository,
        label: String
    ): CostbackupBundle.BackupManifest {
        createFullSchemaDatabase(file = dbFile)
        every { context.cacheDir } returns File(tempDir, "cache").apply { mkdirs() }
        val encryption = lowIterationBackupEncryptionService()
        val repo = createRepository(encryptionService = encryption, currencySettings = settings)

        val result = repo.createCostBackup(
            password = "fl07_export_password",
            includeReceiptImages = false,
            redacted = true,
            privacyMode = null
        )

        assertTrue("backup must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        return extractExport(result.getOrThrow(), "fl07_export_password", encryption, label).manifest
    }

    @Test
    fun `FL07 export writes the current home currency into the manifest`() = runTest(testDispatcher) {
        val settings = relaxedCurrencySettings()

        val manifest = exportAndReadManifest(settings, "fl07_eur")

        assertEquals("EUR", manifest.homeCurrency)
    }

    @Test
    fun `FL07 export writes a non default home currency, proving it is read not assumed`() = runTest(testDispatcher) {
        val settings = mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository>(relaxed = true) {
            every { homeCurrency() } returns flowOf("GBP")
        }

        val manifest = exportAndReadManifest(settings, "fl07_gbp")

        assertEquals("GBP", manifest.homeCurrency)
    }

    @Test
    fun `FL07 export read failure leaves the manifest key out and the backup still succeeds`() = runTest(testDispatcher) {
        val settings = mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository>(relaxed = true) {
            every { homeCurrency() } returns kotlinx.coroutines.flow.flow<String> {
                throw IllegalStateException("synthetic read failure")
            }
        }

        val manifest = exportAndReadManifest(settings, "fl07_read_failure")

        assertNull(manifest.homeCurrency)
    }

    // ── UF-46 S2: semantic capture at export (INV-5, INV-6, INV-14, INV-16; AE-5, AE-6, AE-7, AE-15, AE-18) ──

    private class RecordedEvent(
        val stage: String,
        val outcome: EventOutcome,
        val reasonCode: DiagnosticReasonCode?,
        val metadataJson: String
    )

    private class RecordedRun(
        val recorder: OperationRunRecorder,
        val handle: OperationRunHandle,
        val events: MutableList<RecordedEvent>
    ) {
        fun semanticEvents(): List<RecordedEvent> = events.filter { it.stage == "SEMANTIC_CAPTURED" }
    }

    private fun recordingRun(): RecordedRun {
        val events = mutableListOf<RecordedEvent>()
        val handle = mockk<OperationRunHandle>(relaxed = true)
        coEvery { handle.event(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            events += RecordedEvent(
                stage = arg<String>(0),
                outcome = arg<EventOutcome>(1),
                reasonCode = arg<DiagnosticReasonCode?>(2),
                metadataJson = arg<SafeEventMetadata>(4).toJson()
            )
            Unit
        }
        val recorder = mockk<OperationRunRecorder>(relaxed = true)
        coEvery { recorder.start(any(), any(), any()) } returns handle
        return RecordedRun(recorder, handle, events)
    }

    private suspend fun exportToFile(
        recorded: RecordedRun,
        encryption: BackupEncryptionService,
        password: String,
        redacted: Boolean = false
    ): Result<File> {
        every { context.cacheDir } returns File(tempDir, "cache").apply { mkdirs() }
        val repo = createRepository(encryptionService = encryption, operationRunRecorder = recorded.recorder)
        return repo.createCostBackup(
            password = password,
            includeReceiptImages = false,
            redacted = redacted,
            privacyMode = null
        )
    }

    private fun extractExport(
        bundle: File,
        password: String,
        encryption: BackupEncryptionService,
        label: String
    ): CostbackupBundle.ExtractionResult = CostbackupBundle.extract(
        bundle, File(tempDir, "extract_$label"), password, nowEpochMs = 0L, encryptionService = encryption
    ).getOrThrow()

    private fun assertSingleBoundedSemanticEvent(recorded: RecordedRun, expectedCode: SemanticCode) {
        val events = recorded.semanticEvents()
        assertEquals("exactly one SEMANTIC_CAPTURED event", 1, events.size)
        val event = events.single()
        assertEquals(EventOutcome.SKIPPED, event.outcome)
        assertEquals(DiagnosticReasonCode.VALIDATION_FAILED, event.reasonCode)
        val metadata = org.json.JSONObject(event.metadataJson)
        val keys = mutableSetOf<String>()
        metadata.keys().forEach { keys += it }
        assertEquals("only the bounded code may be carried", setOf("semanticCode"), keys)
        assertEquals(expectedCode.name, metadata.getString("semanticCode"))
        val forbidden = RealSchemaFixture.forbiddenValueText + listOf(
            "no such column", "FROM", "WHERE", tempDir.absolutePath, "secret"
        )
        forbidden.forEach { assertFalse("event must not contain '$it'", event.metadataJson.contains(it)) }
    }

    private fun assertNotCaptured(
        section: SemanticAggregateSet?,
        expected: SemanticNotCapturedReason
    ) {
        assertNotNull("a NOT_CAPTURED section must be written, not omitted", section)
        assertEquals(SemanticCaptureStatus.NOT_CAPTURED, section!!.status)
        assertEquals(expected, section.notCapturedReason)
        assertTrue("a partial CAPTURED section is never written", section.aggregates.isEmpty())
    }

    @Test
    fun `fixture schema version matches the app schema version and the upgraded fixture is stamped with it`() {
        createFullSchemaDatabase(file = dbFile)

        assertEquals(APP_DATABASE_SCHEMA_VERSION, RealSchemaFixture.SCHEMA_VERSION)
        val userVersion = RealSchemaFixture.readOnly(dbFile) { db ->
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }
        assertEquals(APP_DATABASE_SCHEMA_VERSION, userVersion)
    }

    @Test
    fun `INV5 export of fixture F bundles a captured section that round trips`() = runTest(testDispatcher) {
        createFullSchemaDatabase(file = dbFile)
        val encryption = lowIterationBackupEncryptionService()
        val recorded = recordingRun()

        val result = exportToFile(recorded, encryption, "semantic_captured_password")

        assertTrue("backup must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val extracted = extractExport(result.getOrThrow(), "semantic_captured_password", encryption, "captured")
        val section = extracted.manifest.semanticAggregates
        assertNotNull("the section must be present in the bundle manifest", section)
        assertFalse(extracted.manifest.semanticSectionMalformed)
        // Hand-computed expectations of fixture F (spec section 7), independent of production SQL.
        RealSchemaFixture.assertFixtureF(section!!)
        // Conservation against the manifest counts: the raw buckets add up to the expense row count.
        assertEquals(6, extracted.manifest.tableCounts["expenses"])
        assertEquals(
            6,
            section.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY).buckets.sumOf { it.n }
        )
        // The section describes the bundled bytes: recomputing from the bundled database gives the same set.
        assertEquals(RealSchemaFixture.capture(extracted.dbFile), section)
        assertTrue("a captured section emits no SEMANTIC_CAPTURED event", recorded.semanticEvents().isEmpty())
        coVerify(exactly = 1) { recorded.handle.success() }
    }

    @Test
    fun `INV5 export of fixture F with a flagged purchase captures the exclusion rule`() = runTest(testDispatcher) {
        createFullSchemaDatabase(file = dbFile, withFlaggedPurchase = true)
        val encryption = lowIterationBackupEncryptionService()
        val recorded = recordingRun()

        val result = exportToFile(recorded, encryption, "semantic_flagged_password")

        assertTrue(result.isSuccess)
        val extracted = extractExport(result.getOrThrow(), "semantic_flagged_password", encryption, "flagged")
        // The flagged row counts in n and adds 0 to the spending sum (D-SIS), but is in the raw sum.
        RealSchemaFixture.assertFixtureF(extracted.manifest.semanticAggregates!!, withFlaggedPurchase = true)
        assertEquals(7, extracted.manifest.tableCounts["expenses"])
    }

    @Test
    fun `INV5 SAF export carries the captured semantic section`() = runTest(testDispatcher) {
        createFullSchemaDatabase(file = dbFile)
        every { context.cacheDir } returns File(tempDir, "cache").apply { mkdirs() }
        val resolver = mockk<ContentResolver>()
        val output = ByteArrayOutputStream()
        val destination = android.net.Uri.parse("content://backup/semantic-saf")
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(destination, "wt") } returns output
        val encryption = lowIterationBackupEncryptionService()
        val recorded = recordingRun()
        val repo = createRepository(encryptionService = encryption, operationRunRecorder = recorded.recorder)

        val result = repo.createCostBackup(
            destination = destination,
            password = "semantic_saf_password",
            includeReceiptImages = false,
            redacted = false,
            privacyMode = null
        )

        assertTrue(result.isSuccess)
        val bundle = File(tempDir, "saf_semantic.costbackup").apply { writeBytes(output.toByteArray()) }
        val section = extractExport(bundle, "semantic_saf_password", encryption, "saf").manifest.semanticAggregates
        assertNotNull(section)
        RealSchemaFixture.assertFixtureF(section!!)
    }

    @Test
    fun `AE7 capture reads the snapshot after sanitize and equals the bundled database`() = runTest(testDispatcher) {
        createFullSchemaDatabase(file = dbFile)
        // Stand-in for any write to the snapshot after the raw copy (sanitize, loan S6c reconcile): E1 12.5 -> 13.0.
        every { exportAnonymizer.sanitizeExport(any()) } answers {
            RealSchemaFixture.mutate(firstArg<File>(), "UPDATE expenses SET amount = 13.0 WHERE id = 1")
        }
        val encryption = lowIterationBackupEncryptionService()
        val recorded = recordingRun()

        val result = exportToFile(recorded, encryption, "semantic_order_password", redacted = true)

        assertTrue(result.isSuccess)
        verify(exactly = 1) { exportAnonymizer.sanitizeExport(any()) }
        val extracted = extractExport(result.getOrThrow(), "semantic_order_password", encryption, "order")
        val section = extracted.manifest.semanticAggregates!!
        assertEquals(SemanticCaptureStatus.CAPTURED, section.status)
        // Hand-computed: PURCHASE EUR raw = 13.0 + 40.0 = 53.0 (was 52.5); spending EUR = 13.0 + 40.0 * 50 / 100 = 33.0.
        val purchaseEur = section.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY)
            .buckets.single { it.key == listOf("PURCHASE", "EUR") }
        assertEquals(2, purchaseEur.n)
        assertEquals(0, java.math.BigDecimal("53").compareTo(purchaseEur.s))
        val spendingEur = section.aggregates.getValue(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY)
            .buckets.single { it.key == listOf("EUR") }
        assertEquals(0, java.math.BigDecimal("33").compareTo(spendingEur.s))
        assertEquals(RealSchemaFixture.capture(extracted.dbFile), section)
    }

    @Test
    fun `AE5 capture query failure degrades to NOT_CAPTURED and the backup still succeeds`() = runTest(testDispatcher) {
        createIdOnlySchemaDatabase(dbFile)
        val encryption = lowIterationBackupEncryptionService()
        val recorded = recordingRun()

        val result = exportToFile(recorded, encryption, "semantic_query_failure_password")

        assertTrue("capture failure must not fail the backup: ${result.exceptionOrNull()}", result.isSuccess)
        val extracted = extractExport(result.getOrThrow(), "semantic_query_failure_password", encryption, "queryfail")
        assertNotCaptured(extracted.manifest.semanticAggregates, SemanticNotCapturedReason.SEMANTIC_CAPTURE_FAILED)
        assertFalse(extracted.manifest.semanticSectionMalformed)
        // m1: the degraded section is still WRITTEN (key present, parsed non-null), so the restore-side marker
        // (semanticAggregates != null) keeps requiring the nine gated counts for this bundle.
        assertNotNull(
            "the semanticAggregates key must be written even when the capture is degraded",
            extracted.manifest.semanticAggregates
        )
        val gatedCountsInDegradedBundle = listOf(
            "source_stats_events", "warranty_lifecycle_events", "investment_transactions", "group_settlements",
            "group_lifecycle_events", "entity_source_links", "bank_statement_import_runs",
            "bank_statement_import_items", "negotiation_outcomes"
        )
        gatedCountsInDegradedBundle.forEach {
            assertTrue("degraded bundle must still count $it", extracted.manifest.tableCounts.containsKey(it))
        }
        assertEquals("the bundle is otherwise complete", 1, extracted.manifest.tableCounts["expenses"])
        assertSingleBoundedSemanticEvent(recorded, SemanticCode.SEMANTIC_CAPTURE_FAILED)
        coVerify(exactly = 1) { recorded.handle.success() }
        coVerify(exactly = 0) { recorded.handle.failedFinal(any(), any()) }
    }

    @Test
    fun `AE5 a non cancellation failure inside capture degrades without leaking its message`() = runTest(testDispatcher) {
        createFullSchemaDatabase(file = dbFile)
        val encryption = lowIterationBackupEncryptionService()
        val recorded = recordingRun()
        mockkObject(SemanticAggregates)
        try {
            every { SemanticAggregates.capture(any(), any()) } throws
                IllegalStateException("secret 12.5 EUR SELECT amount FROM expenses")

            val result = exportToFile(recorded, encryption, "semantic_throw_password")

            assertTrue("capture failure must not fail the backup: ${result.exceptionOrNull()}", result.isSuccess)
            val extracted = extractExport(result.getOrThrow(), "semantic_throw_password", encryption, "throw")
            assertNotCaptured(extracted.manifest.semanticAggregates, SemanticNotCapturedReason.SEMANTIC_CAPTURE_FAILED)
            assertSingleBoundedSemanticEvent(recorded, SemanticCode.SEMANTIC_CAPTURE_FAILED)
        } finally {
            unmockkObject(SemanticAggregates)
        }
    }

    @Test
    fun `AE5 cancellation during capture propagates with identity and releases the barrier`() = runTest(testDispatcher) {
        createFullSchemaDatabase(file = dbFile)
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        every { context.cacheDir } returns cacheDir
        every { context.contentResolver } returns mockk<ContentResolver>()
        val cancellation = IdentityCancellationException("cancelled during capture")
        val recorded = recordingRun()
        val repo = createRepository(
            encryptionService = lowIterationBackupEncryptionService(),
            operationRunRecorder = recorded.recorder
        )
        mockkObject(SemanticAggregates)
        try {
            every { SemanticAggregates.capture(any(), any()) } throws cancellation

            assertCancellationFromBothExports(repo, cancellation)
        } finally {
            unmockkObject(SemanticAggregates)
        }

        verify(exactly = 2) { mockRestoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING) }
        verify(exactly = 2) { mockRestoreMaintenanceMode.exit(forceRestartRequired = false) }
        assertTrue(
            "no snapshot file may remain after a cancelled capture",
            cacheDir.listFiles().orEmpty().none { it.name.startsWith("costbackup_snapshot_") }
        )
        assertTrue(
            "no bundle may be written after a cancelled capture",
            File(tempDir, "costbackups").listFiles().orEmpty().none { it.name.endsWith(".costbackup") }
        )
        assertTrue("a cancelled capture emits no SEMANTIC_CAPTURED event", recorded.semanticEvents().isEmpty())
    }

    @Test
    fun `AE6 a failed self check degrades to NOT_CAPTURED SELF_CHECK_FAILED and the backup succeeds`() =
        runTest(testDispatcher) {
            createFullSchemaDatabase(file = dbFile)
            val encryption = lowIterationBackupEncryptionService()
            val c0 = SemanticCheckpoint.C0_BACKUP_SELF_CHECK
            val rows = listOf(
                "self check code" to SemanticCheckResult.Failed(c0, SemanticCode.SEMANTIC_SELF_CHECK_FAILED, null, 0),
                "round trip mismatch" to SemanticCheckResult.Failed(
                    c0, SemanticCode.SEMANTIC_MISMATCH, SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, 1
                ),
                "anything but Verified" to SemanticCheckResult.NotVerifiable(
                    c0, SemanticNotVerifiableReason.DEFINITION_CHANGED
                )
            )
            mockkObject(CostbackupBundle)
            try {
                rows.forEachIndexed { index, (label, check) ->
                    every { CostbackupBundle.selfCheckSemanticSection(any(), any()) } returns check
                    val recorded = recordingRun()

                    val result = exportToFile(recorded, encryption, "semantic_selfcheck_password")

                    assertTrue("[$label] backup must succeed: ${result.exceptionOrNull()}", result.isSuccess)
                    val section = extractExport(
                        result.getOrThrow(), "semantic_selfcheck_password", encryption, "selfcheck$index"
                    ).manifest.semanticAggregates
                    assertNotCaptured(section, SemanticNotCapturedReason.SEMANTIC_SELF_CHECK_FAILED)
                    assertSingleBoundedSemanticEvent(recorded, SemanticCode.SEMANTIC_SELF_CHECK_FAILED)
                }
            } finally {
                unmockkObject(CostbackupBundle)
            }
        }

    @Test
    fun `AE15 snapshot verification failure carries exactly the bounded code`() = runTest(testDispatcher) {
        val encryption = lowIterationBackupEncryptionService()
        // Control: the same pipeline with an untouched snapshot succeeds.
        createFullSchemaDatabase(file = dbFile)
        val control = exportToFile(recordingRun(), encryption, "snapshot_verify_control", redacted = true)
        assertTrue("control export must succeed: ${control.exceptionOrNull()}", control.isSuccess)
        // The control legitimately wrote a bundle; clear it so the per-row "no bundle" assertion is meaningful.
        File(tempDir, "costbackups").listFiles().orEmpty().forEach { it.delete() }

        val rows = listOf(
            "installment purchase orphan" to
                "UPDATE expenses SET spendingExclusionReason = 'INSTALLMENT_PURCHASE' WHERE id = 1",
            "installment reversed orphan" to
                "UPDATE expenses SET spendingExclusionReason = 'INSTALLMENT_REVERSED' WHERE id = 1"
        )
        for ((label, statement) in rows) {
            createFullSchemaDatabase(file = dbFile)
            every { exportAnonymizer.sanitizeExport(any()) } answers {
                RealSchemaFixture.mutate(firstArg<File>(), statement)
            }
            val recorded = recordingRun()

            val result = exportToFile(recorded, encryption, "snapshot_verify_password", redacted = true)

            assertTrue("[$label] the backup must fail", result.isFailure)
            val failure = result.exceptionOrNull()!!
            assertEquals("[$label]", "SNAPSHOT_VERIFICATION_FAILED", failure.message)
            assertNull("[$label] no cause text may travel", failure.cause)
            val rendered = failure.toString()
            (listOf(
                "orphan", "Orphan", "installment", "Installment", "INSTALLMENT", "expenses", "Count mismatch",
                "Foreign key", "Integrity", "violation", tempDir.absolutePath
            ) + RealSchemaFixture.forbiddenValueText).forEach {
                assertFalse("[$label] exception text must not contain '$it': $rendered", rendered.contains(it))
            }
            assertTrue(
                "[$label] no bundle may be written",
                File(tempDir, "costbackups").listFiles().orEmpty().none { it.name.endsWith(".costbackup") }
            )
            assertTrue(
                "[$label] the verification stage is recorded as a failure",
                recorded.events.any {
                    it.stage == "SNAPSHOT_VERIFIED" && it.outcome == EventOutcome.FAILED_FINAL &&
                        it.reasonCode == DiagnosticReasonCode.VALIDATION_FAILED
                }
            )
            assertTrue("[$label] capture itself succeeded", recorded.semanticEvents().isEmpty())
            coVerify(exactly = 1) { recorded.handle.failedFinal(any(), any()) }
            coVerify(exactly = 0) { recorded.handle.success() }
        }
    }

    private suspend fun exportWithDistinctCurrencies(
        count: Int,
        password: String
    ): Triple<Result<File>, RecordedRun, BackupEncryptionService> {
        createRealSchemaDatabase(dbFile) { db ->
            for (i in 1..count) {
                RealSchemaFixture.insertExpense(db, i.toLong(), "PURCHASE", "C%03d".format(i), "1.0")
            }
        }
        val encryption = lowIterationBackupEncryptionService()
        val recorded = recordingRun()
        return Triple(exportToFile(recorded, encryption, password), recorded, encryption)
    }

    @Test
    fun `AE18 exactly 256 distinct currencies still capture`() = runTest(testDispatcher) {
        val (result, recorded, encryption) = exportWithDistinctCurrencies(256, "semantic_256_password")

        assertTrue(result.isSuccess)
        val extracted = extractExport(result.getOrThrow(), "semantic_256_password", encryption, "c256")
        val section = extracted.manifest.semanticAggregates!!
        assertEquals(SemanticCaptureStatus.CAPTURED, section.status)
        assertEquals(
            256,
            section.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY).buckets.size
        )
        assertEquals(256, extracted.manifest.tableCounts["expenses"])
        assertTrue(recorded.semanticEvents().isEmpty())
    }

    @Test
    fun `AE18 257 distinct currencies degrade to SEMANTIC_BUCKET_LIMIT and the backup succeeds`() =
        runTest(testDispatcher) {
            val (result, recorded, encryption) = exportWithDistinctCurrencies(257, "semantic_257_password")

            assertTrue("bucket limit must not fail the backup: ${result.exceptionOrNull()}", result.isSuccess)
            val extracted = extractExport(result.getOrThrow(), "semantic_257_password", encryption, "c257")
            assertNotCaptured(extracted.manifest.semanticAggregates, SemanticNotCapturedReason.SEMANTIC_BUCKET_LIMIT)
            assertEquals("the bundled data is still complete", 257, extracted.manifest.tableCounts["expenses"])
            assertSingleBoundedSemanticEvent(recorded, SemanticCode.SEMANTIC_BUCKET_LIMIT)
            coVerify(exactly = 1) { recorded.handle.success() }
        }

    @Test
    fun `safety backup filename timestamp is derived from timeProvider`() = runTest(testDispatcher) {
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )

        val result = repository.createSafetyBackup()

        assertTrue(result.isSuccess)
        val safetyBackupFile = result.getOrNull()
        assertNotNull(safetyBackupFile)
        // T2B: safety backup names are user-visible and timestamped; the timestamp
        // must be derived from the injected TimeProvider, never LocalDateTime.now().
        val expectedTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .format(Instant.ofEpochMilli(fixedTime).atZone(ZoneId.systemDefault()).toLocalDateTime())
        assertEquals("expense_tracker_backup_SAFETY_${expectedTimestamp}.db", safetyBackupFile!!.name)
    }

    @Test
    fun `staging db names are uuid based and unique across imports`() = runTest(testDispatcher) {
        if (!BuildConfig.DEBUG) {
            assertLegacyImportDeniedInRelease()
            return@runTest
        }
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )
        val sourceBackup = File(tempDir, "source_stage_uuid.db")
        createSqliteDatabase(
            file = sourceBackup,
            expenseCount = 3,
            categoryCount = 2,
            merchantCount = 4,
            pendingCount = 1,
            budgetCount = 2
        )

        val stagedNames = mutableListOf<String>()
        every { context.getDatabasePath(any()) } answers {
            val name = firstArg<String>()
            if (name.startsWith("expense_tracker_db_import_stage_")) stagedNames += name
            File(tempDir, name)
        }

        // Two separate repository instances (each starts with a fresh mock database)
        // so the second import is not affected by the first's hot-swap of the member.
        assertTrue(repository.importDatabase(sourceBackup).isSuccess)
        assertTrue(
            createRepository(stagedVerifier = { _, _, _, _, summary -> summary })
                .importDatabase(sourceBackup)
                .isSuccess
        )

        // T2B: staging DB names use UUID.randomUUID() (uniqueness-only), not
        // System.currentTimeMillis(). Assert prefix + UUID shape, never the value.
        assertEquals(2, stagedNames.size)
        stagedNames.forEach { name ->
            assertUuidSuffix(name, "expense_tracker_db_import_stage_")
        }
        assertNotEquals(stagedNames[0], stagedNames[1])
    }

    @Test
    fun `no direct wall clock reads remain in DatabaseBackupRepositoryImpl`() {
        val sourceFile = resolveRepositorySourceFile()
        assertNotNull(
            "Could not locate DatabaseBackupRepositoryImpl.kt — a broken source-root resolver " +
                "would make this guard pass without checking anything",
            sourceFile
        )
        val source = sourceFile!!.readText()
        assertFalse(
            "DatabaseBackupRepositoryImpl.kt must not call System.currentTimeMillis() directly — " +
                "T2B replaced wall-clock uniqueness names (staging DB, extract temp dir) with UUID.randomUUID()",
            source.contains("System.currentTimeMillis")
        )
        assertFalse(
            "DatabaseBackupRepositoryImpl.kt must not call LocalDateTime.now() directly — " +
                "T2B derives timestamped backup/safety filenames from timeProvider.now()",
            source.contains("LocalDateTime.now")
        )
    }

    private fun assertUuidSuffix(name: String, prefix: String) {
        assertTrue(name.startsWith(prefix))
        val suffix = name.removePrefix(prefix)
        assertEquals(suffix, UUID.fromString(suffix).toString())
    }

    private fun resolveRepositorySourceFile(): File? {
        val relative = "app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt"
        val candidates = listOf(
            File(relative),
            File(System.getProperty("user.dir") ?: ".", relative),
            File("src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt")
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
    }

    private data class Schema37FixtureCounts(
        val expenseCount: Int,
        val categoryCount: Int,
        val merchantCount: Int,
        val pendingReviewCount: Int,
        val budgetCount: Int
    ) {
        fun toImportSummary(): DatabaseImportSummary {
            return DatabaseImportSummary(
                transactionCount = expenseCount,
                categoryCount = categoryCount,
                merchantCount = merchantCount,
                pendingReviewCount = pendingReviewCount,
                budgetCount = budgetCount
            )
        }
    }

    /**
     * TimeProvider test double that counts every [now] invocation and returns
     * deterministic sequential values (startTime, startTime + step, ...).
     *
     * Used by the `createCostBackup stamps ...` test to prove the snapshot /
     * timestamped bundle path reads the clock exactly once and that the exact
     * captured value drives both the bundle filename and manifest.createdAt.
     */
    private class CountingTimeProvider(
        private val startTime: Long,
        private val stepMillis: Long = 1_000L
    ) : TimeProvider {

        /** Number of [now] invocations so far. */
        var callCount: Int = 0
            private set

        /** Value returned by the first [now] call (the createCostBackup snapshot capture). */
        val firstValue: Long
            get() = startTime

        override fun now(): Long {
            val value = startTime + callCount * stepMillis
            callCount++
            return value
        }
    }
}
