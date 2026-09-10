package com.yourname.expensetracker.data.backup

import android.content.Context
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Verifies that [DataStoreMaintenanceSafeDiagnosticSink] persists exactly the timestamp
 * provided by [TimeProvider] (via [FakeTimeProvider]) for both blocked-operation records
 * and full diagnostic-event records.
 *
 * Uses the real DataStore-backed sink backed by a temporary directory, so records survive
 * a serialize/parse round-trip through the JSON ring buffer.
 */
class DataStoreMaintenanceSafeDiagnosticSinkTimeProviderTest {

    private companion object {
        const val FIXED_TIME_MS = 1_700_000_000_000L
    }

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var sink: DataStoreMaintenanceSafeDiagnosticSink

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("datastore_sink_time_").toFile()

        val ctx = mockk<Context>(relaxed = true)
        every { ctx.filesDir } returns tempDir
        every { ctx.applicationContext } returns ctx
        context = ctx

        timeProvider = FakeTimeProvider(fixedTime = FIXED_TIME_MS)
        sink = DataStoreMaintenanceSafeDiagnosticSink(context, timeProvider)

        // The preferencesDataStore delegate caches a JVM-wide singleton DataStore per
        // classloader, so reset any records left behind by earlier tests in the same JVM.
        runBlocking { sink.clearOlderThan(Long.MAX_VALUE) }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun recordBlockedOperation_persists_exact_fixed_timestamp() = runTest {
        sink.recordBlockedOperation(
            operation = "NotificationRepository.save",
            mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING,
            pipeline = "P1",
            entity = "Expense",
            reason = MaintenanceBlockedReason.WRITE_BARRIER_DENIED
        )

        val records = sink.observeRecent().first()

        assertEquals(1, records.size)
        val record = records.first()
        assertEquals(FIXED_TIME_MS, record.timestamp)
        assertEquals("NotificationRepository.save", record.operation)
        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_PREPARING.label, record.mode)
        assertEquals(MaintenanceBlockedReason.WRITE_BARRIER_DENIED.name, record.reason)
    }

    @Test
    fun recordDiagnosticEvent_persists_exact_fixed_timestamp() = runTest {
        val event = DiagnosticEvent(
            pipeline = AppPipeline.BACKUP_RESTORE,
            stage = "verify_snapshots",
            outcome = EventOutcome.BLOCKED,
            severity = EventSeverity.WARNING,
            reasonCode = DiagnosticReasonCode.WRITE_BARRIER_DENIED,
            entityType = "Expense",
            entityId = 42L,
            sourceType = "Notification",
            sourceIdHash = "a1b2c3d4",
            correlationId = "corr-12345678",
            causationId = "cause-1",
            metadata = SafeEventMetadata.builder().put("count", 3).build(),
            elapsedMs = 12L,
            isTerminal = false
        )

        sink.recordDiagnosticEvent(
            event = event,
            mode = RestoreMaintenanceMode.Mode.RESTORE_VERIFYING,
            writeFailure = null
        )

        val records = sink.observeRecent().first()

        assertEquals(1, records.size)
        val record = records.first()
        assertEquals(FIXED_TIME_MS, record.timestamp)
        assertEquals("BACKUP_RESTORE.verify_snapshots", record.operation)
        assertEquals(AppPipeline.BACKUP_RESTORE.name, record.pipeline)
        assertEquals(EventOutcome.BLOCKED.name, record.outcome)
        assertEquals(DiagnosticReasonCode.WRITE_BARRIER_DENIED.name, record.reasonCode)
    }

    @Test
    fun recordDiagnosticEvent_hostile_metadata_path_payload_not_persisted() = runTest {
        val hostilePath = "C:\\Users\\panos\\AppData\\Local\\Temp\\secret\\expense_tracker.db"
        val hostileMessage = "open failed: /data/user/0/com.yourname.expensetracker/databases/expense_tracker.db"
        val hostilePayload = "raw notification text: BANK ALERT token=abc1234567890"

        // Hostile metadata: blocked keys ("filepath", "rawbody") are redacted at
        // construction, a safe key ("details") carries a hostile path string that the
        // value sanitizer must redact, and a controlled safe value ("count") survives.
        val metadata = SafeEventMetadata.builder()
            .put("filepath", hostilePath)
            .put("rawbody", hostilePayload)
            .put("details", hostileMessage)
            .put("count", 3)
            .build()

        val event = DiagnosticEvent(
            pipeline = AppPipeline.BACKUP_RESTORE,
            stage = "restore_safety_backup",
            outcome = EventOutcome.FAILED_FINAL,
            severity = EventSeverity.ERROR,
            reasonCode = DiagnosticReasonCode.VALIDATION_FAILED,
            entityType = "Expense",
            entityId = 42L,
            sourceType = "Notification",
            sourceIdHash = "a1b2c3d4e5f6a7b8",
            correlationId = "corr-12345678",
            causationId = "cause-1",
            metadata = metadata,
            exception = RuntimeException(hostileMessage),
            elapsedMs = 12L,
            isTerminal = true
        )

        sink.recordDiagnosticEvent(
            event = event,
            mode = RestoreMaintenanceMode.Mode.RESTORE_VERIFYING,
            writeFailure = IllegalStateException("write failed: $hostilePath")
        )

        val records = sink.observeRecent().first()
        assertEquals(1, records.size)
        val record = records.first()

        // Exact fixed timestamp preserved — no wall clock, no sleeps.
        assertEquals(FIXED_TIME_MS, record.timestamp)

        // Only controlled fields / reason codes are persisted.
        assertEquals("BACKUP_RESTORE.restore_safety_backup", record.operation)
        assertEquals(AppPipeline.BACKUP_RESTORE.name, record.pipeline)
        assertEquals(EventOutcome.FAILED_FINAL.name, record.outcome)
        assertEquals(EventSeverity.ERROR.name, record.severity)
        assertEquals(DiagnosticReasonCode.VALIDATION_FAILED.name, record.reasonCode)
        assertEquals(DiagnosticReasonCode.VALIDATION_FAILED.name, record.reason)
        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_VERIFYING.label, record.mode)
        assertEquals("RuntimeException", record.exceptionClass)
        assertEquals("IllegalStateException", record.writeFailureClass)
        assertTrue(record.isTerminal)

        // Hostile path / message / payload are absent from the persisted record.
        assertFalse(record.metadataJson!!.contains(hostilePath))
        assertFalse(record.metadataJson!!.contains(hostileMessage))
        assertFalse(record.metadataJson!!.contains("BANK ALERT"))
        assertFalse(record.metadataJson!!.contains("token="))
        assertTrue(record.metadataJson!!.contains("[REDACTED]"))
        assertFalse(record.exceptionMessageSafe!!.contains(hostileMessage))
        assertFalse(record.exceptionMessageSafe!!.contains("/data/user/0"))
        assertFalse(record.writeFailureMessageSafe!!.contains(hostilePath))
    }

    @Test
    fun recordBlockedOperation_reason_is_controlled_enum_not_raw_string() = runTest {
        // The blocked-operation API only accepts the controlled MaintenanceBlockedReason
        // enum, so a hostile raw reason string can never reach the persisted record.
        sink.recordBlockedOperation(
            operation = "NotificationRepository.save",
            mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING,
            pipeline = "P1",
            entity = "Expense",
            reason = MaintenanceBlockedReason.UNKNOWN
        )

        val records = sink.observeRecent().first()
        assertEquals(1, records.size)
        val record = records.first()

        // Exact fixed timestamp preserved — no wall clock, no sleeps.
        assertEquals(FIXED_TIME_MS, record.timestamp)

        // reason and mode are derived from controlled enums, never raw strings.
        assertEquals(MaintenanceBlockedReason.UNKNOWN.name, record.reason)
        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_PREPARING.label, record.mode)
    }
}
