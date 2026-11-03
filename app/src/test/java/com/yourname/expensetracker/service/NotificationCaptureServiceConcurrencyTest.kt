package com.yourname.expensetracker.service

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.BlockedPackageDao
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.domain.notification.capture.NotificationCaptureBlockReason
import com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDecision
import com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDeduper
import com.yourname.expensetracker.domain.notification.capture.NotificationCaptureGate
import com.yourname.expensetracker.domain.notification.capture.NotificationIntakeCaptureResult
import com.yourname.expensetracker.domain.notification.capture.NotificationIntakeCoordinator
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.util.FakeMonotonicTimeProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * P-01-3 (S3): service-level proof that [NotificationCaptureService] bounds concurrent
 * capture pipelines with [NotificationCaptureConcurrencyLimiter] without dropping any
 * notification.
 *
 * Drives the REAL service (same harness as `NotificationCaptureServiceBlockedDecisionTest`).
 * The capture gate is the first step under the permit, so a gate that suspends on a
 * [CompletableDeferred] pins a pipeline inside the permit deterministically; the number
 * of gate entries is the number of pipelines holding a permit. All data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCaptureServiceConcurrencyTest {

    private companion object {
        const val PKG = "com.revolut.revolut"
        const val OP_PKG = "com.synthetic.op.package"
        const val TITLE_TEXT = "Bank"
        const val PAYMENT_TEXT = "Paid EUR 12.00 at Cafe"
        const val AUTH_TEXT = "Security code 123456 for login"
        const val NOTIFICATION_TAG = "test-tag"
        val PERMITS = NotificationCaptureConcurrencyLimiter.DEFAULT_PERMITS
    }

    private val emittedEvents = mutableListOf<DiagnosticEvent>()
    private val gateEntryJobs = mutableListOf<Job>()
    private val gateWaiters = mutableListOf<CompletableDeferred<Unit>>()
    private val decisions = ArrayDeque<NotificationCaptureDecision>()
    private var holdGate = false
    private var duplicatesToReport = 0

    private val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>()
    private val captureGate = mockk<NotificationCaptureGate>()
    private val privacyGate = mockk<PrivacyGate>()
    private val privacySettingsRepository = mockk<PrivacySettingsRepository>()
    private val blockedPackageDao = mockk<BlockedPackageDao>()
    private val diagnosticEmitter = mockk<NotificationDiagnosticEmitter>(relaxed = true)
    private val intakeCoordinator = mockk<NotificationIntakeCoordinator>()
    private val notificationRepository = mockk<NotificationRepository>()
    private val deduper = mockk<NotificationCaptureDeduper>()
    private val timeProvider = mockk<TimeProvider>()

    private lateinit var service: NotificationCaptureService
    private lateinit var serviceJob: Job
    private val scheduler = TestCoroutineScheduler()
    private var nextNotificationId = 1

    @Before
    fun setUp() {
        service = Robolectric.buildService(NotificationCaptureService::class.java).get()
        serviceJob = ReflectionHelpers.getField(service, "serviceJob")
        ReflectionHelpers.setField(service, "serviceScope", CoroutineScope(serviceJob + StandardTestDispatcher(scheduler)))
        ReflectionHelpers.setField(service, "restoreMaintenanceMode", restoreMaintenanceMode)
        ReflectionHelpers.setField(service, "captureGate", captureGate)
        ReflectionHelpers.setField(service, "privacyGate", privacyGate)
        ReflectionHelpers.setField(service, "privacySettingsRepository", privacySettingsRepository)
        ReflectionHelpers.setField(service, "blockedPackageDao", blockedPackageDao)
        ReflectionHelpers.setField(service, "notificationDiagnosticEmitter", diagnosticEmitter)
        ReflectionHelpers.setField(service, "intakeCoordinator", intakeCoordinator)
        ReflectionHelpers.setField(service, "repository", notificationRepository)
        ReflectionHelpers.setField(service, "deduper", deduper)
        ReflectionHelpers.setField(service, "timeProvider", timeProvider)

        every { restoreMaintenanceMode.isWritesAllowed() } returns true
        coEvery { captureGate.decide(any(), any()) } coAnswers {
            gateEntryJobs += currentCoroutineContext().job
            if (holdGate) {
                val waiter = CompletableDeferred<Unit>()
                gateWaiters += waiter
                waiter.await()
            }
            decisions.removeFirstOrNull() ?: NotificationCaptureDecision.Allowed
        }
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns PrivacyDecision.Allowed
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(rawNotificationStorageMode = RawStorageMode.STORE_RAW)
        every { deduper.markArrival() } returns 0L
        every { deduper.tryStart(any(), any(), any()) } answers {
            if (duplicatesToReport > 0) {
                duplicatesToReport--
                true
            } else {
                false
            }
        }
        every { deduper.remove(any()) } returns Unit
        every { timeProvider.now() } returns 1_700_000_000_000L
        coEvery { diagnosticEmitter.emit(capture(emittedEvents)) } returns Unit
        coEvery {
            diagnosticEmitter.emitOrdered(capture(emittedEvents), capture(emittedEvents))
        } returns Unit
        coEvery {
            intakeCoordinator.capture(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns NotificationIntakeCaptureResult.Enqueued(1L, "test-correlation-id")
    }

    @After
    fun tearDown() {
        serviceJob.cancel()
        scheduler.advanceUntilIdle()
    }

    // -- helpers ---------------------------------------------------------------

    private fun notification(title: String = TITLE_TEXT, body: String = PAYMENT_TEXT): StatusBarNotification {
        val notification = Notification()
        notification.extras.apply {
            putCharSequence(Notification.EXTRA_TITLE, title)
            putCharSequence(Notification.EXTRA_TEXT, body)
        }
        return StatusBarNotification(
            PKG, OP_PKG, nextNotificationId++, NOTIFICATION_TAG, 10042, 10042, 0,
            notification, Process.myUserHandle(), 1_700_000_000_000L
        )
    }

    private fun postWithoutRunning(count: Int) {
        repeat(count) { service.onNotificationPosted(notification()) }
    }

    /** Releases every pending gate, repeatedly, until queued pipelines have all passed it. */
    private fun releaseAllGatesUntilDrained() {
        var rounds = 0
        while (rounds++ < 50) {
            val pending = gateWaiters.filter { !it.isCompleted }
            if (pending.isEmpty()) break
            pending.forEach { it.complete(Unit) }
            scheduler.advanceUntilIdle()
        }
        scheduler.advanceUntilIdle()
        assertTrue("gate drain must converge", gateWaiters.all { it.isCompleted })
    }

    private fun availablePermits(): Int =
        ReflectionHelpers.getField<NotificationCaptureConcurrencyLimiter>(service, "captureLimiter").availablePermits

    private fun countOutcome(outcome: EventOutcome) = emittedEvents.count { it.outcome == outcome }

    private fun captureCalls(expected: Int) {
        coVerify(exactly = expected) {
            intakeCoordinator.capture(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // -- burst above the limit ---------------------------------------------------

    @Test
    fun `burst above the permit limit queues the excess then captures every notification`() {
        holdGate = true
        val burst = PERMITS + 3

        postWithoutRunning(burst)
        scheduler.advanceUntilIdle()

        // Only PERMITS pipelines passed the permit; the rest are queued, not dropped.
        assertEquals("only the permitted pipelines may enter the gate", PERMITS, gateEntryJobs.size)
        assertEquals(0, availablePermits())
        captureCalls(0)
        // RECEIVED is emitted for every notification before any waits for a permit.
        assertEquals(
            "RECEIVED must be emitted for queued notifications too",
            burst, countOutcome(EventOutcome.RECEIVED)
        )

        releaseAllGatesUntilDrained()

        assertEquals("every queued notification must eventually pass the gate", burst, gateEntryJobs.size)
        captureCalls(burst)
        assertTrue("no capture coroutine may be cancelled", gateEntryJobs.none { it.isCancelled })
        assertTrue(gateEntryJobs.all { it.isCompleted })
        assertEquals("all permits must be returned", PERMITS, availablePermits())
        assertEquals("nothing may be reported as cancelled", 0, countOutcome(EventOutcome.CANCELLED))
    }

    // -- every early-exit path returns the permit -----------------------------------

    @Test
    fun `gate Blocked path releases the permit so later notifications still run`() {
        repeat(PERMITS) {
            decisions.addLast(
                NotificationCaptureDecision.Blocked(
                    reason = NotificationCaptureBlockReason.BLOCKED_PACKAGE,
                    diagnosticReasonCode = DiagnosticReasonCode.BLOCKED_PACKAGE
                )
            )
        }

        postWithoutRunning(PERMITS + 1)
        scheduler.advanceUntilIdle()

        assertEquals(
            "every Blocked pipeline must have emitted its terminal drop",
            PERMITS,
            emittedEvents.count { it.stage == "capture_gate" && it.outcome == EventOutcome.DROPPED }
        )
        captureCalls(1)
        assertTrue(gateEntryJobs.all { it.isCompleted })
        assertEquals(PERMITS, availablePermits())
    }

    @Test
    fun `duplicate path releases the permit so later notifications still run`() {
        duplicatesToReport = PERMITS

        postWithoutRunning(PERMITS + 1)
        scheduler.advanceUntilIdle()

        assertEquals(
            "every duplicate pipeline must have emitted its terminal DUPLICATE",
            PERMITS,
            emittedEvents.count { it.stage == "dedupe" && it.reasonCode == DiagnosticReasonCode.DUPLICATE }
        )
        captureCalls(1)
        assertTrue(gateEntryJobs.all { it.isCompleted })
        assertEquals(PERMITS, availablePermits())
    }

    @Test
    fun `filter-drop path releases the permit so later notifications still run`() {
        repeat(PERMITS) { service.onNotificationPosted(notification(body = AUTH_TEXT)) }
        service.onNotificationPosted(notification())
        scheduler.advanceUntilIdle()

        assertEquals(
            "every filtered pipeline must have emitted its terminal FILTER_REJECTED",
            PERMITS,
            emittedEvents.count { it.stage == "filter" && it.reasonCode == DiagnosticReasonCode.FILTER_REJECTED }
        )
        captureCalls(1)
        assertTrue(gateEntryJobs.all { it.isCompleted })
        assertEquals(PERMITS, availablePermits())
    }

    // -- dedupe window measured from arrival (S7) ---------------------------------------

    @Test
    fun `permit wait longer than the dedupe window still suppresses a duplicate`() {
        val fakeClock = FakeMonotonicTimeProvider()
        ReflectionHelpers.setField(service, "deduper", NotificationCaptureDeduper(fakeClock))
        holdGate = true

        // Fill every permit so later notifications queue; each filler is distinct.
        postWithoutRunning(PERMITS)
        scheduler.advanceUntilIdle()
        assertEquals(PERMITS, gateEntryJobs.size)

        // Two same-key posts arrive 1s apart (DEDUP_WINDOW_MS = 5s) and queue for permits.
        service.onNotificationPosted(notificationWithId(9001)) // arrival t = 0
        scheduler.advanceUntilIdle()
        fakeClock.advanceMillis(1_000L)
        service.onNotificationPosted(notificationWithId(9001)) // arrival t = 1s
        scheduler.advanceUntilIdle()
        assertEquals("both duplicates must still be queued for a permit", PERMITS, gateEntryJobs.size)

        // Free one permit: the first duplicate enters the gate and is released -> recorded.
        gateWaiters[0].complete(Unit)
        scheduler.advanceUntilIdle()
        assertEquals(PERMITS + 1, gateEntryJobs.size)
        gateWaiters[PERMITS].complete(Unit)
        scheduler.advanceUntilIdle()
        assertEquals("first arrival is admitted", 0, dedupeDuplicateCount())

        // The second duplicate now holds a permit after a wait far beyond the window.
        assertEquals(PERMITS + 2, gateEntryJobs.size)
        fakeClock.advanceMillis(20_000L)
        gateWaiters[PERMITS + 1].complete(Unit)
        scheduler.advanceUntilIdle()

        assertEquals(
            "arrival gap (1s) is inside the window even though processing gap (21s) is not",
            1, dedupeDuplicateCount()
        )
        releaseAllGatesUntilDrained()
        // PERMITS fillers + the first duplicate; the second duplicate was suppressed.
        captureCalls(PERMITS + 1)
        assertEquals(PERMITS, availablePermits())
    }

    private fun dedupeDuplicateCount() =
        emittedEvents.count { it.stage == "dedupe" && it.reasonCode == DiagnosticReasonCode.DUPLICATE }

    private fun notificationWithId(id: Int): StatusBarNotification {
        val notification = Notification()
        notification.extras.apply {
            putCharSequence(Notification.EXTRA_TITLE, TITLE_TEXT)
            putCharSequence(Notification.EXTRA_TEXT, PAYMENT_TEXT)
        }
        return StatusBarNotification(
            PKG, OP_PKG, id, NOTIFICATION_TAG, 10042, 10042, 0,
            notification, Process.myUserHandle(), 1_700_000_000_000L
        )
    }

    // -- cancellation ----------------------------------------------------------------

    @Test
    fun `capture cancelled while queued emits exactly one CAPTURE_CANCELLED and leaves no dedupe key`() {
        val realDeduper = NotificationCaptureDeduper(FakeMonotonicTimeProvider())
        ReflectionHelpers.setField(service, "deduper", realDeduper)
        holdGate = true

        postWithoutRunning(PERMITS + 1)
        scheduler.advanceUntilIdle()
        assertEquals(PERMITS, gateEntryJobs.size)
        val queued = serviceJob.children.filter { it !in gateEntryJobs }.toList()
        assertEquals("exactly one pipeline must be queued for a permit", 1, queued.size)

        queued.single().cancel()
        scheduler.advanceUntilIdle()

        fun cancelledDiagnostics() = emittedEvents.filter {
            it.outcome == EventOutcome.CANCELLED && it.reasonCode == DiagnosticReasonCode.CAPTURE_CANCELLED
        }
        assertEquals("exactly one CAPTURE_CANCELLED for the queued capture", 1, cancelledDiagnostics().size)
        assertTrue("the cancellation diagnostic must be terminal", cancelledDiagnostics().single().isTerminal)
        assertEquals("a cancelled queued waiter must not have taken a permit", 0, availablePermits())
        assertEquals("the cancelled capture never reached the gate", PERMITS, gateEntryJobs.size)
        assertEquals("nothing recorded before the permit was granted", 0, realDeduper.size)

        releaseAllGatesUntilDrained()

        captureCalls(PERMITS)
        assertEquals("only the holders recorded dedupe keys; the cancelled one left none", PERMITS, realDeduper.size)
        assertEquals("still exactly one CAPTURE_CANCELLED", 1, cancelledDiagnostics().size)
        assertEquals(
            "RECEIVED was emitted for all PERMITS + 1 notifications, including the cancelled one",
            PERMITS + 1, countOutcome(EventOutcome.RECEIVED)
        )
        assertEquals(PERMITS, availablePermits())
    }

    @Test
    fun `cancelling a permit holder lets the queued capture run`() {
        holdGate = true

        postWithoutRunning(PERMITS + 1)
        scheduler.advanceUntilIdle()
        assertEquals(PERMITS, gateEntryJobs.size)

        gateEntryJobs.first().cancel()
        scheduler.advanceUntilIdle()

        assertEquals(
            "the queued capture must acquire the permit freed by the cancelled holder",
            PERMITS + 1, gateEntryJobs.size
        )

        releaseAllGatesUntilDrained()

        captureCalls(PERMITS)
        assertEquals("all permits must be returned after a holder cancellation", PERMITS, availablePermits())
    }
}
