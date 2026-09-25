package com.yourname.expensetracker.data.repository

import android.content.Context
import android.content.ContentResolver
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.APP_DATABASE_SCHEMA_VERSION
import com.yourname.expensetracker.data.database.AppDatabase
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
import com.yourname.expensetracker.data.backup.BackupVerifier
import com.yourname.expensetracker.data.backup.CostbackupBundle
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.OperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDeniedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
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

        val repo = createRepository(encryptionService = BackupEncryptionService())
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
            encryptionService = BackupEncryptionService()
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

        assertSame(failure, fileResult.exceptionOrNull())
        assertSame(failure, safResult.exceptionOrNull())
        coVerify(exactly = 2) { operationRun.failedFinal(DiagnosticReasonCode.PRIVACY_FAIL_CLOSED.name, null) }
        coVerify(exactly = 2) { operationRun.failedFinal(any(), any()) }
        coVerify(exactly = 0) { operationRun.success() }
        assertEquals(recoveryMode, mockRestoreMaintenanceMode.currentMode())
        assertNoExportSideEffects(resolver, cacheDir)
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
        // (which expects 57 tables), but the write barrier double-check happens early in
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

        // The backup may fail later at verification (57 tables expected), but it should
        // not fail at the write barrier check — i.e. barrier check passes.
        if (result.isFailure) {
            assertTrue(
                result.exceptionOrNull()?.message?.contains("write barrier was exited") != true
            )
        }
    }

    @Test
    fun `restore from backup works`() = runTest(testDispatcher) {
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
    fun `import repairs same version budgets defaults before reopen`() = runTest(testDispatcher) {
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

        every { openHelper.writableDatabase } answers {
            if (isSchema86WithStaleBudgets(dbFile)) {
                throw IllegalStateException("Migration didn't properly handle: budgets")
            }
            supportDb
        }

        val result = repository.importDatabase(sourceBackup)

        assertTrue(result.isSuccess)
        assertFalse(isSchema86WithStaleBudgets(dbFile))
        assertEquals(2, countRows(dbFile, "budgets"))
        assertTrue(indexExists(dbFile, "index_budgets_categoryId"))
        assertTrue(indexExists(dbFile, "index_budgets_isActive"))
    }

    @Test
    fun `import allows same lineage backup missing later non core tables`() = runTest(testDispatcher) {
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
            schemaVersion = 70,
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

        val result = repository.importDatabase(sourceBackup)

        assertTrue(result.isFailure)
        // Import sanitizes source-validation failures to the controlled code.
        assertTrue(result.exceptionOrNull()?.message?.contains("SOURCE_VALIDATION_FAILED") == true)
        assertEquals(1, countRows(dbFile, "expenses"))
    }

    @Test
    fun `import rejects schema86 backups with valid defaults but invalid budgets index uniqueness`() = runTest(testDispatcher) {
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

        val result = repository.importDatabase(sourceBackup)

        assertTrue(result.isFailure)
        // Import sanitizes source-validation failures to the controlled code;
        // the internal budgets-index detail is no longer part of the public error.
        assertTrue(result.exceptionOrNull()?.message?.contains("SOURCE_VALIDATION_FAILED") == true)
        assertEquals(1, countRows(dbFile, "expenses"))
    }

    @Test
    fun `temp migration open failure leaves live db untouched`() = runTest(testDispatcher) {
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
            schemaVersion = 37,
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
            schemaVersion = 37,
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
            schemaVersion = 37,
            expenseCount = fixture.expenseCount,
            categoryCount = fixture.categoryCount,
            merchantCount = fixture.merchantCount,
            pendingCount = fixture.pendingReviewCount,
            budgetCount = fixture.budgetCount
        )

        val expectedSummary = fixture.toImportSummary()
        val seamRepository = createRepository(
            stagedVerifier = { _, _, stagedFile, sourceSchemaVersion, summary ->
                assertEquals(37, sourceSchemaVersion)
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
                assertEquals(37, sourceSchemaVersion)
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
            schemaVersion = 37,
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
                val receiptsDir = File(outputDir, "receipts")
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
        vararg sourceFiles: String
    ): Pair<DatabaseBackupRepositoryImpl.ReceiptAssetRestoreOutcome, com.yourname.expensetracker.data.database.dao.ScannedReceiptDao> {
        val assetsDir = File(tempDir, "asset_extract")
        assetsDir.deleteRecursively()
        val receiptsSrc = File(assetsDir, "receipts")
        receiptsSrc.mkdirs()
        sourceFiles.forEach { name -> File(receiptsSrc, name).writeBytes(byteArrayOf(1, 2, 3)) }

        val dao = mockk<com.yourname.expensetracker.data.database.dao.ScannedReceiptDao>(relaxed = true)
        coEvery { dao.getById(any()) } returns com.yourname.expensetracker.data.database.entity.ScannedReceipt(
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

        val repo = createRepository(journal = journal)
        val outcome = repo.restoreReceiptAssets(
            assetsDir = assetsDir,
            manifest = tier1Manifest(BackupVerifier.requiredManifestTables(1).associateWith { 1 }),
            db = assetDb,
            journalEntry = entry
        )
        return outcome to dao
    }

    @Test
    fun `restoreReceiptAssets restores under identity-derived name and returns ledger for the caller`() = runTest(testDispatcher) {
        val journal = newRealJournal()
        val (outcome, dao) = runDirectAssetRestore(journal, "5_photo.jpg")

        val finalFile = File(File(tempDir, "receipts"), "restored_5.jpg")
        assertTrue("Asset must be restored under the identity-derived final name", finalFile.exists())
        assertEquals("Asset bytes must be preserved", 3L, finalFile.length())
        assertFalse("Temp copy must be gone after the durable rename", File(finalFile.parentFile, "restored_5.jpg.tmp").exists())

        // The returned ledger (RP-03 fix: caller carries it into the committed journal).
        val ledger = outcome.journalEntry!!.assetTasks
        assertEquals(1, ledger.size)
        assertEquals(RestoreJournal.AssetRestoreStatus.COMPLETED, ledger[0].status)
        assertTrue("Ledger target must point at the derived final name", ledger[0].targetPath!!.endsWith("restored_5.jpg"))
        // The journal persisted during the loop round-trips basename-only targets.
        val onDisk = journal.readJournal()!!
        assertEquals(RestoreJournal.AssetRestoreStatus.COMPLETED, onDisk.assetTasks.single().status)
        assertEquals("restored_5.jpg", onDisk.assetTasks.single().targetPath)

        val updated = slot<com.yourname.expensetracker.data.database.entity.ScannedReceipt>()
        coVerify(exactly = 1) { dao.update(capture(updated)) }
        assertEquals(finalFile.absolutePath, updated.captured.imagePath)
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
        assertFalse("No temp residue may linger", File(finalFile.parentFile, "restored_5.jpg.tmp").exists())
        coVerify(exactly = 0) { dao.update(any()) }
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
    }


    private enum class RestoreOperation { COSTBACKUP, IMPORT, RESET }

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
        createFullSchemaDatabase(dbFile)
        val source = File(directory, "source.db")
        createFullSchemaDatabase(source)
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("PRAGMA user_version = 37")
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
        for (operation in RestoreOperation.values()) {
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
        for (operation in RestoreOperation.values()) {
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
        for (operation in RestoreOperation.values()) for (criticalFails in listOf(false, true)) {
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
        for (operation in RestoreOperation.values()) for (secondaryCancellation in listOf(false, true)) {
            val fixture = restoreFixture()
            try {
                val before = dbFile.readBytes()
                val original = IdentityCancellationException("TEST_RESTORE_CANCELLED")
                every { mockRestoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.RESTORE_STAGING) } throws original
                every { fixture.journal.failJournal(any(), any()) } throws RestoreJournal.JournalDurabilityException()
                every { mockRestoreMaintenanceMode.enterCriticalRecoveryRequired(any()) } answers {
                    every { mockRestoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED
                    if (secondaryCancellation) throw kotlinx.coroutines.CancellationException("TEST_CLEANUP_CANCELLED")
                    throw RestoreMaintenanceMode.PersistenceException()
                }
                var observed: Throwable? = null
                try { invokeRestore(operation, createRepository(journal = fixture.journal), fixture.source) }
                catch (e: kotlinx.coroutines.CancellationException) { observed = e }
                org.junit.Assert.assertSame(original, observed)
                assertNoDestructiveStep(fixture, before)
                assertTrue(fixture.journal.hasJournal())
            } finally { releaseRestoreFixture() }
        }
    }

    @Test
    fun swap_and_verification_failures_cross_rollback_and_terminal_durability() = runTest(testDispatcher) {
        for (operation in listOf(RestoreOperation.COSTBACKUP, RestoreOperation.IMPORT))
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
                                    File(entry.extractTempDirPath!!, "receipts/5_photo.jpg").readBytes())
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
            org.junit.Assert.assertArrayEquals(byteArrayOf(9, 8, 7), File(entry.extractTempDirPath!!, "receipts/5_photo.jpg").readBytes())
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
        for (operation in RestoreOperation.values()) for (terminalFails in listOf(false, true)) {
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
        for (operation in RestoreOperation.values()) for (archiveFails in listOf(false, true)) {
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
    fun post_destructive_cancellation_keeps_evidence_and_original_identity_in_all_APIs() = runTest(testDispatcher) {
        for (operation in RestoreOperation.values()) {
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
                every { mockRestoreMaintenanceMode.enterCriticalRecoveryRequired(any()) } answers {
                    every { mockRestoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED
                    throw RestoreMaintenanceMode.PersistenceException()
                }
                var observed: Throwable? = null
                try { invokeRestore(operation, createRepository(journal = fixture.journal), fixture.source) }
                catch (e: kotlinx.coroutines.CancellationException) { observed = e }
                org.junit.Assert.assertSame(cancellation, observed)
                assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mockRestoreMaintenanceMode.currentMode())
                verify(exactly = 0) { mockRestoreMaintenanceMode.exit(any()) }
                val retained = fixture.journal.readJournal()!!
                assertTrue(File(retained.safetyBackupPath!!).exists())
                if (operation == RestoreOperation.COSTBACKUP) {
                    org.junit.Assert.assertArrayEquals(byteArrayOf(9, 8, 7),
                        File(retained.extractTempDirPath!!, "receipts/5_photo.jpg").readBytes())
                    assertTrue(File(dbFile.path + ".pre_restore").exists())
                }
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

    private fun createRepository(
        stagedVerifier: suspend (Context, String, File, Int, DatabaseImportSummary) -> DatabaseImportSummary = { _, _, _, _, summary -> summary },
        liveVerifier: suspend (AppDatabase, File, Int, DatabaseImportSummary) -> DatabaseImportSummary = { _, _, _, summary -> summary },
        encryptionService: BackupEncryptionService = backupEncryptionService,
        timeProvider: TimeProvider = FakeTimeProvider(fixedTime),
        journal: RestoreJournal = mockRestoreJournal,
        operationRunRecorder: OperationRunRecorder = mockk(relaxed = true),
        gate: PrivacyGate = privacyGate
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
     * Creates a database containing every table tracked by [BackupVerifier] (57 tables),
     * so the strict backup pipeline (`collectTableCountsStrict` + `BackupVerifier.verify`)
     * passes and `createCostBackup` reaches `CostbackupBundle.create`.
     */
    private fun createFullSchemaDatabase(file: File) {
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("PRAGMA user_version = $APP_DATABASE_SCHEMA_VERSION")
            for (tableName in BackupVerifier.allTableNames()) {
                db.execSQL("CREATE TABLE IF NOT EXISTS \"$tableName\" (id INTEGER PRIMARY KEY)")
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
        // Full 57-table schema so the strict backup pipeline (collectTableCountsStrict +
        // BackupVerifier.verify) passes and the flow actually reaches CostbackupBundle.create.
        createFullSchemaDatabase(file = dbFile)
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        every { context.cacheDir } returns cacheDir

        // Dedicated counting provider: its counter reflects ONLY createCostBackup's
        // snapshot/timestamped bundle path, so no unrelated TimeProvider call from
        // other repository operations can pollute the "called exactly once" assertion.
        val countingTime = CountingTimeProvider(startTime = fixedTime)

        // Real crypto so the produced bundle is extractable and its manifest can be read back.
        val repo = createRepository(
            stagedVerifier = { _, _, _, _, summary -> summary },
            encryptionService = BackupEncryptionService(),
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
            nowEpochMs = 0L
        )
        assertTrue("bundle should extract with the same password", extraction.isSuccess)
        assertEquals(
            "manifest createdAt must be the exact snapshot capture value",
            snapshotEpochMs,
            extraction.getOrNull()?.manifest?.createdAt
        )

        runCatching { bundleFile.delete() }
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
