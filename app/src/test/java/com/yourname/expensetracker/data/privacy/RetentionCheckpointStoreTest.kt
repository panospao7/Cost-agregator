package com.yourname.expensetracker.data.privacy

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RP-16 16-C: per-target retention checkpoint state machine tests.
 *
 * Covers: v2 record round-trips, legacy boolean compatibility (booleans are
 * NEVER reinterpreted as counts), legacy prefix-resume semantics, per-record
 * cleanup with no blanket preferences clear, corrupt-record handling, and the
 * PENDING → COMPLETED/FAILED transitions.
 */
@RunWith(RobolectricTestRunner::class)
class RetentionCheckpointStoreTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: RetentionCheckpointStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("data_retention_checkpoint_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = RetentionCheckpointStore(prefs)
    }

    private fun completedRecord(
        target: String,
        rowsPurged: Int = 12,
        auditEmitted: Boolean = false
    ) = RetentionCheckpointRecord(
        targetName = target,
        state = RetentionCheckpointState.COMPLETED,
        rowsPurged = rowsPurged,
        auditEmitted = auditEmitted,
        attempts = 1,
        cutoffMs = 1_700_000_000_000L,
        retentionDays = 14,
        updatedAtMs = 1_700_000_000_000L
    )

    // ── v2 record round-trips ──────────────────────────────────────────

    @Test
    fun `save and load round-trips a COMPLETED record`() {
        val record = completedRecord("raw_notifications", rowsPurged = 42)

        assertTrue(store.save(record))

        val loaded = store.load("raw_notifications")
        assertEquals(record, loaded)
        assertTrue(store.hasAnyRecord())
        assertEquals(listOf("raw_notifications"), store.allRecordedTargetNames())
    }

    @Test
    fun `pending to completed to failed transitions persist`() {
        val target = "scanned_receipts.rawOcrText"
        assertTrue(store.save(RetentionCheckpointRecord(targetName = target, state = RetentionCheckpointState.PENDING, attempts = 1)))
        assertEquals(RetentionCheckpointState.PENDING, store.load(target)?.state)

        assertTrue(store.save(completedRecord(target)))
        val completed = store.load(target)!!
        assertEquals(RetentionCheckpointState.COMPLETED, completed.state)
        assertEquals(12, completed.rowsPurged)
        assertFalse(completed.auditEmitted)

        assertTrue(
            store.save(
                RetentionCheckpointRecord(
                    targetName = target,
                    state = RetentionCheckpointState.FAILED,
                    errorCode = "WORKER_TRANSIENT_ERROR",
                    isTransient = true,
                    attempts = 2
                )
            )
        )
        val failed = store.load(target)!!
        assertEquals(RetentionCheckpointState.FAILED, failed.state)
        assertEquals("WORKER_TRANSIENT_ERROR", failed.errorCode)
        assertTrue(failed.isTransient)
        assertEquals(2, failed.attempts)
    }

    @Test
    fun `corrupt record is treated as absent`() {
        prefs.edit().putString("checkpoint_v2_raw_notifications", "{not-json").commit()

        assertNull(store.load("raw_notifications"))
    }

    @Test
    fun `unsupported record version is treated as absent`() {
        prefs.edit().putString("checkpoint_v2_raw_notifications", "{\"v\":99,\"state\":\"COMPLETED\"}").commit()

        assertNull(store.load("raw_notifications"))
    }

    // ── Legacy boolean compatibility ───────────────────────────────────

    @Test
    fun `legacy booleans are read as booleans only`() {
        prefs.edit().putBoolean("completed_raw_notifications", true).commit()

        assertTrue(store.legacyIsComplete("raw_notifications"))
        assertFalse(store.legacyIsComplete("raw_notifications_wrong"))
        // A boolean true is never surfaced as a count.
        assertNull(store.load("raw_notifications"))
    }

    @Test
    fun `legacy resume point returns first incomplete after a complete prefix`() {
        prefs.edit()
            .putBoolean("completed_ai_artifacts", true) // sorts first
            .putBoolean("completed_ai_chat_messages", true)
            .commit()

        val resume = store.legacyResumePoint(listOf("ai_artifacts", "ai_chat_messages", "email_receipt_sources"))

        assertEquals("email_receipt_sources", resume)
    }

    @Test
    fun `legacy resume point is null without a complete prefix`() {
        prefs.edit().putBoolean("completed_email_receipt_sources", true).commit()

        // First target incomplete with NO preceding complete target → no active checkpoint.
        assertNull(store.legacyResumePoint(listOf("ai_artifacts", "email_receipt_sources")))
    }

    @Test
    fun `legacy cleared sentinel voids legacy checkpoint`() {
        prefs.edit()
            .putBoolean("_cleared", true)
            .putBoolean("completed_ai_artifacts", true)
            .commit()

        assertNull(store.legacyResumePoint(listOf("ai_artifacts", "email_receipt_sources")))
    }

    @Test
    fun `v2 records take precedence over legacy resume`() {
        prefs.edit().putBoolean("completed_ai_artifacts", true).commit()
        store.save(completedRecord("raw_notifications"))

        assertNull(store.legacyResumePoint(listOf("ai_artifacts", "raw_notifications")))
    }

    // ── Per-record cleanup (no blanket clear) ──────────────────────────

    @Test
    fun `remove deletes exactly one target record and leaves others`() {
        store.save(completedRecord("raw_notifications", auditEmitted = true))
        store.save(completedRecord("scanned_receipts.rawOcrText", auditEmitted = true))

        assertTrue(store.remove("raw_notifications"))

        assertNull(store.load("raw_notifications"))
        assertEquals("scanned_receipts.rawOcrText", store.load("scanned_receipts.rawOcrText")?.targetName)
    }

    @Test
    fun `remove also clears the legacy boolean keys of the target`() {
        prefs.edit()
            .putBoolean("completed_raw_notifications", true)
            .putBoolean("completed_raw_notifications_failed", true)
            .commit()
        store.save(completedRecord("raw_notifications", auditEmitted = true))

        store.remove("raw_notifications")

        assertFalse(prefs.getBoolean("completed_raw_notifications", false))
        assertFalse(prefs.getBoolean("completed_raw_notifications_failed", false))
        assertFalse(prefs.all.containsKey("checkpoint_v2_raw_notifications"))
    }

    @Test
    fun `no blanket clear — unrelated keys survive cleanup of all records`() {
        prefs.edit().putString("unrelated_future_key", "keep-me").commit()
        store.save(completedRecord("raw_notifications", auditEmitted = true))
        store.save(completedRecord("scanned_receipts.rawOcrText", auditEmitted = true))

        store.remove("raw_notifications")
        store.remove("scanned_receipts.rawOcrText")

        assertEquals("keep-me", prefs.getString("unrelated_future_key", null))
        assertFalse(store.hasAnyRecord())
    }

    @Test
    fun `failed records are preserved by the store until explicitly removed`() {
        store.save(
            RetentionCheckpointRecord(
                targetName = "email_receipt_sources",
                state = RetentionCheckpointState.FAILED,
                errorCode = "WORKER_UNHANDLED_EXCEPTION",
                isTransient = false,
                attempts = 3
            )
        )

        // Simulates the worker's end-of-run cleanup decision: only COMPLETED
        // targets are removable; FAILED persists for the next scheduled run.
        val removable = store.allRecordedTargetNames().filter { name ->
            val rec = store.load(name)
            rec?.state == RetentionCheckpointState.COMPLETED
        }
        removable.forEach { store.remove(it) }

        assertEquals(
            RetentionCheckpointState.FAILED,
            store.load("email_receipt_sources")?.state
        )
        assertEquals(3, store.load("email_receipt_sources")?.attempts)
    }
}
