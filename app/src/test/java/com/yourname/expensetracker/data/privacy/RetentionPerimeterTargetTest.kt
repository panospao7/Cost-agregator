package com.yourname.expensetracker.data.privacy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.data.database.entity.OperationRun
import com.yourname.expensetracker.data.database.entity.OperationRunEvent
import com.yourname.expensetracker.data.database.entity.PrivacyAuditEvent
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.di.RetentionModule
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.privacy.RetentionTarget
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * RP-14 (D14): row-level retention tests for the perimeter-completion targets
 * registered in [RetentionModule]:
 *
 * - P8-001: `10_transaction_events.snapshots` (30d) / `20_transaction_events.rows` (365d)
 * - P8-002: `scanned_receipts.rawOcrText` widened structured-field purge
 * - P8-003: `30_operation_runs` (90d) / `40_receipt_events` (90d)
 * - P8-007: `50_privacy_audit_events` (180d, compliance-flagged)
 * - P8-004: `ai_artifacts` null-TTL backstop
 *
 * These exercise the REAL production [RetentionTarget] implementations against
 * an in-memory Room [AppDatabase], mirroring the Robolectric harness of
 * [RetentionTargetPurgeTest]. Cutoff semantics are strict ` <` : a row whose
 * timestamp is EXACTLY at the cutoff is kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RetentionPerimeterTargetTest {

    private lateinit var database: AppDatabase

    private val now = 1_700_000_000_000L
    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = now }

    private val maintenanceMode = mockk<RestoreMaintenanceMode>()

    private fun writeBarrier(mode: RestoreMaintenanceMode.Mode): DatabaseWriteBarrier {
        val barrier = DatabaseWriteBarrier(maintenanceMode)
        every { maintenanceMode.currentMode() } returns mode
        every { maintenanceMode.isWritesAllowed() } returns
            (mode == RestoreMaintenanceMode.Mode.NORMAL)
        return barrier
    }

    @Before
    fun setUp() {
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun targetNamed(name: String): RetentionTarget =
        RetentionModule.provideRetentionTargets(database, timeProvider, writeBarrier(RestoreMaintenanceMode.Mode.NORMAL))
            .first { it.name == name }

    // ── time helpers (cutoffs are strict `<`: at-cutoff rows are KEPT) ────────

    private fun cutoffDaysAgo(days: Long): Long = now - TimeUnit.DAYS.toMillis(days)
    private fun justBeforeCutoff(days: Long): Long = cutoffDaysAgo(days) - 1

    // ── fixtures ──────────────────────────────────────────────────────────────

    private fun transactionEvent(occurredAt: Long, eventType: String = "UPDATED", withSnapshots: Boolean = true) =
        TransactionEvent(
            expenseId = 100L,
            eventType = eventType,
            source = "TEST",
            actor = "test",
            occurredAt = occurredAt,
            dedupeKey = null,
            duplicateExpenseId = null,
            beforeSnapshot = if (withSnapshots) """{"id":100}""" else null,
            afterSnapshot = if (withSnapshots) """{"id":100,"note":"merchant data"}""" else null,
            metadata = null,
            reason = null
        )

    private fun scannedReceipt(createdAt: Long, purgedAt: Long? = null) = ScannedReceipt(
        imagePath = null,
        rawOcrText = if (purgedAt != null) "" else "TOTAL 12.50 MERCHANT JOHN",
        parsedTotal = 12.5,
        parsedMerchant = if (purgedAt != null) null else "Johns Shop",
        parsedDate = createdAt,
        parsedItems = if (purgedAt != null) null else """[{"desc":"coffee","price":12.5}]""",
        parsedTaxAmount = null,
        confidence = 0.9f,
        createdAt = createdAt,
        updatedAt = createdAt,
        parseFailureReason = if (purgedAt != null) null else "IllegalStateException: some free text",
        rawOcrTextPurgedAt = purgedAt
    )

    private fun operationRun(
        correlationId: String,
        status: String,
        startedAt: Long,
        finishedAt: Long? = null
    ) = OperationRun(
        correlationId = correlationId,
        operationType = "TEST_OP",
        status = status,
        startedAt = startedAt,
        finishedAt = finishedAt
    )

    private fun operationRunEvent(runId: Long?, occurredAt: Long) = OperationRunEvent(
        operationRunId = runId,
        correlationId = "corr",
        operationType = "TEST_OP",
        stage = "stage",
        eventType = "EVENT",
        outcome = "DONE",
        severity = "INFO",
        occurredAt = occurredAt
    )

    private fun receiptEvent(occurredAt: Long) = ReceiptEvent(
        receiptId = 1L,
        sourceType = "CAMERA",
        documentType = "RETAIL_RECEIPT",
        eventType = "CAPTURED",
        occurredAt = occurredAt,
        oldStatus = null,
        newStatus = "CAPTURED",
        actor = "user",
        message = "event",
        metadata = null,
        errorDetails = null
    )

    private fun auditEvent(timestampMs: Long) = PrivacyAuditEvent(
        capability = "RAW_NOTIFICATION_RETENTION",
        decision = "ALLOWED",
        reason = "retention purge",
        context = null,
        timestampMs = timestampMs,
        caller = "DataRetentionWorker"
    )

    private fun aiArtifact(
        sourceHash: String,
        expiresAt: Long?,
        updatedAt: Long
    ) = AiArtifactEntity(
        targetType = AiTargetType.PENDING_REVIEW,
        targetKey = "pending_review:1-$sourceHash",
        capability = AiCapability.REVIEW_EXPLANATION,
        status = AiArtifactStatus.READY,
        mode = AiMode.AUTO,
        promptVersion = "v1",
        sourceHash = sourceHash,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        expiresAt = expiresAt
    )

    // ══ P8-001 — 10_transaction_events.snapshots (30d) ════════════════════════

    @Test
    fun `snapshot stage nulls old snapshots for all event types and keeps recent`() = runTest {
        val dao = database.transactionEventDao()
        val oldUpdate = dao.insert(transactionEvent(justBeforeCutoff(30), eventType = "UPDATED"))
        val oldDelete = dao.insert(transactionEvent(justBeforeCutoff(30), eventType = "DELETED"))
        val oldCreate = dao.insert(transactionEvent(justBeforeCutoff(30), eventType = "CREATED"))
        val recent = dao.insert(transactionEvent(now, eventType = "UPDATED"))

        val result = targetNamed("10_transaction_events.snapshots").purge(cutoffDaysAgo(30))

        assertTrue(result.success)
        assertEquals(3, result.rowsPurged)

        for (id in listOf(oldUpdate, oldDelete, oldCreate)) {
            val row = dao.getEventsForExpense(100L).first { it.id == id }
            assertNull("old beforeSnapshot must be nulled ($id)", row.beforeSnapshot)
            assertNull("old afterSnapshot must be nulled ($id)", row.afterSnapshot)
        }
        val recentRow = dao.getEventsForExpense(100L).first { it.id == recent }
        assertNotNull("recent snapshots must be preserved", recentRow.beforeSnapshot)
        assertNotNull(recentRow.afterSnapshot)
    }

    @Test
    fun `snapshot stage keeps rows exactly at cutoff and nulls one millisecond older`() = runTest {
        val dao = database.transactionEventDao()
        val atCutoff = dao.insert(transactionEvent(cutoffDaysAgo(30)))
        val justOver = dao.insert(transactionEvent(justBeforeCutoff(30)))

        val result = targetNamed("10_transaction_events.snapshots").purge(cutoffDaysAgo(30))

        assertEquals(1, result.rowsPurged)
        assertNotNull(dao.getEventsForExpense(100L).first { it.id == atCutoff }.beforeSnapshot)
        assertNull(dao.getEventsForExpense(100L).first { it.id == justOver }.beforeSnapshot)
    }

    @Test
    fun `snapshot stage is idempotent - rerun reports zero`() = runTest {
        val dao = database.transactionEventDao()
        dao.insert(transactionEvent(justBeforeCutoff(30)))
        val target = targetNamed("10_transaction_events.snapshots")

        assertEquals(1, target.purge(cutoffDaysAgo(30)).rowsPurged)
        assertEquals(0, target.purge(cutoffDaysAgo(30)).rowsPurged)
    }

    @Test
    fun `snapshot stage preserves rows inside the window - 29 days kept`() = runTest {
        val dao = database.transactionEventDao()
        dao.insert(transactionEvent(cutoffDaysAgo(29)))

        val result = targetNamed("10_transaction_events.snapshots").purge(cutoffDaysAgo(30))

        assertEquals(0, result.rowsPurged)
        assertNotNull(dao.getEventsForExpense(100L).first().beforeSnapshot)
    }

    // ══ P8-001 — 20_transaction_events.rows (365d) ════════════════════════════

    @Test
    fun `row stage deletes events past 365 days and keeps the rest`() = runTest {
        val dao = database.transactionEventDao()
        dao.insert(transactionEvent(cutoffDaysAgo(365) - 1, withSnapshots = false))
        dao.insert(transactionEvent(cutoffDaysAgo(365), withSnapshots = false))
        dao.insert(transactionEvent(cutoffDaysAgo(364), withSnapshots = false))

        val result = targetNamed("20_transaction_events.rows").purge(cutoffDaysAgo(365))

        assertTrue(result.success)
        assertEquals(1, result.rowsPurged)
        assertEquals(2, dao.getEventsForExpense(100L).size)
    }

    /**
     * P8-001: stage-1 success + stage-2 failure commits stage 1 and reports ONLY
     * the rows target as failed. With two independent targets, stage 1's SQL
     * update is committed before stage 2 runs — simulated here by closing the
     * database after stage 1 succeeded.
     */
    @Test
    fun `stage-2 failure leaves committed stage-1 result and reports only rows failure`() = runTest {
        val dao = database.transactionEventDao()
        dao.insert(transactionEvent(justBeforeCutoff(30)))

        val stage1 = targetNamed("10_transaction_events.snapshots").purge(cutoffDaysAgo(30))
        assertTrue(stage1.success)
        assertEquals(1, stage1.rowsPurged)

        database.close()

        val stage2 = targetNamed("20_transaction_events.rows").purge(cutoffDaysAgo(365))
        assertFalse("stage-2 failure must be reported as failure", stage2.success)
        assertEquals(0, stage2.rowsPurged)
        assertNotNull(stage2.errorClass)
    }

    // ══ P8-002 — scanned_receipts.rawOcrText structured purge ═════════════════

    @Test
    fun `receipt purge clears every raw-structured field and keeps recent rows`() = runTest {
        val dao = database.scannedReceiptDao()
        val oldId = dao.insert(scannedReceipt(justBeforeCutoff(30)))
        val recentId = dao.insert(scannedReceipt(now))

        val result = targetNamed("scanned_receipts.rawOcrText").purge(cutoffDaysAgo(30))

        assertTrue(result.success)
        assertEquals(1, result.rowsPurged)

        val old = dao.getById(oldId)!!
        assertEquals("rawOcrText column is NOT NULL — purged sentinel is empty string", "", old.rawOcrText)
        assertNull("parsedItems must be nulled", old.parsedItems)
        assertNull("parsedMerchant must be nulled", old.parsedMerchant)
        assertNull("parseFailureReason must be nulled (writers are not controlled codes)", old.parseFailureReason)
        assertNotNull("rawOcrTextPurgedAt must be stamped once", old.rawOcrTextPurgedAt)

        val recent = dao.getById(recentId)!!
        assertTrue(recent.rawOcrText.isNotEmpty())
        assertNotNull(recent.parsedItems)
        assertNotNull(recent.parsedMerchant)
        assertNull("recent row must not be marked purged", recent.rawOcrTextPurgedAt)
    }

    @Test
    fun `receipt purge is idempotent and skips already-purged rows`() = runTest {
        val dao = database.scannedReceiptDao()
        dao.insert(scannedReceipt(justBeforeCutoff(30)))
        // Row already purged on a PREVIOUS run: purgedAt stamped, fields cleared.
        val alreadyPurgedId = dao.insert(scannedReceipt(justBeforeCutoff(30), purgedAt = now))
        val target = targetNamed("scanned_receipts.rawOcrText")

        assertEquals("only the unpurged row is processed", 1, target.purge(cutoffDaysAgo(30)).rowsPurged)
        assertEquals("already-purged rows are never re-processed", 0, target.purge(cutoffDaysAgo(30)).rowsPurged)

        val alreadyPurged = dao.getById(alreadyPurgedId)!!
        assertEquals("", alreadyPurged.rawOcrText)
        assertNotNull(alreadyPurged.rawOcrTextPurgedAt)
    }

    // ══ P8-003 — 30_operation_runs (90d) ══════════════════════════════════════

    @Test
    fun `operation-run purge deletes old terminal runs with children, keeps running and recent`() = runTest {
        val runDao = database.operationRunDao()
        val eventDao = database.operationRunEventDao()

        val oldTerminalId = runDao.insert(operationRun("old-terminal", "SUCCESS", cutoffDaysAgo(91), finishedAt = justBeforeCutoff(90)))
        val oldRunningId = runDao.insert(operationRun("old-running", "RUNNING", justBeforeCutoff(90)))
        val recentTerminalId = runDao.insert(operationRun("recent-terminal", "FAILED_FINAL", now - 1000, finishedAt = now - 500))

        eventDao.insert(operationRunEvent(oldTerminalId, justBeforeCutoff(90)))
        eventDao.insert(operationRunEvent(oldTerminalId, justBeforeCutoff(90) - 1))
        eventDao.insert(operationRunEvent(oldRunningId, justBeforeCutoff(90)))
        eventDao.insert(operationRunEvent(recentTerminalId, now - 500))
        // Orphans: null parent + dangling parent id.
        eventDao.insert(operationRunEvent(null, justBeforeCutoff(90) - 1))
        eventDao.insert(operationRunEvent(999_999L, justBeforeCutoff(90) - 1))

        val result = targetNamed("30_operation_runs").purge(cutoffDaysAgo(90))

        assertTrue(result.success)
        // rowsPurged stays the total: 2 children + 1 parent + 2 orphans.
        assertEquals(5, result.rowsPurged)
        assertEquals(2, result.detailCounts["childEvents"])
        assertEquals(1, result.detailCounts["parentRuns"])
        assertEquals(2, result.detailCounts["orphanEvents"])

        // Old terminal run + its children gone.
        assertNull(runDao.getById(oldTerminalId))
        assertTrue(eventDao.getByRunId(oldTerminalId).isEmpty())
        // RUNNING rows are NEVER purged.
        assertNotNull(runDao.getById(oldRunningId))
        assertEquals(1, eventDao.getByRunId(oldRunningId).size)
        // Recent terminal run untouched.
        assertNotNull(runDao.getById(recentTerminalId))
        assertEquals(1, eventDao.getByRunId(recentTerminalId).size)
        // Both orphans gone.
        assertTrue(eventDao.getByCorrelationId("corr").all { it.operationRunId in listOf(oldRunningId, recentTerminalId) })
    }

    @Test
    fun `operation-run purge reports failure and never success when the database fails`() = runTest {
        database.close()

        val result = targetNamed("30_operation_runs").purge(cutoffDaysAgo(90))

        assertFalse(result.success)
        assertEquals(0, result.rowsPurged)
        assertNotNull(result.errorClass)
    }

    // ══ P8-003 — 40_receipt_events (90d) ══════════════════════════════════════

    @Test
    fun `receipt-event purge deletes old events and keeps the boundary and recent`() = runTest {
        val dao = database.receiptEventDao()
        dao.insert(receiptEvent(cutoffDaysAgo(90) - 1))
        dao.insert(receiptEvent(cutoffDaysAgo(90)))
        dao.insert(receiptEvent(now))

        val result = targetNamed("40_receipt_events").purge(cutoffDaysAgo(90))

        assertTrue(result.success)
        assertEquals(1, result.rowsPurged)
        val remaining = dao.getEventsForReceipt(1L)
        assertEquals(2, remaining.size)
        assertTrue(remaining.none { it.occurredAt < cutoffDaysAgo(90) })
    }

    // ══ P8-007 — 50_privacy_audit_events (180d, compliance-flagged) ═══════════

    @Test
    fun `privacy-audit purge deletes rows older than the 180d cutoff and keeps the rest`() = runTest {
        val dao = database.privacyAuditDao()
        dao.insert(auditEvent(cutoffDaysAgo(180) - 1))
        dao.insert(auditEvent(cutoffDaysAgo(180)))
        dao.insert(auditEvent(now))

        val result = targetNamed("50_privacy_audit_events").purge(cutoffDaysAgo(180))

        assertTrue(result.success)
        assertEquals(1, result.rowsPurged)
        assertEquals(2, dao.getRecent(10).size)
    }

    // ══ P8-004 — ai_artifacts null-TTL backstop ═══════════════════════════════

    @Test
    fun `ai-artifact purge removes expired TTL, stale null-expiry rows, keeps fresh ones`() = runTest {
        val dao = database.aiArtifactDao()
        val backstopCutoff = cutoffDaysAgo(30)

        dao.upsert(aiArtifact("expired_ttl", expiresAt = now - 1, updatedAt = now))
        dao.upsert(aiArtifact("fresh_ttl", expiresAt = now + TimeUnit.DAYS.toMillis(5), updatedAt = now))
        dao.upsert(aiArtifact("null_old", expiresAt = null, updatedAt = backstopCutoff - 1))
        dao.upsert(aiArtifact("null_at_backstop", expiresAt = null, updatedAt = backstopCutoff))
        dao.upsert(aiArtifact("null_recent", expiresAt = null, updatedAt = now))

        val result = targetNamed("ai_artifacts").purge(now)

        assertTrue(result.success)
        assertEquals(2, result.rowsPurged)
        val remaining = dao.getAll().map { it.sourceHash }.toSet()
        assertEquals(setOf("fresh_ttl", "null_at_backstop", "null_recent"), remaining)
    }
}
