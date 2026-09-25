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
import com.yourname.expensetracker.domain.notification.capture.NotificationTextParts
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
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Deferred capture authorizes consent, the fresh toggle and the package before
 * real extraction. The test scheduler observes completion, not an earlier event.
 * Cancellation requires terminal accounting AND a cancelled capture job.
 * All four storage modes preserve the authorized coordinator handoff contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
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
    private lateinit var serviceJob: Job
    private lateinit var captureJob: Job
    private var completionCause: Throwable? = null
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        // Attach only — skip onCreate so Hilt cannot overwrite injected mocks.
        service = spyk(Robolectric.buildService(NotificationCaptureService::class.java).get(), recordPrivateCalls = true)
        serviceJob = ReflectionHelpers.getField(service, "serviceJob")
        ReflectionHelpers.setField(service, "serviceScope", CoroutineScope(serviceJob + StandardTestDispatcher(scheduler)))
        mockkObject(NotificationTextParts.Companion)
        every { NotificationTextParts.extract(any()) } answers { callOriginal() }
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
        coEvery { captureGate.decide(any(), any()) } coAnswers {
            captureJob = currentCoroutineContext().job
            captureJob.invokeOnCompletion { completionCause = it }
            NotificationCaptureDecision.TemporarilyUnavailable(
                reason = NotificationCaptureBlockReason.GATE_NOT_READY,
                retryable = true
            )
        }
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns PrivacyDecision.Allowed
        coEvery { privacySettingsRepository.getSettings() } returns PrivacySettings(notificationCaptureEnabled = true)
        coEvery { blockedPackageDao.isBlocked(any()) } returns false
        coEvery { diagnosticEmitter.emit(capture(emittedEvents)) } returns Unit
        coEvery {
            diagnosticEmitter.emitOrdered(capture(emittedEvents), capture(emittedEvents))
        } returns Unit
        coEvery {
            intakeCoordinator.captureForRetry(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns NotificationIntakeCaptureResult.Enqueued(1L, "test-correlation-id")
    }

    @After
    fun tearDown() {
        serviceJob.cancel()
        scheduler.advanceUntilIdle()
        unmockkObject(NotificationTextParts.Companion)
    }

    private fun postNotification(notification: StatusBarNotification) {
        service.onNotificationPosted(notification)
        scheduler.advanceUntilIdle()
        assertTrue("capture must finish before assertions", captureJob.isCompleted)
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
        coVerify(exactly = 1) {
            diagnosticEmitter.emit(match { event ->
                event.reasonCode == reasonCode && event.isTerminal
            })
        }
        val event = emittedEvents.single { it.isTerminal }
        assertEquals(reasonCode, event.reasonCode)
        assertEquals("capture_gate", event.stage)
        if (reasonCode == DiagnosticReasonCode.CAPTURE_CANCELLED) {
            assertEquals(EventOutcome.CANCELLED, event.outcome)
            assertTrue("cancellation must propagate out of the capture coroutine", captureJob.isCancelled)
            assertTrue(completionCause is CancellationException)
        } else {
            assertEquals(EventOutcome.DROPPED, event.outcome)
            assertNull(completionCause)
        }
        return event
    }

    private fun assertNoDeferredHandoff() {
        assertTrue(captureJob.isCompleted)
        verify(exactly = 0) { NotificationTextParts.extract(any()) }
        verify(exactly = 0) { service["resolveAppName"](any<String>()) }
        coVerify(exactly = 0) {
            intakeCoordinator.capture(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            intakeCoordinator.captureForRetry(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `initial unavailable gate followed by Denied stops before later checks`() {
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns
            PrivacyDecision.Denied("denied")

        postNotification(buildStatusBarNotification())

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

        postNotification(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.PRIVACY_DENIED)
        coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        assertNoDeferredHandoff()
    }

    @Test
    fun `initial unavailable gate followed by NotApplicable stops before later checks`() {
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns
            PrivacyDecision.NotApplicable

        postNotification(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.PRIVACY_DENIED)
        coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        assertNoDeferredHandoff()
    }

    @Test
    fun `Allowed followed by disabled toggle stops before package query and extraction`() {
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(notificationCaptureEnabled = false, rawNotificationStorageMode = RawStorageMode.STORE_RAW)

        postNotification(buildStatusBarNotification {
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

        postNotification(buildStatusBarNotification {
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
            PrivacySettings(notificationCaptureEnabled = true, rawNotificationStorageMode = RawStorageMode.STORE_REDACTED)
        val notification = buildStatusBarNotification {
            putCharSequence(Notification.EXTRA_TITLE, "Bank")
            putCharSequence(Notification.EXTRA_TEXT, "Paid EUR 12.00")
            putCharSequence(Notification.EXTRA_SUB_TEXT, "Card")
        }

        postNotification(notification)

        coVerify(exactly = 1) {
            intakeCoordinator.captureForRetry(
                packageName = "com.bank.app",
                notificationKey = notification.key,
                postTime = notification.postTime,
                correlationId = any(),
                title = "Bank",
                text = "Paid EUR 12.00",
                combinedBody = "Bank Paid EUR 12.00 Card",
                subText = "Card",
                storage = capture(storageSlot)
            )
        }
        coVerifyOrder {
            captureGate.decide("com.bank.app", false)
            privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)
            privacySettingsRepository.getSettings()
            blockedPackageDao.isBlocked("com.bank.app")
            NotificationTextParts.extract(notification.notification.extras)
            intakeCoordinator.captureForRetry(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) }
        coVerify(exactly = 1) { privacySettingsRepository.getSettings() }
        coVerify(exactly = 1) { blockedPackageDao.isBlocked("com.bank.app") }
        verify(exactly = 1) { NotificationTextParts.extract(notification.notification.extras) }
        verify(exactly = 1) { service["resolveAppName"]("com.bank.app") }
        assertEquals(RawStorageMode.STORE_REDACTED, storageSlot.captured.storageMode)
        assertEquals("""{"redacted":true}""", storageSlot.captured.extrasJson)
        assertNull(completionCause)
    }

    @Test
    fun `privacy capability exception fails closed without handoff`() {
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } throws
            RuntimeException("gate unavailable")

        postNotification(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.PRIVACY_DENIED)
        coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        assertNoDeferredHandoff()
    }

    @Test
    fun `settings exception fails closed without handoff`() {
        coEvery { privacySettingsRepository.getSettings() } throws RuntimeException("settings unavailable")

        postNotification(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.DEFERRED_STORAGE_POLICY_UNAVAILABLE)
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        assertNoDeferredHandoff()
    }

    @Test
    fun `blocked package exception fails closed without handoff`() {
        coEvery { blockedPackageDao.isBlocked(any()) } throws RuntimeException("blocked package lookup unavailable")

        postNotification(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.BLOCKED_PACKAGE)
        assertNoDeferredHandoff()
    }

    @Test
    fun `privacy capability local timeout fails closed without handoff`() {
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } coAnswers {
            delay(1_000L)
            PrivacyDecision.Allowed
        }

        postNotification(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.PRIVACY_DENIED)
        coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        assertEquals(300L, scheduler.currentTime)
        assertNoDeferredHandoff()
    }

    @Test
    fun `settings local timeout fails closed without handoff`() {
        coEvery { privacySettingsRepository.getSettings() } coAnswers {
            delay(1_000L)
            PrivacySettings()
        }

        postNotification(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.DEFERRED_STORAGE_POLICY_UNAVAILABLE)
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        assertEquals(300L, scheduler.currentTime)
        assertNoDeferredHandoff()
    }

    @Test
    fun `blocked package local timeout fails closed without handoff`() {
        coEvery { blockedPackageDao.isBlocked(any()) } coAnswers {
            delay(1_000L)
            false
        }

        postNotification(buildStatusBarNotification())

        awaitTerminalDiagnostic(DiagnosticReasonCode.BLOCKED_PACKAGE)
        assertEquals(300L, scheduler.currentTime)
        assertNoDeferredHandoff()
    }

    @Test
    fun `privacy capability cancellation is accounted for and rethrown`() {
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } throws
            CancellationException("cancelled")

        postNotification(buildStatusBarNotification())

        val event = awaitTerminalDiagnostic(DiagnosticReasonCode.CAPTURE_CANCELLED)
        assertEquals(EventOutcome.CANCELLED, event.outcome)
        coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        assertNoDeferredHandoff()
    }

    @Test
    fun `settings cancellation is accounted for and rethrown`() {
        coEvery { privacySettingsRepository.getSettings() } throws CancellationException("cancelled")

        postNotification(buildStatusBarNotification())

        val event = awaitTerminalDiagnostic(DiagnosticReasonCode.CAPTURE_CANCELLED)
        assertEquals(EventOutcome.CANCELLED, event.outcome)
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        assertNoDeferredHandoff()
    }

    @Test
    fun `blocked package cancellation is accounted for and rethrown`() {
        coEvery { blockedPackageDao.isBlocked(any()) } throws CancellationException("cancelled")

        postNotification(buildStatusBarNotification())

        val event = awaitTerminalDiagnostic(DiagnosticReasonCode.CAPTURE_CANCELLED)
        assertEquals(EventOutcome.CANCELLED, event.outcome)
        assertNoDeferredHandoff()
    }

    @Test
    fun `settings read failure emits DEFERRED_STORAGE_POLICY_UNAVAILABLE and defers nothing`() {
        coEvery { privacySettingsRepository.getSettings() } throws RuntimeException("datastore down")

        postNotification(buildStatusBarNotification())

        // Settle: the fail-closed diagnostic is the LAST side effect of the
        // deferred branch when settings resolution fails (settings = null →
        // the branch returns without any captureForRetry call).
        coVerify(exactly = 1) {
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

        // No extraction, app-name resolution or coordinator calls after rejection.
        assertNoDeferredHandoff()
    }

    @Test
    fun `DO_NOT_STORE defers snapshot with null extrasJson`() {
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(notificationCaptureEnabled = true, rawNotificationStorageMode = RawStorageMode.DO_NOT_STORE)
        coEvery {
            intakeCoordinator.captureForRetry(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns NotificationIntakeCaptureResult.NotStored("test-correlation-id")

        postNotification(buildStatusBarNotification())

        val storageSlot = slot<DeferredCaptureStorageSnapshot>()
        coVerify(exactly = 1) {
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
            PrivacySettings(notificationCaptureEnabled = true, rawNotificationStorageMode = RawStorageMode.STORE_METADATA_ONLY)

        postNotification(buildStatusBarNotification {
            putCharSequence(Notification.EXTRA_TITLE, "Payment received")
            putCharSequence(Notification.EXTRA_TEXT, "You received EUR 10.00")
        })

        val storageSlot = slot<DeferredCaptureStorageSnapshot>()
        coVerify(exactly = 1) {
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
            PrivacySettings(notificationCaptureEnabled = true, rawNotificationStorageMode = RawStorageMode.STORE_RAW)

        postNotification(
            buildStatusBarNotification {
                putCharSequence(Notification.EXTRA_TITLE, "Payment received")
                putCharSequence(Notification.EXTRA_TEXT, "You received EUR 10.00")
                putCharSequence("amount", "4242.99 EUR")
                putCharSequence("merchantName", "Coffee Shop")
            }
        )

        val storageSlot = slot<DeferredCaptureStorageSnapshot>()
        coVerify(exactly = 1) {
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
            PrivacySettings(notificationCaptureEnabled = true, rawNotificationStorageMode = RawStorageMode.STORE_REDACTED)

        postNotification(
            buildStatusBarNotification {
                putCharSequence(Notification.EXTRA_TITLE, "Payment received")
                putCharSequence(Notification.EXTRA_TEXT, "You received EUR 10.00")
            }
        )

        val storageSlot = slot<DeferredCaptureStorageSnapshot>()
        coVerify(exactly = 1) {
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

        postNotification(buildStatusBarNotification())

        val event = awaitTerminalDiagnostic(DiagnosticReasonCode.CAPTURE_CANCELLED)
        assertEquals(EventOutcome.CANCELLED, event.outcome)

        assertNoDeferredHandoff()
        assertFalse("cancellation must not be converted into a storage-policy diagnostic",
            emittedEvents.any { it.reasonCode == DiagnosticReasonCode.DEFERRED_STORAGE_POLICY_UNAVAILABLE })
    }

    @Test
    fun `parent cancellation during capability read is accounted for and propagated`() {
        assertParentCancellationAt("capability")
    }

    @Test
    fun `parent cancellation during settings read is accounted for and propagated`() {
        assertParentCancellationAt("settings")
    }

    @Test
    fun `parent cancellation during package read is accounted for and propagated`() {
        assertParentCancellationAt("package")
    }

    private fun assertParentCancellationAt(read: String) {
        val entered = CompletableDeferred<Unit>()
        when (read) {
            "capability" -> coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } coAnswers {
                entered.complete(Unit)
                awaitCancellation()
            }
            "settings" -> coEvery { privacySettingsRepository.getSettings() } coAnswers {
                entered.complete(Unit)
                awaitCancellation()
            }
            "package" -> coEvery { blockedPackageDao.isBlocked(any()) } coAnswers {
                entered.complete(Unit)
                awaitCancellation()
            }
            else -> error("Unknown authorization read")
        }
        service.onNotificationPosted(buildStatusBarNotification())
        scheduler.runCurrent()
        assertTrue("the selected read must suspend before cancellation", entered.isCompleted)
        assertFalse(captureJob.isCompleted)
        assertTrue(emittedEvents.none { it.isTerminal })

        serviceJob.cancel(CancellationException("parent cancelled"))
        scheduler.advanceUntilIdle()

        awaitTerminalDiagnostic(DiagnosticReasonCode.CAPTURE_CANCELLED)
        assertNoDeferredHandoff()
        if (read == "capability") {
            coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        }
        if (read != "package") {
            coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        }
    }
}
