package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.domain.util.FakeMonotonicTimeProvider
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S2: [PrivacyDiagnosticsEmitter] typed API, metadata allowlist (INV-18, AE-21) and the
 * DIAG_RATE limiter with DL_DIAGNOSTICS_SUPPRESSED (INV-18, AE-33).
 */
class PrivacyDiagnosticsEmitterTest {

    private enum class FakeHost { ADD, ANALYTICS }

    private val wallClock = FakeTimeProvider(1_716_163_200_000L)
    private val monotonic = FakeMonotonicTimeProvider(1_000L)
    private var currentMode = RestoreMaintenanceMode.Mode.NORMAL
    private val maintenanceMode = mockk<RestoreMaintenanceMode>().also {
        every { it.currentMode() } answers { currentMode }
    }
    private val sink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)
    private val written = mutableListOf<DiagnosticEvent>()
    private var writerFailure: Throwable? = null
    private val writer = mockk<DiagnosticEventWriter>().also {
        coEvery { it.emit(any()) } answers {
            writerFailure?.let { failure -> throw failure }
            written += firstArg<DiagnosticEvent>()
        }
    }
    private val delegate = NotificationDiagnosticEmitter(
        writer,
        sink,
        maintenanceMode,
        Dispatchers.Unconfined,
        wallClock
    )
    private val emitter = PrivacyDiagnosticsEmitter(delegate, monotonic)

    private fun metadataOf(event: DiagnosticEvent): JSONObject = JSONObject(event.metadata.toJson())

    // --- Event shape (INV-18) -------------------------------------------------------------

    @Test
    fun `emit writes one privacy event with constant stage and no entity source hash or throwable`() = runTest {
        emitter.emit(PrivacyDiagnosticCode.DL_REJECTED_MALFORMED)

        assertEquals(1, written.size)
        val event = written.single()
        assertEquals(AppPipeline.PRIVACY, event.pipeline)
        assertEquals(PrivacyDiagnosticsEmitter.STAGE, event.stage)
        assertEquals(DiagnosticReasonCode.DL_REJECTED_MALFORMED, event.reasonCode)
        assertTrue(event.isTerminal)
        assertNull(event.entityType)
        assertNull(event.entityId)
        assertNull(event.sourceType)
        assertNull(event.sourceIdHash)
        assertNull(event.exception)
        assertTrue(event.metadata.isEmpty())
    }

    @Test
    fun `every code maps to its same-named reason code and the suppressed code is not a caller code`() {
        for (code in PrivacyDiagnosticCode.entries) {
            assertEquals(code.name, code.reasonCode.name)
        }
        assertTrue(PrivacyDiagnosticCode.entries.none { it.name == "DL_DIAGNOSTICS_SUPPRESSED" })
        assertEquals(5, PrivacyDiagnosticCode.entries.count { it.name.startsWith("NOTIF_") })
        assertEquals(14, PrivacyDiagnosticCode.entries.count { it.name.startsWith("DL_") })
    }

    // --- Metadata allowlist (AE-21) -------------------------------------------------------

    @Test
    fun `allowed enum boolean and count metadata is stored`() = runTest {
        emitter.emit(
            PrivacyDiagnosticCode.DL_APPLIED,
            PrivacyDiagnosticMetadata.builder()
                .put(PrivacyDiagnosticKey.HOST, FakeHost.ADD)
                .put(PrivacyDiagnosticKey.AFTER_UNLOCK, true)
                .build()
        )
        emitter.emit(
            PrivacyDiagnosticCode.DL_PARAM_UNKNOWN_DROPPED,
            PrivacyDiagnosticMetadata.builder()
                .put(PrivacyDiagnosticKey.HOST, FakeHost.ANALYTICS)
                .put(PrivacyDiagnosticKey.COUNT, 2)
                .build()
        )

        val applied = metadataOf(written[0])
        assertEquals("ADD", applied.getString("host"))
        assertTrue(applied.getBoolean("afterUnlock"))
        val dropped = metadataOf(written[1])
        assertEquals("ANALYTICS", dropped.getString("host"))
        assertEquals(2, dropped.getInt("count"))
    }

    @Test
    fun `keys not allowed for a code are dropped`() = runTest {
        emitter.emit(
            PrivacyDiagnosticCode.DL_REJECTED_UNKNOWN_HOST,
            PrivacyDiagnosticMetadata.builder()
                .put(PrivacyDiagnosticKey.HOST, FakeHost.ADD)
                .put(PrivacyDiagnosticKey.COUNT, 7)
                .exceptionClass(IllegalStateException("evilhost"))
                .build()
        )

        assertTrue(written.single().metadata.isEmpty())
    }

    @Test
    fun `negative counts are stored as zero`() = runTest {
        emitter.emit(
            PrivacyDiagnosticCode.NOTIF_REPOSTED_DISCREET,
            PrivacyDiagnosticMetadata.builder().put(PrivacyDiagnosticKey.COUNT, -5).build()
        )

        assertEquals(0, metadataOf(written.single()).getInt("count"))
    }

    @Test
    fun `exception metadata is the class simple name only and the event carries no throwable`() = runTest {
        val error = IllegalStateException("merchant ACME 12345678901234 /data/user/0/secret.db")
        emitter.emit(
            PrivacyDiagnosticCode.DL_TARGET_READ_BLOCKED,
            PrivacyDiagnosticMetadata.builder().exceptionClass(error).build()
        )

        val event = written.single()
        assertNull(event.exception)
        assertEquals("IllegalStateException", metadataOf(event).getString("exceptionClass"))
        val json = event.metadata.toJson()
        assertFalse(json.contains("ACME"))
        assertFalse(json.contains("12345678901234"))
        assertFalse(json.contains("secret.db"))
    }

    @Test
    fun `every metadata wire key survives the production sanitizer unredacted`() {
        val sanitizer = EventMetadataSanitizer()
        for (key in PrivacyDiagnosticKey.entries) {
            assertFalse("${key.wireName} must not be dangerous", sanitizer.isDangerousKey(key.wireName))
        }
        for (code in PrivacyDiagnosticCode.entries) {
            assertTrue(code.allowedKeys.none { it == PrivacyDiagnosticKey.SUPPRESSED_REASON_CODE })
        }
    }

    @Test
    fun `the nine S16 lock codes carry no metadata keys at all`() {
        val lockControllerCodes = listOf(
            PrivacyDiagnosticCode.LOCK_SHOWN_COLD_START,
            PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT,
            PrivacyDiagnosticCode.LOCK_SHOWN_SETTINGS_UNREADABLE,
            PrivacyDiagnosticCode.LOCK_UNLOCKED,
            PrivacyDiagnosticCode.LOCK_INERT_NO_CREDENTIAL,
            PrivacyDiagnosticCode.LOCK_INERT_HW_UNAVAILABLE,
            PrivacyDiagnosticCode.LOCK_INERT_UNKNOWN,
            PrivacyDiagnosticCode.LOCK_INERT_API_UNSUPPORTED,
            PrivacyDiagnosticCode.LOCK_INERT_SETTINGS_UNREADABLE
        )

        // The emitter's real S16 part (i) entries: shown, unlocked and inert (not the settings-durability
        // LOCK_SETTING* codes). A new such code must be added here on purpose.
        val emitterLockCodes = PrivacyDiagnosticCode.entries.filter {
            it.name.startsWith("LOCK_SHOWN_") || it.name.startsWith("LOCK_INERT_") || it.name == "LOCK_UNLOCKED"
        }
        assertEquals(
            listOf(
                "LOCK_SHOWN_COLD_START",
                "LOCK_SHOWN_TIMEOUT",
                "LOCK_SHOWN_SETTINGS_UNREADABLE",
                "LOCK_UNLOCKED",
                "LOCK_INERT_NO_CREDENTIAL",
                "LOCK_INERT_HW_UNAVAILABLE",
                "LOCK_INERT_UNKNOWN",
                "LOCK_INERT_API_UNSUPPORTED",
                "LOCK_INERT_SETTINGS_UNREADABLE"
            ).sorted(),
            emitterLockCodes.map { it.name }.sorted()
        )
        assertEquals(lockControllerCodes.toSet(), emitterLockCodes.toSet())
        for (code in lockControllerCodes) {
            assertTrue("${code.name} must allow no metadata key", code.allowedKeys.isEmpty())
        }
    }

    @Test
    fun `every allowed key round-trips through the sanitizer for every code`() = runTest {
        for (code in PrivacyDiagnosticCode.entries) {
            val builder = PrivacyDiagnosticMetadata.builder()
            for (key in code.allowedKeys) {
                when (key) {
                    PrivacyDiagnosticKey.AFTER_UNLOCK,
                    PrivacyDiagnosticKey.BANK_SYNC_AVAILABLE -> builder.put(key, true)
                    PrivacyDiagnosticKey.COUNT,
                    PrivacyDiagnosticKey.SDK_INT -> builder.put(key, 30)
                    PrivacyDiagnosticKey.EXCEPTION_CLASS -> builder.exceptionClass(IllegalStateException("x"))
                    else -> builder.put(key, FakeHost.ADD)
                }
            }
            emitter.emit(code, builder.build())
            monotonic.advanceMillis(61_000L)
        }

        assertEquals(PrivacyDiagnosticCode.entries.size, written.size)
        for ((index, code) in PrivacyDiagnosticCode.entries.withIndex()) {
            val json = metadataOf(written[index])
            for (key in code.allowedKeys) {
                assertTrue("${code.name} must carry ${key.wireName}", json.has(key.wireName))
                assertFalse(
                    "${code.name}.${key.wireName} must not be redacted",
                    json.get(key.wireName).toString() == "[REDACTED]"
                )
            }
        }
    }

    // --- Rate limiter (AE-33) -------------------------------------------------------------

    @Test
    fun `flood of one code writes one event then one suppressed summary at the next window`() = runTest {
        repeat(1000) {
            emitter.emit(PrivacyDiagnosticCode.DL_REJECTED_UNKNOWN_HOST)
            monotonic.advanceMillis(10L)
        }
        assertEquals(1, written.size)
        assertEquals(DiagnosticReasonCode.DL_REJECTED_UNKNOWN_HOST, written.single().reasonCode)

        monotonic.advanceMillis(60_000L)
        emitter.emit(PrivacyDiagnosticCode.DL_REJECTED_UNKNOWN_HOST)

        assertEquals(3, written.size)
        val summary = written[1]
        assertEquals(DiagnosticReasonCode.DL_DIAGNOSTICS_SUPPRESSED, summary.reasonCode)
        assertEquals(AppPipeline.PRIVACY, summary.pipeline)
        assertEquals(PrivacyDiagnosticsEmitter.STAGE, summary.stage)
        val json = metadataOf(summary)
        assertEquals("DL_REJECTED_UNKNOWN_HOST", json.getString("reasonCode"))
        assertEquals(999, json.getInt("count"))
        assertEquals(DiagnosticReasonCode.DL_REJECTED_UNKNOWN_HOST, written[2].reasonCode)
    }

    @Test
    fun `window boundary is exactly sixty seconds`() = runTest {
        emitter.emit(PrivacyDiagnosticCode.DL_HELD_LOCKED)
        monotonic.advanceNanos(PrivacyDiagnosticsEmitter.RATE_WINDOW_NANOS - 1L)
        emitter.emit(PrivacyDiagnosticCode.DL_HELD_LOCKED)
        assertEquals(1, written.size)

        monotonic.advanceNanos(1L)
        emitter.emit(PrivacyDiagnosticCode.DL_HELD_LOCKED)
        // first event, summary (count 1), second window event
        assertEquals(3, written.size)
        assertEquals(1, metadataOf(written[1]).getInt("count"))
    }

    @Test
    fun `a new window without suppressed events writes no summary`() = runTest {
        emitter.emit(PrivacyDiagnosticCode.DL_HELD_EXPIRED)
        monotonic.advanceMillis(61_000L)
        emitter.emit(PrivacyDiagnosticCode.DL_HELD_EXPIRED)

        assertEquals(2, written.size)
        assertTrue(written.none { it.reasonCode == DiagnosticReasonCode.DL_DIAGNOSTICS_SUPPRESSED })
    }

    @Test
    fun `limiter is keyed per code`() = runTest {
        emitter.emit(PrivacyDiagnosticCode.DL_REJECTED_ACTION)
        emitter.emit(PrivacyDiagnosticCode.DL_REJECTED_MALFORMED)
        emitter.emit(PrivacyDiagnosticCode.DL_REJECTED_ACTION)

        assertEquals(
            listOf(DiagnosticReasonCode.DL_REJECTED_ACTION, DiagnosticReasonCode.DL_REJECTED_MALFORMED),
            written.map { it.reasonCode }
        )
    }

    @Test
    fun `wall clock changes do not release the limiter`() = runTest {
        emitter.emit(PrivacyDiagnosticCode.DL_TARGET_NOT_FOUND)
        wallClock.advanceTime(24L * 60L * 60L * 1000L)
        emitter.emit(PrivacyDiagnosticCode.DL_TARGET_NOT_FOUND)

        assertEquals(1, written.size)
    }

    // --- Best effort and cancellation -----------------------------------------------------

    @Test
    fun `cancellation from the writer propagates`() = runTest {
        writerFailure = CancellationException("cancelled")
        try {
            emitter.emit(PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED)
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            // expected
        }
    }

    @Test
    fun `writer failure is routed to the maintenance sink and never thrown`() = runTest {
        writerFailure = IllegalStateException("db down")

        emitter.emit(
            PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED,
            PrivacyDiagnosticMetadata.builder().put(PrivacyDiagnosticKey.FIELD, FakeHost.ADD).build()
        )

        assertTrue(written.isEmpty())
        coVerify(exactly = 1) { sink.recordDiagnosticEvent(any(), any(), any()) }
    }

    @Test
    fun `non-normal maintenance mode uses the sink path through the delegate`() = runTest {
        currentMode = RestoreMaintenanceMode.Mode.RESTORE_ROLLING_BACK

        emitter.emit(PrivacyDiagnosticCode.NOTIF_ACTIONS_SUPPRESSED_LOCK)

        assertTrue(written.isEmpty())
        coVerify(exactly = 1) { sink.recordDiagnosticEvent(any(), any(), any()) }
    }
}
