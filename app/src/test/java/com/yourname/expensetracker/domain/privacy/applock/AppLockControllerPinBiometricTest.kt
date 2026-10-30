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
 * S26 fingerprint unlock in APP_PIN mode on the real controller over fakes (D-I2-7 amended, G26-1, G26-2; INV-39,
 * INV-40, INV-51). There is no proof-less unlock: only a verified PIN_FINGERPRINT prompt success unlocks, and only if the
 * mode, verifier and flag still hold when the attempt closes.
 */
class AppLockControllerPinBiometricTest {

    private val locked = AppLockState.Locked(LockCause.COLD_START)

    /** A resolved LOCKED(COLD_START) APP_PIN controller. With [biometric] true the fingerprint is offered (key READY). */
    private fun fpLocked(
        biometric: Boolean = true,
        script: List<PinVerifyResult> = emptyList()
    ): Pair<AppLockControllerHarness, AppLockController> {
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, biometric, false))
        store.verifyScript += script
        val settings = AppLockPrivacySettings(
            appLockEnabled = true,
            autoLockTimeout = AutoLockTimeout.IMMEDIATE,
            lockMode = LockMode.APP_PIN
        )
        val h = AppLockControllerHarness(AppLockSettingsLoadState.Loaded(settings), pinStore = store)
        val controller = h.resolvedController()
        assertEquals(locked, controller.state.value)
        if (biometric) awaitOffered(controller) else assertFalse(controller.canOfferFingerprintUnlock.value)
        return h to controller
    }

    /** The key readiness is read off the main thread by the observer, so the offer appears asynchronously. */
    private fun awaitOffered(controller: AppLockController) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!controller.canOfferFingerprintUnlock.value && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("fingerprint was never offered", controller.canOfferFingerprintUnlock.value)
        assertTrue(controller.pin.value.canOfferFingerprint)
    }

    private fun request(h: AppLockControllerHarness, controller: AppLockController) {
        runBlocking { controller.requestFingerprintUnlock() }
        h.settle()
    }

    private fun awaitResetCount(h: AppLockControllerHarness, expected: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (h.pinStore.count("resetAttempts") < expected && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(expected, h.pinStore.count("resetAttempts"))
    }

    private fun assertNothingStarted(h: AppLockControllerHarness, controller: AppLockController) {
        assertEquals(locked, controller.state.value)
        assertNull(controller.inFlightAttemptId)
        assertEquals(0, h.authenticator.calls.size)
        assertEquals(0, h.pinStore.count("resetAttempts"))
    }

    // --- Spec: no unlock without a verified authenticator success -----------------------------------

    @Test
    fun `G26-1 a request alone unlocks nothing and waits for the prompt outcome`() {
        val (h, controller) = fpLocked()
        request(h, controller)
        assertEquals(1, h.authenticator.calls.size)
        assertEquals(AppLockState.Authenticating(h.authenticator.calls.single().attemptId, AuthPurpose.UNLOCK), controller.state.value)
        assertEquals(0, h.pinStore.count("resetAttempts"))
    }

    @Test
    fun `G26-1 a cancelled prompt returns to LOCKED and leaves the attempt counter untouched`() {
        val (h, controller) = fpLocked()
        request(h, controller)
        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
        assertEquals(locked, controller.state.value)
        assertEquals(0, h.pinStore.count("resetAttempts"))
        assertFalse(h.emitted.contains(PrivacyDiagnosticCode.LOCK_UNLOCKED))
    }

    @Test
    fun `G26-1 a failed prompt returns to LOCKED and leaves the attempt counter untouched`() {
        val (h, controller) = fpLocked()
        request(h, controller)
        h.authenticator.deliver(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.TIMEOUT))
        assertEquals(locked, controller.state.value)
        assertEquals(0, h.pinStore.count("resetAttempts"))
        assertFalse(h.emitted.contains(PrivacyDiagnosticCode.LOCK_UNLOCKED))
    }

    // --- Success path and prompt copy ----------------------------------------------------------------

    @Test
    fun `G26-2 a PIN_FINGERPRINT success unlocks once and resets the shared attempt counter once`() {
        val (h, controller) = fpLocked()
        request(h, controller)
        assertEquals(AuthPromptCopy.PIN_FINGERPRINT, h.authenticator.calls.single().copy) // spec g: the copy passed
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertTrue(h.emitted.contains(PrivacyDiagnosticCode.LOCK_UNLOCKED))
        awaitResetCount(h, 1)
        assertEquals(0, h.pinStore.count("verify"))
    }

    // --- G26-1: the state is re-read when the attempt closes -------------------------------------------

    private fun successAfter(change: (FakeAppPinStore) -> Unit) {
        val (h, controller) = fpLocked()
        request(h, controller)
        change(h.pinStore)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(locked, controller.state.value)
        assertFalse(h.emitted.contains(PrivacyDiagnosticCode.LOCK_UNLOCKED))
        assertNull(controller.inFlightAttemptId)
        assertEquals(0, h.pinStore.count("resetAttempts"))
    }

    @Test
    fun `G26-1 a success after the fingerprint flag was turned off does not unlock and resets nothing`() {
        successAfter { it.setVerifier(PinVerifierState.PRESENT, biometricEnabled = false) }
    }

    @Test
    fun `G26-1 a success after the PIN verifier was removed does not unlock and resets nothing`() {
        successAfter { it.setVerifier(PinVerifierState.ABSENT, biometricEnabled = true) }
    }

    @Test
    fun `G26-1 a success after the PIN verifier became unreadable does not unlock and resets nothing`() {
        successAfter { it.setVerifier(PinVerifierState.UNREADABLE, biometricEnabled = true) }
    }

    // --- G26-2: unavailable and locked-out prompts return to the PIN pad ---------------------------

    private fun assertReturnsToPinPad(outcome: DeviceAuthOutcome) {
        val (h, controller) = fpLocked()
        val capabilityReadsBefore = h.capabilityProvider.evaluations
        request(h, controller)
        assertEquals(AuthPromptCopy.PIN_FINGERPRINT, h.authenticator.calls.single().copy)
        h.authenticator.deliver(outcome)
        assertEquals(locked, controller.state.value)
        assertFalse(controller.state.value is AppLockState.Inert)
        assertEquals(1, h.authenticator.calls.size) // no second (device-credential) prompt
        assertEquals(capabilityReadsBefore, h.capabilityProvider.evaluations) // no capability re-check, no INERT route
        assertEquals(0, h.pinStore.count("resetAttempts"))
        assertFalse(h.emitted.contains(PrivacyDiagnosticCode.LOCK_UNLOCKED))
    }

    @Test
    fun `G26-2 a NotAvailable fingerprint prompt returns to LOCKED, never INERT and without a device-credential fallback`() {
        assertReturnsToPinPad(DeviceAuthOutcome.NotAvailable)
    }

    @Test
    fun `G26-2 a temporary lockout of the fingerprint prompt returns to LOCKED and never uses the device credential`() {
        assertReturnsToPinPad(DeviceAuthOutcome.LockedOut(permanent = false))
    }

    @Test
    fun `G26-2 a permanent lockout of the fingerprint prompt returns to LOCKED and never uses the device credential`() {
        assertReturnsToPinPad(DeviceAuthOutcome.LockedOut(permanent = true))
    }

    @Test
    fun `G26-2 NotAvailable stays LOCKED even when the device capability now reports no credential`() {
        val (h, controller) = fpLocked()
        h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        request(h, controller)
        h.authenticator.deliver(DeviceAuthOutcome.NotAvailable)
        assertEquals(locked, controller.state.value)
        assertFalse(controller.state.value is AppLockState.Inert)
    }

    // --- Spec e: a request is a no-op while an attempt, a PIN operation or an unreadable attempt state blocks it ---

    private fun pin() = charArrayOf('1', '2', '3', '4')

    @Test
    fun `G26-1 a second request while an attempt is in flight opens nothing and keeps the attempt id`() {
        val (h, controller) = fpLocked()
        request(h, controller)
        val attemptId = controller.inFlightAttemptId
        val stateBefore = controller.state.value
        assertEquals(1, h.authenticator.calls.size)
        request(h, controller)
        assertEquals(1, h.authenticator.calls.size)
        assertEquals(attemptId, controller.inFlightAttemptId)
        assertEquals(stateBefore, controller.state.value)
    }

    @Test
    fun `G26-1 a request while a PIN operation is running is a no-op and works again once it ended`() {
        val (h, controller) = fpLocked(script = listOf(PinVerifyResult.Wrong(PinDelayTier.NONE)))
        val gate = CompletableDeferred<Unit>()
        h.pinStore.verifyGate = gate
        runBlocking {
            val verifying = launch(start = CoroutineStart.UNDISPATCHED) { controller.submitUnlockPin(pin()) }
            controller.requestFingerprintUnlock()
            h.settle()
            assertNothingStarted(h, controller)
            gate.complete(Unit)
            verifying.join()
        }
        assertNothingStarted(h, controller)
        request(h, controller) // the operation ended: the guard is released
        assertEquals(1, h.authenticator.calls.size)
        assertEquals(AuthPromptCopy.PIN_FINGERPRINT, h.authenticator.calls.single().copy)
    }

    @Test
    fun `G26-1 a request is a no-op when the PIN delay reason is ATTEMPT_STATE_UNREADABLE`() {
        val (h, controller) = fpLocked(
            script = listOf(PinVerifyResult.Delayed(PinDelayReason.ATTEMPT_STATE_UNREADABLE, 1_000L))
        )
        runBlocking { controller.submitUnlockPin(pin()) }
        assertEquals(locked, controller.state.value)
        assertFalse(controller.canOfferFingerprintUnlock.value)
        request(h, controller)
        assertNothingStarted(h, controller)
    }

    // --- Spec f: an ordinary wrong-PIN backoff delay does not hide the fingerprint (INV-39) ------------------

    @Test
    fun `INV-39 during an ordinary backoff delay the fingerprint is offered, opens an attempt and a success unlocks`() {
        val (h, controller) = fpLocked(
            script = listOf(
                PinVerifyResult.Wrong(PinDelayTier.S30),
                PinVerifyResult.Delayed(PinDelayReason.FAILURES, 27_000L)
            )
        )
        runBlocking {
            controller.submitUnlockPin(pin())
            controller.submitUnlockPin(pin())
        }
        assertEquals(locked, controller.state.value)
        assertTrue(controller.pin.value.delayRemainingMillis != null)
        assertTrue(controller.canOfferFingerprintUnlock.value)
        request(h, controller)
        assertEquals(1, h.authenticator.calls.size)
        assertEquals(AuthPromptCopy.PIN_FINGERPRINT, h.authenticator.calls.single().copy)
        assertEquals(AppLockState.Authenticating(h.authenticator.calls.single().attemptId, AuthPurpose.UNLOCK), controller.state.value)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        awaitResetCount(h, 1)
    }

    // END-OF-TESTS
}
