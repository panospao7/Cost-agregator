package com.yourname.expensetracker.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.APP_DATABASE_SCHEMA_VERSION
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import com.yourname.expensetracker.data.privacy.ExportAnonymizer
import com.yourname.expensetracker.data.repository.DatabaseBackupRepositoryImpl
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * R0-5 / RG01-N1: restoring the same backup twice.
 *
 * The second restore finds the `restored_<id>` file published by the first restore while the
 * receipt row (restored from the backup database) again points at the absent original path.
 * Intended behavior: a file that matches the journal's bundle proof (SHA-256 + size) is adopted
 * through the normal pointer CAS; a non-matching or proof-less file stays TARGET_COLLISION and is
 * never overwritten, adopted, or deleted.
 *
 * Real in-memory Room, real app filesDir, real [RestoreJournal]; only unrelated repository
 * dependencies and the maintenance-mode gate are mocked. Fixture data is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptAssetRepeatRestoreTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var receiptsDir: File
    private lateinit var assetsDir: File
    private lateinit var absentOriginal: String

    private val fixedTime = 1716163200000L
    private val testDispatcher = StandardTestDispatcher()
    private val maintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true)

    private val assetBytes = byteArrayOf(1, 2, 3)
    private val assetSha256: String = sha256(assetBytes)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        receiptsDir = File(context.filesDir, "receipts")
        assetsDir = File(context.cacheDir, "rg01_n1_extract")
        absentOriginal = File(context.filesDir, "original_missing/photo.jpg").absolutePath
        cleanFiles()
        // RestoreInternalWriteScope permits writes only in restore modes.
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.ASSETS_RESTORING
    }

    @After
    fun tearDown() {
        database.close()
        cleanFiles()
    }

    // -- repository-level tests ------------------------------------------------------------

    @Test
    fun `repeat_restore_adopts_proof_matching_restored_file`() = runTest {
        seedReceipt(absentOriginal)

        val first = restoreOnce(withProof = true)
        assertEquals("RG01-N1 precondition: first restore warningCount", 0, first.warnings.size)
        assertEquals(
            "RG01-N1 precondition: first restore task status",
            RestoreJournal.AssetRestoreStatus.COMPLETED,
            first.journalEntry!!.assetTasks.single().status
        )
        assertEquals("RG01-N1 precondition: first restore pointer", finalFile().absolutePath, pointer())
        assertTrue("RG01-N1 precondition: restored_5.jpg exists", finalFile().isFile)

        // Second restore of the same backup: the restored database again points at the absent original.
        resetPointerTo(absentOriginal)
        val second = restoreOnce(withProof = true)

        val task = second.journalEntry!!.assetTasks.single()
        assertEquals(
            "RG01-N1 second restore task status (error=${task.error})",
            RestoreJournal.AssetRestoreStatus.COMPLETED,
            task.status
        )
        assertEquals("RG01-N1 second restore warningCount", 0, second.warnings.size)
        assertEquals("RG01-N1 second restore pointer is restored_5.jpg", finalFile().absolutePath, pointer())
        assertTrue("RG01-N1 restored_5.jpg bytes unchanged", assetBytes.contentEquals(finalFile().readBytes()))
        assertTrue("RG01-N1 no *.tmp left in receipts dir", noTemps())
    }

    @Test
    fun `repeat_restore_never_adopts_non_matching_restored_file`() = runTest {
        seedReceipt(absentOriginal)
        restoreOnce(withProof = true)
        resetPointerTo(absentOriginal)

        val foreign = byteArrayOf(9, 9, 9, 9, 9)
        finalFile().writeBytes(foreign)
        val second = restoreOnce(withProof = true)

        assertCollision(second, foreign)
    }

    @Test
    fun `repeat_restore_never_adopts_same_size_different_hash_file`() = runTest {
        seedReceipt(absentOriginal)
        restoreOnce(withProof = true)
        resetPointerTo(absentOriginal)

        val foreign = byteArrayOf(1, 2, 4) // same size as the proof, different SHA-256
        finalFile().writeBytes(foreign)
        val second = restoreOnce(withProof = true)

        assertCollision(second, foreign)
    }

    @Test
    fun `pending_task_without_proof_and_existing_file_stays_collision`() = runTest {
        seedReceipt(absentOriginal)
        // Existing file even has the "right" bytes, but the bundle carries no checksums proof.
        receiptsDir.mkdirs()
        finalFile().writeBytes(assetBytes)

        val outcome = restoreOnce(withProof = false)

        assertCollision(outcome, assetBytes)
    }

    // -- engine-level tests ----------------------------------------------------------------

    @Test
    fun `adopted_file_survives_not_applied_cas`() {
        // Public behavior only: PENDING task + proof-matching existing final file.
        // Pre-R5 begin() rejects with TARGET_COLLISION, so the NeedsPointer assertion fails first.
        val (recovery, entry, task) = engineFixture(existingFinalBytes = assetBytes)
        val finalFile = finalFile()

        val begun = recovery.begin(entry, task)
        assertTrue(
            "RG01-N1 begin() step is NeedsPointer (was ${begun::class.simpleName}, " +
                "taskStatus=${begun.task.status}, error=${begun.task.error})",
            begun is ReceiptAssetRecovery.Step.NeedsPointer
        )
        val admitted = recovery.admit(
            begun as ReceiptAssetRecovery.Step.NeedsPointer,
            rowExists = true,
            pointer = absentOriginal
        )
        assertTrue(
            "RG01-N1 admit() step is NeedsCas (was ${admitted::class.simpleName}, error=${admitted.task.error})",
            admitted is ReceiptAssetRecovery.Step.NeedsCas
        )
        val after = recovery.afterCas(
            admitted as ReceiptAssetRecovery.Step.NeedsCas,
            ReceiptAssetRecovery.CasOutcome.NOT_APPLIED
        )

        assertEquals(
            "RG01-N1 NOT_APPLIED task status",
            RestoreJournal.AssetRestoreStatus.FAILED,
            after.task.status
        )
        assertEquals("RG01-N1 NOT_APPLIED reason", RestoreJournal.ASSET_REASON_IMAGE_PATH_CONFLICT, after.task.error)
        assertTrue("RG01-N1 adopted restored_5.jpg still exists after NOT_APPLIED", finalFile.isFile)
        assertTrue("RG01-N1 adopted bytes unchanged", assetBytes.contentEquals(finalFile.readBytes()))
    }

    @Test
    fun `engine_not_applied_still_deletes_operation_published_file`() {
        // Control: a file this operation published itself is still removed on NOT_APPLIED.
        val (recovery, entry, task) = engineFixture(existingFinalBytes = null)

        val begun = recovery.begin(entry, task)
        assertTrue(
            "RG01-N1 begin() step is NeedsPointer (was ${begun::class.simpleName})",
            begun is ReceiptAssetRecovery.Step.NeedsPointer
        )
        val admitted = recovery.admit(
            begun as ReceiptAssetRecovery.Step.NeedsPointer,
            rowExists = true,
            pointer = absentOriginal
        )
        assertTrue(
            "RG01-N1 admit() step is NeedsCas (was ${admitted::class.simpleName})",
            admitted is ReceiptAssetRecovery.Step.NeedsCas
        )
        assertTrue("RG01-N1 operation published restored_5.jpg before CAS", finalFile().isFile)

        recovery.afterCas(admitted as ReceiptAssetRecovery.Step.NeedsCas, ReceiptAssetRecovery.CasOutcome.NOT_APPLIED)

        assertEquals("RG01-N1 operation-owned file exists after NOT_APPLIED", false, finalFile().exists())
    }

    @Test
    fun `engine_pending_task_without_proof_and_existing_file_stays_collision`() {
        val (recovery, entry, task) = engineFixture(existingFinalBytes = assetBytes, withProof = false)

        val begun = recovery.begin(entry, task)

        assertTrue(
            "RG01-N1 begin() without proof is terminal (was ${begun::class.simpleName})",
            begun is ReceiptAssetRecovery.Step.Done
        )
        assertEquals("RG01-N1 status", RestoreJournal.AssetRestoreStatus.FAILED, begun.task.status)
        assertEquals("RG01-N1 reason", RestoreJournal.ASSET_REASON_TARGET_COLLISION, begun.task.error)
        assertTrue("RG01-N1 existing file untouched", assetBytes.contentEquals(finalFile().readBytes()))
    }

    @Test
    fun `engine_stage_recheck_rejects_file_changed_after_begin`() {
        // begin() adopts a proof-matching file; stage() must re-verify before publishing/adopting.
        val (recovery, entry, task) = engineFixture(existingFinalBytes = assetBytes)
        val begun = recovery.begin(entry, task)
        assertTrue(
            "RG01-N1 begin() step is NeedsPointer (was ${begun::class.simpleName})",
            begun is ReceiptAssetRecovery.Step.NeedsPointer
        )
        val changedBytes = ByteArray(assetBytes.size) { (assetBytes[it].toInt() xor 0x5A).toByte() }
        finalFile().writeBytes(changedBytes)

        val admitted = recovery.admit(
            begun as ReceiptAssetRecovery.Step.NeedsPointer,
            rowExists = true,
            pointer = absentOriginal
        )

        assertTrue(
            "RG01-N1 admit() is terminal, not NeedsCas (was ${admitted::class.simpleName})",
            admitted is ReceiptAssetRecovery.Step.Done
        )
        assertEquals("RG01-N1 status", RestoreJournal.AssetRestoreStatus.FAILED, admitted.task.status)
        assertEquals("RG01-N1 reason", RestoreJournal.ASSET_REASON_TARGET_COLLISION, admitted.task.error)
        assertTrue("RG01-N1 changed bytes untouched", changedBytes.contentEquals(finalFile().readBytes()))
    }

    // -- helpers ---------------------------------------------------------------------------

    private fun assertCollision(
        outcome: DatabaseBackupRepositoryImpl.ReceiptAssetRestoreOutcome,
        expectedBytes: ByteArray
    ) {
        val task = outcome.journalEntry!!.assetTasks.single()
        assertEquals("RG01-N1 task status", RestoreJournal.AssetRestoreStatus.FAILED, task.status)
        assertEquals("RG01-N1 task reason", RestoreJournal.ASSET_REASON_TARGET_COLLISION, task.error)
        assertEquals(
            "RG01-N1 warnings",
            listOf("Receipt asset task rejected: ${RestoreJournal.ASSET_REASON_TARGET_COLLISION}"),
            outcome.warnings
        )
        assertTrue("RG01-N1 restored_5.jpg bytes untouched", expectedBytes.contentEquals(finalFile().readBytes()))
        assertEquals("RG01-N1 pointer unchanged", absentOriginal, pointer())
        assertTrue("RG01-N1 no *.tmp left in receipts dir", noTemps())
    }

    private fun finalFile() = File(receiptsDir, "restored_5.jpg")

    private fun noTemps(): Boolean = receiptsDir.listFiles { f -> f.name.endsWith(".tmp") }.isNullOrEmpty()

    private fun cleanFiles() {
        receiptsDir.deleteRecursively()
        assetsDir.deleteRecursively()
        File(context.cacheDir, "rg01_n1_engine_src").deleteRecursively()
        listOf(
            "restore_journal.json",
            RestoreJournal.FAILURE_JOURNAL_FILENAME,
            RestoreJournal.SUCCESS_JOURNAL_FILENAME
        ).forEach { File(context.filesDir, it).delete() }
    }

    private fun seedReceipt(imagePath: String?) = runTestBlocking {
        database.scannedReceiptDao().insert(
            ScannedReceipt(
                id = 5L,
                imagePath = imagePath,
                rawOcrText = "synthetic",
                parsedTotal = null,
                parsedMerchant = null,
                parsedDate = null,
                parsedItems = null,
                parsedTaxAmount = null,
                confidence = 0.5f
            )
        )
    }

    private suspend fun pointerSuspend(): String? = database.scannedReceiptDao().getById(5L)?.imagePath

    private fun pointer(): String? = runTestBlocking { pointerSuspend() }

    private fun resetPointerTo(path: String) {
        val current = pointer()
        val rows = runTestBlocking { database.scannedReceiptDao().updateImagePathIfUnchanged(5L, current, path) }
        assertEquals("RG01-N1 fixture pointer reset rows", 1, rows)
    }

    private fun <T> runTestBlocking(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }

    /** Extracted bundle like [DatabaseBackupRepositoryImplTest.runDirectAssetRestore]. */
    private fun writeBundle(withProof: Boolean) {
        assetsDir.deleteRecursively()
        val src = File(assetsDir, "files/receipts").apply { mkdirs() }
        File(src, "5_photo.jpg").writeBytes(assetBytes)
        if (withProof) {
            File(assetsDir, "checksums.json").writeText(
                CostbackupBundle.ChecksumsManifest(mapOf("files/receipts/5_photo.jpg" to assetSha256))
                    .toJson().toString()
            )
        }
    }

    private suspend fun restoreOnce(withProof: Boolean): DatabaseBackupRepositoryImpl.ReceiptAssetRestoreOutcome {
        writeBundle(withProof)
        // Fresh journal for each restore operation (as a new restore would begin).
        listOf("restore_journal.json", RestoreJournal.FAILURE_JOURNAL_FILENAME, RestoreJournal.SUCCESS_JOURNAL_FILENAME)
            .forEach { File(context.filesDir, it).delete() }
        val journal = RestoreJournal(context, FakeTimeProvider(fixedTime))
        var entry = journal.beginJournal(
            sourceBackupPath = File(context.filesDir, "bundle.costbackup").absolutePath,
            stagedDbPath = File(context.filesDir, "staged_restore.db").absolutePath,
            liveDbPath = File(context.filesDir, "expense_tracker_db").absolutePath
        )
        entry = journal.transitionTo(entry, RestoreJournal.JournalState.ASSETS_RESTORING)

        val repo = DatabaseBackupRepositoryImpl(
            context = context,
            database = database,
            ioDispatcher = testDispatcher,
            privacyGate = mockk<PrivacyGate>(relaxed = true),
            privacySettingsRepository = mockk<PrivacySettingsRepository>(relaxed = true),
            backupEncryptionService = mockk<BackupEncryptionService>(relaxed = true),
            exportAnonymizer = mockk<ExportAnonymizer>(relaxed = true),
            secureKeyStorage = mockk<SecureKeyStorage>(relaxed = true),
            receiptAssetStore = mockk(relaxed = true),
            restoreMaintenanceMode = maintenanceMode,
            restoreJournal = journal,
            stagedImportVerifier = { _, _, _, _, summary -> summary },
            liveImportVerifier = { _, _, _, summary -> summary },
            timeProvider = FakeTimeProvider(fixedTime),
            // S6c-4: restore never reconciles; a strict mock fails the test if it is ever called.
            installmentPreBackupReconciler = dagger.Lazy {
                mockk<com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPreBackupReconciler>()
            }
        )
        return repo.restoreReceiptAssets(
            assetsDir = assetsDir,
            manifest = CostbackupBundle.BackupManifest(
                databaseVersion = APP_DATABASE_SCHEMA_VERSION,
                createdAt = fixedTime,
                tableCounts = emptyMap()
            ),
            db = database,
            journalEntry = entry
        )
    }

    private data class EngineFixture(
        val recovery: ReceiptAssetRecovery,
        val entry: RestoreJournal.JournalEntry,
        val task: RestoreJournal.AssetRestoreTask
    )

    /** PENDING task with a usable extracted source; optionally pre-existing final bytes. */
    private fun engineFixture(existingFinalBytes: ByteArray?, withProof: Boolean = true): EngineFixture {
        val sourceDir = File(context.cacheDir, "rg01_n1_engine_src").apply { deleteRecursively(); mkdirs() }
        File(sourceDir, "5_photo.jpg").writeBytes(assetBytes)
        receiptsDir.mkdirs()
        if (existingFinalBytes != null) finalFile().writeBytes(existingFinalBytes)

        val journal = RestoreJournal(context, FakeTimeProvider(fixedTime))
        val task = RestoreJournal.AssetRestoreTask(
            receiptId = 5L,
            sourceRelativePath = "5_photo.jpg",
            status = RestoreJournal.AssetRestoreStatus.PENDING,
            targetPath = finalFile().absolutePath,
            expectedImagePath = absentOriginal,
            expectedImagePathRecorded = true,
            expectedSha256 = if (withProof) assetSha256 else null,
            expectedSize = if (withProof) assetBytes.size.toLong() else null
        )
        // Build the entry through the real journal API so its recovery identity validates when the engine
        // re-reads the journal on persist (a hand-built entry is read back as Corrupt).
        listOf("restore_journal.json", RestoreJournal.FAILURE_JOURNAL_FILENAME, RestoreJournal.SUCCESS_JOURNAL_FILENAME)
            .forEach { File(context.filesDir, it).delete() }
        val begun = journal.beginJournal(
            sourceBackupPath = File(context.filesDir, "bundle.costbackup").absolutePath,
            stagedDbPath = File(context.filesDir, "staged_restore.db").absolutePath,
            liveDbPath = File(context.filesDir, "expense_tracker_db").absolutePath
        )
        val entry = journal.transitionTo(begun, RestoreJournal.JournalState.ASSETS_RESTORING)
            .copy(assetTasks = listOf(task))
        journal.writeJournal(entry)
        return EngineFixture(ReceiptAssetRecovery(journal, receiptsDir, sourceDir), entry, task)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
