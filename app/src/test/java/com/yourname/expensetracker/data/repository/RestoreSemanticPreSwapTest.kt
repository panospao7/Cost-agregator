package com.yourname.expensetracker.data.repository

import android.content.Context
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.BackupVerifier
import com.yourname.expensetracker.data.backup.CostbackupBundle
import com.yourname.expensetracker.data.backup.RealSchemaFixture
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.backup.SemanticAggregateId
import com.yourname.expensetracker.data.backup.SemanticAggregateSet
import com.yourname.expensetracker.data.backup.SemanticBasis
import com.yourname.expensetracker.data.backup.SemanticCaptureStatus
import com.yourname.expensetracker.data.backup.SemanticCheckpoint
import com.yourname.expensetracker.data.backup.SemanticCode
import com.yourname.expensetracker.data.backup.SemanticExpectation
import com.yourname.expensetracker.data.backup.SemanticNotCapturedReason
import com.yourname.expensetracker.data.database.APP_DATABASE_SCHEMA_VERSION
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.backup.DatabaseImportResult
import com.yourname.expensetracker.domain.backup.SemanticNotVerifiableReason
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.OperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPreBackupReconciler
import com.yourname.expensetracker.domain.installment.lifecycle.PreBackupReconcileResult
import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import com.yourname.expensetracker.data.privacy.ExportAnonymizer
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * UF-46 / S3 (restore-semantic-verify): the PRE-SWAP semantic gates of `restoreCostBackup`.
 *
 * Oracle: `docs/features/restore-semantic-verify/domain-spec.md` (INV-7, INV-9, INV-11, INV-12, INV-13,
 * INV-14, AE-1, AE-8, AE-10, AE-13, AE-14, AE-15), never the implementation.
 *
 * Real boundaries: a real file-backed [RestoreJournal], real staged database files on the real Room schema
 * (fixture F via [RealSchemaFixture]) and the real [BackupVerifier]. Mocked, peripheral only: the Room open
 * of the staged copy (the migration), the bundle extraction (crypto), maintenance mode, and the diagnostics
 * recorder. Synthetic data only.
 *
 * Three distinct failing states are driven through the repository:
 *  - malformed section: blocked before anything is staged;
 *  - C1: staged copy differs at the source schema;
 *  - C2: staged copy differs only after the (mocked) migration open, i.e. between C1 and C2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreSemanticPreSwapTest {

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
    private val preBackupReconciler = mockk<InstallmentPreBackupReconciler> {
        coEvery { reconcileBeforeBackup() } returns PreBackupReconcileResult.Reconciled
    }
    private val testDispatcher = StandardTestDispatcher()
    private val realAppContext: Context by lazy { ApplicationProvider.getApplicationContext() }

    private lateinit var tempDir: File
    private var fixtureSeq = 0

    @Before
    fun setUp() {
        tempDir = java.nio.file.Files.createTempDirectory("rsv-s3-").toFile()
    }

    @After
    fun tearDown() {
        unmockkAll()
        tempDir.deleteRecursively()
    }

    // ── Cancellation sentinel (coroutine debug recovery must not copy it) ───

    private class IdentityCancellationException(message: String) :
        CancellationException(message), CopyableThrowable<IdentityCancellationException> {
        override fun createCopy(): IdentityCancellationException? = null
    }

    // ── Fixture ──────────────────────────────────────────────────────────

    private class Source(val file: File, val counts: Map<String, Int>)

    private class RecordedEvent(
        val stage: String,
        val outcome: EventOutcome,
        val reasonCode: DiagnosticReasonCode?,
        val metadataJson: String,
        val exception: Throwable?
    )

    private class Fixture(
        val directory: File,
        val liveDb: File,
        val repo: DatabaseBackupRepositoryImpl,
        val journal: RestoreJournal,
        val failReasons: MutableList<String>,
        val trace: MutableList<String>,
        val events: MutableList<RecordedEvent>,
        val expectations: MutableList<SemanticExpectation?>,
        val stagedFile: AtomicReference<File?>
    )

    /** Real-schema fixture F (6 expenses, 2 recurring rules) as a database file outside the app files dir. */
    private fun realSource(): Source {
        val file = File(tempDir, "source-${UUID.randomUUID()}.sqlite")
        val created = RealSchemaFixture.createFixtureF(realSchemaHelper, "rsv-${fixtureSeq++}")
        created.copyTo(file, overwrite = true)
        created.delete()
        return Source(file, RealSchemaFixture.manifestCounts(file))
    }

    /**
     * Every tracked table with only an id column (plus the few reference columns the orphan checks read) and one
     * expenses row. It has NO amount, currency or type column: any semantic aggregate query against it fails.
     */
    private fun idOnlySource(): Source {
        val file = File(tempDir, "idonly-${UUID.randomUUID()}.sqlite")
        createIdOnlySchemaDatabase(file)
        return Source(file, BackupVerifier.allTableNames().associateWith { if (it == "expenses") 1 else 0 })
    }

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
                    "expenses" -> ", spendingExclusionReason TEXT, refundNettedAmount REAL NOT NULL DEFAULT 0.0"
                    "expense_refund_links" -> ", refundExpenseId INTEGER, purchaseExpenseId INTEGER, appliedAmount REAL"
                    "installment_plans" -> ", purchaseExpenseId INTEGER, displayName TEXT, lenderLabel TEXT"
                    "installment_entries" -> ", planId INTEGER, linkedExpenseId INTEGER, status TEXT"
                    else -> ""
                }
                db.execSQL("CREATE TABLE IF NOT EXISTS \"$tableName\" (id INTEGER PRIMARY KEY$referenceColumn)")
            }
            db.execSQL("INSERT INTO expenses (id) VALUES (1)")
        } finally {
            db.close()
        }
    }

    private fun manifest(
        source: Source,
        section: SemanticAggregateSet?,
        version: Int = APP_DATABASE_SCHEMA_VERSION,
        malformed: Boolean = false
    ) = CostbackupBundle.BackupManifest(
        databaseVersion = version,
        createdAt = FIXED_TIME,
        tableCounts = source.counts,
        semanticAggregates = section,
        semanticSectionMalformed = malformed
    )

    /** Alters E3 (DEPOSIT EUR 1000.0) without changing any row count. Detected by the aggregates only. */
    private fun alterE3(file: File) =
        RealSchemaFixture.mutate(file, "UPDATE expenses SET amount = 1001.0 WHERE id = 3")

    private fun parsedManifest(source: Source, edit: (JSONObject) -> Unit): CostbackupBundle.BackupManifest {
        val json = manifest(source, RealSchemaFixture.capture(source.file)).toJson()
        edit(json)
        return CostbackupBundle.BackupManifest.fromJson(json, FIXED_TIME)
    }

    private fun buildFixture(
        source: Source,
        manifest: CostbackupBundle.BackupManifest,
        onStagedOpened: ((File) -> Unit)? = null,
        beforeVerifyQuick: ((Int) -> Unit)? = null
    ): Fixture {
        val directory = File(tempDir, UUID.randomUUID().toString()).apply { mkdirs() }
        every { context.filesDir } returns directory
        every { context.cacheDir } returns File(directory, "cache").apply { mkdirs() }
        every { context.applicationContext } returns realAppContext
        every { context.getDatabasePath(any()) } answers { File(directory, firstArg<String>()) }
        val liveDb = File(directory, AppDatabase.DATABASE_NAME)
        createIdOnlySchemaDatabase(liveDb)
        // A marker row so the live bytes can never equal a restored source (a swap is then observable).
        RealSchemaFixture.mutate(liveDb, "INSERT INTO expenses (id) VALUES (777)")

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
        every { liveOpenHelper.writableDatabase } returns supportDb

        val liveCounts = source.counts
        every { supportDb.query(match<String> { it.startsWith("SELECT COUNT(*)") }) } answers {
            val table = Regex("\"([^\"]+)\"").find(firstArg<String>())!!.groupValues[1]
            MatrixCursor(arrayOf("count")).apply { addRow(arrayOf(liveCounts[table] ?: 0)) }
        }
        every { supportDb.query("PRAGMA wal_checkpoint(TRUNCATE)") } answers {
            MatrixCursor(arrayOf("busy")).apply { addRow(arrayOf(0)) }
        }

        // The staged Room open is the (mocked) migration. The hook lets a test alter the staged file between
        // C1 and C2, which is exactly where a migration could corrupt aggregate content.
        val stagedFile = AtomicReference<File?>(null)
        mockkObject(AppDatabase.Companion)
        val liveBuilder = mockk<androidx.room.RoomDatabase.Builder<AppDatabase>>()
        val stageBuilder = mockk<androidx.room.RoomDatabase.Builder<AppDatabase>>()
        val stagedDatabase = mockk<AppDatabase>(relaxed = true)
        every { stagedDatabase.openHelper } returns stagedOpenHelper
        every { stagedOpenHelper.writableDatabase } answers {
            stagedFile.get()?.let { file -> onStagedOpened?.invoke(file) }
            supportDb
        }
        every { liveBuilder.build() } returns database
        every { stageBuilder.build() } returns stagedDatabase
        every { AppDatabase.fileBuilder(context, any()) } answers {
            val name = secondArg<String>()
            if (name == AppDatabase.DATABASE_NAME) liveBuilder else {
                stagedFile.set(File(directory, name))
                stageBuilder
            }
        }

        // Observes the semantic expectation handed to every verifyQuick (C1 first, C2 second). The real
        // verification still runs.
        val expectations = mutableListOf<SemanticExpectation?>()
        mockkObject(BackupVerifier)
        every { BackupVerifier.verifyQuick(any(), any(), any()) } answers {
            expectations += arg<SemanticExpectation?>(2)
            beforeVerifyQuick?.invoke(expectations.size)
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
            trace += "JOURNAL_" + secondArg<RestoreJournal.JournalState>().name
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

        val events = mutableListOf<RecordedEvent>()
        val handle = mockk<OperationRunHandle>(relaxed = true)
        coEvery { handle.event(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            events += RecordedEvent(
                stage = arg<String>(0),
                outcome = arg<EventOutcome>(1),
                reasonCode = arg<DiagnosticReasonCode?>(2),
                metadataJson = arg<SafeEventMetadata>(4).toJson(),
                exception = arg<Throwable?>(7)
            )
            Unit
        }
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
            stagedImportVerifier = { _, _, _, _, summary -> summary },
            liveImportVerifier = { _, _, _, summary -> summary },
            timeProvider = FakeTimeProvider(FIXED_TIME),
            currencySettingsRepository = io.mockk.mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository>(relaxed = true) {
                io.mockk.every { homeCurrency() } returns kotlinx.coroutines.flow.flowOf("EUR")
            },
            installmentPreBackupReconciler = dagger.Lazy { preBackupReconciler },
            operationRunRecorder = recorder
        )
        return Fixture(directory, liveDb, repo, journal, failReasons, trace, events, expectations, stagedFile)
    }

    private suspend fun runRestore(f: Fixture): Result<DatabaseImportResult> =
        f.repo.restoreCostBackup(File(tempDir, "bundle.costbackup"), "pw")

    // ── Assertions ───────────────────────────────────────────────────────

    private fun stagingResidue(f: Fixture): List<File> =
        f.directory.listFiles { file -> file.name.startsWith("expense_tracker_db_import_stage_") }.orEmpty().toList()

    private fun extractionResidue(f: Fixture): List<File> =
        File(f.directory, "cache").listFiles { file -> file.name.startsWith("costbackup_extract_") }
            .orEmpty().toList()

    private fun safetyBackups(f: Fixture): List<File> =
        File(f.directory, "safety_backups").listFiles().orEmpty().toList()

    /** The pre-destructive abort: nothing destructive happened, everything the restore created is gone. */
    private fun assertAbortedBeforeAnyDestructiveStep(f: Fixture, liveBefore: ByteArray) {
        assertArrayEquals("live database must be byte-identical", liveBefore, f.liveDb.readBytes())
        assertTrue("no safety backup may be created: ${safetyBackups(f)}", safetyBackups(f).isEmpty())
        assertFalse(File(f.liveDb.path + ".pre_restore").exists())
        assertTrue("staging residue leaked: ${stagingResidue(f)}", stagingResidue(f).isEmpty())
        assertTrue("extraction residue leaked: ${extractionResidue(f)}", extractionResidue(f).isEmpty())
        listOf(
            "JOURNAL_STAGED", "JOURNAL_SAFETY_BACKUP_CREATED", "JOURNAL_SWAPPING", "MODE_RESTORE_SWAPPING", "CLOSE_LIVE"
        ).forEach { step -> assertFalse("must not reach $step: ${f.trace}", f.trace.contains(step)) }
        verify(exactly = 0) { database.close() }
        verify(exactly = 0) { maintenance.exit(forceRestartRequired = true) }
        verify(exactly = 1) { maintenance.exit(forceRestartRequired = false) }
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, maintenance.currentMode())
        assertTrue(
            "the terminal failed journal is written before the maintenance exit",
            f.trace.indexOf("FINALIZED_FAILURE") in 0 until f.trace.indexOf("EXIT_NORMAL")
        )
        assertFalse("no active journal may remain after an abort", f.journal.hasJournal())
        assertEquals(RestoreJournal.JournalState.FAILED, f.journal.readFailureJournal()?.state)
    }

    /** Persisted `error` stays the controlled UNKNOWN_ERROR code for the reused literals (errata E-1). */
    private fun assertPersistedErrorUnchanged(f: Fixture) {
        assertEquals(DiagnosticReasonCode.UNKNOWN_ERROR.name, f.journal.readFailureJournal()?.error)
    }

    private fun assertBounded(label: String, text: String?, f: Fixture) {
        val forbidden = RealSchemaFixture.forbiddenValueText +
            listOf(f.directory.absolutePath, tempDir.absolutePath, "no such column", "FROM", "WHERE")
        forbidden.forEach { assertFalse("$label leaked '$it': $text", text?.contains(it) == true) }
    }

    private fun terminalEvent(f: Fixture, stage: String): RecordedEvent {
        val matching = f.events.filter { it.stage == stage && it.outcome == EventOutcome.FAILED_FINAL }
        assertEquals("exactly one terminal $stage event", 1, matching.size)
        return matching.single()
    }

    private fun assertBoundedSemanticEvent(event: RecordedEvent, code: SemanticCode, checkpoint: SemanticCheckpoint?) {
        val metadata = JSONObject(event.metadataJson)
        val keys = metadata.keys().asSequence().toSet()
        assertTrue(
            "only the four bounded keys may be carried: $keys",
            setOf("semanticCode", "aggregateId", "checkpoint", "mismatchedBuckets").containsAll(keys)
        )
        assertEquals(code.name, metadata.getString("semanticCode"))
        if (checkpoint != null) assertEquals(checkpoint.name, metadata.getString("checkpoint"))
        assertNull("no Throwable may be attached to a semantic event", event.exception)
    }

    private fun assertSemanticFailure(
        f: Fixture,
        result: Result<DatabaseImportResult>,
        code: SemanticCode,
        checkpoint: SemanticCheckpoint?,
        journalLiteral: String,
        eventStage: String
    ): BackupSemanticVerificationException {
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as? BackupSemanticVerificationException
        assertNotNull("failure must be the typed semantic exception but was ${result.exceptionOrNull()}", error)
        error!!
        assertEquals(code, error.code)
        assertEquals(checkpoint, error.checkpoint)
        assertEquals("message is the bounded code only", code.name, error.message)
        assertEquals("the literal passed to failJournal is the reused one", listOf(journalLiteral), f.failReasons)
        assertPersistedErrorUnchanged(f)
        val event = terminalEvent(f, eventStage)
        assertBoundedSemanticEvent(event, code, checkpoint)
        // Domain spec section 6: every semantic failure event (malformed, C1, C2, and C3 in the post-swap
        // tests) carries the existing VALIDATION_FAILED reason code, never null.
        assertEquals(DiagnosticReasonCode.VALIDATION_FAILED, event.reasonCode)
        assertBounded("exception message", error.message, f)
        assertBounded("exception text", error.toString(), f)
        assertBounded("event metadata", event.metadataJson, f)
        f.failReasons.forEach { assertBounded("journal reason", it, f) }
        assertBounded("persisted journal error", f.journal.readFailureJournal()?.error, f)
        return error
    }

    /** The restore reached the swap: C1 and C2 let it through and the safety backup exists. */
    private fun assertProceededPastC2(f: Fixture, liveBefore: ByteArray, result: Result<DatabaseImportResult>) {
        assertTrue("no journal failure may be recorded: ${f.failReasons}", f.failReasons.isEmpty())
        assertFalse(
            "the restore must not be blocked by a semantic failure",
            result.exceptionOrNull() is BackupSemanticVerificationException
        )
        val expected = listOf(
            "MODE_RESTORE_PREPARING", "MODE_RESTORE_STAGING", "JOURNAL_STAGED",
            "JOURNAL_SAFETY_BACKUP_CREATED", "JOURNAL_SWAPPING", "MODE_RESTORE_SWAPPING", "CLOSE_LIVE"
        )
        var previous = -1
        expected.forEach { step ->
            val index = f.trace.indexOf(step)
            assertTrue("expected $step after index $previous in ${f.trace}", index > previous)
            previous = index
        }
        assertTrue("the safety backup must exist once C1 and C2 passed", safetyBackups(f).isNotEmpty())
        assertFalse("the swap replaced the live database", liveBefore.contentEquals(f.liveDb.readBytes()))
        assertTrue("restore must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertTrue(result.getOrNull() is DatabaseImportResult.SuccessNeedsRestart)
    }

    private fun assertExpectations(f: Fixture, vararg expected: SemanticCheckpoint) {
        assertEquals(expected.toList(), f.expectations.map { it?.checkpoint })
    }

    private fun assertNotVerifiableBasis(f: Fixture, reason: SemanticNotVerifiableReason) {
        assertExpectations(f, SemanticCheckpoint.C1_STAGED_SOURCE, SemanticCheckpoint.C2_STAGED_POST_MIGRATION)
        f.expectations.forEach { expectation ->
            val basis = expectation!!.basis
            assertTrue("basis must be NotVerifiable but was ${basis::class.java.simpleName}", basis is SemanticBasis.NotVerifiable)
            assertEquals(reason, (basis as SemanticBasis.NotVerifiable).reason)
        }
    }

    private fun assertCompareBasis(f: Fixture) {
        f.expectations.forEach { expectation ->
            assertTrue(
                "basis must be Compare but was ${expectation!!.basis::class.java.simpleName}",
                expectation.basis is SemanticBasis.Compare
            )
        }
    }

    // ── AE-1 / INV-12: a malformed section blocks the restore before anything is staged ─────────────

    private suspend fun assertMalformedSectionBlocked(edit: (JSONObject) -> Unit) {
        val source = realSource()
        val parsed = parsedManifest(source, edit)
        // The parser does not throw, yields no set and flags the section (INV-12).
        assertTrue("precondition: the edited section must be flagged malformed", parsed.semanticSectionMalformed)
        assertNull(parsed.semanticAggregates)

        val f = buildFixture(source, parsed)
        val liveBefore = f.liveDb.readBytes()

        val result = runRestore(f)

        assertSemanticFailure(
            f, result, SemanticCode.SEMANTIC_MANIFEST_MALFORMED, null,
            journalLiteral = "MANIFEST_INCOMPLETE", eventStage = "BUNDLE_VALIDATED"
        )
        val event = terminalEvent(f, "BUNDLE_VALIDATED")
        assertEquals(DiagnosticReasonCode.VALIDATION_FAILED, event.reasonCode)
        assertAbortedBeforeAnyDestructiveStep(f, liveBefore)
        assertNull("nothing may be staged for a malformed section", f.stagedFile.get())
        assertTrue("no verification may run for a malformed section", f.expectations.isEmpty())
    }

    @Test
    fun `AE1 INV12 section with a SQL looking aggregate key is rejected before staging`() = runTest(testDispatcher) {
        assertMalformedSectionBlocked { manifestJson ->
            manifestJson.getJSONObject("semanticAggregates").getJSONObject("aggregates").put(
                "SELECT 1",
                JSONObject().put("definitionHash", "0123456789abcdef").put("buckets", JSONArray())
            )
        }
    }

    @Test
    fun `AE1 INV12 section with a negative bucket count is rejected before staging`() = runTest(testDispatcher) {
        assertMalformedSectionBlocked { manifestJson ->
            manifestJson.getJSONObject("semanticAggregates").getJSONObject("aggregates")
                .getJSONObject(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY.name)
                .getJSONArray("buckets").getJSONObject(0).put("n", -1)
        }
    }

    @Test
    fun `AE1 INV12 NOT_CAPTURED section that still carries aggregates is rejected before staging`() = runTest(testDispatcher) {
        assertMalformedSectionBlocked { manifestJson ->
            manifestJson.getJSONObject("semanticAggregates").put("status", "NOT_CAPTURED")
        }
    }

    @Test
    fun `AE1 INV12 section with a duplicate bucket key is rejected before staging`() = runTest(testDispatcher) {
        assertMalformedSectionBlocked { manifestJson ->
            val buckets = manifestJson.getJSONObject("semanticAggregates").getJSONObject("aggregates")
                .getJSONObject(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY.name).getJSONArray("buckets")
            buckets.put(buckets.getJSONObject(0))
        }
    }

    @Test
    fun `AE1 INV12 section key that is not a JSON object is rejected before staging`() = runTest(testDispatcher) {
        assertMalformedSectionBlocked { manifestJson -> manifestJson.put("semanticAggregates", "SELECT 1") }
    }

    // ── AE-8 / INV-7: C1 and C2 mismatches abort before the safety backup and the swap ─────────────

    @Test
    fun `AE8 INV7 C1 mismatch aborts before the safety backup and leaves the live database byte-identical`() =
        runTest(testDispatcher) {
            val source = realSource()
            val section = RealSchemaFixture.capture(source.file)
            alterE3(source.file)
            val f = buildFixture(source, manifest(source, section))
            val liveBefore = f.liveDb.readBytes()

            val result = runRestore(f)

            val error = assertSemanticFailure(
                f, result, SemanticCode.SEMANTIC_MISMATCH, SemanticCheckpoint.C1_STAGED_SOURCE,
                journalLiteral = "STAGED_VERIFICATION_FAILED", eventStage = "STAGED_DB_VERIFIED"
            )
            assertTrue(
                "E3 is a DEPOSIT, so only the raw or the income fingerprint can name it",
                error.aggregateId in setOf(
                    SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY,
                    SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY
                )
            )
            assertEquals("exactly the altered bucket differs", 1, error.mismatchedBuckets)
            assertAbortedBeforeAnyDestructiveStep(f, liveBefore)
            assertExpectations(f, SemanticCheckpoint.C1_STAGED_SOURCE)
            assertNull("C1 fails before the staged database is migrated", f.stagedFile.get())
        }

    @Test
    fun `AE8 INV7 C2 only mismatch after the migration open aborts before the safety backup`() =
        runTest(testDispatcher) {
            val source = realSource()
            val section = RealSchemaFixture.capture(source.file)
            // The source is intact (C1 passes). The staged copy is altered when Room opens it for migration.
            val f = buildFixture(source, manifest(source, section), onStagedOpened = { staged -> alterE3(staged) })
            val liveBefore = f.liveDb.readBytes()

            val result = runRestore(f)

            val error = assertSemanticFailure(
                f, result, SemanticCode.SEMANTIC_MISMATCH, SemanticCheckpoint.C2_STAGED_POST_MIGRATION,
                journalLiteral = "POST_MIGRATION_VERIFICATION_FAILED", eventStage = "STAGED_DB_POST_MIGRATION_VERIFIED"
            )
            assertEquals(1, error.mismatchedBuckets)
            assertAbortedBeforeAnyDestructiveStep(f, liveBefore)
            assertExpectations(f, SemanticCheckpoint.C1_STAGED_SOURCE, SemanticCheckpoint.C2_STAGED_POST_MIGRATION)
            assertNotNull("the staged copy existed and was migrated before C2", f.stagedFile.get())
            assertFalse("the staged copy must be cleaned", f.stagedFile.get()!!.exists())
        }

    @Test
    fun `INV7 matching section passes C1 and C2 and the restore proceeds to the swap`() = runTest(testDispatcher) {
        val source = realSource()
        val section = RealSchemaFixture.capture(source.file)
        assertEquals("precondition: a CAPTURED section", SemanticCaptureStatus.CAPTURED, section.status)
        val f = buildFixture(source, manifest(source, section))
        val liveBefore = f.liveDb.readBytes()

        val result = runRestore(f)

        assertProceededPastC2(f, liveBefore, result)
        assertExpectations(f, SemanticCheckpoint.C1_STAGED_SOURCE, SemanticCheckpoint.C2_STAGED_POST_MIGRATION)
        assertCompareBasis(f)
    }

    // ── AE-10 / INV-9: sources below the aggregate floor are NOT_VERIFIABLE(VERSION) ─────────────────

    private suspend fun assertSourceBelowFloorProceeds(version: Int) {
        // The id-only source cannot answer any aggregate query. If a query ran, C1 would fail with
        // SEMANTIC_QUERY_FAILED, so reaching the swap proves the aggregate query never ran.
        val source = idOnlySource()
        val section = RealSchemaFixture.capture(realSource().file)
        val f = buildFixture(source, manifest(source, section, version = version))
        val liveBefore = f.liveDb.readBytes()

        val result = runRestore(f)

        assertProceededPastC2(f, liveBefore, result)
        assertNotVerifiableBasis(f, SemanticNotVerifiableReason.VERSION)
    }

    @Test
    fun `AE10 INV9 source schema 145 with a section is NOT_VERIFIABLE VERSION and proceeds`() = runTest(testDispatcher) {
        assertSourceBelowFloorProceeds(145)
    }

    @Test
    fun `AE10 INV9 source schema 150 with a section is NOT_VERIFIABLE VERSION and proceeds`() = runTest(testDispatcher) {
        assertSourceBelowFloorProceeds(150)
    }

    @Test
    fun `AE10 INV9 source schema 151 just below the floor is NOT_VERIFIABLE VERSION and proceeds`() = runTest(testDispatcher) {
        assertSourceBelowFloorProceeds(151)
    }

    @Test
    fun `AE14 INV13 query failure at the floor schema 152 blocks the restore and is never NOT_VERIFIABLE`() =
        runTest(testDispatcher) {
            // Boundary partner of the 151 test: the same unanswerable source, but at schema 152 the query runs.
            val source = idOnlySource()
            val section = RealSchemaFixture.capture(realSource().file)
            val f = buildFixture(source, manifest(source, section, version = 152))
            val liveBefore = f.liveDb.readBytes()

            val result = runRestore(f)

            assertSemanticFailure(
                f, result, SemanticCode.SEMANTIC_QUERY_FAILED, SemanticCheckpoint.C1_STAGED_SOURCE,
                journalLiteral = "STAGED_VERIFICATION_FAILED", eventStage = "STAGED_DB_VERIFIED"
            )
            assertAbortedBeforeAnyDestructiveStep(f, liveBefore)
            assertCompareBasis(f)
        }

    // ── AE-13 / INV-11 / INV-12: not verifiable sections never block ──────────────────────────────

    @Test
    fun `AE13 INV12 unknown section version is NOT_VERIFIABLE VERSION and never blocks even when data differs`() =
        runTest(testDispatcher) {
            val source = realSource()
            val parsed = parsedManifest(source) { manifestJson ->
                manifestJson.put(
                    "semanticAggregates",
                    JSONObject().put("version", 99).put("status", "CAPTURED")
                        .put("aggregates", JSONObject().put("FUTURE_ONLY", JSONObject().put("anything", 1)))
                )
            }
            assertFalse("precondition: version 99 is not malformed", parsed.semanticSectionMalformed)
            assertEquals(99, parsed.semanticAggregates?.version)
            alterE3(source.file)
            val f = buildFixture(source, parsed)
            val liveBefore = f.liveDb.readBytes()

            val result = runRestore(f)

            assertProceededPastC2(f, liveBefore, result)
            assertNotVerifiableBasis(f, SemanticNotVerifiableReason.VERSION)
        }

    @Test
    fun `INV11 absent section is NOT_VERIFIABLE LEGACY and never blocks even when data differs`() =
        runTest(testDispatcher) {
            val source = realSource()
            alterE3(source.file)
            val f = buildFixture(source, manifest(source, section = null))
            val liveBefore = f.liveDb.readBytes()

            val result = runRestore(f)

            assertProceededPastC2(f, liveBefore, result)
            assertNotVerifiableBasis(f, SemanticNotVerifiableReason.LEGACY)
        }

    @Test
    fun `INV11 NOT_CAPTURED section never blocks even when data differs`() = runTest(testDispatcher) {
        val source = realSource()
        alterE3(source.file)
        val section = SemanticAggregateSet.ofNotCaptured(SemanticNotCapturedReason.SEMANTIC_CAPTURE_FAILED)
        val f = buildFixture(source, manifest(source, section))
        val liveBefore = f.liveDb.readBytes()

        val result = runRestore(f)

        assertProceededPastC2(f, liveBefore, result)
        assertNotVerifiableBasis(f, SemanticNotVerifiableReason.NOT_CAPTURED)
    }

    @Test
    fun `INV11 NOT_CAPTURED section without a reason never blocks even when data differs`() = runTest(testDispatcher) {
        val source = realSource()
        val parsed = parsedManifest(source) { manifestJson ->
            manifestJson.put("semanticAggregates", JSONObject().put("version", 1).put("status", "NOT_CAPTURED"))
        }
        assertFalse("precondition: a missing reason is tolerated, not malformed", parsed.semanticSectionMalformed)
        assertEquals(SemanticCaptureStatus.NOT_CAPTURED, parsed.semanticAggregates?.status)
        assertNull(parsed.semanticAggregates?.notCapturedReason)
        alterE3(source.file)
        val f = buildFixture(source, parsed)
        val liveBefore = f.liveDb.readBytes()

        val result = runRestore(f)

        assertProceededPastC2(f, liveBefore, result)
        assertNotVerifiableBasis(f, SemanticNotVerifiableReason.NOT_CAPTURED)
    }

    // ── AE-14 / INV-13: cancellation at C2 propagates ────────────────────────────────────────────

    @Test
    fun `AE14 INV13 cancellation during C2 propagates cleans staging and leaves the live database untouched`() =
        runTest(testDispatcher) {
            val source = realSource()
            val section = RealSchemaFixture.capture(source.file)
            val cancellation = IdentityCancellationException("TEST_C2_CANCELLED")
            // Call 1 is C1 (runs for real and passes). Call 2 is C2 inside the cancellation-safe wrapper.
            val f = buildFixture(
                source,
                manifest(source, section),
                beforeVerifyQuick = { call -> if (call == 2) throw cancellation }
            )
            val liveBefore = f.liveDb.readBytes()

            var observed: Throwable? = null
            try {
                runRestore(f)
                fail("cancellation at C2 must propagate and never become a Result")
            } catch (e: CancellationException) {
                observed = e
            }

            assertSame("the original cancellation must propagate unwrapped", cancellation, observed)
            assertExpectations(f, SemanticCheckpoint.C1_STAGED_SOURCE, SemanticCheckpoint.C2_STAGED_POST_MIGRATION)
            assertEquals(
                "cancellation is a RESTORE_CANCELLED abort, never a semantic failure",
                listOf("RESTORE_CANCELLED"), f.failReasons
            )
            assertTrue(
                "no semantic event may be emitted for a cancellation",
                f.events.none { it.outcome == EventOutcome.FAILED_FINAL && it.metadataJson.contains("semanticCode") }
            )
            assertAbortedBeforeAnyDestructiveStep(f, liveBefore)
            assertNotNull("the staged copy existed when C2 was cancelled", f.stagedFile.get())
            assertFalse("the staged copy must be cleaned", f.stagedFile.get()!!.exists())
        }

    private companion object {
        const val FIXED_TIME = 1716163200000L
    }
}
