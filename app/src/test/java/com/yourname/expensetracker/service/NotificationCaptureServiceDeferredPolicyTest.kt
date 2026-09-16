package com.yourname.expensetracker.service

import android.app.Notification
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.domain.notification.capture.DeferredCaptureStorageSnapshot
import com.yourname.expensetracker.domain.notification.capture.NotificationCaptureBlockReason
import com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDecision
import com.yourname.expensetracker.domain.notification.capture.NotificationCaptureGate
import com.yourname.expensetracker.domain.notification.capture.NotificationIntakeCaptureResult
import com.yourname.expensetracker.domain.notification.capture.NotificationIntakeCoordinator
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * RP-10 10a (P1-002): the TemporarilyUnavailable deferred branch of
 * [NotificationCaptureService] must resolve ONE bounded privacy snapshot
 * (a single settings read) before deferring a notification:
 *
 *  - If the settings read fails, fail closed: a terminal
 *    DEFERRED_STORAGE_POLICY_UNAVAILABLE diagnostic is emitted (stage
 *    "capture_gate", outcome DROPPED, hashed packageName) and NOTHING is
 *    deferred — no extraction, no row, no payload.
 *  - If it succeeds, the deferred [DeferredCaptureStorageSnapshot] carries the
 *    user's actual [RawStorageMode] with the same per-mode extras contract as
 *    the live path: STORE_RAW → sanitized extras JSON (sensitive keys
 *    excluded), STORE_REDACTED → literal redacted JSON,
 *    STORE_METADATA_ONLY / DO_NOT_STORE → null.
 *  - A [CancellationException] from the settings read must propagate (never be
 *    converted into a terminal diagnostic) and must not defer anything.
 *
 * The service is instantiated with Robolectric WITHOUT running onCreate so
 * Hilt field injection cannot overwrite the test mocks; all collaborators
 * needed by the branch are @Inject lateinit var fields injected via
 * [ReflectionHelpers.setField]. The service's own coroutine scope runs on the
 * real Dispatchers.IO, so async side effects are settled with
 * coVerify(timeout = ...) instead of a test dispatcher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationCaptureServiceDeferredPolicyTest {

    private val emittedEvents = mutableListOf<DiagnosticEvent>()
    private val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>()
    private val captureGate = mockk<NotificationCaptureGate>()
    private val diagnosticEmitter = mockk<NotificationDiagnosticEmitter>(relaxed = true)
    private val privacySettingsRepository = mockk<PrivacySettingsRepository>()
    private val intakeCoordinator = mockk<NotificationIntakeCoordinator>()

    private lateinit var service: NotificationCaptureService

    @Before
    fun setUp() {
        // Attach only — skip onCreate so Hilt cannot overwrite injected mocks.
        service = Robolectric.buildService(NotificationCaptureService::class.java).get()
        ReflectionHelpers.setField(service, "restoreMaintenanceMode", restoreMaintenanceMode)
        ReflectionHelpers.setField(service, "captureGate", captureGate)
        ReflectionHelpers.setField(service, "notificationDiagnosticEmitter", diagnosticEmitter)
        ReflectionHelpers.setField(service, "privacySettingsRepository", privacySettingsRepository)
        ReflectionHelpers.setField(service, "intakeCoordinator", intakeCoordinator)

        // Fast pre-checks pass; the gate defers every notification into the
        // TemporarilyUnavailable branch under test.
        every { restoreMaintenanceMode.isWritesAllowed() } returns true
        coEvery { captureGate.decide(any(), any()) } returns
            NotificationCaptureDecision.TemporarilyUnavailable(
                reason = NotificationCaptureBlockReason.GATE_NOT_READY,
                retryable = true
            )
        coEvery { diagnosticEmitter.emit(capture(emittedEvents)) } returns Unit
        coEvery {
            diagnosticEmitter.emitOrdered(capture(emittedEvents), capture(emittedEvents))
        } returns Unit
        coEvery {
            intakeCoordinator.captureForRetry(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns NotificationIntakeCaptureResult.Enqueued(1L, "test-correlation-id")
    }

    /** Builds a minimal StatusBarNotification whose extras are configured by [extrasConfig]. */
    private fun buildStatusBarNotification(extrasConfig: Bundle.() -> Unit = {}): StatusBarNotification {
        val notification = Notification()
        notification.extras.apply(extrasConfig)
        return StatusBarNotification(
            "com.bank.app",        // pkg
            "com.bank.app",        // opPkg
            1,                     // id
            "test-tag",            // tag
            10042,                 // uid
            10042,                 // initialPid
            0,                     // intended-user id (mockable-jar ctor)
            notification,
            Process.myUserHandle(),
            1_700_000_000_000L     // postTime
        )
    }

    @Test
    fun `settings read failure emits DEFERRED_STORAGE_POLICY_UNAVAILABLE and defers nothing`() {
        coEvery { privacySettingsRepository.getSettings() } throws RuntimeException("datastore down")

        service.onNotificationPosted(buildStatusBarNotification())

        // Settle: the fail-closed diagnostic is the LAST side effect of the
        // deferred branch when settings resolution fails (settings = null →
        // the branch returns without any captureForRetry call).
        coVerify(timeout = 5_000) {
            diagnosticEmitter.emit(match { event ->
                event.reasonCode == DiagnosticReasonCode.DEFERRED_STORAGE_POLICY_UNAVAILABLE
            })
        }

        val event = emittedEvents.single {
            it.reasonCode == DiagnosticReasonCode.DEFERRED_STORAGE_POLICY_UNAVAILABLE
        }
        assertEquals("capture_gate", event.stage)
        assertEquals(EventOutcome.DROPPED, event.outcome)
        assertTrue(event.isTerminal)

        // Privacy: packageName must be hashed in metadata, never raw.
        val metadataJson = event.metadata.toJson()
        assertTrue(JSONObject(metadataJson).has("packageName"))
        assertFalse("raw packageName must not appear in diagnostics", metadataJson.contains("com.bank.app"))

        // Fail closed: no deferred row, no payload.
        coVerify(exactly = 0) {
            intakeCoordinator.captureForRetry(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `DO_NOT_STORE defers snapshot with null extrasJson`() {
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(rawNotificationStorageMode = RawStorageMode.DO_NOT_STORE)

        service.onNotificationPosted(buildStatusBarNotification())

        val storageSlot = slot<DeferredCaptureStorageSnapshot>()
        coVerify(timeout = 5_000) {
            intakeCoordinator.captureForRetry(
                any(), any(), any(), any(), any(), any(), any(), any(), capture(storageSlot)
            )
        }
        assertEquals(RawStorageMode.DO_NOT_STORE, storageSlot.captured.storageMode)
        assertNull("DO_NOT_STORE must not carry extras JSON", storageSlot.captured.extrasJson)
    }

    @Test
    fun `STORE_RAW defers sanitized extras JSON`() {
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(rawNotificationStorageMode = RawStorageMode.STORE_RAW)

        service.onNotificationPosted(
            buildStatusBarNotification {
                putCharSequence(Notification.EXTRA_TITLE, "Payment received")
                putCharSequence(Notification.EXTRA_TEXT, "You received EUR 10.00")
                putCharSequence("amount", "4242.99 EUR")
                putCharSequence("merchantName", "Coffee Shop")
            }
        )

        val storageSlot = slot<DeferredCaptureStorageSnapshot>()
        coVerify(timeout = 5_000) {
            intakeCoordinator.captureForRetry(
                any(), any(), any(), any(), any(), any(), any(), any(), capture(storageSlot)
            )
        }
        val extrasJson = storageSlot.captured.extrasJson
        assertTrue("STORE_RAW must carry extras JSON", extrasJson != null)

        // Must parse as a JSON object (throws if malformed).
        val json = JSONObject(extrasJson!!)
        assertFalse("sensitive key must be excluded", json.has("amount"))
        assertFalse(
            "sensitive value must not leak anywhere in the JSON",
            json.toString().contains("4242.99")
        )
        assertEquals("Coffee Shop", json.optString("merchantName"))
    }

    @Test
    fun `STORE_REDACTED defers literal redacted JSON`() {
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(rawNotificationStorageMode = RawStorageMode.STORE_REDACTED)

        service.onNotificationPosted(
            buildStatusBarNotification {
                putCharSequence(Notification.EXTRA_TITLE, "Payment received")
                putCharSequence(Notification.EXTRA_TEXT, "You received EUR 10.00")
            }
        )

        val storageSlot = slot<DeferredCaptureStorageSnapshot>()
        coVerify(timeout = 5_000) {
            intakeCoordinator.captureForRetry(
                any(), any(), any(), any(), any(), any(), any(), any(), capture(storageSlot)
            )
        }
        assertEquals(RawStorageMode.STORE_REDACTED, storageSlot.captured.storageMode)
        assertEquals("""{"redacted":true}""", storageSlot.captured.extrasJson)
    }

    @Test
    fun `settings read CancellationException propagates without deferring`() {
        coEvery { privacySettingsRepository.getSettings() } throws CancellationException("cancelled")

        service.onNotificationPosted(buildStatusBarNotification())

        // Settle: the gate's retryable diagnostic fires immediately BEFORE the
        // settings read; once observed, the CancellationException rethrow has
        // already propagated out of the launch child (contained by the
        // SupervisorJob, never converted into a terminal diagnostic).
        coVerify(timeout = 5_000) {
            diagnosticEmitter.emit(match { event ->
                event.stage == "capture_gate" &&
                    event.reasonCode == DiagnosticReasonCode.UNKNOWN_ERROR
            })
        }
        Thread.sleep(250)

        coVerify(exactly = 0) {
            intakeCoordinator.captureForRetry(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        // Scope the assertion to the deferred-policy reason code only: the
        // RECEIVED and gate-retryable events legitimately fire before the CE.
        assertFalse(
            "cancellation must not be converted into a DEFERRED_STORAGE_POLICY_UNAVAILABLE diagnostic",
            emittedEvents.any { it.reasonCode == DiagnosticReasonCode.DEFERRED_STORAGE_POLICY_UNAVAILABLE }
        )
    }
}
