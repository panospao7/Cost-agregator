package com.yourname.expensetracker.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.DatabaseMigrations
import com.yourname.expensetracker.data.database.DatabaseSchemaPolicy
import com.yourname.expensetracker.data.database.migrationTestInstrumentation
import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import com.yourname.expensetracker.data.repository.DatabaseBackupRepositoryImpl
import com.yourname.expensetracker.domain.backup.DatabaseImportResult
import com.yourname.expensetracker.domain.diagnostics.NoOpOperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.workers.NoOpWorkerDrainController
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupVerifierSavingsLedgerTest {
    @get:Rule
    val helper = MigrationTestHelper(
        migrationTestInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun legacy_backup_preserves_savings_and_verifies_exact_empty_ledger_after_migration() {
        val name = "backup-savings-ledger-149"
        val legacy = helper.createDatabase(name, 149)
        seedSavings(legacy)
        legacy.close()
        val file = databaseFile(name)
        val counts = manifestCounts(file, 149)
        BackupVerifier.validateManifestCompleteness(counts, databaseVersion = 149)
        BackupVerifier.verifyQuick(file, counts)

        helper.runMigrationsAndValidate(
            name, DatabaseSchemaPolicy.CURRENT_VERSION, true, *DatabaseSchemaPolicy.ALL_MIGRATIONS
        ).close()
        val migratedCounts = BackupVerifier.expectedCountsAfterMigration(counts, 149)
        BackupVerifier.verifyQuick(file, migratedCounts)
        val result = BackupVerifier.verify(file, migratedCounts)
        assertTrue(result.errors.toString(), result.passed)
        assertEquals(0, result.tableResults.single { it.tableName == "savings_contribution_ledger" }.actualCount)
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT currentAmount FROM savings_goals WHERE id = 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(240.0, cursor.getDouble(0), 0.0)
            }
            db.rawQuery("SELECT status, acceptanceKey FROM savings_sweep_plan WHERE id = 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ACCEPTED", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    @Test
    fun current_backup_preserves_ledger_and_rejects_an_incorrect_exact_count() {
        val name = "backup-savings-ledger-current"
        val current = helper.createDatabase(name, DatabaseSchemaPolicy.CURRENT_VERSION)
        seedSavings(current)
        current.execSQL("UPDATE savings_sweep_plan SET acceptanceKey = 'accepted-key' WHERE id = 1")
        current.execSQL(
            "INSERT INTO savings_contribution_ledger " +
                "(idempotencyKey, goalId, amount, currency, timestamp, source, sweepPlanId) " +
                "VALUES ('accepted-key', 1, 40.0, 'EUR', 1710000000000, 'sweep', 1)"
        )
        current.execSQL("INSERT INTO savings_sweep_month_acceptance (monthStart, monthEndExclusive, actionedAt, reasonCode) VALUES (1709251200000, 1711929600000, 1710000000000, 'SAVINGS_SWEEP_MONTH_ACCEPTED')")
        current.close()
        val file = databaseFile(name)
        val counts = manifestCounts(file, DatabaseSchemaPolicy.CURRENT_VERSION)
        assertEquals(1, counts["savings_contribution_ledger"])
        assertEquals(1, counts["savings_sweep_month_acceptance"])
        val backup = File.createTempFile("savings-ledger-backup-", ".db")
        try {
            file.copyTo(backup, overwrite = true)
            BackupVerifier.verifyQuick(backup, counts)
            assertTrue(BackupVerifier.verify(backup, counts).passed)
            assertFalse(
                BackupVerifier.verify(backup, counts + ("savings_contribution_ledger" to 0)).passed
            )
            assertFalse(BackupVerifier.verify(backup, counts + ("savings_sweep_month_acceptance" to 0)).passed)
            SQLiteDatabase.openDatabase(backup.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT amount, currency, sweepPlanId FROM savings_contribution_ledger", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(40.0, cursor.getDouble(0), 0.0)
                    assertEquals("EUR", cursor.getString(1))
                    assertEquals(1L, cursor.getLong(2))
                }
            }
        } finally {
            backup.delete()
        }
    }

    @Test
    fun legacy_encrypted_bundle_restore_migrates_and_preserves_savings() = runTest {
        assertBundleRestore(sourceVersion = 149, expectedLedgerCount = 0)
    }

    @Test
    fun current_encrypted_bundle_restore_preserves_authoritative_ledger() = runTest {
        assertBundleRestore(sourceVersion = DatabaseSchemaPolicy.CURRENT_VERSION, expectedLedgerCount = 1)
    }

    @Test
    fun v150_bundle_restore_preserves_ledger_and_seeds_exact_upgrade_month_marker() = runTest {
        assertBundleRestore(sourceVersion = 150, expectedLedgerCount = 1)
    }

    @Test
    fun current_bundle_restore_keeps_month_marker_after_all_accepted_goals_were_deleted() = runTest {
        assertBundleRestore(sourceVersion = DatabaseSchemaPolicy.CURRENT_VERSION, expectedLedgerCount = 0,
            deleteAcceptedGoals = true)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertBundleRestore(
        sourceVersion: Int,
        expectedLedgerCount: Int,
        deleteAcceptedGoals: Boolean = false
    ) {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        // Keep UUID staging paths below the legacy SQLite Windows path limit.
        val workspace = Files.createTempDirectory("swr-").toFile()
        val context = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(workspace, name)
            override fun getFilesDir(): File = File(workspace, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(workspace, "cache").apply { mkdirs() }
        }
        val sourceName = "bundle-source-$sourceVersion"
        val sourceDb = helper.createDatabase(sourceName, sourceVersion)
        seedSavings(sourceDb)
        if (expectedLedgerCount > 0 || deleteAcceptedGoals) {
            sourceDb.execSQL("UPDATE savings_sweep_plan SET acceptanceKey = 'accepted-key' WHERE id = 1")
            sourceDb.execSQL(
                "INSERT INTO savings_contribution_ledger " +
                    "(idempotencyKey, goalId, amount, currency, timestamp, source, sweepPlanId) " +
                    "VALUES ('accepted-key', 1, 40.0, 'EUR', 1710000000000, 'sweep', 1)"
            )
        }
        if (sourceVersion >= 151) {
            sourceDb.execSQL("INSERT INTO savings_sweep_month_acceptance (monthStart, monthEndExclusive, actionedAt, reasonCode) VALUES (1709251200000, 1711929600000, 1710000000000, 'SAVINGS_SWEEP_MONTH_ACCEPTED')")
        }
        if (deleteAcceptedGoals) {
            sourceDb.execSQL("PRAGMA foreign_keys = ON")
            sourceDb.execSQL("DELETE FROM savings_goals WHERE id = 1")
        }
        sourceDb.close()
        val sourceFile = databaseFile(sourceName)
        val counts = manifestCounts(sourceFile, sourceVersion)
        val bundle = File(context.cacheDir, "savings-$sourceVersion.costbackup")
        CostbackupBundle.create(
            outputFile = bundle, databaseFile = sourceFile, receiptFiles = emptyMap(),
            password = "test-savings-password", nowEpochMs = 1710000000000,
            tableCounts = counts, databaseVersion = sourceVersion,
            redacted = false, includeReceiptImages = false
        ).getOrThrow()

        val liveDatabase = AppDatabase.fileBuilder(context).allowMainThreadQueries().build()
        liveDatabase.openHelper.writableDatabase
        val clock = FakeTimeProvider(1710000000000)
        val journal = RestoreJournal(context, clock)
        val mode = mockk<RestoreMaintenanceMode>(relaxed = true)
        var currentMode = RestoreMaintenanceMode.Mode.NORMAL
        every { mode.currentMode() } answers { currentMode }
        every { mode.enter(any()) } answers { currentMode = firstArg() }
        every { mode.exit(any()) } answers {
            currentMode = if (firstArg<Boolean>())
                RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED
            else RestoreMaintenanceMode.Mode.NORMAL
        }
        every { mode.isWritesAllowed() } answers { currentMode == RestoreMaintenanceMode.Mode.NORMAL }
        val gate = mockk<PrivacyGate>()
        coEvery { gate.check(any(), any()) } returns PrivacyDecision.Allowed
        val recorder = mockk<OperationRunRecorder>()
        coEvery { recorder.start(any(), any(), any()) } returns NoOpOperationRunHandle
        val drain = NoOpWorkerDrainController()
        val sink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)
        val failures = mutableListOf<String>()
        coEvery { sink.recordDiagnosticEvent(any(), any(), any()) } coAnswers {
            val event = firstArg<com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent>()
            event.exception?.let { error ->
                val detail = if (error is BackupVerifier.CountMismatchException)
                    " table=${error.tableName} expected=${error.expected} actual=${error.actual}"
                else ""
                failures += "${event.stage}:${error.javaClass.simpleName}$detail"
            }
        }
        val repository = DatabaseBackupRepositoryImpl(
            context = context, database = liveDatabase,
            ioDispatcher = StandardTestDispatcher(testScheduler), privacyGate = gate,
            privacySettingsRepository = mockk(relaxed = true),
            backupEncryptionService = BackupEncryptionService(),
            exportAnonymizer = mockk(relaxed = true), secureKeyStorage = mockk(relaxed = true),
            receiptAssetStore = mockk(relaxed = true), restoreMaintenanceMode = mode,
            restoreJournal = journal, workerDrain = drain,
            restoreDatabaseOpener = RestoreDatabaseOpenerImpl(context),
            maintenanceOperationRunner = MaintenanceOperationRunner(mode, drain, DatabaseWriteBarrier(mode)),
            snapshotCreator = SqliteSnapshotCreator(),
            restoreInternalWriteScope = RestoreInternalWriteScope(mode),
            operationRunRecorder = recorder, diagnosticSink = sink,
            timeProvider = clock,
            // S6c-4: restore never reconciles; a strict mock fails the test if it is ever called.
            installmentPreBackupReconciler = dagger.Lazy {
                mockk<com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPreBackupReconciler>()
            }
        )
        try {
            val result = repository.restoreCostBackup(bundle, "test-savings-password")
            val stages = journal.getAllDiagnosticEvents().map { it.stage to it.outcome }
            assertTrue(
                "mode=$currentMode stages=$stages failures=$failures",
                result.isSuccess
            )
            val restored = result.getOrThrow() as DatabaseImportResult.SuccessNeedsRestart
            assertEquals(expectedLedgerCount, restored.summary.allTableCounts["savings_contribution_ledger"])
            assertEquals(1, restored.summary.allTableCounts["savings_sweep_month_acceptance"])
            assertEquals(if (deleteAcceptedGoals) 0 else 1, restored.summary.savingsGoalCount)
            assertEquals(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED, currentMode)
            assertFalse(journal.hasJournal())
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(AppDatabase.DATABASE_NAME).absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                assertEquals(DatabaseSchemaPolicy.CURRENT_VERSION, db.version)
                db.rawQuery("SELECT reasonCode FROM savings_sweep_month_acceptance", null).use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals(if (sourceVersion < 151) "SAVINGS_SWEEP_LEGACY_MONTH_UNKNOWN" else "SAVINGS_SWEEP_MONTH_ACCEPTED", row.getString(0))
                    assertFalse(row.moveToNext())
                }
                db.rawQuery("SELECT currentAmount, currency, currencyAssumption FROM savings_goals WHERE id = 1", null).use { row ->
                    if (deleteAcceptedGoals) {
                        assertFalse(row.moveToFirst())
                        return@use
                    }
                    assertTrue(row.moveToFirst())
                    assertEquals(240.0, row.getDouble(0), 0.0)
                    assertEquals("EUR", row.getString(1))
                    assertEquals("EXPLICIT", row.getString(2))
                }
                db.rawQuery("SELECT status, acceptanceKey FROM savings_sweep_plan WHERE id = 1", null).use { row ->
                    if (deleteAcceptedGoals) {
                        assertFalse(row.moveToFirst())
                        return@use
                    }
                    assertTrue(row.moveToFirst())
                    assertEquals("ACCEPTED", row.getString(0))
                    if (sourceVersion < 150) assertTrue(row.isNull(1))
                    else assertEquals("accepted-key", row.getString(1))
                }
                if (expectedLedgerCount > 0) {
                    db.rawQuery("SELECT idempotencyKey, amount, currency, sweepPlanId FROM savings_contribution_ledger", null).use { row ->
                        assertTrue(row.moveToFirst())
                        assertEquals("accepted-key", row.getString(0))
                        assertEquals(40.0, row.getDouble(1), 0.0)
                        assertEquals("EUR", row.getString(2))
                        assertEquals(1L, row.getLong(3))
                        assertFalse(row.moveToNext())
                    }
                }
            }
        } finally {
            liveDatabase.close()
            workspace.deleteRecursively()
        }
    }

    private fun databaseFile(name: String): File =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name)

    private fun manifestCounts(file: File, version: Int): Map<String, Int> =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            BackupVerifier.requiredManifestTables(databaseVersion = version).associateWith { table ->
                db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                }
            }
        }

    private fun seedSavings(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO savings_goals " +
                "(id, name, targetAmount, currentAmount, protectionLevel, currency, currencyAssumption, createdAt) " +
                "VALUES (1, 'Savings', 1000.0, 240.0, 'WARNING', 'EUR', 'EXPLICIT', 1710000000000)"
        )
        db.execSQL(
            "INSERT INTO savings_sweep_plan " +
                "(id, goalId, monthEnd, totalUnderspend, riskBuffer, safeSweepAmount, allocatedAmount, " +
                "allocationPercentage, status, confidence, currency, computedAt) " +
                "VALUES (1, 1, 1710000001000, 50.0, 10.0, 40.0, 40.0, 1.0, 'ACCEPTED', 0.9, 'EUR', 1710000000000)"
        )
    }
}
