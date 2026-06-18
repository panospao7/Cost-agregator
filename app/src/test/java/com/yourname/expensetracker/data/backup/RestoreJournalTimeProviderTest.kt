package com.yourname.expensetracker.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * PR4 — TimeProvider contract tests for RestoreJournal.
 *
 * Verifies that all journal timestamps (startedAt, occurredAt, importedAt)
 * are sourced from the injected [TimeProvider] rather than direct wall-clock.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreJournalTimeProviderTest {

    private lateinit var context: Context
    private val fixedTime = 1716163200000L // 2024-05-20 00:00 UTC
    private val fakeTimeProvider = FakeTimeProvider(fixedTime)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clean up any journal files from prior tests
        listOf(
            "restore_journal.json",
            RestoreJournal.FAILURE_JOURNAL_FILENAME,
            RestoreJournal.SUCCESS_JOURNAL_FILENAME
        ).forEach { File(context.filesDir, it).delete() }
    }

    @Test
    fun `beginJournal uses TimeProvider for startedAt`() {
        val journal = RestoreJournal(context, fakeTimeProvider)
        val entry = journal.beginJournal("/tmp/src", "/tmp/staged", "/tmp/live")

        assertEquals("startedAt should come from TimeProvider", fixedTime, entry.startedAt)
    }

    @Test
    fun `appendEvent uses TimeProvider for occurredAt`() {
        val journal = RestoreJournal(context, fakeTimeProvider)
        val entry = journal.beginJournal("/tmp/src", "/tmp/staged", "/tmp/live")
        journal.writeJournal(entry)

        journal.appendEvent(
            correlationId = entry.operationCorrelationId,
            stage = "TEST_STAGE",
            outcome = "OK"
        )

        val events = journal.getEventsByCorrelationId(entry.operationCorrelationId)
        assertEquals(1, events.size)
        assertEquals("occurredAt should come from TimeProvider", fixedTime, events[0].occurredAt)
    }

    @Test
    fun `markSuccessJournalImported uses TimeProvider for importedAt`() {
        val journal = RestoreJournal(context, fakeTimeProvider)
        val entry = journal.beginJournal("/tmp/src", "/tmp/staged", "/tmp/live")
        journal.commitJournal(entry)

        journal.markSuccessJournalImported(entry.operationCorrelationId)

        assertTrue("success journal should be marked imported", journal.isSuccessJournalImported(entry.operationCorrelationId))
        // Verify by reading the file directly
        val successFile = File(context.filesDir, RestoreJournal.SUCCESS_JOURNAL_FILENAME)
        val json = org.json.JSONObject(successFile.readText())
        assertEquals("importedAt should come from TimeProvider", fixedTime, json.optLong("importedAt"))
    }

    @Test
    fun `markFailureJournalImported uses TimeProvider for importedAt`() {
        val journal = RestoreJournal(context, fakeTimeProvider)
        val entry = journal.beginJournal("/tmp/src", "/tmp/staged", "/tmp/live")
        journal.failJournal(entry, "test failure")

        journal.markFailureJournalImported(entry.operationCorrelationId)

        assertTrue("failure journal should be marked imported", journal.isFailureJournalImported(entry.operationCorrelationId))
        val failureFile = File(context.filesDir, RestoreJournal.FAILURE_JOURNAL_FILENAME)
        val json = org.json.JSONObject(failureFile.readText())
        assertEquals("importedAt should come from TimeProvider", fixedTime, json.optLong("importedAt"))
    }

    @Test
    fun `beginJournal with different timeProvider returns different startedAt`() {
        val journal1 = RestoreJournal(context, FakeTimeProvider(1000L))
        val journal2 = RestoreJournal(context, FakeTimeProvider(2000L))

        val entry1 = journal1.beginJournal("/tmp/src1", "/tmp/staged1", "/tmp/live1")
        val entry2 = journal2.beginJournal("/tmp/src2", "/tmp/staged2", "/tmp/live2")

        assertEquals(1000L, entry1.startedAt)
        assertEquals(2000L, entry2.startedAt)
    }

    @Test
    fun `readJournal restores startedAt from json`() {
        val journal = RestoreJournal(context, fakeTimeProvider)
        val entry = journal.beginJournal("/tmp/src", "/tmp/staged", "/tmp/live")

        val readBack = journal.readJournal()
        assertEquals("readJournal should preserve startedAt", fixedTime, readBack?.startedAt)
    }

    @Test
    fun `JournalEntry fromJson uses wall-clock fallback for legacy startedAt`() {
        // Simulate old JSON without startedAt field
        val oldJson = org.json.JSONObject().apply {
            put("operationId", "test-op")
            put("operationCorrelationId", "test-corr")
            put("state", "PREPARING")
            // No startedAt field
        }

        // Note: fromJson() uses System.currentTimeMillis() as fallback for old JSON
        // This is documented as legacy behavior
        val entry = RestoreJournal.JournalEntry.fromJson(oldJson)
        assertTrue("legacy fallback should set startedAt to current time (within 5s)",
            kotlin.math.abs(entry.startedAt - System.currentTimeMillis()) < 5000)
    }

    @Test
    fun `JournalEntry fromJson preserves explicit startedAt`() {
        val json = org.json.JSONObject().apply {
            put("operationId", "test-op")
            put("operationCorrelationId", "test-corr")
            put("state", "PREPARING")
            put("startedAt", 1234567890L)
        }

        val entry = RestoreJournal.JournalEntry.fromJson(json)
        assertEquals(1234567890L, entry.startedAt)
    }
}