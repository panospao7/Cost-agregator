package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStoreState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayReason
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayTier
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifyResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S27b (AMD-2, T30, INV-54[2]): the PIN confirm steps TIMEOUT_CONFIRM / REVIEW_CONFIRM of [AppLockController]: opening,
 * the shared-counter verify, the one-shot authority and its clearing, and the applied callbacks. Synthetic PINs only.
 */
class AppLockControllerPinConfirmTest {

    private val timeoutStep = PinStep.TIMEOUT_CONFIRM
    private val reviewStep = PinStep.REVIEW_CONFIRM

    private fun pin(text: String = "482916"): CharArray = text.toCharArray()

    /** UNLOCKED in effective APP_PIN mode (lock on, [timeout]); the rig's own unlock traffic is cleared afterwards. */
    private fun pinModeRig(timeout: AutoLockTimeout = AutoLockTimeout.M15): SettingsOperationsRig {
        val settings = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = timeout, lockMode = LockMode.APP_PIN)
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, false, false))
        store.verifyScript += PinVerifyResult.Correct
        val h = AppLockControllerHarness(AppLockSettingsLoadState.Loaded(settings), pinStore = store)
        val controller = h.resolvedController()
        controller.onAppForegrounded()
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin()) })
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(EffectiveLockMode.APP_PIN, controller.pin.value.effectiveLockMode)
        h.authenticator.calls.clear()
        store.log.clear()
        store.calls.clear()
        h.emitted.clear()
        return SettingsOperationsRig(h, controller)
    }

    /** A rig with the [step] open and the verify script set to [results]. */
    private fun openRig(step: PinStep, vararg results: PinVerifyResult): SettingsOperationsRig {
        val rig = pinModeRig()
        rig.h.pinStore.verifyScript += results.toList()
        assertTrue(rig.controller.beginPinConfirm(step))
        return rig
    }

    /** Re-locks through a real background past the timeout, then a foreground. */
    private fun relockByTimeout(rig: SettingsOperationsRig) {
        rig.controller.onAppBackgrounded()
        rig.h.clock.advance(AutoLockTimeout.M15.millis + 1_000L)
        rig.controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), rig.controller.state.value)
    }

    // --- 1. beginPinConfirm ------------------------------------------------------------------------

    @Test
    fun `INV-54 beginPinConfirm opens each confirm step in UNLOCKED effective APP_PIN`() {
        for (step in listOf(timeoutStep, reviewStep)) {
            val rig = pinModeRig()
            assertTrue("$step", rig.controller.beginPinConfirm(step))
            assertEquals("$step", step, rig.controller.pin.value.step)
            assertEquals(AppLockState.Unlocked, rig.controller.state.value)
            assertEquals(0, rig.h.authenticator.calls.size)
        }
    }

    @Test
    fun `INV-54 beginPinConfirm refuses every other step and a second step while one is open`() {
        val rig = pinModeRig()
        val others = listOf(PinStep.NONE, PinStep.SETUP_ENTRY, PinStep.CHANGE_ENTRY, PinStep.REMOVE_CONFIRM, PinStep.DISABLE_CONFIRM)
        for (step in others) {
            assertFalse("$step", rig.controller.beginPinConfirm(step))
            assertEquals("$step", PinStep.NONE, rig.controller.pin.value.step)
        }
        assertTrue(rig.controller.beginPinConfirm(timeoutStep))
        assertFalse(rig.controller.beginPinConfirm(reviewStep))
        assertEquals(timeoutStep, rig.controller.pin.value.step)
    }

    @Test
    fun `INV-54 beginPinConfirm refuses in effective DEVICE_AUTH mode`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M15)
        assertEquals(EffectiveLockMode.DEVICE_AUTH, controller.pin.value.effectiveLockMode)
        for (step in listOf(timeoutStep, reviewStep)) {
            assertFalse("$step", controller.beginPinConfirm(step))
            assertEquals(PinStep.NONE, controller.pin.value.step)
        }
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(0, h.pinStore.count("verify"))
    }

    @Test
    fun `INV-54 beginPinConfirm refuses outside UNLOCKED`() {
        val settings = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.M15, lockMode = LockMode.APP_PIN)
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, false, false))
        val h = AppLockControllerHarness(AppLockSettingsLoadState.Loaded(settings), pinStore = store)
        val controller = h.resolvedController()
        controller.onAppForegrounded()
        assertTrue(controller.state.value is AppLockState.Locked)
        for (step in listOf(timeoutStep, reviewStep)) {
            assertFalse("$step", controller.beginPinConfirm(step))
            assertEquals(PinStep.NONE, controller.pin.value.step)
        }
    }

    @Test
    fun `INV-54 beginPinConfirm refuses while a PIN operation runs and that operation is unaffected`() {
        val rig = openRig(timeoutStep, PinVerifyResult.Correct)
        val gate = CompletableDeferred<Unit>()
        rig.h.pinStore.verifyGate = gate
        runBlocking {
            var first: PinOperationResult? = null
            val job = launch(start = CoroutineStart.UNDISPATCHED) { first = rig.controller.verifyPinForStep(timeoutStep, pin()) }
            assertFalse(rig.controller.beginPinConfirm(reviewStep))
            assertEquals(PinOperationResult.AlreadyInFlight, rig.controller.verifyPinForStep(timeoutStep, pin()))
            gate.complete(Unit)
            job.join()
            assertEquals(PinOperationResult.Done, first)
        }
        assertEquals(timeoutStep, rig.controller.pin.value.step)
        assertTrue(rig.controller.pinStepAuthorised(timeoutStep))
    }

    // --- 2. verifyPinForStep -----------------------------------------------------------------------

    @Test
    fun `INV-54 verifyPinForStep with the correct PIN is Done and authorises exactly that step`() {
        for ((step, other) in listOf(timeoutStep to reviewStep, reviewStep to timeoutStep)) {
            val rig = openRig(step, PinVerifyResult.Correct)
            assertFalse("$step before", rig.controller.pinStepAuthorised(step))
            assertEquals(PinOperationResult.Done, runBlocking { rig.controller.verifyPinForStep(step, pin()) })
            assertTrue("$step", rig.controller.pinStepAuthorised(step))
            assertFalse("$step must not authorise $other", rig.controller.pinStepAuthorised(other))
            assertEquals(step, rig.controller.pin.value.step)
            assertEquals(1, rig.h.pinStore.count("verify"))
            assertTrue(rig.h.repository.lockWrites.isEmpty() && rig.h.repository.timeoutWrites.isEmpty())
        }
    }

    @Test
    fun `INV-54 verifyPinForStep refuses another step, a non-confirm step and no open step with no verify`() {
        val rig = openRig(timeoutStep, PinVerifyResult.Correct)
        assertEquals(PinOperationResult.NotInStep, runBlocking { rig.controller.verifyPinForStep(reviewStep, pin()) })
        assertEquals(PinOperationResult.NotInStep, runBlocking { rig.controller.verifyPinForStep(PinStep.CHANGE_ENTRY, pin()) })
        assertEquals(PinOperationResult.NotInStep, runBlocking { rig.controller.verifyPinForStep(PinStep.NONE, pin()) })
        rig.controller.cancelPinStep()
        assertEquals(PinOperationResult.NotInStep, runBlocking { rig.controller.verifyPinForStep(timeoutStep, pin()) })
        assertEquals(0, rig.h.pinStore.count("verify"))
        assertFalse(rig.controller.pinStepAuthorised(timeoutStep) || rig.controller.pinStepAuthorised(reviewStep))
    }

    @Test
    fun `INV-54 verifyPinForStep with a wrong PIN gives the wrong result, no authority and one counted verify`() {
        val rig = openRig(timeoutStep, PinVerifyResult.Wrong(PinDelayTier.S30))
        val result = runBlocking { rig.controller.verifyPinForStep(timeoutStep, pin("000000")) }
        assertEquals(PinOperationResult.NotVerified(PinRejection.Wrong(PinDelayTier.S30)), result)
        assertFalse(rig.controller.pinStepAuthorised(timeoutStep))
        assertEquals(timeoutStep, rig.controller.pin.value.step)
        assertEquals(1, rig.h.pinStore.count("verify")) // the store owns the shared counter: one verify, one count
        assertEquals(listOf("verify"), rig.h.pinStore.log)
    }

    @Test
    fun `INV-54 verifyPinForStep publishes a delay in the confirm step and clears it on the next correct PIN`() {
        val rig = openRig(
            reviewStep,
            PinVerifyResult.Delayed(PinDelayReason.FAILURES, 9_000L),
            PinVerifyResult.Correct
        )
        val delayed = runBlocking { rig.controller.verifyPinForStep(reviewStep, pin()) }
        assertEquals(PinOperationResult.NotVerified(PinRejection.Delayed(PinDelayReason.FAILURES, 9_000L)), delayed)
        assertEquals(9_000L, rig.controller.pin.value.delayRemainingMillis)
        assertFalse(rig.controller.pin.value.delayNeutral)
        assertEquals(reviewStep, rig.controller.pin.value.step)
        assertFalse(rig.controller.pinStepAuthorised(reviewStep))
        assertEquals(PinOperationResult.Done, runBlocking { rig.controller.verifyPinForStep(reviewStep, pin()) })
        assertNull(rig.controller.pin.value.delayRemainingMillis)
        assertTrue(rig.controller.pinStepAuthorised(reviewStep))
    }

    @Test
    fun `INV-54 an ATTEMPT_STATE_UNREADABLE delay in the confirm step is published as neutral`() {
        val rig = openRig(timeoutStep, PinVerifyResult.Delayed(PinDelayReason.ATTEMPT_STATE_UNREADABLE, 5_000L))
        runBlocking { rig.controller.verifyPinForStep(timeoutStep, pin()) }
        assertEquals(5_000L, rig.controller.pin.value.delayRemainingMillis)
        assertTrue(rig.controller.pin.value.delayNeutral)
        assertFalse(rig.controller.pinStepAuthorised(timeoutStep))
    }

    @Test
    fun `INV-54 verifyPinForStep zero-fills the PIN on success, rejection and refusal`() {
        val results = listOf<PinVerifyResult>(
            PinVerifyResult.Correct,
            PinVerifyResult.Wrong(PinDelayTier.NONE),
            PinVerifyResult.Delayed(PinDelayReason.FAILURES, 3_000L),
            PinVerifyResult.Unavailable
        )
        for (scripted in results) {
            val rig = openRig(timeoutStep, scripted)
            val entered = pin()
            runBlocking { rig.controller.verifyPinForStep(timeoutStep, entered) }
            assertTrue("$scripted", entered.all { it == '\u0000' })
            assertTrue("$scripted store", rig.h.pinStore.callsOf("verify").single().contentAtCall.single().isNotEmpty())
        }
        val refused = pin()
        val rig = pinModeRig() // no step open: refused before any store call
        runBlocking { rig.controller.verifyPinForStep(timeoutStep, refused) }
        assertTrue(refused.all { it == '\u0000' })
        assertEquals(0, rig.h.pinStore.count("verify"))
    }

    @Test
    fun `INV-54 verifyPinForStep zero-fills the PIN when cancelled and leaves no authority or stuck operation`() {
        val rig = openRig(timeoutStep, PinVerifyResult.Correct)
        val gate = CompletableDeferred<Unit>()
        rig.h.pinStore.verifyGate = gate
        val entered = pin()
        runBlocking {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { rig.controller.verifyPinForStep(timeoutStep, entered) }
            job.cancel()
            job.join()
        }
        assertTrue(entered.all { it == '\u0000' })
        assertFalse(rig.controller.pinStepAuthorised(timeoutStep))
        rig.h.pinStore.verifyGate = null
        assertEquals(PinOperationResult.Done, runBlocking { rig.controller.verifyPinForStep(timeoutStep, pin()) })
    }

    // --- 3. one-shot authority and clearing --------------------------------------------------------

    @Test
    fun `INV-54 consumePinStepAuthority is one-shot and only for the authorised step`() {
        val rig = openRig(reviewStep, PinVerifyResult.Correct)
        assertFalse(rig.controller.consumePinStepAuthority(reviewStep)) // nothing granted yet
        runBlocking { rig.controller.verifyPinForStep(reviewStep, pin()) }
        assertFalse(rig.controller.consumePinStepAuthority(timeoutStep))
        assertTrue(rig.controller.pinStepAuthorised(reviewStep))
        assertTrue(rig.controller.consumePinStepAuthority(reviewStep))
        assertFalse(rig.controller.consumePinStepAuthority(reviewStep))
        assertFalse(rig.controller.pinStepAuthorised(reviewStep))
        assertEquals(reviewStep, rig.controller.pin.value.step)
    }

    @Test
    fun `INV-54 cancelPinStep clears the authority and a reopened step needs a fresh PIN`() {
        val rig = openRig(timeoutStep, PinVerifyResult.Correct)
        runBlocking { rig.controller.verifyPinForStep(timeoutStep, pin()) }
        assertTrue(rig.controller.pinStepAuthorised(timeoutStep))
        rig.controller.cancelPinStep()
        assertFalse(rig.controller.pinStepAuthorised(timeoutStep))
        assertTrue(rig.controller.beginPinConfirm(timeoutStep))
        assertFalse(rig.controller.pinStepAuthorised(timeoutStep))
        assertFalse(rig.controller.consumePinStepAuthority(timeoutStep))
    }

    @Test
    fun `INV-54 a re-lock clears the authority and a later unlock does not restore it`() {
        val rig = openRig(timeoutStep, PinVerifyResult.Correct)
        runBlocking { rig.controller.verifyPinForStep(timeoutStep, pin()) }
        assertTrue(rig.controller.pinStepAuthorised(timeoutStep))
        relockByTimeout(rig)
        assertFalse(rig.controller.pinStepAuthorised(timeoutStep))
        rig.h.pinStore.verifyScript += PinVerifyResult.Correct
        assertEquals(PinUnlockResult.Unlocked, runBlocking { rig.controller.submitUnlockPin(pin()) })
        assertEquals(PinStep.NONE, rig.controller.pin.value.step)
        assertTrue(rig.controller.beginPinConfirm(timeoutStep))
        assertFalse(rig.controller.pinStepAuthorised(timeoutStep))
        assertFalse(rig.controller.consumePinStepAuthority(timeoutStep))
    }

    // --- 4. applied callbacks ----------------------------------------------------------------------

    /** True when the controller's settings view now measures with [timeout]: a background of that length re-locks. */
    private fun relocksAfter(rig: SettingsOperationsRig, timeout: AutoLockTimeout): Boolean {
        rig.controller.onAppBackgrounded()
        rig.h.clock.advance(timeout.millis + 1_000L)
        rig.controller.onAppForegrounded()
        return rig.controller.state.value == AppLockState.Locked(LockCause.TIMEOUT)
    }

    @Test
    fun `INV-54 onPinConfirmedTimeoutApplied without authority and not committed changes no step or view but records the failure`() {
        val rig = pinModeRig()
        assertTrue(rig.controller.beginPinConfirm(timeoutStep)) // open, but no correct PIN yet
        rig.controller.onPinConfirmedTimeoutApplied(AutoLockTimeout.M1, false)
        assertEquals(timeoutStep, rig.controller.pin.value.step)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), rig.h.emitted)
        assertFalse("view must keep the old timeout", relocksAfter(rig, AutoLockTimeout.M1))
    }

    @Test
    fun `INV-54 onPinConfirmedTimeoutApplied without authority but committed still applies the durable timeout and closes no step`() {
        val rig = pinModeRig() // M15
        assertTrue(rig.controller.beginPinConfirm(timeoutStep)) // open, authority never granted (or already consumed)
        rig.controller.onPinConfirmedTimeoutApplied(AutoLockTimeout.M1, true)
        assertEquals("the step is closed only by an authority", timeoutStep, rig.controller.pin.value.step)
        assertTrue("the committed 1-minute timeout is now the view", relocksAfter(rig, AutoLockTimeout.M1))
    }

    @Test
    fun `INV-54 a failed re-verify after a successful verify clears the authority`() {
        val rig = openRig(timeoutStep, PinVerifyResult.Correct, PinVerifyResult.Wrong(PinDelayTier.S30))
        assertEquals(PinOperationResult.Done, runBlocking { rig.controller.verifyPinForStep(timeoutStep, pin()) })
        assertTrue(rig.controller.pinStepAuthorised(timeoutStep))
        val second = runBlocking { rig.controller.verifyPinForStep(timeoutStep, pin("000000")) }
        assertEquals(PinOperationResult.NotVerified(PinRejection.Wrong(PinDelayTier.S30)), second)
        assertFalse(rig.controller.pinStepAuthorised(timeoutStep))
        assertFalse(rig.controller.consumePinStepAuthority(timeoutStep))
        assertEquals(timeoutStep, rig.controller.pin.value.step)
    }

    @Test
    fun `INV-54 onPinConfirmedTimeoutApplied with the review authority is a no-op and keeps that authority`() {
        val rig = openRig(reviewStep, PinVerifyResult.Correct)
        runBlocking { rig.controller.verifyPinForStep(reviewStep, pin()) }
        rig.controller.onPinConfirmedTimeoutApplied(AutoLockTimeout.M1, true)
        assertEquals(reviewStep, rig.controller.pin.value.step)
        assertTrue(rig.controller.pinStepAuthorised(reviewStep))
        assertTrue(rig.h.emitted.isEmpty())
    }

    @Test
    fun `INV-54 onPinConfirmedTimeoutApplied committed applies the timeout and closes the step`() {
        val rig = pinModeRig(AutoLockTimeout.M1)
        rig.h.pinStore.verifyScript += PinVerifyResult.Correct
        assertTrue(rig.controller.beginPinConfirm(timeoutStep))
        runBlocking { rig.controller.verifyPinForStep(timeoutStep, pin()) }
        rig.controller.onPinConfirmedTimeoutApplied(AutoLockTimeout.M5, true)
        assertEquals(PinStep.NONE, rig.controller.pin.value.step)
        assertFalse(rig.controller.pinStepAuthorised(timeoutStep))
        assertTrue(rig.h.emitted.isEmpty())
        assertFalse("5 minutes is longer than the old 1", relocksAfter(rig, AutoLockTimeout.M1))
    }

    @Test
    fun `INV-54 onPinConfirmedTimeoutApplied not committed keeps the step and records the persist-failed diagnostic`() {
        val rig = openRig(timeoutStep, PinVerifyResult.Correct)
        runBlocking { rig.controller.verifyPinForStep(timeoutStep, pin()) }
        rig.controller.onPinConfirmedTimeoutApplied(AutoLockTimeout.M1, false)
        assertEquals(timeoutStep, rig.controller.pin.value.step)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), rig.h.emitted)
        assertFalse(rig.controller.pinStepAuthorised(timeoutStep)) // the authority is spent even by a failed write
        assertFalse("the failed write changed nothing", relocksAfter(rig, AutoLockTimeout.M1))
    }

    private val lockOffRecord = AppLockPrivacySettings(appLockEnabled = false)

    @Test
    fun `INV-54 onPinConfirmedReviewSaved without authority and not committed changes no step or view but records the failure`() {
        val rig = pinModeRig()
        assertTrue(rig.controller.beginPinConfirm(reviewStep))
        rig.controller.onPinConfirmedReviewSaved(lockOffRecord, false)
        assertEquals(reviewStep, rig.controller.pin.value.step)
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), rig.h.emitted)
    }

    @Test
    fun `INV-54 onPinConfirmedReviewSaved without authority but committed applies a non-weakening record and closes no step`() {
        val rig = pinModeRig()
        assertTrue(rig.controller.beginPinConfirm(reviewStep))
        rig.controller.onPinConfirmedReviewSaved(AppLockPrivacySettings(appLockEnabled = true), true)
        assertEquals("the step is closed only by an authority", reviewStep, rig.controller.pin.value.step)
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue(rig.h.emitted.contains(PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
    }

    @Test
    fun `INV-54 onPinConfirmedReviewSaved without authority but committed with a lock-off record yields Disabled and no step`() {
        val rig = pinModeRig()
        assertTrue(rig.controller.beginPinConfirm(reviewStep))
        rig.controller.onPinConfirmedReviewSaved(lockOffRecord, true)
        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertEquals(PinStep.NONE, rig.controller.pin.value.step)
    }

    @Test
    fun `INV-54 onPinConfirmedReviewSaved committed applies the record with the PIN as authorisation and closes the step`() {
        val rig = openRig(reviewStep, PinVerifyResult.Correct)
        runBlocking { rig.controller.verifyPinForStep(reviewStep, pin()) }
        rig.controller.onPinConfirmedReviewSaved(lockOffRecord, true)
        assertEquals(PinStep.NONE, rig.controller.pin.value.step)
        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertTrue(rig.h.emitted.contains(PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertFalse(rig.controller.pinStepAuthorised(reviewStep))
    }

    @Test
    fun `INV-54 onPinConfirmedReviewSaved not committed keeps the step and records the persist-failed diagnostic`() {
        val rig = openRig(reviewStep, PinVerifyResult.Correct)
        runBlocking { rig.controller.verifyPinForStep(reviewStep, pin()) }
        rig.controller.onPinConfirmedReviewSaved(lockOffRecord, false)
        assertEquals(reviewStep, rig.controller.pin.value.step)
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), rig.h.emitted)
        assertFalse(rig.controller.pinStepAuthorised(reviewStep))
    }

    // --- 5. API pin --------------------------------------------------------------------------------

    @Test
    fun `INV-54 the controller declares no synthetic default-argument methods`() {
        val synthetic = AppLockController::class.java.declaredMethods.filter { it.name.contains("\$default") }
        assertTrue("default-argument bridges: ${synthetic.map { it.name }}", synthetic.isEmpty())
    }
}
