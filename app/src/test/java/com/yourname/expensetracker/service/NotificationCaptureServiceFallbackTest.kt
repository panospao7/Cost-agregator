package com.yourname.expensetracker.service

import android.app.Notification
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.BlockedPackageDao
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDecision
import com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDeduper
import com.yourname.expensetracker.domain.notification.capture.NotificationIntakeCaptureResult
import com.yourname.expensetracker.domain.notification.capture.NotificationIntakeCoordinator
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NotificationCaptureServiceFallbackTest {
    private val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>()
    private val captureGate = mockk<com.yourname.expensetracker.domain.notification.capture.NotificationCaptureGate>()
    private val privacyGate = mockk<PrivacyGate>()
    private val privacySettingsRepository = mockk<PrivacySettingsRepository>()
    private val blockedPackageDao = mockk<BlockedPackageDao>(relaxed = true)
    private val diagnosticEmitter = mockk<com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter>(relaxed = true)
    private val intakeCoordinator = mockk<NotificationIntakeCoordinator>()
    private val deduper = mockk<NotificationCaptureDeduper>()
    private val timeProvider = mockk<TimeProvider>()
    private lateinit var service: NotificationCaptureService

    @Before
    fun setUp() {
        service = Robolectric.buildService(NotificationCaptureService::class.java).get()
        ReflectionHelpers.setField(service, "restoreMaintenanceMode", restoreMaintenanceMode)
        ReflectionHelpers.setField(service, "captureGate", captureGate)
        ReflectionHelpers.setField(service, "privacyGate", privacyGate)
        ReflectionHelpers.setField(service, "privacySettingsRepository", privacySettingsRepository)
        ReflectionHelpers.setField(service, "blockedPackageDao", blockedPackageDao)
        ReflectionHelpers.setField(service, "notificationDiagnosticEmitter", diagnosticEmitter)
        ReflectionHelpers.setField(service, "intakeCoordinator", intakeCoordinator)
        ReflectionHelpers.setField(service, "deduper", deduper)
        ReflectionHelpers.setField(service, "timeProvider", timeProvider)
        every { restoreMaintenanceMode.isWritesAllowed() } returns true
        coEvery { captureGate.decide(any(), any()) } returns NotificationCaptureDecision.Allowed
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns PrivacyDecision.Allowed
        coEvery { privacySettingsRepository.getSettings() } returns PrivacySettings(rawNotificationStorageMode = RawStorageMode.STORE_RAW)
        every { deduper.tryStart(any(), any()) } returns false
        every { timeProvider.now() } returns 1_700_000_000_000L
        coEvery { intakeCoordinator.capture(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            NotificationIntakeCaptureResult.Enqueued(1L, "test-correlation-id")
    }

    private fun buildStatusBarNotification(extrasConfig: Bundle.() -> Unit = {}): StatusBarNotification {
        val notification = Notification()
        notification.extras.apply(extrasConfig)
        return buildStatusBarNotification(notification)
    }

    private fun buildStatusBarNotification(notification: Notification): StatusBarNotification {
        return StatusBarNotification("com.revolut.revolut", "com.revolut.revolut", 1, "test-tag", 10042, 10042, 0, notification, Process.myUserHandle(), 1_700_000_000_000L)
    }

    private fun assertCapturedCombinedBody(expected: String) {
        val combinedBody = slot<String>()
        coVerify(timeout = 5_000) { intakeCoordinator.capture(any(), any(), any(), any(), any(), any(), capture(combinedBody), any(), any(), any(), any(), any(), any()) }
        assertEquals(expected, combinedBody.captured)
    }

    private fun assertFilterRejectedBeforeCoordinator() {
        coVerify(timeout = 5_000) {
            diagnosticEmitter.emit(match { event ->
                event.reasonCode == DiagnosticReasonCode.FILTER_REJECTED && event.isTerminal
            })
        }
        coVerify(exactly = 0) { intakeCoordinator.capture(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `payment only in EXTRA_TEXT_LINES reaches live filter and coordinator`() {
        service.onNotificationPosted(buildStatusBarNotification {
            putCharSequence(Notification.EXTRA_TITLE, "Bank")
            putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("Paid EUR 12.00 at Cafe"))
        })
        assertCapturedCombinedBody("Bank Paid EUR 12.00 at Cafe")
    }

    @Test
    fun `payment only in EXTRA_INFO_TEXT with null bigText reaches coordinator`() {
        service.onNotificationPosted(buildStatusBarNotification {
            putCharSequence(Notification.EXTRA_TITLE, "Bank")
            putCharSequence(Notification.EXTRA_INFO_TEXT, "Paid EUR 12.00 at Cafe")
        })
        assertCapturedCombinedBody("Bank Paid EUR 12.00 at Cafe")
    }

    @Test
    fun `payment only in EXTRA_SUMMARY_TEXT with blank bigText reaches coordinator`() {
        service.onNotificationPosted(buildStatusBarNotification {
            putCharSequence(Notification.EXTRA_TITLE, "Bank")
            putCharSequence(Notification.EXTRA_BIG_TEXT, "   ")
            putCharSequence(Notification.EXTRA_SUMMARY_TEXT, "Paid EUR 12.00 at Cafe")
        })
        assertCapturedCombinedBody("Bank     Paid EUR 12.00 at Cafe")
    }

    @Test
    fun `payment only in a real MessagingStyle message reaches coordinator`() {
        val notification = Notification.Builder(service)
            .setContentTitle("Bank")
            .setStyle(
                Notification.MessagingStyle("Bank")
                    .addMessage("Paid EUR 12.00 at Cafe", 1_700_000_000_000L, "Bank")
            )
            .build()
        service.onNotificationPosted(buildStatusBarNotification(notification))
        assertCapturedCombinedBody("Bank Paid EUR 12.00 at Cafe")
    }

    @Test
    fun `top level text and bigText capture with repeated fields deduplicated`() {
        service.onNotificationPosted(buildStatusBarNotification {
            putCharSequence(Notification.EXTRA_TITLE, "Bank")
            putCharSequence(Notification.EXTRA_TEXT, "Paid EUR 12.00 at Cafe")
            putCharSequence(Notification.EXTRA_BIG_TEXT, "Paid EUR 12.00 at Cafe")
            putCharSequence(Notification.EXTRA_INFO_TEXT, "Paid EUR 12.00 at Cafe")
        })
        assertCapturedCombinedBody("Bank Paid EUR 12.00 at Cafe")
    }

    @Test
    fun `no amount anywhere rejects before coordinator`() {
        service.onNotificationPosted(buildStatusBarNotification {
            putCharSequence(Notification.EXTRA_TITLE, "Bank")
            putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("Paid at Cafe"))
        })
        assertFilterRejectedBeforeCoordinator()
    }

    @Test
    fun `extended security text remains rejected for finance package`() {
        service.onNotificationPosted(buildStatusBarNotification {
            putCharSequence(Notification.EXTRA_TITLE, "Bank")
            putCharSequence(Notification.EXTRA_INFO_TEXT, "Security code EUR 12.00")
        })
        assertFilterRejectedBeforeCoordinator()
    }

    @Test
    fun `extended promotion text remains rejected for finance package`() {
        service.onNotificationPosted(buildStatusBarNotification {
            putCharSequence(Notification.EXTRA_TITLE, "Bank")
            putCharSequence(Notification.EXTRA_SUMMARY_TEXT, "Cashback offer EUR 12.00")
        })
        assertFilterRejectedBeforeCoordinator()
    }
}
