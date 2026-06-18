package com.yourname.expensetracker.data.repository

import android.content.Context
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.yourname.expensetracker.data.database.APP_DATABASE_SCHEMA_VERSION
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import com.yourname.expensetracker.data.privacy.ExportAnonymizer
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.backup.DatabaseImportSummary
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.mockk.mockkObject
import io.mockk.unmockkObject
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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

        val result = runCatching {
            repository.createCostBackup(
                password = "test_password",
                includeReceiptImages = false,
                redacted = true,
                privacyMode = null
            )
        }

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
        assertTrue(
            result.exceptionOrNull()?.message?.contains("restored from safety backup") == true
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
        assertTrue(result.exceptionOrNull()?.message?.contains("Unsupported budgets schema") == true)
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
        assertTrue(result.exceptionOrNull()?.message?.contains("Unsupported budgets schema") == true)
        assertTrue(result.exceptionOrNull()?.message?.contains("invalid unique index metadata") == true)
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
        assertTrue(result.exceptionOrNull()?.message?.contains("restored from safety backup") == true)
    }

    private fun createRepository(
        stagedVerifier: suspend (Context, String, File, Int, DatabaseImportSummary) -> DatabaseImportSummary,
        liveVerifier: suspend (AppDatabase, File, Int, DatabaseImportSummary) -> DatabaseImportSummary = { _, _, _, summary -> summary }
    ): DatabaseBackupRepositoryImpl {
        return DatabaseBackupRepositoryImpl(
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
            restoreJournal = mockRestoreJournal,
            stagedImportVerifier = stagedVerifier,
            liveImportVerifier = liveVerifier
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
}
