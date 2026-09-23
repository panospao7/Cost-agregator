package com.yourname.expensetracker.service

import android.app.Notification
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.BlockedPackageDao
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
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
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
    private val privacyGate = mockk<PrivacyGate>()
    private val privacySettingsRepository = mockk<PrivacySettingsRepository>()
    private val blockedPackageDao = mockk<BlockedPackageDao>()
    private val intakeCoordinator = mockk<NotificationIntakeCoordinator>()

    private lateinit var service: NotificationCaptureService

    @Before
    fun setUp() {
        // Attach only — skip onCreate so Hilt cannot overwrite injected mocks.
        service = Robolectric.buildService(NotificationCaptureService::class.java).get()
        ReflectionHelpers.setField(service, "restoreMaintenanceMode", restoreMaintenanceMode)
        ReflectionHelpers.setField(service, "captureGate", captureGate)
        ReflectionHelpers.setField(service, "notificationDiagnosticEmitter", diagnosticEmitter)
        ReflectionHelpers.setField(service, "privacyGate", privacyGate)
        ReflectionHelpers.setField(service, "privacySettingsRepository", privacySettingsRepository)
        ReflectionHelpers.setField(service, "blockedPackageDao", blockedPackageDao)
        ReflectionHelpers.setField(service, "intakeCoordinator", intakeCoordinator)

        // Fast pre-checks pass; the gate defers every notification into the
        // TemporarilyUnavailable branch under test.
        every { restoreMaintenanceMode.isWritesAllowed() } returns true
        coEvery { captureGate.decide(any(), any()) } returns
            NotificationCaptureDecision.TemporarilyUnavailable(
                reason = NotificationCaptureBlockReason.GATE_NOT_READY,
                retryable = true
            )
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns PrivacyDecision.Allowed
        coEvery { privacySettingsRepository.getSettings() } returns PrivacySettings()
        coEvery { blockedPackageDao.isBlocked(any()) } returns false
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

    private fun awaitTerminalDiagnostic(reasonCode: DiagnosticReasonCode): DiagnosticEvent {
        coVerify(timeout = 5_000) {
            diagnosticEmitter.emit(match { event ->
                event.reasonCode == reasonCode && event.isTerminal
            })
        }
        return emittedEvents.last { it.reasonCode == reasonCode && it.isTerminal }
    }

    private fun assertNoDeferredHandoff() {
        coVerify(exactly = 0) {
            intakeCoordinator.captureForRetry(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `initial unavailable gate followed by Denied stops before later checks`() {
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns
            PrivacyDecision.Denied("denied")

        service.onNotificationPosted(buildStatusBarNotification())

        val event = awaitTerminalDiagnostic(DiagnosticReasonCode.PRIVACY_DENIED)
        assertEquals(EventOutcome.DROPPED, event.outcome)
        coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        assertNoDeferredHandoff()
    }

    @Test
    fun `initial unavailable gate followed by FailClosed stops before later checks`() {
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns
            PrivacyDecision.FailClosed("failed closed")

        service.onNotificationPosted(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.PRIVACY_DENIED)
        coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        assertNoDeferredHandoff()
    }

    @Test
    fun `initial unavailable gate followed by NotApplicable stops before later checks`() {
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns
            PrivacyDecision.NotApplicable

        service.onNotificationPosted(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.PRIVACY_DENIED)
        coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        assertNoDeferredHandoff()
    }

    @Test
    fun `Allowed followed by disabled toggle stops before package query and extraction`() {
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(notificationCaptureEnabled = false, rawNotificationStorageMode = RawStorageMode.STORE_RAW)

        service.onNotificationPosted(buildStatusBarNotification {
            putCharSequence(Notification.EXTRA_TEXT, "Paid EUR 12.00")
        })

        awaitTerminalDiagnostic(DiagnosticReasonCode.PRIVACY_DENIED)
        coVerify(exactly = 1) { privacySettingsRepository.getSettings() }
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        assertNoDeferredHandoff()
    }

    @Test
    fun `Allowed enabled and blocked package stops before extraction and handoff`() {
        coEvery { blockedPackageDao.isBlocked(any()) } returns true

        service.onNotificationPosted(buildStatusBarNotification {
            putCharSequence(Notification.EXTRA_TEXT, "Paid EUR 12.00")
        })

        val event = awaitTerminalDiagnostic(DiagnosticReasonCode.BLOCKED_PACKAGE)
        assertEquals(EventOutcome.DROPPED, event.outcome)
        coVerify(exactly = 1) { privacySettingsRepository.getSettings() }
        coVerify(exactly = 1) { blockedPackageDao.isBlocked("com.bank.app") }
        assertNoDeferredHandoff()
    }

    @Test
    fun `successful deferred authorization is ordered and uses fresh blocked DAO lookup`() {
        val storageSlot = slot<DeferredCaptureStorageSnapshot>()
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(rawNotificationStorageMode = RawStorageMode.STORE_REDACTED)

        service.onNotificationPosted(buildStatusBarNotification {
            putCharSequence(Notification.EXTRA_TITLE, "Bank")
            putCharSequence(Notification.EXTRA_TEXT, "Paid EUR 12.00")
        })

        coVerify(timeout = 5_000) {
            intakeCoordinator.captureForRetry(
                any(), any(), any(), any(), any(), any(), any(), any(), capture(storageSlot)
            )
        }
        coVerifyOrder {
            privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)
            privacySettingsRepository.getSettings()
            blockedPackageDao.isBlocked("com.bank.app")
            intakeCoordinator.captureForRetry(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) { privacySettingsRepository.getSettings() }
        coVerify(exactly = 1) { blockedPackageDao.isBlocked("com.bank.app") }
        assertEquals(RawStorageMode.STORE_REDACTED, storageSlot.captured.storageMode)
    }

    @Test
    fun `privacy capability exception fails closed without handoff`() {
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } throws
            RuntimeException("gate unavailable")

        service.onNotificationPosted(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.PRIVACY_DENIED)
        coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        assertNoDeferredHandoff()
    }

    @Test
    fun `settings exception fails closed without handoff`() {
        coEvery { privacySettingsRepository.getSettings() } throws RuntimeException("settings unavailable")

        service.onNotificationPosted(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.DEFERRED_STORAGE_POLICY_UNAVAILABLE)
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        assertNoDeferredHandoff()
    }

    @Test
    fun `blocked package exception fails closed without handoff`() {
        coEvery { blockedPackageDao.isBlocked(any()) } throws RuntimeException("blocked package lookup unavailable")

        service.onNotificationPosted(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.BLOCKED_PACKAGE)
        assertNoDeferredHandoff()
    }

    @Test
    fun `privacy capability local timeout fails closed without handoff`() {
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } coAnswers {
            delay(1_000L)
            PrivacyDecision.Allowed
        }

        service.onNotificationPosted(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.PRIVACY_DENIED)
        coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        assertNoDeferredHandoff()
    }

    @Test
    fun `settings local timeout fails closed without handoff`() {
        coEvery { privacySettingsRepository.getSettings() } coAnswers {
            delay(1_000L)
            PrivacySettings()
        }

        service.onNotificationPosted(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.DEFERRED_STORAGE_POLICY_UNAVAILABLE)
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        assertNoDeferredHandoff()
    }

    @Test
    fun `blocked package local timeout fails closed without handoff`() {
        coEvery { blockedPackageDao.isBlocked(any()) } coAnswers {
            delay(1_000L)
            false
        }

        service.onNotificationPosted(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.BLOCKED_PACKAGE)
        assertNoDeferredHandoff()
    }

    @Test
    fun `privacy capability cancellation is accounted for and rethrown`() {
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } throws
            CancellationException("cancelled")

        service.onNotificationPosted(buildStatusBarNotification())

        val event = awaitTerminalDiagnostic(DiagnosticReasonCode.CAPTURE_CANCELLED)
        assertEquals(EventOutcome.CANCELLED, event.outcome)
        coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        assertNoDeferredHandoff()
    }

    @Test
    fun `settings cancellation is accounted for and rethrown`() {
        coEvery { privacySettingsRepository.getSettings() } throws CancellationException("cancelled")

        service.onNotificationPosted(buildStatusBarNotification())

        val event = awaitTerminalDiagnostic(DiagnosticReasonCode.CAPTURE_CANCELLED)
        assertEquals(EventOutcome.CANCELLED, event.outcome)
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        assertNoDeferredHandoff()
    }

    @Test
    fun `blocked package cancellation is accounted for and rethrown`() {
        coEvery { blockedPackageDao.isBlocked(any()) } throws CancellationException("cancelled")

        service.onNotificationPosted(buildStatusBarNotification())

        val event = awaitTerminalDiagnostic(DiagnosticReasonCode.CAPTURE_CANCELLED)
        assertEquals(EventOutcome.CANCELLED, event.outcome)
        assertNoDeferredHandoff()
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
        coEvery {
            intakeCoordinator.captureForRetry(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns NotificationIntakeCaptureResult.NotStored("test-correlation-id")

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
    fun `STORE_METADATA_ONLY defers snapshot with null extrasJson`() {
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(rawNotificationStorageMode = RawStorageMode.STORE_METADATA_ONLY)

        service.onNotificationPosted(buildStatusBarNotification {
            putCharSequence(Notification.EXTRA_TITLE, "Payment received")
            putCharSequence(Notification.EXTRA_TEXT, "You received EUR 10.00")
        })

        val storageSlot = slot<DeferredCaptureStorageSnapshot>()
        coVerify(timeout = 5_000) {
            intakeCoordinator.captureForRetry(
                any(), any(), any(), any(), any(), any(), any(), any(), capture(storageSlot)
            )
        }
        assertEquals(RawStorageMode.STORE_METADATA_ONLY, storageSlot.captured.storageMode)
        assertNull("STORE_METADATA_ONLY must not carry extras JSON", storageSlot.captured.extrasJson)
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
    fun `settings read CancellationException is accounted for and does not defer`() {
        coEvery { privacySettingsRepository.getSettings() } throws CancellationException("cancelled")

        service.onNotificationPosted(buildStatusBarNotification())

        val event = awaitTerminalDiagnostic(DiagnosticReasonCode.CAPTURE_CANCELLED)
        assertEquals(EventOutcome.CANCELLED, event.outcome)

        assertNoDeferredHandoff()
        assertFalse("cancellation must not be converted into a storage-policy diagnostic",
            emittedEvents.any { it.reasonCode == DiagnosticReasonCode.DEFERRED_STORAGE_POLICY_UNAVAILABLE })
    }
}
