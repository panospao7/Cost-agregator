package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStoreState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayReason
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayTier
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifyResult
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

    // --- G2-F1: the key is re-read at the tap and on a foreground (AE-76, AE-100, INV-51) ---------------------------------

    /**
     * A scriptable key gate for the re-check tests. [keyState] is answered by every read unless [blockWith] is set: then
     * every read parks (real thread, bounded) until [release] and answers [blockWith] (a read that started before the
     * view changed, so its answer may be stale). [entered] is released once per parked read. [failure] makes a read throw.
     */
    private class ScriptedKeyGate(@Volatile var keyState: PinFingerprintKeyState = PinFingerprintKeyState.READY) :
        PinFingerprintKeyGate {
        private val readCount = AtomicInteger(0)
        val reads: Int get() = readCount.get()

        @Volatile
        var blockWith: PinFingerprintKeyState? = null

        @Volatile
        var failure: Exception? = null

        val entered = Semaphore(0)
        private val released = CountDownLatch(1)

        fun release() = released.countDown()

        override fun state(): PinFingerprintKeyState {
            readCount.incrementAndGet()
            failure?.let { throw it }
            val parked = blockWith
            if (parked != null) {
                entered.release()
                released.await(5, TimeUnit.SECONDS)
                return parked
            }
            return keyState
        }
    }

    /** Polls (real time, bounded) until [condition] holds; the Keystore read and the observer run on other threads. */
    private fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(message, condition())
    }

    /** Gives an asynchronous path that must NOT change anything time to (wrongly) do so before a negative assertion. */
    private fun letAsyncWorkRun() = Thread.sleep(300)

    private fun appPinSettings(timeout: AutoLockTimeout) =
        AppLockSettingsLoadState.Loaded(
            AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = timeout, lockMode = LockMode.APP_PIN)
        )

    /** [fpLocked] over a caller-supplied [gate] (the harness always injects its own gate). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun fpLockedWithGate(
        gate: ScriptedKeyGate,
        expectOffered: Boolean = true,
        applyWrites: Boolean = false
    ): Pair<AppLockControllerHarness, AppLockController> {
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, true, false))
        store.applyFlagWrites = applyWrites
        val h = AppLockControllerHarness(appPinSettings(AutoLockTimeout.IMMEDIATE), pinStore = store)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(h.scheduler))
        val controller = AppLockController(
            h.repository, h.capabilityProvider, h.clock, mockk<PrivacyDiagnosticsEmitter>(relaxed = true), scope,
            h.authenticator, h.main, store, h.writeSerializer, gate
        )
        runBlocking { controller.resolve() }
        assertEquals(locked, controller.state.value)
        if (expectOffered) {
            awaitOffered(controller)
        } else {
            awaitUntil("the observer read the key") { gate.reads >= 1 }
            letAsyncWorkRun()
            assertFalse(controller.canOfferFingerprintUnlock.value)
        }
        return h to controller
    }

    /** UNLOCKED in APP_PIN mode with the flag on and the key READY (the harness gate), the given [timeout]. */
    private fun unlockedWithFingerprint(timeout: AutoLockTimeout): Pair<AppLockControllerHarness, AppLockController> {
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, true, false))
        store.verifyScript += PinVerifyResult.Correct
        val h = AppLockControllerHarness(appPinSettings(timeout), pinStore = store)
        val controller = h.resolvedController()
        val jobsBefore = h.activeControllerJobs().toSet()
        controller.onAppForegrounded()
        val setupJobs = h.activeControllerJobs().filter { it !in jobsBefore } // the setup re-read and fingerprint re-check
        awaitOffered(controller)
        // The setup re-check holds the single-flight flag until it finishes; a test foreground before then launches nothing.
        awaitUntil("the setup foreground jobs finished") { setupJobs.none { it.isActive } }
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin()) })
        assertEquals(AppLockState.Unlocked, controller.state.value)
        return h to controller
    }

    private fun invalidatedWrites(h: AppLockControllerHarness): Int = h.pinStore.count("recordBiometricInvalidated")

    @Test
    fun `G2-F1 INV-51 a key invalidated after the offer is not launched at the tap and is recorded once`() {
        val (h, controller) = fpLocked()
        h.fingerprintKeyGate.keyState = PinFingerprintKeyState.INVALIDATED

        request(h, controller)

        assertNothingStarted(h, controller)
        assertFalse(controller.canOfferFingerprintUnlock.value)
        assertFalse(controller.pin.value.canOfferFingerprint)
        assertEquals(1, invalidatedWrites(h))
    }

    @Test
    fun `G2-F1 control a key still READY at the tap starts the fingerprint attempt as before`() {
        val (h, controller) = fpLocked()
        val readsBefore = h.fingerprintKeyGate.reads

        request(h, controller)

        assertTrue("the key is re-read at the tap", h.fingerprintKeyGate.reads > readsBefore)
        assertEquals(1, h.authenticator.calls.size)
        assertEquals(AuthPromptCopy.PIN_FINGERPRINT, h.authenticator.calls.single().copy)
        assertEquals(AppLockState.Authenticating(h.authenticator.calls.single().attemptId, AuthPurpose.UNLOCK), controller.state.value)
        assertEquals(0, invalidatedWrites(h))
    }

    @Test
    fun `G2-F1 INV-51 a foreground hides the stale fingerprint button without a tap and records the invalidation once`() {
        val (h, controller) = fpLocked()
        h.fingerprintKeyGate.keyState = PinFingerprintKeyState.INVALIDATED

        controller.onAppForegrounded()

        awaitUntil("the button is hidden by the foreground re-check") { !controller.canOfferFingerprintUnlock.value }
        letAsyncWorkRun()
        assertFalse(controller.pin.value.canOfferFingerprint)
        assertEquals(1, invalidatedWrites(h))
        assertEquals(locked, controller.state.value)
        assertEquals(0, h.authenticator.calls.size)
        assertNull(controller.inFlightAttemptId)
    }

    @Test
    fun `G2-F1 the foreground re-check does not change the timeout lock evaluation`() {
        val results = mutableMapOf<PinFingerprintKeyState, AppLockState>()
        for (keyState in listOf(PinFingerprintKeyState.READY, PinFingerprintKeyState.INVALIDATED)) {
            val (h, controller) = unlockedWithFingerprint(AutoLockTimeout.M1)
            h.fingerprintKeyGate.keyState = keyState
            controller.onAppBackgrounded()
            h.clock.advance(AutoLockTimeout.M1.millis + 1_000L)

            controller.onAppForegrounded()

            results[keyState] = controller.state.value
            if (keyState == PinFingerprintKeyState.INVALIDATED) {
                awaitUntil("the invalidated key hides the button") { !controller.canOfferFingerprintUnlock.value }
                letAsyncWorkRun()
                assertEquals(1, invalidatedWrites(h))
            } else {
                letAsyncWorkRun()
                assertTrue(controller.canOfferFingerprintUnlock.value)
                assertEquals(0, invalidatedWrites(h))
            }
        }
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), results[PinFingerprintKeyState.READY])
        assertEquals(results[PinFingerprintKeyState.READY], results[PinFingerprintKeyState.INVALIDATED])
    }

    @Test
    fun `G2-F1 an invalidated key does not lock early when the background is shorter than the timeout`() {
        val (h, controller) = unlockedWithFingerprint(AutoLockTimeout.M1)
        h.fingerprintKeyGate.keyState = PinFingerprintKeyState.INVALIDATED
        controller.onAppBackgrounded()
        h.clock.advance(AutoLockTimeout.M1.millis - 5_000L)

        controller.onAppForegrounded()

        awaitUntil("the invalidation is recorded by the foreground re-check") { invalidatedWrites(h) == 1 }
        letAsyncWorkRun()
        assertEquals(1, invalidatedWrites(h))
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(0, h.authenticator.calls.size)
    }

    @Test
    fun `G2-F1 a foreground with the fingerprint flag off reads the key zero times`() {
        val (h, controller) = fpLocked(biometric = false)
        val readsBefore = h.fingerprintKeyGate.reads

        controller.onAppForegrounded()
        letAsyncWorkRun()
        request(h, controller)

        assertEquals(readsBefore, h.fingerprintKeyGate.reads)
        assertEquals(0, invalidatedWrites(h))
        assertNothingStarted(h, controller)
        assertFalse(controller.canOfferFingerprintUnlock.value)
    }

    @Test
    fun `G2-F1 a transient UNAVAILABLE key at the tap starts nothing records nothing and keeps the flag on`() {
        val (h, controller) = fpLocked()
        h.fingerprintKeyGate.keyState = PinFingerprintKeyState.UNAVAILABLE

        request(h, controller)

        assertNothingStarted(h, controller)
        assertFalse(controller.canOfferFingerprintUnlock.value)
        assertEquals(0, invalidatedWrites(h))
        assertEquals(0, h.pinStore.count("setBiometricEnabled"))
        assertTrue(controller.pin.value.fingerprintEnabled)
    }

    @Test
    fun `G2-F1 self-heal after a transient UNAVAILABLE the next foreground offers the fingerprint again`() {
        val (h, controller) = fpLocked()
        h.fingerprintKeyGate.keyState = PinFingerprintKeyState.UNAVAILABLE
        request(h, controller)
        assertFalse(controller.canOfferFingerprintUnlock.value)

        h.fingerprintKeyGate.keyState = PinFingerprintKeyState.READY
        controller.onAppForegrounded()

        awaitUntil("the foreground re-check offers the fingerprint again") { controller.canOfferFingerprintUnlock.value }
        assertTrue(controller.pin.value.canOfferFingerprint)
        assertEquals(0, invalidatedWrites(h))
        request(h, controller)
        assertEquals(1, h.authenticator.calls.size)
        assertEquals(AuthPromptCopy.PIN_FINGERPRINT, h.authenticator.calls.single().copy)
    }

    @Test
    fun `G2-F1 a throwing key read at the tap is fail-closed with nothing recorded and the flag untouched`() {
        val gate = ScriptedKeyGate()
        val (h, controller) = fpLockedWithGate(gate)
        gate.failure = IllegalStateException("synthetic keystore failure")

        request(h, controller)

        assertNothingStarted(h, controller)
        assertFalse(controller.canOfferFingerprintUnlock.value)
        assertEquals(0, invalidatedWrites(h))
        assertEquals(0, h.pinStore.count("setBiometricEnabled"))
        assertTrue(controller.pin.value.fingerprintEnabled)
    }

    @Test
    fun `G2-F1 a stale re-check that read INVALIDATED does not hide a newer view the observer found READY`() {
        val gate = ScriptedKeyGate(PinFingerprintKeyState.UNAVAILABLE)
        val (h, controller) = fpLockedWithGate(gate, expectOffered = false)
        gate.keyState = PinFingerprintKeyState.READY
        gate.blockWith = PinFingerprintKeyState.INVALIDATED // the re-check for the old view parks with a stale answer
        controller.onAppForegrounded()
        assertTrue("the re-check reached the key read", gate.entered.tryAcquire(5, TimeUnit.SECONDS))
        gate.blockWith = null

        h.pinStore.stateFlow.value = AppPinStoreState(PinVerifierState.PRESENT, true, true) // a newer committed view
        awaitUntil("the observer applied the newer view with a READY key") { controller.canOfferFingerprintUnlock.value }
        gate.release()
        letAsyncWorkRun()

        assertTrue("the flag follows the observer", controller.canOfferFingerprintUnlock.value)
        assertTrue(controller.pin.value.canOfferFingerprint)
    }

    @Test
    fun `G2-F1 a stale re-check that read READY does not offer a newer view the observer found INVALIDATED`() {
        val gate = ScriptedKeyGate()
        val (h, controller) = fpLockedWithGate(gate)
        gate.blockWith = PinFingerprintKeyState.READY // the re-check for the old view parks with a stale answer
        controller.onAppForegrounded()
        assertTrue("the re-check reached the key read", gate.entered.tryAcquire(5, TimeUnit.SECONDS))
        gate.blockWith = null
        gate.keyState = PinFingerprintKeyState.INVALIDATED

        h.pinStore.stateFlow.value = AppPinStoreState(PinVerifierState.PRESENT, true, true) // a newer committed view
        awaitUntil("the observer applied the newer view with an INVALIDATED key") { !controller.canOfferFingerprintUnlock.value }
        gate.release()
        letAsyncWorkRun()

        assertFalse("the flag follows the observer", controller.canOfferFingerprintUnlock.value)
        assertFalse(controller.pin.value.canOfferFingerprint)
        assertEquals(1, invalidatedWrites(h)) // only the observer's single write
    }

    @Test
    fun `G2-F1 a double tap while the re-check is blocked starts at most one attempt`() {
        val gate = ScriptedKeyGate()
        val (h, controller) = fpLockedWithGate(gate)
        val readsBefore = gate.reads
        gate.blockWith = PinFingerprintKeyState.READY

        runBlocking {
            val taps = List(2) { launch(Dispatchers.Default) { controller.requestFingerprintUnlock() } }
            try {
                assertTrue("a re-check reached the key read", gate.entered.tryAcquire(5, TimeUnit.SECONDS))
                // Single-flight (MINOR-2): the second tap returns without a read, so no second read parks.
                assertFalse("no second read may park", gate.entered.tryAcquire(500, TimeUnit.MILLISECONDS))
                assertEquals(readsBefore + 1, gate.reads)
                h.settle()
                assertEquals(locked, controller.state.value)
                assertNull(controller.inFlightAttemptId)
                assertEquals(0, h.authenticator.calls.size)
            } finally {
                gate.release()
            }
            taps.forEach { it.join() }
        }
        h.settle()

        assertEquals(1, h.authenticator.calls.size)
        assertEquals(
            AppLockState.Authenticating(h.authenticator.calls.single().attemptId, AuthPurpose.UNLOCK),
            controller.state.value
        )
        assertEquals(0, invalidatedWrites(h))
    }

    @Test
    fun `G2-F1 cancelling the caller during a blocked re-check propagates and starts nothing`() {
        val gate = ScriptedKeyGate()
        val (h, controller) = fpLockedWithGate(gate)
        gate.blockWith = PinFingerprintKeyState.READY
        val thrown = AtomicReference<Throwable?>(null)

        runBlocking {
            val tap = launch(Dispatchers.IO) {
                try {
                    controller.requestFingerprintUnlock()
                } catch (e: CancellationException) {
                    thrown.set(e)
                    throw e
                }
            }
            try {
                assertTrue("the re-check reached the key read", gate.entered.tryAcquire(5, TimeUnit.SECONDS))
                tap.cancel()
            } finally {
                gate.release()
            }
            tap.join()
        }
        h.settle()

        assertTrue("the cancellation must not be swallowed", thrown.get() is CancellationException)
        assertNothingStarted(h, controller)
        assertNull(controller.inFlightAttemptId)
        assertEquals(0, invalidatedWrites(h))
        assertTrue("the offer is unchanged", controller.canOfferFingerprintUnlock.value)
        assertTrue(controller.pin.value.fingerprintEnabled)

        gate.blockWith = null // the controller is still usable afterwards
        request(h, controller)
        assertEquals(1, h.authenticator.calls.size)
    }

    // --- G2-F1 review minors: idempotent record (MINOR-1/3) and the shared single-flight re-check (MINOR-2) ----------------

    @Test
    fun `G2-F1 INV-51 end to end a foreground on an invalidated key flips the flag off and raises the notice once`() {
        val gate = ScriptedKeyGate()
        val (h, controller) = fpLockedWithGate(gate, applyWrites = true)
        val readsBefore = gate.reads
        gate.keyState = PinFingerprintKeyState.INVALIDATED

        controller.onAppForegrounded()

        val expectedStore = AppPinStoreState(PinVerifierState.PRESENT, false, true)
        awaitUntil("the store applied the single invalidation write") { h.pinStore.stateFlow.value == expectedStore }
        awaitUntil("the controller took the re-emitted view") {
            !controller.canOfferFingerprintUnlock.value && !controller.pin.value.fingerprintEnabled
        }
        letAsyncWorkRun()

        assertEquals(expectedStore, h.pinStore.stateFlow.value) // notice pending, flag off
        assertFalse(controller.canOfferFingerprintUnlock.value)
        assertFalse(controller.pin.value.canOfferFingerprint)
        assertFalse(controller.pin.value.fingerprintEnabled)
        assertEquals(1, invalidatedWrites(h))
        assertEquals(readsBefore + 1, gate.reads) // the re-emitted flag-off view reads no key
        request(h, controller)
        assertNothingStarted(h, controller)
        assertEquals(1, invalidatedWrites(h))
        assertEquals(readsBefore + 1, gate.reads)
    }

    @Test
    fun `G2-F1 INV-51 a tap while a foreground re-check of an invalidated key is blocked adds no read and records once`() {
        val gate = ScriptedKeyGate()
        val (h, controller) = fpLockedWithGate(gate)
        val readsBefore = gate.reads
        gate.blockWith = PinFingerprintKeyState.INVALIDATED

        controller.onAppForegrounded()
        assertTrue("the foreground re-check reached the key read", gate.entered.tryAcquire(5, TimeUnit.SECONDS))
        runBlocking { controller.requestFingerprintUnlock() } // returns at once: the re-check is in flight
        assertFalse("no second read may be in flight", gate.entered.tryAcquire(500, TimeUnit.MILLISECONDS))
        assertEquals(readsBefore + 1, gate.reads)
        assertEquals(0, invalidatedWrites(h))
        gate.release()

        awaitUntil("the single invalidation is recorded") { invalidatedWrites(h) == 1 }
        letAsyncWorkRun()
        assertEquals(1, invalidatedWrites(h))
        assertEquals(readsBefore + 1, gate.reads)
        h.settle()
        assertNothingStarted(h, controller)
        assertFalse(controller.canOfferFingerprintUnlock.value)
    }

    @Test
    fun `G2-F1 INV-51 a foreground while a tap re-check of an invalidated key is blocked adds no read and records once`() {
        val gate = ScriptedKeyGate()
        val (h, controller) = fpLockedWithGate(gate)
        val readsBefore = gate.reads
        gate.blockWith = PinFingerprintKeyState.INVALIDATED

        runBlocking {
            val tap = launch(Dispatchers.Default) { controller.requestFingerprintUnlock() }
            try {
                assertTrue("the tap re-check reached the key read", gate.entered.tryAcquire(5, TimeUnit.SECONDS))
                controller.onAppForegrounded() // launches nothing: the tap's re-check holds the single-flight
                assertFalse("no second read may be in flight", gate.entered.tryAcquire(500, TimeUnit.MILLISECONDS))
                assertEquals(readsBefore + 1, gate.reads)
            } finally {
                gate.release()
            }
            tap.join()
        }
        h.settle()

        letAsyncWorkRun()
        assertEquals(1, invalidatedWrites(h))
        assertEquals(readsBefore + 1, gate.reads)
        assertNothingStarted(h, controller)
        assertFalse(controller.canOfferFingerprintUnlock.value)
    }

    @Test
    fun `G2-F1 two foregrounds with a blocked gate read once and a later foreground reads again after it is released`() {
        val gate = ScriptedKeyGate()
        val (_, controller) = fpLockedWithGate(gate)
        val readsBefore = gate.reads
        gate.blockWith = PinFingerprintKeyState.READY

        controller.onAppForegrounded()
        assertTrue("the first re-check reached the key read", gate.entered.tryAcquire(5, TimeUnit.SECONDS))
        controller.onAppForegrounded()
        assertFalse("no second read may be in flight", gate.entered.tryAcquire(500, TimeUnit.MILLISECONDS))
        assertEquals(readsBefore + 1, gate.reads)

        gate.release()
        gate.blockWith = null
        letAsyncWorkRun() // the first re-check finished and released the flag
        assertEquals(readsBefore + 1, gate.reads)
        controller.onAppForegrounded()

        awaitUntil("a later foreground reads the key again") { gate.reads >= readsBefore + 2 }
        letAsyncWorkRun()
        assertEquals(readsBefore + 2, gate.reads)
        assertTrue("the offer is unchanged by READY re-checks", controller.canOfferFingerprintUnlock.value)
    }

    @Test
    fun `G2-F1 a tap during a blocked foreground re-check starts no attempt and a tap after it completes does`() {
        val gate = ScriptedKeyGate()
        val (h, controller) = fpLockedWithGate(gate)
        val readsBefore = gate.reads
        gate.blockWith = PinFingerprintKeyState.READY

        controller.onAppForegrounded()
        assertTrue("the foreground re-check reached the key read", gate.entered.tryAcquire(5, TimeUnit.SECONDS))
        runBlocking { controller.requestFingerprintUnlock() }
        h.settle()

        assertNothingStarted(h, controller)
        assertEquals(readsBefore + 1, gate.reads)
        assertFalse("the tap issued no read of its own", gate.entered.tryAcquire(300, TimeUnit.MILLISECONDS))

        gate.release()
        gate.blockWith = null
        letAsyncWorkRun()
        assertNothingStarted(h, controller) // a foreground re-check never opens an attempt
        assertTrue(controller.canOfferFingerprintUnlock.value)

        request(h, controller) // the flag is released: the tap re-checks and starts the attempt
        assertEquals(readsBefore + 2, gate.reads)
        assertEquals(1, h.authenticator.calls.size)
        assertEquals(AuthPromptCopy.PIN_FINGERPRINT, h.authenticator.calls.single().copy)
    }

    @Test
    fun `G2-F1 the single-flight is released after a throwing gate read on the tap path`() {
        val gate = ScriptedKeyGate()
        val (h, controller) = fpLockedWithGate(gate)
        val readsBefore = gate.reads
        gate.failure = IllegalStateException("synthetic keystore failure")

        request(h, controller)
        assertEquals(readsBefore + 1, gate.reads)
        assertNothingStarted(h, controller)
        assertFalse(controller.canOfferFingerprintUnlock.value)

        // The failure hid the offer, so the next re-check is a foreground (self-heal); it must not be refused as in flight.
        gate.failure = null
        gate.keyState = PinFingerprintKeyState.READY
        controller.onAppForegrounded()

        awaitUntil("the foreground re-check ran and offers the fingerprint again") { controller.canOfferFingerprintUnlock.value }
        assertEquals(readsBefore + 2, gate.reads)
        request(h, controller)
        assertEquals(1, h.authenticator.calls.size)
        assertEquals(0, invalidatedWrites(h))
    }

    @Test
    fun `G2-F1 the single-flight is released after a throwing gate read on the foreground path`() {
        val gate = ScriptedKeyGate()
        val (h, controller) = fpLockedWithGate(gate)
        val readsBefore = gate.reads
        gate.failure = IllegalStateException("synthetic keystore failure")

        controller.onAppForegrounded()
        awaitUntil("the foreground re-check hid the offer") { !controller.canOfferFingerprintUnlock.value }
        assertEquals(readsBefore + 1, gate.reads)
        letAsyncWorkRun() // the failed re-check has released the flag

        gate.failure = null
        gate.keyState = PinFingerprintKeyState.READY
        controller.onAppForegrounded()

        awaitUntil("a later foreground re-checks and offers the fingerprint again") { controller.canOfferFingerprintUnlock.value }
        assertEquals(readsBefore + 2, gate.reads)
        assertEquals(0, invalidatedWrites(h))
        assertNothingStarted(h, controller)
    }

    // END-OF-TESTS
}
