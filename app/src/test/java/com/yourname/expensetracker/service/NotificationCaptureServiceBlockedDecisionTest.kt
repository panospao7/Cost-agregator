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
import com.yourname.expensetracker.domain.notification.capture.NotificationTextParts
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
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * B2-2b / B2-2c: service-level proof that a `Blocked` capture-gate decision is
 * final and that a step-5 privacy denial never poisons the dedupe cache.
 *
 * Drives the REAL [NotificationCaptureService] with a stubbed
 * [NotificationCaptureGate] (PRIV-441-05 / PRIV-441-06 / PRIV-441-07 ordering):
 *  - a `Blocked` decision means the notification extras are never read: the
 *    title/text extras are tripwire [CharSequence]s whose every accessor sets a
 *    flag, and `NotificationTextParts.extract` (the extraction seam, the same
 *    one used by `NotificationCaptureServiceDeferredPolicyTest`) is never
 *    called. `deduper.tryStart` is never called and nothing is handed to the
 *    intake coordinator or the notification repository;
 *  - an `Allowed` decision (control) does perform each of those steps and
 *    touches the tripwires, so the zero-call assertions above are not vacuous;
 *  - when the step-5 privacy re-check returns a non-Allowed decision (Denied,
 *    FailClosed or NotApplicable) after the gate allowed capture, nothing is
 *    handed to intake and the dedupe key recorded by `deduper.tryStart` is
 *    released (`deduper.remove`), so a later allowed repost is captured rather
 *    than swallowed as DUPLICATE. PRIV-441-07 is therefore proven at the
 *    service level here, not only at the gate boundary;
 *  - diagnostics emitted on every Blocked, restore and step-5 path carry none
 *    of the raw notification text (or its fragments), the notification tag or the
 *    raw package names in any String field, the metadata JSON or the exception
 *    cause chain. Production records
 *    the package only via `putHashed`, so there is deliberately NO exception:
 *    even the `capture_gate` metadata must not contain the raw package name.
 *
 * All data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCaptureServiceBlockedDecisionTest {

    private companion object {
        const val PKG = "com.revolut.revolut"
        // Deliberately different from PKG so a leak of either is detectable.
        const val OP_PKG = "com.synthetic.op.package"
        const val TITLE_TEXT = "Bank"
        const val PAYMENT_TEXT = "Paid EUR 12.00 at Cafe"
        const val NOTIFICATION_TAG = "test-tag"
    }

    /** CharSequence whose every accessor records that the extras value was read. */
    private class TripwireText(private val value: String) : CharSequence {
        @Volatile
        var touched: Boolean = false
            private set

        override val length: Int
            get() {
                touched = true
                return value.length
            }

        override fun get(index: Int): Char {
            touched = true
            return value[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            touched = true
            return value.subSequence(startIndex, endIndex)
        }

        override fun toString(): String {
            touched = true
            return value
        }
    }

    private val emittedEvents = mutableListOf<DiagnosticEvent>()
    private val captureJobs = mutableListOf<Job>()
    private val startedKeys = mutableListOf<String>()
    private val removedKeys = mutableListOf<String>()
    private val titleTripwire = TripwireText(TITLE_TEXT)
    private val bodyTripwire = TripwireText(PAYMENT_TEXT)

    private val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>()
    private val captureGate = mockk<NotificationCaptureGate>()
    private val privacyGate = mockk<PrivacyGate>()
    private val privacySettingsRepository = mockk<PrivacySettingsRepository>()
    private val blockedPackageDao = mockk<BlockedPackageDao>()
    private val diagnosticEmitter = mockk<NotificationDiagnosticEmitter>(relaxed = true)
    private val intakeCoordinator = mockk<NotificationIntakeCoordinator>()
    // Strict: the synchronous (DO_NOT_STORE) processing path would call processAndSave.
    private val notificationRepository = mockk<NotificationRepository>()
    private val deduper = mockk<NotificationCaptureDeduper>()
    private val timeProvider = mockk<TimeProvider>()

    private lateinit var service: NotificationCaptureService
    private lateinit var serviceJob: Job
    private val scheduler = TestCoroutineScheduler()

    private var nextDecision: NotificationCaptureDecision = NotificationCaptureDecision.Allowed
    private var nextNotificationId = 1

    @Before
    fun setUp() {
        // Attach only: skip onCreate so Hilt cannot overwrite the injected doubles.
        service = Robolectric.buildService(NotificationCaptureService::class.java).get()
        serviceJob = ReflectionHelpers.getField(service, "serviceJob")
        ReflectionHelpers.setField(service, "serviceScope", CoroutineScope(serviceJob + StandardTestDispatcher(scheduler)))
        mockkObject(NotificationTextParts.Companion)
        every { NotificationTextParts.extract(any()) } answers { callOriginal() }
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
            captureJobs += currentCoroutineContext().job
            nextDecision
        }
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns PrivacyDecision.Allowed
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(rawNotificationStorageMode = RawStorageMode.STORE_RAW)
        every { deduper.tryStart(any(), any()) } answers {
            startedKeys.add(firstArg<String>())
            false
        }
        every { deduper.remove(any()) } answers {
            removedKeys.add(firstArg<String>())
            Unit
        }
        every { timeProvider.now() } returns 1_700_000_000_000L
        coEvery { diagnosticEmitter.emit(capture(emittedEvents)) } returns Unit
        coEvery {
            diagnosticEmitter.emitOrdered(capture(emittedEvents), capture(emittedEvents))
        } returns Unit
        coEvery {
            intakeCoordinator.capture(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns NotificationIntakeCaptureResult.Enqueued(1L, "test-correlation-id")
        coEvery {
            intakeCoordinator.captureForRetry(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns NotificationIntakeCaptureResult.Enqueued(2L, "test-correlation-id")
    }

    @After
    fun tearDown() {
        serviceJob.cancel()
        scheduler.advanceUntilIdle()
        unmockkObject(NotificationTextParts.Companion)
    }

    // -- helpers ---------------------------------------------------------------

    private fun buildPaymentNotification(): StatusBarNotification {
        val notification = Notification()
        notification.extras.apply {
            putCharSequence(Notification.EXTRA_TITLE, titleTripwire)
            putCharSequence(Notification.EXTRA_TEXT, bodyTripwire)
        }
        return StatusBarNotification(
            PKG, OP_PKG, nextNotificationId++, NOTIFICATION_TAG, 10042, 10042, 0,
            notification, Process.myUserHandle(), 1_700_000_000_000L
        )
    }

    private fun post(sbn: StatusBarNotification = buildPaymentNotification()): StatusBarNotification {
        service.onNotificationPosted(sbn)
        scheduler.advanceUntilIdle()
        return sbn
    }

    private fun assertEveryCaptureJobCompleted(expectedJobs: Int) {
        assertEquals("gate must be consulted once per posted notification", expectedJobs, captureJobs.size)
        assertTrue("capture work must finish before assertions", captureJobs.all { it.isCompleted })
        assertTrue("no capture coroutine may be cancelled", captureJobs.none { it.isCancelled })
    }

    /**
     * Nothing may reach durable intake or the synchronous processing repository.
     * The zero-call checks matter because production's broad catch around step 5/6
     * swallows exceptions from unstubbed (tripwire) mocks, so a missing stub alone
     * would not fail a test. The service only ever calls the 4-argument
     * `processAndSave` overload (the `RequiresSynchronousProcessing` branch), so that
     * is the overload verified here. In this test class the intake stub always
     * returns `Enqueued`, so that branch is not reachable today; the zero-call check
     * is a tripwire against a future hand-off, not proof of a current path.
     */
    private fun assertNoIntakeHandoff() {
        coVerify(exactly = 0) {
            intakeCoordinator.capture(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            intakeCoordinator.captureForRetry(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { notificationRepository.processAndSave(any(), any(), any(), any()) }
    }

    private fun assertExtrasNeverRead() {
        assertFalse("EXTRA_TITLE value must not be read", titleTripwire.touched)
        assertFalse("EXTRA_TEXT value must not be read", bodyTripwire.touched)
    }

    /**
     * No emitted diagnostics event may carry raw notification text, a raw package
     * name or the notification tag in any String field (stage, entityType, sourceType,
     * sourceIdHash, causationId), in its metadata JSON, or in the message of any
     * exception in its cause chain. Production hashes the package (`putHashed`), so no
     * event is exempt. The text fragments ("12.00", "Cafe") and the tag catch a leak
     * of part of the payload, not only of the whole string.
     */
    private fun assertNoRawTextInDiagnostics() {
        val rawValues = listOf(TITLE_TEXT, PAYMENT_TEXT, PKG, OP_PKG, "12.00", "Cafe", NOTIFICATION_TAG)
        for (event in emittedEvents) {
            val stringFields = mapOf(
                "metadata" to event.metadata.toJson(),
                "stage" to event.stage,
                "entityType" to event.entityType.orEmpty(),
                "sourceType" to event.sourceType.orEmpty(),
                "sourceIdHash" to event.sourceIdHash.orEmpty(),
                "causationId" to event.causationId.orEmpty()
            )
            val causeMessages = generateSequence(event.exception) { it.cause }
                .map { it.message.orEmpty() }
                .toList()
            for (raw in rawValues) {
                for ((fieldName, value) in stringFields) {
                    assertFalse(
                        "raw value '$raw' leaked into ${event.stage} $fieldName",
                        value.contains(raw)
                    )
                }
                for (message in causeMessages) {
                    assertFalse(
                        "raw value '$raw' leaked into ${event.stage} exception cause chain",
                        message.contains(raw)
                    )
                }
            }
        }
    }

    /** Everything the service does strictly after a gate `Allowed`. */
    private fun assertNothingPastTheGate() {
        verify(exactly = 0) { NotificationTextParts.extract(any()) }
        assertExtrasNeverRead()
        verify(exactly = 0) { deduper.tryStart(any(), any()) }
        verify(exactly = 0) { deduper.remove(any()) }
        coVerify(exactly = 0) { privacyGate.check(any(), any()) }
        coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        coVerify(exactly = 0) { blockedPackageDao.isBlocked(any()) }
        verify(exactly = 0) { timeProvider.now() }
        assertNoIntakeHandoff()
    }

    private fun assertCaptureGateDrop(decision: NotificationCaptureDecision.Blocked) {
        val matching = emittedEvents.filter {
            it.stage == "capture_gate" && it.isTerminal &&
                it.outcome == EventOutcome.DROPPED && it.reasonCode == decision.diagnosticReasonCode
        }
        assertEquals(
            "exactly one terminal capture_gate DROPPED diagnostic with ${decision.diagnosticReasonCode}",
            1, matching.size
        )
        val metadataJson = matching.single().metadata.toJson()
        assertFalse("raw package name must never appear in diagnostics", metadataJson.contains(PKG))
    }

    private fun assertBlockedDecisionIsFinal(decision: NotificationCaptureDecision.Blocked) {
        nextDecision = decision

        post()

        assertEveryCaptureJobCompleted(expectedJobs = 1)
        assertCaptureGateDrop(decision)
        assertNothingPastTheGate()
        assertNoRawTextInDiagnostics()
    }

    // -- control: the zero-call assertions are only meaningful if Allowed does the work --

    @Test
    fun `control - Allowed decision extracts then dedupes then hands off to intake in that order`() {
        nextDecision = NotificationCaptureDecision.Allowed

        val sbn = post()

        assertEveryCaptureJobCompleted(expectedJobs = 1)
        coVerifyOrder {
            captureGate.decide(PKG, false)
            NotificationTextParts.extract(sbn.notification.extras)
            deduper.tryStart(any(), any())
            privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)
            intakeCoordinator.capture(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 1) { NotificationTextParts.extract(any()) }
        verify(exactly = 1) { deduper.tryStart(any(), any()) }
        coVerify(exactly = 1) {
            intakeCoordinator.capture(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        // The extras tripwires are live: an Allowed capture does read both values.
        assertTrue("Allowed control must read EXTRA_TITLE", titleTripwire.touched)
        assertTrue("Allowed control must read EXTRA_TEXT", bodyTripwire.touched)
    }

    // -- Blocked decisions: no extraction, no dedupe, no handoff ---------------

    @Test
    fun `Blocked PRIVACY_SETTING_DISABLED stops before extraction dedupe and intake`() {
        assertBlockedDecisionIsFinal(
            NotificationCaptureDecision.Blocked(
                reason = NotificationCaptureBlockReason.PRIVACY_SETTING_DISABLED,
                diagnosticReasonCode = DiagnosticReasonCode.PRIVACY_DENIED
            )
        )
    }

    @Test
    fun `Blocked PRIVACY_GATE_DENIED stops before extraction dedupe and intake`() {
        assertBlockedDecisionIsFinal(
            NotificationCaptureDecision.Blocked(
                reason = NotificationCaptureBlockReason.PRIVACY_GATE_DENIED,
                diagnosticReasonCode = DiagnosticReasonCode.PRIVACY_DENIED
            )
        )
    }

    @Test
    fun `Blocked BLOCKED_PACKAGE stops before extraction dedupe and intake`() {
        assertBlockedDecisionIsFinal(
            NotificationCaptureDecision.Blocked(
                reason = NotificationCaptureBlockReason.BLOCKED_PACKAGE,
                diagnosticReasonCode = DiagnosticReasonCode.BLOCKED_PACKAGE
            )
        )
    }

    @Test
    fun `Blocked RESTORE_MODE from the gate stops before extraction dedupe and intake`() {
        assertBlockedDecisionIsFinal(
            NotificationCaptureDecision.Blocked(
                reason = NotificationCaptureBlockReason.RESTORE_MODE,
                diagnosticReasonCode = DiagnosticReasonCode.RESTORE_BLOCKED
            )
        )
    }

    @Test
    fun `every Blocked reason stops before extraction dedupe and intake`() {
        val blockedReasons = NotificationCaptureBlockReason.values().filter {
            it != NotificationCaptureBlockReason.GATE_NOT_READY // TemporarilyUnavailable-only reason
        }
        val decisions = blockedReasons.map { reason ->
            NotificationCaptureDecision.Blocked(
                reason = reason,
                diagnosticReasonCode = DiagnosticReasonCode.PRIVACY_DENIED
            )
        }
        assertTrue("expected several Blocked reasons to exercise", decisions.size >= 8)

        for (decision in decisions) {
            nextDecision = decision
            post()
        }

        assertEveryCaptureJobCompleted(expectedJobs = decisions.size)
        assertEquals(
            "one terminal capture_gate DROPPED diagnostic per Blocked decision",
            decisions.size,
            emittedEvents.count {
                it.stage == "capture_gate" && it.isTerminal && it.outcome == EventOutcome.DROPPED
            }
        )
        assertNothingPastTheGate()
        assertNoRawTextInDiagnostics()
    }

    @Test
    fun `service-level restore maintenance mode drops before the gate extraction dedupe and intake`() {
        every { restoreMaintenanceMode.isWritesAllowed() } returns false

        post()

        coVerify(exactly = 0) { captureGate.decide(any(), any()) }
        assertTrue(
            "terminal RESTORE_BLOCKED diagnostic expected",
            emittedEvents.any { it.isTerminal && it.reasonCode == DiagnosticReasonCode.RESTORE_BLOCKED }
        )
        assertNothingPastTheGate()
        assertNoRawTextInDiagnostics()
    }

    // -- Step 5: privacy re-check after an Allowed gate -------------------------

    private fun assertStep5DenialHandsNothingToIntake(decision: PrivacyDecision) {
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns decision

        post()

        assertEveryCaptureJobCompleted(expectedJobs = 1)
        // The gate allowed capture, so extraction and the dedupe attempt did happen ...
        verify(exactly = 1) { NotificationTextParts.extract(any()) }
        verify(exactly = 1) { deduper.tryStart(any(), any()) }
        coVerify(exactly = 1) { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) }
        // ... but the privacy re-check is final: nothing is stored, read back, or enqueued.
        assertNoIntakeHandoff()
        coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        // The dedupe key recorded by tryStart is released exactly once (B2-2c fix),
        // and it is the very same key.
        verify(exactly = 1) { deduper.remove(any()) }
        assertEquals("exactly one key was started", 1, startedKeys.size)
        assertEquals("the started dedupe key must be the one released", startedKeys, removedKeys)
        val drop = emittedEvents.single { it.stage == "privacy_gate" }
        assertEquals(EventOutcome.DROPPED, drop.outcome)
        assertEquals(DiagnosticReasonCode.PRIVACY_DENIED, drop.reasonCode)
        assertTrue(drop.isTerminal)
        // A denial is not an intake failure: no intake-stage diagnostic of any kind.
        assertTrue(
            "no intake-stage diagnostic expected for a step-5 denial",
            emittedEvents.none { it.stage == "intake" }
        )
        assertNoRawTextInDiagnostics()
    }

    @Test
    fun `step 5 privacy Denied hands nothing to intake`() {
        assertStep5DenialHandsNothingToIntake(PrivacyDecision.Denied("synthetic denial"))
    }

    @Test
    fun `step 5 privacy FailClosed hands nothing to intake`() {
        assertStep5DenialHandsNothingToIntake(PrivacyDecision.FailClosed("synthetic fail-closed"))
    }

    @Test
    fun `step 5 privacy NotApplicable is not an allow and hands nothing to intake`() {
        assertStep5DenialHandsNothingToIntake(PrivacyDecision.NotApplicable)
    }

    @Test
    fun `step 5 privacy gate exception fails closed and hands nothing to intake`() {
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } throws
            IllegalStateException("synthetic gate failure")

        post()

        assertEveryCaptureJobCompleted(expectedJobs = 1)
        assertNoIntakeHandoff()
        coVerify(exactly = 0) { privacySettingsRepository.getSettings() }
        // Production's broad catch removes the dedupe key exactly once.
        verify(exactly = 1) { deduper.tryStart(any(), any()) }
        verify(exactly = 1) { deduper.remove(any()) }
        assertEquals("the started dedupe key must be the one released", startedKeys, removedKeys)
        val intake = emittedEvents.single { it.stage == "intake" }
        assertTrue("intake diagnostic must be terminal", intake.isTerminal)
        assertEquals(EventOutcome.FAILED_FINAL, intake.outcome)
        assertEquals(DiagnosticReasonCode.UNKNOWN_ERROR, intake.reasonCode)
        assertNoRawTextInDiagnostics()
    }

    // -- PRIV-441-07 at service level: a step-5 denial must not poison the dedupe cache --

    /**
     * Uses the REAL [NotificationCaptureDeduper] (fresh per test) and one
     * notification posted twice inside the dedupe window, so it asserts
     * behaviour rather than a call: the denied first delivery leaves no key and
     * the allowed repost is captured instead of being dropped as DUPLICATE.
     */
    private fun assertStep5DenialDoesNotPoisonDedupe(denial: PrivacyDecision) {
        val realDeduper = NotificationCaptureDeduper(FakeMonotonicTimeProvider())
        ReflectionHelpers.setField(service, "deduper", realDeduper)
        val sbn = buildPaymentNotification()

        // First delivery: gate allows, step-5 privacy re-check does not.
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns denial
        post(sbn)
        // The first delivery really reached step 5 (one terminal privacy_gate PRIVACY_DENIED
        // event), so the empty cache below is the effect of the fix, not of an earlier exit.
        val denialEvents = emittedEvents.filter { it.stage == "privacy_gate" }
        assertEquals("first delivery must reach step 5 exactly once", 1, denialEvents.size)
        assertEquals(DiagnosticReasonCode.PRIVACY_DENIED, denialEvents.single().reasonCode)
        assertEquals(EventOutcome.DROPPED, denialEvents.single().outcome)
        assertNoIntakeHandoff()
        assertEquals("a step-5 denial must leave no dedupe key behind", 0, realDeduper.size)

        // Privacy is restored; the identical notification is re-posted inside the dedupe window.
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE) } returns PrivacyDecision.Allowed
        post(sbn)

        assertEveryCaptureJobCompleted(expectedJobs = 2)
        coVerify(exactly = 1) {
            intakeCoordinator.capture(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertFalse(
            "the re-post must not be dropped as a duplicate",
            emittedEvents.any { it.stage == "dedupe" && it.reasonCode == DiagnosticReasonCode.DUPLICATE }
        )
        assertEquals("the captured repost keeps its dedupe key", 1, realDeduper.size)
        assertNoRawTextInDiagnostics()
    }

    @Test
    fun `step 5 privacy Denied must not poison the dedupe key so a later allowed repost is captured`() {
        assertStep5DenialDoesNotPoisonDedupe(PrivacyDecision.Denied("synthetic denial"))
    }

    @Test
    fun `step 5 privacy FailClosed must not poison the dedupe key so a later allowed repost is captured`() {
        assertStep5DenialDoesNotPoisonDedupe(PrivacyDecision.FailClosed("synthetic fail-closed"))
    }

    @Test
    fun `step 5 privacy NotApplicable must not poison the dedupe key so a later allowed repost is captured`() {
        assertStep5DenialDoesNotPoisonDedupe(PrivacyDecision.NotApplicable)
    }
}
