package com.yourname.expensetracker.data.backup

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import org.json.JSONObject
import timber.log.Timber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P7-CURRENT-022 — journal write durability.
 *
 * The fsync added to the temp-file write before the atomic rename is a
 * non-observable durability improvement (cannot simulate power loss in a unit
 * test). These tests are the regression guard that the fsync'd write path still
 * produces a correct, fully-readable journal — in particular that the
 * safety-backup path survives a re-read, which is exactly the field crash
 * recovery depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreJournalDurabilityTest {
    @get:org.junit.Rule
    val temporaryFolder = org.junit.rules.TemporaryFolder()

    private fun journalAt(directory: File): RestoreJournal {
        val context = object : android.content.ContextWrapper(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        ) {
            override fun getFilesDir(): File = directory
        }
        return RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
    }

    private fun isolatedJournal(): Pair<RestoreJournal, File> {
        val directory = temporaryFolder.newFolder()
        return journalAt(directory) to directory
    }

    private fun finalizeDestination(owner: RestoreJournal, entry: RestoreJournal.JournalEntry, destination: String) {
        when (destination) {
            "restore_journal.json" -> owner.transitionTo(entry, RestoreJournal.JournalState.SWAPPING)
            RestoreJournal.SUCCESS_JOURNAL_FILENAME -> owner.commitJournal(entry)
            else -> owner.failJournal(entry, "TEST_FAILURE")
        }
    }

    @Test
    fun malformed_input_matrix_retains_exact_bytes_across_fresh_journal_owners() {
        val (owner, directory) = isolatedJournal()
        val active = File(directory, "restore_journal.json")
        for (input in corruptJournalInputs()) {
            val bytes = input.toByteArray(Charsets.UTF_8)
            active.writeBytes(bytes)
            assertTrue(owner.checkAndRecover() is RestoreJournal.RecoveryResult.CriticalRecoveryRequired)
            org.junit.Assert.assertArrayEquals(bytes, active.readBytes())
            org.junit.Assert.assertThrows(RestoreJournal.JournalDurabilityException::class.java) {
                owner.beginJournal("", "", "/live")
            }
            org.junit.Assert.assertArrayEquals(bytes, active.readBytes())
            assertTrue(journalAt(directory).checkAndRecover() is RestoreJournal.RecoveryResult.CriticalRecoveryRequired)
        }
    }

    @Test
    fun inspection_and_read_faults_are_corrupt_not_absent_and_preserve_bytes() {
        for (stage in listOf(RestoreJournal.IoStage.INSPECT, RestoreJournal.IoStage.READ)) {
            val (owner, directory) = isolatedJournal()
            owner.beginJournal("", "", "/live")
            val active = File(directory, "restore_journal.json")
            val bytes = active.readBytes()
            owner.beforeIo = { file, current ->
                if (file == active && current == stage) throw SecurityException("TEST_UNREADABLE")
            }
            assertTrue(owner.checkAndRecover() is RestoreJournal.RecoveryResult.CriticalRecoveryRequired)
            org.junit.Assert.assertArrayEquals(bytes, active.readBytes())
        }
    }

    @Test
    fun legacy_missing_time_and_reset_empty_source_identities_remain_valid() {
        val json = RestoreJournal.JournalEntry(liveDbPath = "/live", sourceBackupPath = "", stagedDbPath = "").toJson()
        json.remove("startedAt")
        val entry = RestoreJournal.JournalEntry.fromJson(json, 1234L, validateRecoveryIdentity = true)
        assertEquals(1234L, entry.startedAt)
        assertEquals("/live", entry.liveDbPath)
        val legacy = org.json.JSONObject().put("operationId", "op").put("operationCorrelationId", "corr")
            .put("state", "PREPARING").put("liveDbPath", "/live")
        assertEquals("/live", RestoreJournal.JournalEntry.fromJson(legacy, 1234L, true).liveDbPath)
    }

    @Test
    fun temp_open_write_sync_faults_are_independent_for_all_destinations() {
        for (destination in destinations) for (stage in writeStages) {
            val (owner, directory) = isolatedJournal()
            val entry = owner.beginJournal("", "", "/live")
            val active = File(directory, "restore_journal.json")
            val original = active.readBytes()
            owner.beforeIo = { file, current ->
                if (file.name == "$destination.tmp" && current == stage) throw java.io.IOException("TEST_IO_FAILURE")
            }
            org.junit.Assert.assertThrows(RestoreJournal.JournalDurabilityException::class.java) {
                finalizeDestination(owner, entry, destination)
            }
            assertTrue(active.exists())
            if (destination == active.name) org.junit.Assert.assertArrayEquals(original, active.readBytes())
            else assertEquals(
                if (destination == RestoreJournal.SUCCESS_JOURNAL_FILENAME) RestoreJournal.JournalState.COMPLETE else RestoreJournal.JournalState.FAILED,
                owner.readJournal()!!.state
            )
        }
    }

    @Test
    fun rename_false_fallback_success_and_write_failures_are_checked_for_all_destinations() {
        for (destination in destinations) {
            val (owner, directory) = isolatedJournal()
            val entry = owner.beginJournal("", "", "/live")
            owner.testRenameTo = { _, _ -> false }
            finalizeDestination(owner, entry, destination)
            val archived = org.json.JSONObject(File(directory, destination).readText())
            assertEquals(entry.operationId, archived.getString("operationId"))
            org.junit.Assert.assertFalse(File(directory, "$destination.tmp").exists())
        }
        for (destination in destinations) for (stage in writeStages) {
            val (owner, directory) = isolatedJournal()
            val entry = owner.beginJournal("", "", "/live")
            owner.testRenameTo = { _, _ -> false }
            owner.beforeIo = { file, current ->
                if (file.name == destination && current == stage) throw java.io.IOException("TEST_FALLBACK_FAILURE")
            }
            org.junit.Assert.assertThrows(RestoreJournal.JournalDurabilityException::class.java) {
                finalizeDestination(owner, entry, destination)
            }
            assertTrue(File(directory, "restore_journal.json").exists())
            assertEquals(entry.operationId, org.json.JSONObject(File(directory, "$destination.tmp").readText()).getString("operationId"))
        }
    }

    @Test
    fun rename_exceptions_and_failed_fallback_cleanup_never_certify_durability() {
        for (destination in destinations) for (failRename in listOf(true, false)) {
            val (owner, directory) = isolatedJournal()
            val entry = owner.beginJournal("", "", "/live")
            owner.testRenameTo = { _, target ->
                if (failRename && target.name == destination) throw java.io.IOException("TEST_RENAME_FAILURE")
                false
            }
            owner.testDelete = { file -> if (file.name == "$destination.tmp") false else file.delete() }
            org.junit.Assert.assertThrows(RestoreJournal.JournalDurabilityException::class.java) {
                finalizeDestination(owner, entry, destination)
            }
            assertTrue(File(directory, "restore_journal.json").exists())
        }
    }

    @Test
    fun terminal_preservation_and_active_deletion_failures_retry_on_fresh_owners() {
        for (destination in destinations.drop(1)) for (failDelete in listOf(true, false)) {
            val (owner, directory) = isolatedJournal()
            val entry = owner.beginJournal("", "", "/live")
            owner.beforeIo = { file, stage ->
                if ((!failDelete && file.name == "$destination.tmp" && stage == RestoreJournal.IoStage.SYNC) ||
                    (failDelete && file.name == "restore_journal.json" && stage == RestoreJournal.IoStage.DELETE)) {
                    throw java.io.IOException("TEST_FINALIZATION_FAILURE")
                }
            }
            org.junit.Assert.assertThrows(RestoreJournal.JournalDurabilityException::class.java) {
                finalizeDestination(owner, entry, destination)
            }
            val active = File(directory, "restore_journal.json")
            val retained = active.readBytes()
            org.junit.Assert.assertThrows(RestoreJournal.JournalDurabilityException::class.java) { owner.checkAndRecover() }
            org.junit.Assert.assertArrayEquals(retained, active.readBytes())
            val fresh = journalAt(directory)
            val result = fresh.checkAndRecover()
            assertTrue(result is RestoreJournal.RecoveryResult.CompleteClean || result is RestoreJournal.RecoveryResult.CleanedNonDestructive)
            org.junit.Assert.assertArrayEquals(retained, File(directory, destination).readBytes())
            org.junit.Assert.assertFalse(active.exists())
            assertTrue(fresh.checkAndRecover() is RestoreJournal.RecoveryResult.NoAction)
        }
    }

    @Test
    fun cancellation_survives_each_journal_io_boundary() {
        for (destination in destinations) for (stage in writeStages + RestoreJournal.IoStage.RENAME) {
            val (owner, _) = isolatedJournal()
            val entry = owner.beginJournal("", "", "/live")
            val cancellation = kotlinx.coroutines.CancellationException("TEST_CANCELLED")
            owner.beforeIo = { file, current ->
                val target = if (stage == RestoreJournal.IoStage.RENAME) destination else "$destination.tmp"
                if (current == stage && file.name == target) throw cancellation
            }
            org.junit.Assert.assertSame(cancellation, org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                finalizeDestination(owner, entry, destination)
            })
        }
        for (stage in listOf(RestoreJournal.IoStage.INSPECT, RestoreJournal.IoStage.READ, RestoreJournal.IoStage.DELETE)) {
            val (owner, _) = isolatedJournal()
            owner.beginJournal("", "", "/live")
            val cancellation = kotlinx.coroutines.CancellationException("TEST_CANCELLED")
            owner.beforeIo = { _, current -> if (current == stage) throw cancellation }
            org.junit.Assert.assertSame(cancellation, org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                if (stage == RestoreJournal.IoStage.DELETE) owner.deleteJournal() else owner.readJournalResult()
            })
        }
    }

    @Test
    fun failed_initial_open_preserves_the_previous_failure_archive() {
        val (owner, directory) = isolatedJournal()
        val failure = File(directory, RestoreJournal.FAILURE_JOURNAL_FILENAME)
        val retained = "PREVIOUS_FAILURE_EVIDENCE".toByteArray()
        failure.writeBytes(retained)
        owner.beforeIo = { _, stage -> if (stage == RestoreJournal.IoStage.OPEN) throw java.io.IOException("TEST_OPEN_FAILURE") }
        org.junit.Assert.assertThrows(RestoreJournal.JournalDurabilityException::class.java) { owner.beginJournal("", "", "/live") }
        org.junit.Assert.assertArrayEquals(retained, failure.readBytes())
    }


    companion object {
        private val destinations = listOf("restore_journal.json", RestoreJournal.SUCCESS_JOURNAL_FILENAME, RestoreJournal.FAILURE_JOURNAL_FILENAME)
        private val writeStages = listOf(RestoreJournal.IoStage.OPEN, RestoreJournal.IoStage.WRITE, RestoreJournal.IoStage.SYNC)

        internal fun corruptJournalInputs(): List<String> {
            fun valid() = RestoreJournal.JournalEntry(operationId = "op", operationCorrelationId = "corr", liveDbPath = "/live").toJson()
            val cases = mutableListOf("", " ", "{", "[]", "null", "invalid-json")
            val wrong = listOf(org.json.JSONObject.NULL, 17, true, org.json.JSONObject(), org.json.JSONArray())
            for (key in listOf("operationId", "operationCorrelationId", "state", "_liveDbPath")) {
                cases += valid().apply { remove(key) }.toString()
                for (value in wrong + "") cases += valid().put(key, value).toString()
            }
            cases += valid().put("state", "UNKNOWN").toString()
            for (value in wrong.filterNot { it is Int } + listOf("1234", 1.5)) cases += valid().put("startedAt", value).toString()
            for (key in listOf("_sourceBackupPath", "_stagedDbPath", "_safetyBackupPath", "_extractTempDirPath", "liveDbPath")) {
                cases += valid().put(key, 17).toString()
            }
            for (value in wrong.filterNot { it is org.json.JSONArray } + "tasks") cases += valid().put("assetTasks", value).toString()
            fun task() = org.json.JSONObject().put("receiptId", 1L).put("src", "receipt.jpg").put("status", "PENDING")
            for (key in listOf("receiptId", "src", "status")) {
                cases += valid().put("assetTasks", org.json.JSONArray().put(task().apply { remove(key) })).toString()
                val invalid = if (key == "receiptId") wrong.filterNot { it is Int } + listOf("1", 1.5) else wrong + ""
                for (value in invalid) cases += valid().put("assetTasks", org.json.JSONArray().put(task().put(key, value))).toString()
            }
            cases += valid().put("assetTasks", org.json.JSONArray().put(task().put("status", "UNKNOWN"))).toString()
            cases += valid().put("assetTasks", org.json.JSONArray().put(org.json.JSONObject.NULL)).toString()
            return cases
        }
    }


    private lateinit var journal: RestoreJournal
    private lateinit var context: android.content.Context
    private val logs = mutableListOf<Pair<Throwable?, String>>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += t to message
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clean slate.
        listOf(
            "restore_journal.json",
            "restore_journal_last_failure.json",
            RestoreJournal.SUCCESS_JOURNAL_FILENAME
        ).forEach { File(context.filesDir, it).delete() }
        journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(1716163200000L))
        logs.clear()
        Timber.plant(logTree)
    }

    @After
    fun tearDown() {
        Timber.uproot(logTree)
    }

    @Test
    fun `malformed journal logs parser code and class without raw content`() {
        val hostile = "SELECT receipt FROM /data/private/ledger.db content://receipts/4 Merchant 123.45"
        File(context.filesDir, "restore_journal.json").writeText("{ $hostile")

        assertNull(journal.readJournal())
        assertTrue(logs.any { it.second.contains("PARSER_FAILED stage=read class=") })
        assertTrue(logs.all { it.first == null && !it.second.contains(hostile) })
    }

    @Test
    fun `staging cleanup never logs a database path`() {
        val entry = journal.beginJournal("/cache/src.costbackup", "/data/staged.db", "/data/live.db")
        val staged = File(context.filesDir, "merchant_123.45_staging.db")
        staged.writeText("temporary")

        journal.cleanStagingFiles(entry.copy(stagedDbPath = staged.absolutePath))

        assertTrue(logs.any { it.second == "RestoreJournal: staging DB cleaned" })
        assertTrue(logs.all { it.first == null && !it.second.contains(staged.absolutePath) })
    }

    @Test
    fun `writeJournal then readJournal round-trips state and paths`() {
        val entry = journal.beginJournal(
            sourceBackupPath = "/cache/src.costbackup",
            stagedDbPath = "/data/staged.db",
            liveDbPath = "/data/live.db"
        )
        val updated = journal.transitionTo(
            entry,
            RestoreJournal.JournalState.SAFETY_BACKUP_CREATED,
            safetyBackupPath = "/data/safety_backup.db"
        )

        val readBack = journal.readJournal()
        assertNotNull("journal must be readable after fsync'd write", readBack)
        assertEquals(RestoreJournal.JournalState.SAFETY_BACKUP_CREATED, readBack!!.state)
        assertEquals(
            "safety backup path must survive the write+reread (needed for crash recovery)",
            "/data/safety_backup.db",
            readBack.safetyBackupPath
        )
        assertEquals(updated.operationId, readBack.operationId)
    }

    @Test
    fun `appended events survive journal state transitions`() {
        val entry = journal.beginJournal("/cache/s.costbackup", "/data/staged.db", "/data/live.db")
        journal.appendEvent(
            correlationId = entry.operationCorrelationId,
            stage = "MAINTENANCE_ENTERED",
            outcome = "COMPLETED"
        )
        // A subsequent state write must preserve existing events.
        journal.transitionTo(entry, RestoreJournal.JournalState.STAGED)

        val events = journal.getEventsByCorrelationId(entry.operationCorrelationId)
        assertTrue("appended event must persist across transitions", events.any { it.stage == "MAINTENANCE_ENTERED" })
    }

    @Test
    fun `failure journal writes only allowlisted reasons to its error field`() {
        val allowed = listOf(
            DiagnosticReasonCode.UNKNOWN_ERROR,
            DiagnosticReasonCode.PARSER_FAILED,
            DiagnosticReasonCode.RESTORE_BLOCKED,
            DiagnosticReasonCode.WRITE_BARRIER_DENIED,
            DiagnosticReasonCode.READ_BARRIER_DENIED,
            DiagnosticReasonCode.TIMEOUT
        )
        val hostile = "SELECT * FROM receipts /data/private/ledger.db content://receipts/4 Merchant 123.45"
        val rejected = listOf(hostile, "", "TIMEOUT $hostile", "RESTORE_FAILED", "VALIDATION_FAILED", "unknown")

        (allowed.map { it.name to it.name } + rejected.map { it to "UNKNOWN_ERROR" }).forEach { (input, expected) ->
            val entry = journal.beginJournal("/cache/src.costbackup", "/data/staged.db", "/data/live.db")
            val failed = journal.failJournal(entry, input)
            val failureFile = File(context.filesDir, RestoreJournal.FAILURE_JOURNAL_FILENAME)
            val storedError = JSONObject(failureFile.readText()).getString("error")

            assertEquals(expected, failed.error)
            assertEquals(expected, storedError)
            assertEquals(expected, journal.readFailureJournal()?.error)
            assertEquals(expected, failed.toDiagnosticsJson().getString("error"))
            // Recovery paths are separate internal fields and must still round-trip.
            assertEquals("/data/staged.db", journal.readFailureJournal()?.stagedDbPath)
        }
    }

    @Test
    fun `direct journal serialization and legacy reads bound error without removing recovery paths`() {
        val hostile = "SELECT * FROM receipts /data/private/ledger.db content://receipts/4 Merchant 123.45"
        val entry = journal.beginJournal("/cache/src.costbackup", "/data/staged.db", "/data/live.db")
        journal.writeJournal(entry.copy(error = hostile))

        val activeFile = File(context.filesDir, "restore_journal.json")
        assertEquals("UNKNOWN_ERROR", JSONObject(activeFile.readText()).getString("error"))

        val legacy = JSONObject(activeFile.readText()).put("error", hostile)
        activeFile.writeText(legacy.toString())
        val readBack = journal.readJournal()!!
        assertEquals("UNKNOWN_ERROR", readBack.error)
        assertEquals("UNKNOWN_ERROR", readBack.toJson().getString("error"))
        assertEquals("/data/staged.db", readBack.stagedDbPath)
    }

    /**
     * RP-03B (P7-002 resume): the asset-task ledger must round-trip through the
     * fsync'd journal write so a crash mid-asset-loop can be resumed at startup,
     * and the persisted target must be a basename only (privacy: no absolute paths).
     */
    @Test
    fun `asset task ledger round-trips statuses and basename-only targets`() {
        var entry = journal.beginJournal("/cache/s.costbackup", "/data/staged.db", "/data/live.db")
        entry = entry.copy(
            extractTempDirPath = "/cache/costbackup_extract_x",
            assetTasks = listOf(
                RestoreJournal.AssetRestoreTask(
                    receiptId = 5L,
                    sourceRelativePath = "5_photo.jpg",
                    status = RestoreJournal.AssetRestoreStatus.PENDING,
                    targetPath = "/data/data/app/files/receipts/restored_5.jpg"
                ),
                RestoreJournal.AssetRestoreTask(
                    receiptId = 6L,
                    sourceRelativePath = "6_photo.jpg",
                    status = RestoreJournal.AssetRestoreStatus.COMPLETED
                )
            )
        )
        journal.writeJournal(entry)
        journal.transitionTo(entry, RestoreJournal.JournalState.ASSETS_RESTORING)

        val readBack = journal.readJournal()!!
        assertEquals(RestoreJournal.JournalState.ASSETS_RESTORING, readBack.state)
        assertEquals("/cache/costbackup_extract_x", readBack.extractTempDirPath)
        assertEquals(2, readBack.assetTasks.size)

        val pending = readBack.assetTasks.first { it.receiptId == 5L }
        assertEquals(RestoreJournal.AssetRestoreStatus.PENDING, pending.status)
        assertEquals("5_photo.jpg", pending.sourceRelativePath)
        assertEquals(
            "target must round-trip as a basename only (no absolute path in journal)",
            "restored_5.jpg",
            pending.targetPath
        )
        assertEquals(
            RestoreJournal.AssetRestoreStatus.COMPLETED,
            readBack.assetTasks.first { it.receiptId == 6L }.status
        )
    }

    /**
     * RP-03 fix: the final asset name must be derived from the task's own identity
     * (receiptId + allowlisted extension) - deterministic across retries - and must
     * reject any extension the backup write side cannot produce.
     */
    @Test
    fun `deriveAssetTargetName is identity-derived, deterministic, and extension-validated`() {
        // Deterministic per task identity - same task always yields the same name.
        assertEquals(
            "restored_5.jpg",
            RestoreJournal.deriveAssetTargetName(5L, "jpg")
        )
        assertEquals(
            "Retry with the same identity must derive the identical name",
            RestoreJournal.deriveAssetTargetName(5L, "jpg"),
            RestoreJournal.deriveAssetTargetName(5L, "jpg")
        )

        // Extension is normalized (case/whitespace) and legacy-tolerated types pass.
        assertEquals("restored_5.jpg", RestoreJournal.deriveAssetTargetName(5L, "JPG"))
        assertEquals("restored_5.jpg", RestoreJournal.deriveAssetTargetName(5L, " jpg "))
        assertEquals("restored_7.png", RestoreJournal.deriveAssetTargetName(7L, "png"))
        assertEquals("restored_7.webp", RestoreJournal.deriveAssetTargetName(7L, "webp"))
        assertEquals("restored_7.jpeg", RestoreJournal.deriveAssetTargetName(7L, "jpeg"))

        // Distinct identities never share a final name key.
        assertNotEquals(
            RestoreJournal.deriveAssetTargetName(5L, "jpg"),
            RestoreJournal.deriveAssetTargetName(6L, "jpg")
        )

        // Anything outside the fixed allowlist fails closed (null).
        assertNull(RestoreJournal.deriveAssetTargetName(5L, "exe"))
        assertNull(RestoreJournal.deriveAssetTargetName(5L, "html"))
        assertNull(RestoreJournal.deriveAssetTargetName(5L, ""))
        assertNull(RestoreJournal.deriveAssetTargetName(5L, "jpg.exe"))
    }

    @Test
    fun `fsync failure is surfaced and does not advance the journal`() {
        journal.testWriteTextSynced = { _, _ -> throw java.io.SyncFailedException("injected") }
        org.junit.Assert.assertThrows(RestoreJournal.JournalDurabilityException::class.java) {
            journal.beginJournal("/cache/src.costbackup", "/data/staged.db", "/data/live.db")
        }
        assertTrue("failed write must not publish an active journal", !journal.hasJournal())
    }

    @Test
    fun `rename false uses a checked durable fallback`() {
        journal.testRenameTo = { _, _ -> false }
        val entry = journal.beginJournal("/cache/src.costbackup", "/data/staged.db", "/data/live.db")
        assertEquals(entry, journal.readJournal())
    }

    @Test
    fun `fallback copy or fsync failure is surfaced and active journal is retained`() {
        journal.testRenameTo = { _, _ -> false }
        journal.testWriteTextSynced = { file, _ ->
            if (file.name == "restore_journal.json") throw java.io.SyncFailedException("injected")
        }
        org.junit.Assert.assertThrows(RestoreJournal.JournalDurabilityException::class.java) {
            journal.beginJournal("/cache/src.costbackup", "/data/staged.db", "/data/live.db")
        }
        assertTrue("fallback failure must leave recovery evidence", journal.hasJournal())
    }

    @Test
    fun `success journal preservation failure retains active journal`() {
        var preserve = false
        journal.testWriteTextSynced = { file, _ ->
            if (preserve && file.name == RestoreJournal.SUCCESS_JOURNAL_FILENAME + ".tmp") {
                throw java.io.SyncFailedException("injected")
            }
        }
        val entry = journal.beginJournal("/cache/src.costbackup", "/data/staged.db", "/data/live.db")
        preserve = true
        org.junit.Assert.assertThrows(RestoreJournal.JournalDurabilityException::class.java) {
            journal.commitJournal(entry)
        }
        assertTrue(journal.hasJournal())
    }

    @Test
    fun `absent journal is no action but corrupt bytes are critical`() {
        assertTrue(journal.checkAndRecover() is RestoreJournal.RecoveryResult.NoAction)
        val active = File(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "restore_journal.json"
        )
        active.writeText("   ")
        assertTrue(journal.checkAndRecover() is RestoreJournal.RecoveryResult.CriticalRecoveryRequired)
        assertTrue("corrupt bytes must remain for recovery", active.exists())
    }

    @Test
    fun `unknown state and malformed asset task are critical`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val active = File(context.filesDir, "restore_journal.json")
        val base = """{"operationId":"op","operationCorrelationId":"corr","state":"BOGUS","liveDbName":"live","_liveDbPath":"/data/live.db"}"""
        active.writeText(base)
        assertTrue(journal.checkAndRecover() is RestoreJournal.RecoveryResult.CriticalRecoveryRequired)
        active.writeText("""{"operationId":"op","operationCorrelationId":"corr","state":"PREPARING","_liveDbPath":"/data/live.db","assetTasks":[{"receiptId":1}]}""")
        assertTrue(journal.checkAndRecover() is RestoreJournal.RecoveryResult.CriticalRecoveryRequired)
    }

    @Test
    fun `valid recovery states remain classified by state`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val active = File(context.filesDir, "restore_journal.json")
        val states = listOf(
            RestoreJournal.JournalState.PREPARING to RestoreJournal.RecoveryResult.CleanedNonDestructive::class,
            RestoreJournal.JournalState.STAGED to RestoreJournal.RecoveryResult.CleanedNonDestructive::class,
            RestoreJournal.JournalState.SWAPPING to RestoreJournal.RecoveryResult.RecoveredFromSwap::class,
            RestoreJournal.JournalState.VERIFYING to RestoreJournal.RecoveryResult.RecoveredFromSwap::class,
            RestoreJournal.JournalState.ROLLING_BACK to RestoreJournal.RecoveryResult.RecoveredFromSwap::class
        )
        states.forEach { (state, expected) ->
            val entry = journal.beginJournal("/src", "/staged", "/live")
            journal.writeJournal(entry.copy(state = state))
            assertTrue("$state should classify as ${expected.simpleName}", expected.isInstance(journal.checkAndRecover()))
            if (state == RestoreJournal.JournalState.SWAPPING ||
                state == RestoreJournal.JournalState.VERIFYING ||
                state == RestoreJournal.JournalState.ROLLING_BACK
            ) active.delete()
        }
    }
}
