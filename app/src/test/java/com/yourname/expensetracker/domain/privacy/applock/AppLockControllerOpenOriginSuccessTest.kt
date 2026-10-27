package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkHost
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T33 for ENABLE and DISABLE through [AppLockSettingsOperations] (S18 part (i-a)): the hand-off is non-blocking,
 * the apply is last, both callback orders (AE-117), the held link waits for the apply, a failed or cancelled apply
 * still completes, and the unlock-equivalent discard is done once (by the close) and not repeated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockControllerOpenOriginSuccessTest {

    private val home = DeepLinkRequest(DeepLinkHost.HOME)

    /** A readable store with the lock off and the given stored timeout (AE-117: the timeout is kept across enable). */
    private fun enabledOff(timeout: AutoLockTimeout): AppLockSettingsLoadState =
        AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = false, autoLockTimeout = timeout))

    private fun disabledRig(deferred: Boolean = false, settings: AppLockSettingsLoadState = AppLockTestSettings.DISABLED): SettingsOperationsRig {
        val h = AppLockControllerHarness(settings)
        val controller = h.resolvedController()
        controller.onAppForegrounded()
        assertEquals(AppLockState.Disabled, controller.state.value)
        return SettingsOperationsRig(h, controller, deferred)
    }

    private fun startEnable(rig: SettingsOperationsRig) {
        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestEnable())
        rig.h.settle()
    }

    @Test
    fun `the hand-off handler returns before the apply runs, and the origin state is visible until it does`() {
        val rig = disabledRig(deferred = true)
        startEnable(rig)

        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        // The handler only started the apply: nothing was written, the state is the closed origin.
        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertTrue(rig.h.repository.lockWrites.isEmpty())
        assertNull(rig.controller.inFlightAttemptId)

        rig.h.settle()

        assertEquals(listOf(true), rig.h.repository.lockWrites)
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue(rig.uncaught.isEmpty())
    }

    @Test
    fun `AE-117 enable with a stored IMMEDIATE timeout and an OEM stop - success before APP_FOREGROUNDED gives UNLOCKED`() {
        val rig = disabledRig(settings = enabledOff(AutoLockTimeout.IMMEDIATE))
        startEnable(rig)
        rig.controller.onAppBackgrounded() // the OEM credential UI stops the host at t=0
        rig.h.clock.advance(20_000L)

        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        rig.controller.onAppForegrounded()

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertFalse(rig.h.emitted.contains(PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT))
        assertNull(rig.controller.pendingBackgroundAtMillis)
        assertNull(rig.controller.promptBackgroundAtMillis)
        assertEquals(listOf(true), rig.h.repository.lockWrites)
    }

    @Test
    fun `AE-117 enable with a stored IMMEDIATE timeout and an OEM stop - APP_FOREGROUNDED before the success gives UNLOCKED`() {
        val rig = disabledRig(settings = enabledOff(AutoLockTimeout.IMMEDIATE))
        startEnable(rig)
        rig.controller.onAppBackgrounded()
        rig.h.clock.advance(20_000L)
        rig.controller.onAppForegrounded() // no evaluation while the prompt is in flight (T32)

        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertFalse(rig.h.emitted.contains(PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT))
        assertNull(rig.controller.pendingBackgroundAtMillis)
        // A later foreground does not find a leftover background either.
        rig.controller.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
    }

    @Test
    fun `a held link waits for the apply and is released after the completion, also when the write failed`() {
        for (failWrite in listOf(false, true)) {
            val rig = disabledRig(deferred = true)
            if (failWrite) {
                rig.h.repository.lockWriteResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED) }
            }
            startEnable(rig)
            assertEquals("$failWrite", LinkDecision.HOLD, rig.controller.onLockQuery(home))

            rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            // The gate is open: the apply has not completed, so nothing is released.
            assertEquals("$failWrite", HeldLinkRelease.None, rig.controller.releaseHeldLink())

            rig.h.settle()

            val expectedState = if (failWrite) AppLockState.Disabled else AppLockState.Unlocked
            assertEquals("$failWrite", expectedState, rig.controller.state.value)
            assertEquals("$failWrite", HeldLinkRelease.Apply(home, false), rig.controller.releaseHeldLink())
            assertEquals("$failWrite", HeldLinkRelease.None, rig.controller.releaseHeldLink())
        }
    }

    @Test
    fun `a write that throws CancellationException ends the apply cancelled, records the unconfirmed write once (F7) and still completes`() {
        val rig = disabledRig(deferred = true)
        rig.h.repository.lockWriteFailure = CancellationException("cancelled")
        startEnable(rig)
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        val apply = rig.job.children.single()

        rig.h.settle()

        assertTrue("the cancellation must propagate, not be swallowed", apply.isCancelled)
        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        // S18 ii-a F7: the store was re-read, showed no commit, so the failure is recorded once and nothing moved.
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), rig.h.emitted)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.FIELD to AppLockSettingField.APP_LOCK_ENABLED),
            rig.h.emittedMetadata.single().entries
        )
        assertTrue(rig.uncaught.isEmpty())
        // The completion ran in the finally: the gate is closed, so a new attempt may start.
        assertTrue(rig.controller.startOpenOriginAttempt(OpenOriginPrompt.ENABLE, RecordingSuccessHandler()) is OpenOriginStartResult.Started)
    }

    @Test
    fun `a cancelled scope cannot leave a half applied change - the write and the state move still finish`() {
        val rig = disabledRig(deferred = true)
        val gate = CompletableDeferred<Unit>()
        rig.h.repository.lockWriteGate = gate
        startEnable(rig)
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        rig.h.settle() // the apply is now suspended inside the durable write

        rig.job.cancel()
        gate.complete(Unit)
        rig.h.settle()

        assertEquals(listOf(true), rig.h.repository.lockWrites)
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue(rig.h.emitted.contains(PrivacyDiagnosticCode.LOCK_ENABLED))
        assertEquals(listOf(false to true), rig.triggerCalls)
        // The finally ran: the gate is closed.
        assertTrue(rig.controller.startOpenOriginAttempt(OpenOriginPrompt.DISABLE, RecordingSuccessHandler()) is OpenOriginStartResult.Started)
    }

    @Test
    fun `ATOMIC start - a scope cancelled right after the hand-off still runs the write, the state move and the completion`() {
        val rig = disabledRig(deferred = true)
        startEnable(rig)
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // the handler launched the apply; it has not run yet
        assertTrue(rig.h.repository.lockWrites.isEmpty())

        rig.job.cancel() // BEFORE the apply gets its first dispatch
        rig.h.settle()

        // Final gate finding 5: ATOMIC means the body starts despite the cancelled parent.
        assertEquals(listOf(true), rig.h.repository.lockWrites)
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue(rig.h.emitted.contains(PrivacyDiagnosticCode.LOCK_ENABLED))
        assertTrue(rig.uncaught.isEmpty())
        // The completion and the flag clear ran: the next request is not ALREADY_IN_FLIGHT. From UNLOCKED the
        // disable request starts (the attempt is launched by the controller's own scope, not the cancelled one).
        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
    }

    @Test
    fun `the repost trigger gets the before and after effective discreet values of the committed write`() {
        val rig = disabledRig(settings = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = true)))
        startEnable(rig)

        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        // Discreet was already effective before, so the trigger itself will not sweep (it gets true, true).
        assertEquals(listOf(true to true), rig.triggerCalls)
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
    }

    @Test
    fun `a failed write fires no trigger and a stale completion for another id changes nothing`() {
        val rig = disabledRig(deferred = true)
        rig.h.repository.lockWriteResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED) }
        startEnable(rig)
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        rig.controller.onOpenOriginApplyCompleted(999L) // a wrong id does not open the gate
        assertTrue(
            rig.controller.startOpenOriginAttempt(OpenOriginPrompt.ENABLE, RecordingSuccessHandler()) == OpenOriginStartResult.NotInOrigin
        )

        rig.h.settle()

        assertTrue(rig.triggerCalls.isEmpty())
        assertEquals(AppLockState.Disabled, rig.controller.state.value)
    }

    @Test
    fun `the enable path records one bounded LOCK_ENABLED and no diagnostic carries anything but an enum`() {
        val rig = disabledRig()
        startEnable(rig)
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        for (metadata in rig.h.emittedMetadata) {
            for ((key, value) in metadata.entries) {
                assertTrue("$key", value is Enum<*>)
            }
        }
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_ENABLED), rig.h.emitted)
    }

    // --- S18 ii-d: the review-save (T27) through the same T33 hand-off --------------------------------------------

    /** `UNLOCKED` over a CORRUPTED store (a review is open), setup prompts and diagnostics cleared. */
    private fun reviewRig(deferred: Boolean = false): SettingsOperationsRig {
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED))
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, controller.state.value)
        h.authenticator.calls.clear()
        h.emitted.clear()
        h.emittedMetadata.clear()
        return SettingsOperationsRig(h, controller, deferred)
    }

    private val weakeningOff = AppLockPrivacySettings(appLockEnabled = false, autoLockTimeout = AutoLockTimeout.M1)
    private val lockOnLonger = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.M5)

    private fun startReview(rig: SettingsOperationsRig, record: AppLockPrivacySettings) {
        assertEquals(ReviewSaveResult.STARTED, runBlocking { rig.ops.requestReviewSave(record) })
        rig.h.settle()
    }

    @Test
    fun `review-save the hand-off handler returns before the apply runs, and the origin state is visible until it does`() {
        val rig = reviewRig(deferred = true)
        startReview(rig, weakeningOff)

        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        // The handler only started the apply: nothing was written, the state is the closed origin.
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue(rig.h.repository.reviewSaves.isEmpty())
        assertNull(rig.controller.inFlightAttemptId)

        rig.h.settle()

        assertEquals(listOf(weakeningOff), rig.h.repository.reviewSaves)
        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertTrue(rig.uncaught.isEmpty())
    }

    @Test
    fun `review-save AE-117 an OEM stop during the prompt - success before APP_FOREGROUNDED gives the saved state and no timeout lock`() {
        for ((record, expected) in listOf(weakeningOff to AppLockState.Disabled, lockOnLonger to AppLockState.Unlocked)) {
            val rig = reviewRig()
            startReview(rig, record)
            rig.controller.onAppBackgrounded() // the credential UI stops the host at t=0 (recorded as the prompt background)
            rig.h.clock.advance(20_000L)

            rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            rig.controller.onAppForegrounded()

            assertEquals("$record", expected, rig.controller.state.value)
            assertFalse("$record", rig.h.emitted.contains(PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT))
            assertNull("$record", rig.controller.pendingBackgroundAtMillis)
            assertNull("$record", rig.controller.promptBackgroundAtMillis)
            assertEquals("$record", listOf(record), rig.h.repository.reviewSaves)
        }
    }

    @Test
    fun `review-save AE-117 an OEM stop during the prompt - APP_FOREGROUNDED before the success gives the saved state and no timeout lock`() {
        for ((record, expected) in listOf(weakeningOff to AppLockState.Disabled, lockOnLonger to AppLockState.Unlocked)) {
            val rig = reviewRig()
            startReview(rig, record)
            rig.controller.onAppBackgrounded()
            rig.h.clock.advance(20_000L)
            rig.controller.onAppForegrounded() // no evaluation while the prompt is in flight (T32)

            rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

            assertEquals("$record", expected, rig.controller.state.value)
            assertFalse("$record", rig.h.emitted.contains(PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT))
            assertNull("$record", rig.controller.pendingBackgroundAtMillis)
            // A later foreground does not find a leftover background either.
            rig.controller.onAppForegrounded()
            assertEquals("$record", expected, rig.controller.state.value)
        }
    }

    @Test
    fun `review-save a held link waits for the apply and is released after the completion, also when the write failed`() {
        for (failWrite in listOf(false, true)) {
            val rig = reviewRig(deferred = true)
            if (failWrite) {
                rig.h.repository.reviewSaveResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.REVIEW_SAVE) }
            }
            startReview(rig, weakeningOff)
            assertEquals("$failWrite", LinkDecision.HOLD, rig.controller.onLockQuery(home))

            rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            // The gate is open: the apply has not completed, so nothing is released.
            assertEquals("$failWrite", HeldLinkRelease.None, rig.controller.releaseHeldLink())
            assertTrue(
                "$failWrite: a new attempt is refused while the apply is open",
                rig.controller.startOpenOriginAttempt(OpenOriginPrompt.DISABLE, RecordingSuccessHandler()) == OpenOriginStartResult.NotInOrigin
            )

            rig.h.settle()

            val expectedState = if (failWrite) AppLockState.Unlocked else AppLockState.Disabled
            assertEquals("$failWrite", expectedState, rig.controller.state.value)
            val released = rig.controller.releaseHeldLink()
            assertTrue("$failWrite: $released", released is HeldLinkRelease.Apply)
            assertEquals("$failWrite", home, (released as HeldLinkRelease.Apply).request)
            assertEquals("$failWrite", HeldLinkRelease.None, rig.controller.releaseHeldLink())
            // The completion closed the gate: the origin is available again.
            val probe = if (failWrite) OpenOriginPrompt.DISABLE else OpenOriginPrompt.ENABLE
            assertTrue("$failWrite", rig.controller.startOpenOriginAttempt(probe, RecordingSuccessHandler()) is OpenOriginStartResult.Started)
        }
    }

    @Test
    fun `review-save the repost trigger runs once after the commit and the completion releases the apply flag`() {
        val rig = reviewRig()
        startReview(rig, weakeningOff.copy(discreetNotifications = true))

        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(listOf(false to true), rig.triggerCalls)
        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        // The apply flag is cleared: a request is answered by the state check, not ALREADY_IN_FLIGHT.
        assertEquals(ReviewSaveResult.NOT_IN_REVIEW, runBlocking { rig.ops.requestReviewSave(weakeningOff) })
    }

    // --- The prompt copy of each purpose ------------------------------------------------------------------------

    @Test
    fun `the prompt copy table - ENABLE gives ENABLE, T18 gives DISABLE, a longer timeout or a weakening review-save gives CHANGE`() {
        val enable = disabledRig()
        assertEquals(SettingsRequestResult.STARTED, enable.ops.requestEnable())
        enable.h.settle()
        assertEquals(AuthPromptCopy.ENABLE, enable.h.authenticator.calls.single().copy)

        val (disableHarness, disableController) = unlockedControllerFor(AutoLockTimeout.M1)
        val disable = SettingsOperationsRig(disableHarness, disableController)
        disableHarness.authenticator.calls.clear()
        assertEquals(SettingsRequestResult.STARTED, disable.ops.requestDisable())
        disableHarness.settle()
        assertEquals(AuthPromptCopy.DISABLE, disableHarness.authenticator.calls.single().copy)

        val (timeoutHarness, timeoutController) = unlockedControllerFor(AutoLockTimeout.M1)
        val timeout = SettingsOperationsRig(timeoutHarness, timeoutController)
        timeoutHarness.authenticator.calls.clear()
        assertEquals(TimeoutChangeResult.STARTED, runBlocking { timeout.ops.requestTimeoutChange(AutoLockTimeout.M5) })
        timeoutHarness.settle()
        assertEquals(AuthPromptCopy.CHANGE, timeoutHarness.authenticator.calls.single().copy)

        for (record in listOf(weakeningOff, lockOnLonger)) {
            val review = reviewRig()
            startReview(review, record)
            assertEquals("$record", AuthPromptCopy.CHANGE, review.h.authenticator.calls.single().copy)
        }
    }

    @Test
    fun `a review-save that is not weakening starts no prompt at all`() {
        val rig = reviewRig()

        val result = runBlocking {
            rig.ops.requestReviewSave(AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.IMMEDIATE))
        }
        rig.h.settle()

        assertEquals(ReviewSaveResult.COMMITTED, result)
        assertTrue(rig.h.authenticator.calls.isEmpty())
        assertNull(rig.controller.inFlightAttemptId)
    }
}
