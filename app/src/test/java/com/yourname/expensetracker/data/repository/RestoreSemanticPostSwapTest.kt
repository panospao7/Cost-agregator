package com.yourname.expensetracker.data.repository

import android.content.Context
import android.database.MatrixCursor
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.BackupVerifier
import com.yourname.expensetracker.data.backup.CostbackupBundle
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceOperationRunner
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RealSchemaFixture
import com.yourname.expensetracker.data.backup.RestoreDatabaseOpener
import com.yourname.expensetracker.data.backup.RestoreInternalWriteScope
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.backup.SemanticAggregateId
import com.yourname.expensetracker.data.backup.SemanticAggregateSet
import com.yourname.expensetracker.data.backup.SemanticBasis
import com.yourname.expensetracker.data.backup.SemanticCheckResult
import com.yourname.expensetracker.data.backup.SemanticCheckpoint
import com.yourname.expensetracker.data.backup.SemanticCode
import com.yourname.expensetracker.data.backup.SemanticExpectation
import com.yourname.expensetracker.data.backup.SemanticNotCapturedReason
import com.yourname.expensetracker.data.backup.SqliteSnapshotCreator
import com.yourname.expensetracker.data.database.APP_DATABASE_SCHEMA_VERSION
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import com.yourname.expensetracker.data.privacy.ExportAnonymizer
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.backup.DatabaseImportResult
import com.yourname.expensetracker.domain.backup.SemanticNotVerifiableReason
import com.yourname.expensetracker.domain.backup.SemanticVerificationOutcome
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.OperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPreBackupReconciler
import com.yourname.expensetracker.domain.installment.lifecycle.PreBackupReconcileResult
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.workers.NoOpWorkerDrainController
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * UF-46 / S4 (restore-semantic-verify): the POST-SWAP half of `restoreCostBackup` (checkpoint C3), the
 * outcome folding (INV-15) and the bounded diagnostics (INV-14).
 *
 * Oracle: `docs/features/restore-semantic-verify/domain-spec.md` (INV-8, INV-11, INV-14, INV-15, AE-5 restore
 * half, AE-9, AE-10, AE-11, AE-12, AE-15, AE-16), never the implementation.
 *
 * Real boundaries: a real file-backed [RestoreJournal], real database files on the real Room schema (fixture
 * F via [RealSchemaFixture]), the real [BackupVerifier] and the real rollback (`restoreFromSafetyBackup`).
 * Mocked, peripheral only: the Room opens (the migration of the staged copy and the fresh post-swap open),
 * the bundle extraction (crypto), maintenance mode, the diagnostics recorder and the safe sink (to capture
 * every event, including the ones written after the swap). Synthetic data only.
 *
 * The C3 failures are induced WITHOUT a production seam: the fresh post-swap Room open
 * (`openHelper.writableDatabase`) is the first thing the live verification does after the swap, so a hook on
 * it alters the swapped-in live file exactly where a bad swap or a bad migration would.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreSemanticPostSwapTest {

    @get:Rule
    val realSchemaHelper: MigrationTestHelper = RealSchemaFixture.newHelper()

    private val context = mockk<Context>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val liveOpenHelper = mockk<SupportSQLiteOpenHelper>(relaxed = true)
    private val stagedOpenHelper = mockk<SupportSQLiteOpenHelper>(relaxed = true)
    private val supportDb = mockk<SupportSQLiteDatabase>(relaxed = true)
    private val maintenance = mockk<RestoreMaintenanceMode>(relaxed = true)
    private val privacyGate = mockk<PrivacyGate>(relaxed = true)
    private val privacySettingsRepository = mockk<PrivacySettingsRepository>(relaxed = true)
    private val backupEncryptionService = mockk<BackupEncryptionService>(relaxed = true)
    private val exportAnonymizer = mockk<ExportAnonymizer>(relaxed = true)
    private val secureKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
    private val diagnosticSink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)
    private val preBackupReconciler = mockk<InstallmentPreBackupReconciler> {
        coEvery { reconcileBeforeBackup() } returns PreBackupReconcileResult.Reconciled
    }
    private val testDispatcher = StandardTestDispatcher()
    private val realAppContext: Context by lazy { ApplicationProvider.getApplicationContext() }

    private lateinit var tempDir: File
    private var fixtureSeq = 0
    private val logLines = mutableListOf<String>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logLines += message
            t?.message?.let { logLines += it }
        }
    }

    @Before
    fun setUp() {
        tempDir = java.nio.file.Files.createTempDirectory("rsv-s4-").toFile()
        Timber.plant(logTree)
    }

    @After
    fun tearDown() {
        Timber.uproot(logTree)
        unmockkAll()
        tempDir.deleteRecursively()
    }

    // ── Fixture ──────────────────────────────────────────────────────────

    private class Source(val file: File, val counts: Map<String, Int>)

    private class RecordedEvent(
        val stage: String,
        val outcome: EventOutcome,
        val severity: EventSeverity,
        val reasonCode: DiagnosticReasonCode?,
        val metadataJson: String,
        val exception: Throwable?,
        val isTerminal: Boolean
    )

    /** Hooks that alter the world at a precise point of the post-swap flow. */
    private class Hooks {
        /** Runs once on the swapped-in live file, right before the post-swap live verification reads it. */
        var afterSwap: ((File) -> Unit)? = null

        /** Makes the fresh Room open that verifies the ROLLBACK fail, so the rollback itself fails. */
        var failRollbackReopen = false
    }

    private class Fixture(
        val directory: File,
        val liveDb: File,
        val repo: DatabaseBackupRepositoryImpl,
        val journal: RestoreJournal,
        val failReasons: MutableList<String>,
        val trace: MutableList<String>,
        val events: MutableList<RecordedEvent>,
        val verifyExpectations: MutableList<SemanticExpectation?>
    )

    /** Real-schema fixture F (6 expenses, 2 recurring rules) as a database file outside the app files dir. */
    private fun realSource(): Source {
        val file = File(tempDir, "source-${UUID.randomUUID()}.sqlite")
        val created = RealSchemaFixture.createFixtureF(realSchemaHelper, "rsv-${fixtureSeq++}")
        created.copyTo(file, overwrite = true)
        created.delete()
        return Source(file, RealSchemaFixture.manifestCounts(file))
    }

    /** The live (pre-restore) database: every tracked table with an id column, plus the marker row 777. */
    private fun createLiveDatabase(file: File) {
        file.parentFile?.mkdirs()
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("PRAGMA user_version = $APP_DATABASE_SCHEMA_VERSION")
            for (tableName in BackupVerifier.allTableNames()) {
                val referenceColumn = when (tableName) {
                    "receipt_expense_links" -> ", expenseId INTEGER"
                    "recurring_occurrences" -> ", sourceId INTEGER"
                    "budget_forecasts" -> ", budgetId INTEGER"
                    "expenses" -> ", spendingExclusionReason TEXT, refundNettedAmount REAL NOT NULL DEFAULT 0.0"
                    "expense_refund_links" -> ", refundExpenseId INTEGER, purchaseExpenseId INTEGER, appliedAmount REAL"
                    "installment_plans" -> ", purchaseExpenseId INTEGER, displayName TEXT, lenderLabel TEXT"
                    "installment_entries" -> ", planId INTEGER, linkedExpenseId INTEGER, status TEXT"
                    else -> ""
                }
                db.execSQL("CREATE TABLE IF NOT EXISTS \"$tableName\" (id INTEGER PRIMARY KEY$referenceColumn)")
            }
            db.execSQL("INSERT INTO expenses (id) VALUES (1)")
            db.execSQL("INSERT INTO expenses (id) VALUES (777)")
        } finally {
            db.close()
        }
    }

    /** What a backup writes: the captured section, serialised into the manifest JSON and parsed back. */
    private fun roundTripManifest(
        source: Source,
        section: SemanticAggregateSet?,
        edit: (JSONObject) -> Unit = {}
    ): CostbackupBundle.BackupManifest {
        val json = CostbackupBundle.BackupManifest(
            databaseVersion = APP_DATABASE_SCHEMA_VERSION,
            createdAt = FIXED_TIME,
            tableCounts = source.counts,
            semanticAggregates = section
        ).toJson()
        edit(json)
        return CostbackupBundle.BackupManifest.fromJson(json, FIXED_TIME)
    }

    private fun alterE3Live(file: File) =
        RealSchemaFixture.mutate(file, "UPDATE expenses SET amount = 1001.0 WHERE id = 3")

    /** Removes one row: a Tier-1 count mismatch against the manifest, with no aggregate involved. */
    private fun dropOneExpenseLive(file: File) =
        RealSchemaFixture.mutate(file, "DELETE FROM expenses WHERE id = 6")

    private fun buildFixture(
        source: Source,
        manifest: CostbackupBundle.BackupManifest,
        hooks: Hooks = Hooks()
    ): Fixture {
        val directory = File(tempDir, UUID.randomUUID().toString()).apply { mkdirs() }
        every { context.filesDir } returns directory
        every { context.cacheDir } returns File(directory, "cache").apply { mkdirs() }
        every { context.applicationContext } returns realAppContext
        every { context.getDatabasePath(any()) } answers { File(directory, firstArg<String>()) }
        val liveDb = File(directory, AppDatabase.DATABASE_NAME)
        createLiveDatabase(liveDb)

        val trace = mutableListOf<String>()
        var mode = RestoreMaintenanceMode.Mode.NORMAL
        every { maintenance.currentMode() } answers { mode }
        every { maintenance.isWritesAllowed() } answers { mode == RestoreMaintenanceMode.Mode.NORMAL }
        every { maintenance.enter(any()) } answers {
            mode = firstArg<RestoreMaintenanceMode.Mode>()
            trace += "MODE_" + mode.name
        }
        every { maintenance.enterCriticalRecoveryRequired(any()) } answers {
            mode = RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED
            trace += "CRITICAL"
        }
        every { maintenance.exit(any()) } answers {
            mode = if (firstArg<Boolean>()) RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED
            else RestoreMaintenanceMode.Mode.NORMAL
            trace += "EXIT_" + mode.name
        }
        every { database.close() } answers { trace += "CLOSE_LIVE" }
        every { database.openHelper } returns liveOpenHelper
        every { liveOpenHelper.close() } returns Unit

        // The two phases of the post-swap flow, driven by the real journal transitions.
        var verifying = false
        var rollingBack = false
        var afterSwapRan = false
        every { liveOpenHelper.writableDatabase } answers {
            if (verifying && !rollingBack && !afterSwapRan) {
                afterSwapRan = true
                hooks.afterSwap?.invoke(liveDb)
            }
            if (rollingBack && hooks.failRollbackReopen) throw IllegalStateException("TEST_ROLLBACK_REOPEN_FAILED")
            supportDb
        }

        val liveCounts = source.counts
        every { supportDb.query(match<String> { it.startsWith("SELECT COUNT(*)") }) } answers {
            val table = Regex("\"([^\"]+)\"").find(firstArg<String>())!!.groupValues[1]
            MatrixCursor(arrayOf("count")).apply { addRow(arrayOf(liveCounts[table] ?: 0)) }
        }
        every { supportDb.query("PRAGMA wal_checkpoint(TRUNCATE)") } answers {
            MatrixCursor(arrayOf("busy")).apply { addRow(arrayOf(0)) }
        }

        mockkObject(AppDatabase.Companion)
        val liveBuilder = mockk<androidx.room.RoomDatabase.Builder<AppDatabase>>()
        val stageBuilder = mockk<androidx.room.RoomDatabase.Builder<AppDatabase>>()
        val stagedDatabase = mockk<AppDatabase>(relaxed = true)
        every { stagedDatabase.openHelper } returns stagedOpenHelper
        every { stagedOpenHelper.writableDatabase } returns supportDb
        every { liveBuilder.build() } returns database
        every { stageBuilder.build() } returns stagedDatabase
        every { AppDatabase.fileBuilder(context, any()) } answers {
            if (secondArg<String>() == AppDatabase.DATABASE_NAME) liveBuilder else stageBuilder
        }

        // Observes the expectation handed to the full (C3) verify. The real verification still runs.
        val verifyExpectations = mutableListOf<SemanticExpectation?>()
        mockkObject(BackupVerifier)
        every { BackupVerifier.verify(any<File>(), any(), any()) } answers {
            verifyExpectations += arg<SemanticExpectation?>(2)
            callOriginal()
        }

        mockkObject(CostbackupBundle)
        every { CostbackupBundle.extract(any(), any(), any(), any(), any(), any()) } answers {
            val outputDir = secondArg<File>()
            outputDir.mkdirs()
            val receiptsDir = File(outputDir, "files/receipts")
            receiptsDir.mkdirs()
            File(receiptsDir, "5_photo.jpg").writeBytes(byteArrayOf(9, 8, 7))
            Result.success(
                CostbackupBundle.ExtractionResult(
                    manifest = manifest,
                    dbFile = source.file,
                    assetsDir = null,
                    checksumsVerified = true,
                    warnings = emptyList(),
                    extractedFiles = emptyMap()
                )
            )
        }

        val failReasons = mutableListOf<String>()
        val journal = spyk(RestoreJournal(context, FakeTimeProvider(FIXED_TIME)))
        every { journal.transitionTo(any(), any(), any(), any()) } answers {
            val result = callOriginal()
            val state = secondArg<RestoreJournal.JournalState>()
            trace += "JOURNAL_" + state.name
            if (state == RestoreJournal.JournalState.VERIFYING) verifying = true
            if (state == RestoreJournal.JournalState.ROLLING_BACK) rollingBack = true
            result
        }
        every { journal.failJournal(any(), any()) } answers {
            failReasons += secondArg<String>()
            val result = callOriginal()
            trace += "FINALIZED_FAILURE"
            result
        }
        every { journal.commitJournal(any()) } answers {
            val result = callOriginal()
            trace += "FINALIZED_SUCCESS"
            result
        }

        // Every event, before AND after the swap, reaches the safe sink (the run handle stops at the swap).
        val events = mutableListOf<RecordedEvent>()
        coEvery { diagnosticSink.recordDiagnosticEvent(any(), any(), any()) } coAnswers {
            val event = firstArg<DiagnosticEvent>()
            events += RecordedEvent(
                stage = event.stage,
                outcome = event.outcome,
                severity = event.severity,
                reasonCode = event.reasonCode,
                metadataJson = event.metadata.toJson(),
                exception = event.exception,
                isTerminal = event.isTerminal
            )
            Unit
        }
        val handle = mockk<OperationRunHandle>(relaxed = true)
        val recorder = mockk<OperationRunRecorder>(relaxed = true)
        coEvery { recorder.start(any(), any(), any()) } returns handle

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
            restoreMaintenanceMode = maintenance,
            restoreJournal = journal,
            workerDrain = NoOpWorkerDrainController(),
            restoreDatabaseOpener = object : RestoreDatabaseOpener {
                override fun openFreshDatabase() = database
            },
            maintenanceOperationRunner = MaintenanceOperationRunner(
                maintenance,
                NoOpWorkerDrainController(),
                DatabaseWriteBarrier(maintenance)
            ),
            snapshotCreator = SqliteSnapshotCreator(),
            restoreInternalWriteScope = RestoreInternalWriteScope(maintenance),
            operationRunRecorder = recorder,
            diagnosticSink = diagnosticSink,
            timeProvider = FakeTimeProvider(FIXED_TIME),
            currencySettingsRepository = io.mockk.mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository>(relaxed = true) {
                io.mockk.every { homeCurrency() } returns kotlinx.coroutines.flow.flowOf("EUR")
            },
            installmentPreBackupReconciler = dagger.Lazy { preBackupReconciler }
        )
        return Fixture(directory, liveDb, repo, journal, failReasons, trace, events, verifyExpectations)
    }

    private suspend fun runRestore(f: Fixture): Result<DatabaseImportResult> =
        f.repo.restoreCostBackup(File(tempDir, "bundle.costbackup"), "pw")

    // ── Assertions ───────────────────────────────────────────────────────

    private fun expenseIds(file: File): List<Long> = RealSchemaFixture.readOnly(file) { db ->
        db.rawQuery("SELECT id FROM expenses ORDER BY id", null).use { cursor ->
            val ids = mutableListOf<Long>()
            while (cursor.moveToNext()) ids += cursor.getLong(0)
            ids
        }
    }

    private fun assertTraceOrder(f: Fixture, vararg steps: String) {
        var previous = -1
        steps.forEach { step ->
            val index = f.trace.indexOf(step)
            assertTrue("expected $step after index $previous in ${f.trace}", index > previous)
            previous = index
        }
    }

    private fun forbiddenIn(label: String, text: String?, f: Fixture) {
        val forbidden = RealSchemaFixture.forbiddenValueText +
            listOf(f.directory.absolutePath, tempDir.absolutePath, "no such column", "FROM", "WHERE")
        forbidden.forEach { assertFalse("$label leaked '$it': $text", text?.contains(it) == true) }
    }

    /** The restore completed: SuccessNeedsRestart carrying exactly [expected]. */
    private fun assertRestoredWith(
        f: Fixture,
        result: Result<DatabaseImportResult>,
        expected: SemanticVerificationOutcome
    ) {
        assertTrue("restore must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val restored = result.getOrThrow() as DatabaseImportResult.SuccessNeedsRestart
        assertEquals(expected, restored.summary.semanticVerification)
        assertTrue("no journal failure may be recorded: ${f.failReasons}", f.failReasons.isEmpty())
        assertEquals(RestoreJournal.JournalState.COMPLETE, f.journal.readSuccessJournal()?.state)
        assertFalse(f.journal.hasJournal())
        assertFalse("CRITICAL must not be entered: ${f.trace}", f.trace.contains("CRITICAL"))
        assertTraceOrder(f, "JOURNAL_VERIFYING", "FINALIZED_SUCCESS", "EXIT_RESTORE_COMPLETE_RESTART_REQUIRED")
        assertEquals(
            "the swapped-in database is the restored source",
            listOf(1L, 2L, 3L, 4L, 5L, 6L), expenseIds(f.liveDb)
        )
        assertTrue(f.events.none { it.stage == "ROLLBACK_STARTED" })
    }

    /** The existing rollback ran and succeeded: the pre-restore data is back and the reason is the existing one. */
    private fun assertRolledBack(f: Fixture, result: Result<DatabaseImportResult>) {
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertEquals("VERIFICATION_FAILED_ROLLED_BACK", error?.message)
        assertFalse(
            "the result is the existing rolled-back reason, never the typed semantic exception",
            error is BackupSemanticVerificationException
        )
        assertEquals(listOf("VERIFICATION_FAILED_ROLLED_BACK"), f.failReasons)
        assertEquals(1, f.trace.count { it == "JOURNAL_ROLLING_BACK" })
        assertTraceOrder(
            f, "JOURNAL_VERIFYING", "JOURNAL_ROLLING_BACK", "FINALIZED_FAILURE", "EXIT_RESTORE_COMPLETE_RESTART_REQUIRED"
        )
        assertFalse("CRITICAL must not be entered: ${f.trace}", f.trace.contains("CRITICAL"))
        assertFalse(f.journal.hasJournal())
        assertEquals(RestoreJournal.JournalState.FAILED, f.journal.readFailureJournal()?.state)
        assertEquals(
            "the pre-restore data is intact",
            listOf(1L, 777L), expenseIds(f.liveDb)
        )
        val started = f.events.filter { it.stage == "ROLLBACK_STARTED" }
        assertEquals(1, started.size)
        val completed = f.events.filter { it.stage == "ROLLBACK_COMPLETED" }
        assertEquals(1, completed.size)
        assertEquals(EventOutcome.COMPLETED, completed.single().outcome)
        assertTrue(completed.single().isTerminal)
        assertTrue("no ROLLBACK_FAILED may be emitted", f.events.none { it.stage == "ROLLBACK_FAILED" })
        assertTrue("no success event may be emitted", f.events.none { it.stage == "RESTART_REQUIRED" })
        assertNull("no semantic outcome exists for a failed restore", (result.getOrNull() as? DatabaseImportResult.SuccessNeedsRestart))
        forbiddenIn("exception text", error.toString(), f)
        f.failReasons.forEach { forbiddenIn("journal reason", it, f) }
        forbiddenIn("persisted journal error", f.journal.readFailureJournal()?.error, f)
    }

    private fun c3Events(f: Fixture) =
        f.events.filter { it.stage == "LIVE_DB_VERIFIED" && it.outcome == EventOutcome.FAILED_FINAL }

    private fun assertC3ExpectationWasCompare(f: Fixture) {
        val c3 = f.verifyExpectations.filterNotNull()
        assertEquals(listOf(SemanticCheckpoint.C3_LIVE_POST_SWAP), c3.map { it.checkpoint })
        assertTrue("C3 must compare, but was ${c3.single().basis::class.java.simpleName}", c3.single().basis is SemanticBasis.Compare)
    }

    // ── AE-16 / AE-10 / INV-15: the round trip is VERIFIED ───────────────────────────────────────

    @Test
    fun `AE16 AE10 INV15 backup then restore of fixture F restores SuccessNeedsRestart with semanticVerification VERIFIED`() =
        runTest(testDispatcher) {
            val source = realSource()
            val section = RealSchemaFixture.capture(source.file)
            val f = buildFixture(source, roundTripManifest(source, section))

            val result = runRestore(f)

            assertRestoredWith(f, result, SemanticVerificationOutcome.Verified)
            assertC3ExpectationWasCompare(f)
            val summary = (result.getOrThrow() as DatabaseImportResult.SuccessNeedsRestart).summary
            assertEquals("the live counts of the restored database", source.counts["expenses"], summary.transactionCount)
            assertTrue("no semantic failure event on the good path", f.events.none { it.reasonCode == DiagnosticReasonCode.VALIDATION_FAILED })
            assertTrue(c3Events(f).isEmpty())
            assertTrue(
                "a COMPLETED LIVE_DB_VERIFIED event is emitted",
                f.events.any { it.stage == "LIVE_DB_VERIFIED" && it.outcome == EventOutcome.COMPLETED }
            )
        }

    // ── AE-9 / INV-8: a C3 mismatch rolls back through the existing rollback ───────────────────

    @Test
    fun `AE9 INV8 INV14 C3 mismatch after the swap rolls back with VERIFICATION_FAILED_ROLLED_BACK and a bounded C3 event`() =
        runTest(testDispatcher) {
            val source = realSource()
            val section = RealSchemaFixture.capture(source.file)
            val hooks = Hooks().apply { afterSwap = { live -> alterE3Live(live) } }
            val f = buildFixture(source, roundTripManifest(source, section), hooks)

            val result = runRestore(f)

            assertRolledBack(f, result)
            assertC3ExpectationWasCompare(f)
            val event = c3Events(f).single()
            assertEquals(DiagnosticReasonCode.VALIDATION_FAILED, event.reasonCode)
            assertEquals(EventSeverity.ERROR, event.severity)
            assertNull("no Throwable may be attached to a semantic event", event.exception)
            val metadata = JSONObject(event.metadataJson)
            val keys = metadata.keys().asSequence().toSet()
            assertTrue(
                "only the four bounded keys may be carried: $keys",
                setOf("semanticCode", "aggregateId", "checkpoint", "mismatchedBuckets").containsAll(keys)
            )
            assertEquals(SemanticCode.SEMANTIC_MISMATCH.name, metadata.getString("semanticCode"))
            assertEquals(SemanticCheckpoint.C3_LIVE_POST_SWAP.name, metadata.getString("checkpoint"))
            assertEquals("exactly the altered bucket differs", "1", metadata.get("mismatchedBuckets").toString())
            assertTrue(
                "E3 is a DEPOSIT, so only the raw or the income fingerprint can name it",
                metadata.getString("aggregateId") in setOf(
                    SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY.name,
                    SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY.name
                )
            )
            forbiddenIn("C3 event metadata", event.metadataJson, f)
            // C1 and C2 passed: no pre-swap failure event exists.
            assertTrue(
                f.events.none {
                    (it.stage == "STAGED_DB_VERIFIED" || it.stage == "STAGED_DB_POST_MIGRATION_VERIFIED") &&
                        it.outcome == EventOutcome.FAILED_FINAL
                }
            )
            // The C3 event precedes the rollback events.
            val stages = f.events.map { it.stage }
            assertTrue(stages.indexOf("LIVE_DB_VERIFIED") < stages.indexOf("ROLLBACK_STARTED"))
            assertTrue(stages.indexOf("ROLLBACK_STARTED") < stages.indexOf("ROLLBACK_COMPLETED"))
        }

    @Test
    fun `AE9 INV8 rollback failure during the C3 triggered rollback leaves CRITICAL_RECOVERY_REQUIRED unchanged`() =
        runTest(testDispatcher) {
            val source = realSource()
            val section = RealSchemaFixture.capture(source.file)
            val hooks = Hooks().apply {
                afterSwap = { live -> alterE3Live(live) }
                failRollbackReopen = true
            }
            val f = buildFixture(source, roundTripManifest(source, section), hooks)

            val result = runRestore(f)

            assertTrue(result.isFailure)
            assertEquals("RESTORE_OPERATION_UNCERTAIN", result.exceptionOrNull()?.message)
            assertFalse(result.exceptionOrNull() is BackupSemanticVerificationException)
            assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, maintenance.currentMode())
            verify(exactly = 1) { maintenance.enterCriticalRecoveryRequired("RESTORE_OPERATION_UNCERTAIN") }
            verify(exactly = 0) { maintenance.exit(false) }
            verify(exactly = 0) { maintenance.exit(true) }
            assertTrue("no terminal failure journal may be written: ${f.failReasons}", f.failReasons.isEmpty())
            assertTrue("the active journal is preserved for the startup recovery", f.journal.hasJournal())
            assertEquals(RestoreJournal.JournalState.ROLLING_BACK, f.journal.readJournal()?.state)
            assertTrue("the safety backup is retained", File(f.directory, "safety_backups").listFiles().orEmpty().isNotEmpty())
            assertEquals(1, c3Events(f).size)
            assertEquals(DiagnosticReasonCode.VALIDATION_FAILED, c3Events(f).single().reasonCode)
            val failed = f.events.filter { it.stage == "ROLLBACK_FAILED" }
            assertEquals(1, failed.size)
            assertEquals(EventSeverity.CRITICAL, failed.single().severity)
            assertTrue(failed.single().isTerminal)
            assertTrue("no ROLLBACK_COMPLETED may be emitted", f.events.none { it.stage == "ROLLBACK_COMPLETED" })
            assertTrue("no success event may be emitted", f.events.none { it.stage == "RESTART_REQUIRED" })
            forbiddenIn("exception text", result.exceptionOrNull().toString(), f)
        }

    @Test
    fun `INV11 INV8 the same live alteration never blocks when the section is absent (LEGACY at C3)`() =
        runTest(testDispatcher) {
            val source = realSource()
            // Control for the C3 mismatch test: identical alteration, but there is nothing to compare against.
            val hooks = Hooks().apply { afterSwap = { live -> alterE3Live(live) } }
            val f = buildFixture(source, roundTripManifest(source, section = null), hooks)

            val result = runRestore(f)

            assertRestoredWith(
                f, result, SemanticVerificationOutcome.NotVerifiable(SemanticNotVerifiableReason.LEGACY)
            )
            val c3 = f.verifyExpectations.filterNotNull().single()
            assertEquals(SemanticCheckpoint.C3_LIVE_POST_SWAP, c3.checkpoint)
            assertTrue(c3.basis is SemanticBasis.NotVerifiable)
            assertTrue(c3Events(f).isEmpty())
        }

    // ── AE-15 / INV-14: a Tier-1 count mismatch is the generic, detail-free live failure ────────

    private suspend fun assertCountMismatchRollsBackWithoutDetail(captured: Boolean) {
        val source = realSource()
        val section = if (captured) RealSchemaFixture.capture(source.file) else null
        val hooks = Hooks().apply { afterSwap = { live -> dropOneExpenseLive(live) } }
        val f = buildFixture(source, roundTripManifest(source, section), hooks)

        val result = runRestore(f)

        assertRolledBack(f, result)
        // The semantic pass never ran (an earlier check failed), so there is no semantic failure to report.
        assertTrue("no LIVE_DB_VERIFIED event on the generic path", f.events.none { it.stage == "LIVE_DB_VERIFIED" })
        assertTrue(
            "no event may carry a semantic code or a validation reason",
            f.events.none { it.metadataJson.contains("semanticCode") || it.reasonCode == DiagnosticReasonCode.VALIDATION_FAILED }
        )
        assertTrue(f.events.none { it.exception is BackupSemanticVerificationException })
        // The count, table and integrity detail stays out of every log line (it used to be joined into the text).
        assertTrue(
            "no log line may carry the count mismatch detail: $logLines",
            logLines.none { it.contains("count mismatch", ignoreCase = true) }
        )
        f.events.filter { it.stage.startsWith("ROLLBACK") || it.stage.startsWith("LIVE_DB") }
            .forEach { forbiddenIn("event metadata of ${it.stage}", it.metadataJson, f) }
    }

    @Test
    fun `AE15 INV14 Tier-1 count mismatch after the swap rolls back through the generic path with a CAPTURED section`() =
        runTest(testDispatcher) {
            assertCountMismatchRollsBackWithoutDetail(captured = true)
        }

    @Test
    fun `AE12 AE15 INV14 Tier-1 count mismatch after the swap still rolls back when the section is absent (LEGACY)`() =
        runTest(testDispatcher) {
            assertCountMismatchRollsBackWithoutDetail(captured = false)
        }

    // ── AE-5 / AE-11 / AE-12 / INV-11: NOT_VERIFIABLE outcomes never block ─────────────────────

    @Test
    fun `AE5 INV11 NOT_CAPTURED section restores as NotVerifiable NOT_CAPTURED`() = runTest(testDispatcher) {
        val source = realSource()
        val section = SemanticAggregateSet.ofNotCaptured(SemanticNotCapturedReason.SEMANTIC_CAPTURE_FAILED)
        val f = buildFixture(source, roundTripManifest(source, section))

        val result = runRestore(f)

        assertRestoredWith(
            f, result, SemanticVerificationOutcome.NotVerifiable(SemanticNotVerifiableReason.NOT_CAPTURED)
        )
    }

    @Test
    fun `AE12 INV11 absent section restores as NotVerifiable LEGACY and the count and integrity gates still ran`() =
        runTest(testDispatcher) {
            val source = realSource()
            val f = buildFixture(source, roundTripManifest(source, section = null))

            val result = runRestore(f)

            assertRestoredWith(
                f, result, SemanticVerificationOutcome.NotVerifiable(SemanticNotVerifiableReason.LEGACY)
            )
            assertEquals(
                "the full verification still ran at C3",
                1, f.verifyExpectations.filterNotNull().size
            )
        }

    @Test
    fun `AE11 INV10 wrong SPENDING definition hash restores as NotVerifiable DEFINITION_CHANGED while the others still compare`() =
        runTest(testDispatcher) {
            val source = realSource()
            val section = RealSchemaFixture.capture(source.file)
            val manifest = roundTripManifest(source, section) { json ->
                json.getJSONObject("semanticAggregates").getJSONObject("aggregates")
                    .getJSONObject(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY.name)
                    .put("definitionHash", "fedcba9876543210")
            }
            assertFalse("precondition: a wrong hash is not malformed", manifest.semanticSectionMalformed)
            val f = buildFixture(source, manifest)

            val result = runRestore(f)

            assertRestoredWith(
                f, result, SemanticVerificationOutcome.NotVerifiable(SemanticNotVerifiableReason.DEFINITION_CHANGED)
            )
            assertC3ExpectationWasCompare(f)
        }

    @Test
    fun `AE11 INV10 INV8 a wrong SPENDING hash does not mask a mismatch in an aggregate whose hash is right`() =
        runTest(testDispatcher) {
            val source = realSource()
            val section = RealSchemaFixture.capture(source.file)
            val manifest = roundTripManifest(source, section) { json ->
                json.getJSONObject("semanticAggregates").getJSONObject("aggregates")
                    .getJSONObject(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY.name)
                    .put("definitionHash", "fedcba9876543210")
            }
            val hooks = Hooks().apply { afterSwap = { live -> alterE3Live(live) } }
            val f = buildFixture(source, manifest, hooks)

            val result = runRestore(f)

            assertRolledBack(f, result)
            assertEquals(1, c3Events(f).size)
        }

    @Test
    fun `INV11 unknown section version restores as NotVerifiable VERSION`() = runTest(testDispatcher) {
        val source = realSource()
        val manifest = roundTripManifest(source, RealSchemaFixture.capture(source.file)) { json ->
            json.put(
                "semanticAggregates",
                JSONObject().put("version", 99).put("status", "CAPTURED")
                    .put("aggregates", JSONObject().put("FUTURE_ONLY", JSONObject().put("anything", 1)))
            )
        }
        assertFalse("precondition: version 99 is not malformed", manifest.semanticSectionMalformed)
        val f = buildFixture(source, manifest)

        val result = runRestore(f)

        assertRestoredWith(
            f, result, SemanticVerificationOutcome.NotVerifiable(SemanticNotVerifiableReason.VERSION)
        )
    }

    // ── INV-15: combineSemanticOutcome ───────────────────────────────────────────────────────────

    private fun verified(cp: SemanticCheckpoint) = SemanticCheckResult.Verified(cp)

    private fun notVerifiable(cp: SemanticCheckpoint, reason: SemanticNotVerifiableReason) =
        SemanticCheckResult.NotVerifiable(cp, reason)

    private fun failed(cp: SemanticCheckpoint, id: SemanticAggregateId? = SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY) =
        SemanticCheckResult.Failed(cp, SemanticCode.SEMANTIC_MISMATCH, id, 2)

    private val c1 = SemanticCheckpoint.C1_STAGED_SOURCE
    private val c2 = SemanticCheckpoint.C2_STAGED_POST_MIGRATION
    private val c3 = SemanticCheckpoint.C3_LIVE_POST_SWAP

    private fun combine(a: SemanticCheckResult?, b: SemanticCheckResult?, c: SemanticCheckResult?) =
        DatabaseBackupRepositoryImpl.combineSemanticOutcome(a, b, c)

    @Test
    fun `INV15 combine all three Verified is Verified`() {
        assertEquals(SemanticVerificationOutcome.Verified, combine(verified(c1), verified(c2), verified(c3)))
    }

    @Test
    fun `INV15 combine takes the first non-verified reason in C1 C2 C3 order`() {
        fun nv(cp: SemanticCheckpoint, reason: SemanticNotVerifiableReason) = notVerifiable(cp, reason)
        fun out(reason: SemanticNotVerifiableReason) = SemanticVerificationOutcome.NotVerifiable(reason)

        assertEquals(
            out(SemanticNotVerifiableReason.LEGACY),
            combine(
                nv(c1, SemanticNotVerifiableReason.LEGACY),
                nv(c2, SemanticNotVerifiableReason.VERSION),
                nv(c3, SemanticNotVerifiableReason.NOT_CAPTURED)
            )
        )
        assertEquals(
            out(SemanticNotVerifiableReason.VERSION),
            combine(
                nv(c1, SemanticNotVerifiableReason.VERSION),
                nv(c2, SemanticNotVerifiableReason.LEGACY),
                verified(c3)
            )
        )
        assertEquals(
            out(SemanticNotVerifiableReason.DEFINITION_CHANGED),
            combine(
                verified(c1),
                nv(c2, SemanticNotVerifiableReason.DEFINITION_CHANGED),
                nv(c3, SemanticNotVerifiableReason.VERSION)
            )
        )
        // A reason that only appears at C3 is still reported (boundary: last position).
        assertEquals(
            out(SemanticNotVerifiableReason.NOT_CAPTURED),
            combine(verified(c1), verified(c2), nv(c3, SemanticNotVerifiableReason.NOT_CAPTURED))
        )
    }

    @Test
    fun `INV15 combine a Failed at any checkpoint throws the typed bounded exception`() {
        fun assertThrows(label: String, a: SemanticCheckResult?, b: SemanticCheckResult?, c: SemanticCheckResult?, at: SemanticCheckpoint) {
            try {
                combine(a, b, c)
                fail("$label: a Failed result must never become an outcome")
            } catch (e: BackupSemanticVerificationException) {
                assertEquals(label, SemanticCode.SEMANTIC_MISMATCH, e.code)
                assertEquals(label, at, e.checkpoint)
                assertEquals(label, SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY, e.aggregateId)
                assertEquals(label, 2, e.mismatchedBuckets)
                assertEquals("message is the bounded code only", SemanticCode.SEMANTIC_MISMATCH.name, e.message)
            }
        }
        assertThrows("C1", failed(c1), verified(c2), verified(c3), c1)
        assertThrows("C2", verified(c1), failed(c2), verified(c3), c2)
        assertThrows("C3", verified(c1), verified(c2), failed(c3), c3)
        // A Failed wins over an earlier NOT_VERIFIABLE and over an earlier missing result.
        assertThrows("C3 after NOT_VERIFIABLE", notVerifiable(c1, SemanticNotVerifiableReason.LEGACY), null, failed(c3), c3)
        assertThrows("C2 after null", null, failed(c2), verified(c3), c2)
    }

    @Test
    fun `INV15 combine a missing checkpoint result is never Verified and maps to NOT_CAPTURED`() {
        val notCaptured = SemanticVerificationOutcome.NotVerifiable(SemanticNotVerifiableReason.NOT_CAPTURED)

        assertEquals(notCaptured, combine(null, null, null))
        assertEquals(notCaptured, combine(verified(c1), verified(c2), null))
        assertEquals(notCaptured, combine(null, verified(c2), verified(c3)))
        // Order still applies: the earlier real reason wins over a later missing result, and vice versa.
        assertEquals(
            SemanticVerificationOutcome.NotVerifiable(SemanticNotVerifiableReason.LEGACY),
            combine(notVerifiable(c1, SemanticNotVerifiableReason.LEGACY), null, verified(c3))
        )
        assertEquals(
            notCaptured,
            combine(null, notVerifiable(c2, SemanticNotVerifiableReason.LEGACY), verified(c3))
        )
        assertFalse(combine(verified(c1), verified(c2), null) is SemanticVerificationOutcome.Verified)
        assertNotNull(combine(null, null, null))
    }

    private companion object {
        const val FIXED_TIME = 1716163200000L
    }
}
