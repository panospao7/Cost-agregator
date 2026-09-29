package com.yourname.expensetracker.data.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreAssetSnapshotJournalTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun entry(recorded: Boolean, path: String?) = RestoreJournal.JournalEntry(
        operationId = "fixture-operation", operationCorrelationId = "fixture-correlation",
        liveDbPath = "/fixture/live.db", startedAt = 10L,
        assetTasks = listOf(RestoreJournal.AssetRestoreTask(
            5L, "5_photo.jpg", RestoreJournal.AssetRestoreStatus.PENDING,
            expectedImagePath = path, expectedImagePathRecorded = recorded
        ))
    )

    @Test
    fun captured_null_round_trips_without_becoming_an_absent_legacy_snapshot() {
        val captured = entry(true, null).toJson()
        assertTrue(captured.getJSONArray("assetTasks").getJSONObject(0).has("_expectedImagePath"))
        val restored = RestoreJournal.JournalEntry.fromJson(captured, 20L).assetTasks.single()
        assertTrue(restored.expectedImagePathRecorded)
        assertNull(restored.expectedImagePath)
        val legacy = RestoreJournal.JournalEntry.fromJson(entry(false, null).toJson(), 20L).assetTasks.single()
        assertFalse(legacy.expectedImagePathRecorded)
        assertNull(legacy.expectedImagePath)
    }

    @Test
    fun private_snapshot_is_available_for_recovery_but_never_in_diagnostics() {
        val privatePath = "/private/fixture/original.jpg"
        val journal = entry(true, privatePath)
        val json = journal.toJson()
        assertEquals(privatePath, json.getJSONArray("assetTasks").getJSONObject(0).getString("_expectedImagePath"))
        assertEquals(privatePath, RestoreJournal.JournalEntry.fromJson(json, 20L).assetTasks.single().expectedImagePath)
        val diagnostics = journal.toDiagnosticsJson()
        assertFalse(diagnostics.getJSONArray("assetTasks").getJSONObject(0).has("_expectedImagePath"))
        assertFalse(diagnostics.toString().contains(privatePath))
        // Producing diagnostics must not mutate the recovery entry.
        assertEquals(privatePath, journal.assetTasks.single().expectedImagePath)
    }

    @Test
    fun s2_replay_proof_round_trips_but_neither_proof_nor_file_name_reach_diagnostics() {
        val sha = "0123456789abcdef".repeat(4)
        val proven = entry(true, null).let { e ->
            e.copy(assetTasks = e.assetTasks.map { it.copy(expectedSha256 = sha, expectedSize = 3L) })
        }
        val task = RestoreJournal.JournalEntry.fromJson(proven.toJson(), 20L).assetTasks.single()
        assertEquals(sha, task.expectedSha256)
        assertEquals(3L, task.expectedSize)
        assertEquals("5_photo.jpg", task.sourceRelativePath)
        val diagnostics = proven.toDiagnosticsJson().toString()
        assertFalse(diagnostics.contains(sha))
        assertFalse(diagnostics.contains("5_photo.jpg"))
        // Legacy `src` key still reads.
        val legacy = entry(true, null).toJson()
        val legacyTask = legacy.getJSONArray("assetTasks").getJSONObject(0)
        legacyTask.put("src", legacyTask.remove("_src"))
        assertEquals("5_photo.jpg", RestoreJournal.JournalEntry.fromJson(legacy, 20L).assetTasks.single().sourceRelativePath)
    }

    @Test
    fun malformed_snapshot_types_are_rejected_instead_of_coerced() {
        for (value in listOf(1, true, JSONObject(), JSONArray())) {
            val json = entry(true, null).toJson()
            json.getJSONArray("assetTasks").getJSONObject(0).put("_expectedImagePath", value)
            assertFailsWith<IllegalArgumentException> {
                RestoreJournal.JournalEntry.fromJson(json, 20L)
            }
        }
    }

    @Test
    fun fresh_journal_owner_reads_the_original_nullable_snapshot_from_disk() {
        for (path in listOf(null, "/private/fixture/original.jpg")) {
            val directory = temporaryFolder.newFolder()
            val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                override fun getFilesDir(): File = directory
            }
            val time = FakeTimeProvider(10L)
            val owner = RestoreJournal(context, time)
            val original = owner.beginJournal("", "", "/fixture/live.db")
                .copy(assetTasks = entry(true, path).assetTasks)
            owner.writeJournal(original)
            val recovered = RestoreJournal(context, time).readJournal()!!.assetTasks.single()
            assertTrue(recovered.expectedImagePathRecorded)
            assertEquals(path, recovered.expectedImagePath)
        }
    }
}
