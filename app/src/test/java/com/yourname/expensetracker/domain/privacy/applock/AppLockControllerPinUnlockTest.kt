package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStoreState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayReason
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayTier
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifyResult
import com.yourname.expensetracker.domain.privacy.applock.pin.VerifierUnreadableCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S25 PIN unlock on the real controller over fakes (AE-72, 74, 77 to 83, 91, 92, 94 to 96, 99, 107, 110, 119; AMD-4;
 * OD-15; INV-39, INV-41, INV-42, INV-38). The PIN store is a scripted fake; the settings store is the harness fake.
 */
class AppLockControllerPinUnlockTest {

    private fun pin(text: String = "482916"): CharArray = text.toCharArray()

    /** A resolved controller in LOCKED(COLD_START) with the lock on, IMMEDIATE timeout, [storedMode] and a [verifier] in the PIN store. */
    private fun pinLocked(
        verifier: PinVerifierState = PinVerifierState.PRESENT,
        storedMode: LockMode = LockMode.APP_PIN,
        script: List<PinVerifyResult> = emptyList(),
        capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE
    ): Pair<AppLockControllerHarness, AppLockController> {
        val store = FakeAppPinStore(AppPinStoreState(verifier, false, false))
        store.verifyScript += script
        val settings = AppLockPrivacySettings(
            appLockEnabled = true,
            autoLockTimeout = AutoLockTimeout.IMMEDIATE,
            lockMode = storedMode
        )
        val h = AppLockControllerHarness(AppLockSettingsLoadState.Loaded(settings), capability, pinStore = store)
        val controller = h.resolvedController()
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        return h to controller
    }

    // --- Effective lock mode (AMD-4, INV-41) ------------------------------------------------------

    @Test
    fun `AMD-4 verifier PRESENT gives APP_PIN even when the stored mode says DEVICE_AUTH`() {
        val (_, controller) = pinLocked(PinVerifierState.PRESENT, LockMode.DEVICE_AUTH)
        assertEquals(EffectiveLockMode.APP_PIN, controller.pin.value.effectiveLockMode)
    }

    @Test
    fun `AMD-4 verifier UNREADABLE still gives APP_PIN and publishes the recovery view`() {
        val (_, controller) = pinLocked(PinVerifierState.UNREADABLE, LockMode.DEVICE_AUTH)
        assertEquals(EffectiveLockMode.APP_PIN, controller.pin.value.effectiveLockMode)
        assertEquals(PinRecovery.RECORD_UNREADABLE, controller.pin.value.recovery)
    }

    @Test
    fun `AMD-4 verifier ABSENT gives DEVICE_AUTH even when the stored mode says APP_PIN`() {
        val (h, controller) = pinLocked(PinVerifierState.ABSENT, LockMode.APP_PIN)
        assertEquals(EffectiveLockMode.DEVICE_AUTH, controller.pin.value.effectiveLockMode)
        assertNull(controller.pin.value.recovery)
        // The device path is live: a tap starts the normal device-auth attempt.
        assertTrue(controller.onUnlockTapped() is UnlockTapResult.Started)
        h.settle()
        assertEquals(1, h.authenticator.calls.size)
    }

    @Test
    fun `AMD-4 an unknown verifier state uses the stored mode (fail-closed rule of the pure function)`() {
        assertEquals(EffectiveLockMode.APP_PIN, EffectivePrivacyValues.effectiveLockMode(null, LockMode.APP_PIN))
        assertEquals(EffectiveLockMode.DEVICE_AUTH, EffectivePrivacyValues.effectiveLockMode(null, LockMode.DEVICE_AUTH))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `G25-2 a PIN store read that outlives T_resolve fails closed to UNREADABLE with APP_PIN and a retry offer`() = runTest {
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, false, false))
        val readGate = CompletableDeferred<Unit>()
        store.readGate = readGate
        // The live observation is down too, so only the timed-out read decides what the controller knows.
        store.failures["observeState"] = IllegalStateException("synthetic")
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE), scheduler = testScheduler, pinStore = store)
        val controller = h.newController()
        val resolving = launch { controller.resolve() }
        runCurrent()
        advanceTimeBy(AppLockController.RESOLVE_TIMEOUT_MILLIS + 1)
        runCurrent()
        resolving.join()
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        // G25-2: the verifier was never learned, so the PIN state is UNREADABLE: effective APP_PIN, never DEVICE_AUTH.
        assertEquals(EffectiveLockMode.APP_PIN, controller.pin.value.effectiveLockMode)
        assertEquals(PinRecovery.RECORD_UNREADABLE, controller.pin.value.recovery)
        assertTrue(controller.pin.value.recovery!!.retryOffered)
        // No device-auth fallback: a tap starts nothing, and an unavailable verify entry does not unlock.
        assertEquals(UnlockTapResult.NotLocked, controller.onUnlockTapped())
        assertEquals(0, h.authenticator.calls.size)
        store.verifyScript += PinVerifyResult.Unavailable
        assertEquals(PinUnlockResult.Rejected(PinRejection.Unavailable), controller.submitUnlockPin(pin()))
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        // Recovery by retry: once the store answers, the fresh read replaces the fail-closed view.
        readGate.complete(Unit)
        store.failures.clear()
        controller.retryPinRecovery()
        assertNull(controller.pin.value.recovery)
        assertEquals(EffectiveLockMode.APP_PIN, controller.pin.value.effectiveLockMode) // the verifier is PRESENT
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
    }

    // --- submitUnlockPin outcomes (T23 to T25, INV-39) ---------------------------------------------

    @Test
    fun `T23 a Correct PIN unlocks through the normal unlock transition and clears the pending background`() {
        val (h, controller) = pinLocked(script = listOf(PinVerifyResult.Correct))
        controller.onAppForegrounded() // LOW-2: the pending background is cleared only while the app is in the foreground
        val entered = pin()
        val result = runBlocking { controller.submitUnlockPin(entered) }
        assertEquals(PinUnlockResult.Unlocked, result)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertNull(controller.pendingBackgroundAtMillis)
        assertTrue(h.emitted.contains(PrivacyDiagnosticCode.LOCK_UNLOCKED))
        assertEquals(0, h.authenticator.calls.size)
    }

    @Test
    fun `T24 a Wrong PIN stays LOCKED with the same cause and opens no attempt`() {
        val (h, controller) = pinLocked(script = listOf(PinVerifyResult.Wrong(PinDelayTier.S30)))
        val result = runBlocking { controller.submitUnlockPin(pin("111112")) }
        assertEquals(PinUnlockResult.Rejected(PinRejection.Wrong(PinDelayTier.S30)), result)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertNull(controller.inFlightAttemptId)
        assertFalse(h.emitted.contains(PrivacyDiagnosticCode.LOCK_UNLOCKED))
    }

    @Test
    fun `T25 a Delayed answer stays LOCKED and publishes the remaining time in the PinPresentation`() {
        val (_, controller) = pinLocked(script = listOf(PinVerifyResult.Delayed(PinDelayReason.FAILURES, 27_000L)))
        val result = runBlocking { controller.submitUnlockPin(pin()) }
        assertEquals(PinUnlockResult.Rejected(PinRejection.Delayed(PinDelayReason.FAILURES, 27_000L)), result)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertEquals(27_000L, controller.pin.value.delayRemainingMillis)
    }

    @Test
    fun `AE-94 an Unavailable attempt state stays LOCKED, is retryable and shows no recovery view`() {
        val (_, controller) = pinLocked(script = listOf(PinVerifyResult.Unavailable, PinVerifyResult.Correct))
        val first = runBlocking { controller.submitUnlockPin(pin()) }
        assertEquals(PinUnlockResult.Rejected(PinRejection.Unavailable), first)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertNull(controller.pin.value.recovery)
        // Retryable: the very next entry is evaluated and may unlock.
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin()) })
        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    @Test
    fun `OD-15 VerifierUnreadable KEY_MISSING publishes the recovery view without a retry offer`() {
        val (_, controller) = pinLocked(
            script = listOf(PinVerifyResult.VerifierUnreadable(VerifierUnreadableCause.KEY_MISSING))
        )
        val result = runBlocking { controller.submitUnlockPin(pin()) }
        assertEquals(PinUnlockResult.Rejected(PinRejection.VerifierUnreadable(VerifierUnreadableCause.KEY_MISSING)), result)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertEquals(PinRecovery.KEY_MISSING, controller.pin.value.recovery)
        assertFalse(controller.pin.value.recovery!!.retryOffered)
    }

    @Test
    fun `OD-15 VerifierUnreadable KEYSTORE_ERROR publishes the recovery view with a retry offer`() {
        val (_, controller) = pinLocked(
            script = listOf(PinVerifyResult.VerifierUnreadable(VerifierUnreadableCause.KEYSTORE_ERROR))
        )
        runBlocking { controller.submitUnlockPin(pin()) }
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertEquals(PinRecovery.KEYSTORE_ERROR, controller.pin.value.recovery)
        assertTrue(controller.pin.value.recovery!!.retryOffered)
    }

    @Test
    fun `INV-41 no rejected outcome ever unlocks and none opens a device-auth attempt`() {
        val rejected = listOf(
            PinVerifyResult.Wrong(PinDelayTier.NONE),
            PinVerifyResult.Delayed(PinDelayReason.ATTEMPT_STATE_UNREADABLE, 1_000L),
            PinVerifyResult.Unavailable,
            PinVerifyResult.VerifierUnreadable(VerifierUnreadableCause.RECORD_UNREADABLE)
        )
        val (h, controller) = pinLocked(script = rejected)
        repeat(rejected.size) {
            assertTrue(runBlocking { controller.submitUnlockPin(pin()) } is PinUnlockResult.Rejected)
            assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        }
        assertEquals(0, h.authenticator.calls.size)
    }

    @Test
    fun `AMD-4 an Absent answer from verify falls back to DEVICE_AUTH and reports NotInPinMode`() {
        val (_, controller) = pinLocked(script = listOf(PinVerifyResult.Absent))
        val result = runBlocking { controller.submitUnlockPin(pin()) }
        assertEquals(PinUnlockResult.NotInPinMode, result)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertEquals(EffectiveLockMode.DEVICE_AUTH, controller.pin.value.effectiveLockMode)
    }

    @Test
    fun `T23 a PIN submitted outside LOCKED is not evaluated`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED, pinStore = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, false, false)))
        val controller = h.resolvedController()
        assertEquals(AppLockState.Disabled, controller.state.value)
        val entered = pin()
        assertEquals(PinUnlockResult.NotInPinMode, runBlocking { controller.submitUnlockPin(entered) })
        assertEquals(0, h.pinStore.count("verify"))
        assertTrue(entered.all { it == '\u0000' })
    }

    // --- The CharArray is owned by the call (INV-38) ----------------------------------------------

    @Test
    fun `INV-38 the PIN array is zeroed after Correct, Wrong, Delayed, Unavailable and Unreadable`() {
        val results = listOf(
            PinVerifyResult.Correct,
            PinVerifyResult.Wrong(PinDelayTier.NONE),
            PinVerifyResult.Delayed(PinDelayReason.FAILURES, 3_000L),
            PinVerifyResult.Unavailable,
            PinVerifyResult.VerifierUnreadable(VerifierUnreadableCause.KEYSTORE_ERROR)
        )
        for (scripted in results) {
            val (h, controller) = pinLocked(script = listOf(scripted))
            val entered = pin()
            runBlocking { controller.submitUnlockPin(entered) }
            assertTrue("array not zeroed after $scripted", entered.all { it == '\u0000' })
            // The store saw the real digits (the fake does not zero), and the caller's array is the very one it got.
            val call = h.pinStore.callsOf("verify").single()
            assertEquals("482916", call.contentAtCall.single())
            assertTrue(call.allZeroedNow())
        }
    }

    @Test
    fun `INV-38 the PIN array is zeroed when the call is cancelled and the cancellation propagates`() = runBlocking {
        val (h, controller) = pinLocked(script = listOf(PinVerifyResult.Correct))
        h.pinStore.verifyGate = CompletableDeferred()
        val entered = pin()
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.DEFAULT) { controller.submitUnlockPin(entered) }
        assertEquals(1, h.pinStore.count("verify"))
        assertFalse(entered.all { it == '\u0000' })
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(entered.all { it == '\u0000' })
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        // The in-flight guard was released: a later entry is evaluated.
        h.pinStore.verifyGate = null
        assertEquals(PinUnlockResult.Unlocked, controller.submitUnlockPin(pin()))
    }

    @Test
    fun `INV-38 a CancellationException thrown by the store propagates unchanged and the array is zeroed`() {
        val (h, controller) = pinLocked()
        val boom = CancellationException("synthetic")
        h.pinStore.failures["verify"] = boom
        val entered = pin()
        var caught: CancellationException? = null
        runBlocking {
            try {
                controller.submitUnlockPin(entered)
                fail("expected a CancellationException")
            } catch (e: CancellationException) {
                caught = e
            }
        }
        assertTrue("the store's CancellationException was swallowed", caught === boom)
        assertTrue(entered.all { it == '\u0000' })
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
    }

    @Test
    fun `T25 the published delay is cleared by the next non-delayed answer`() {
        val (_, controller) = pinLocked(
            script = listOf(
                PinVerifyResult.Delayed(PinDelayReason.FAILURES, 5_000L),
                PinVerifyResult.Wrong(PinDelayTier.NONE)
            )
        )
        runBlocking { controller.submitUnlockPin(pin()) }
        assertNotNull(controller.pin.value.delayRemainingMillis)
        runBlocking { controller.submitUnlockPin(pin()) }
        assertNull(controller.pin.value.delayRemainingMillis)
    }

    // --- APP_PIN mode and the device path (AMD-4) -------------------------------------------------

    @Test
    fun `AMD-4 APP_PIN mode skips the device capability re-check and never becomes inert`() {
        val (h, controller) = pinLocked(capability = DeviceAuthCapability.NO_CREDENTIAL)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        h.pinStore.verifyScript += PinVerifyResult.Correct
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin()) })
        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    @Test
    fun `AMD-4 onUnlockTapped returns NotLocked in APP_PIN mode and starts no prompt`() {
        val (h, controller) = pinLocked(capability = DeviceAuthCapability.NO_CREDENTIAL)
        assertEquals(UnlockTapResult.NotLocked, controller.onUnlockTapped())
        h.settle()
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertNull(controller.inFlightAttemptId)
        assertEquals(0, h.authenticator.calls.size)
    }

    // --- retryPinRecovery (OD-15, G24-5) ----------------------------------------------------------

    @Test
    fun `OD-15 retryPinRecovery reads nothing when no recovery view is shown`() {
        val (h, controller) = pinLocked()
        val reads = h.pinStore.count("readState")
        runBlocking { controller.retryPinRecovery() }
        assertEquals(reads, h.pinStore.count("readState"))
    }

    @Test
    fun `OD-15 retryPinRecovery reads nothing for KEY_MISSING because retry is not offered`() {
        val (h, controller) = pinLocked(
            script = listOf(PinVerifyResult.VerifierUnreadable(VerifierUnreadableCause.KEY_MISSING))
        )
        runBlocking { controller.submitUnlockPin(pin()) }
        val reads = h.pinStore.count("readState")
        runBlocking { controller.retryPinRecovery() }
        assertEquals(reads, h.pinStore.count("readState"))
        assertEquals(PinRecovery.KEY_MISSING, controller.pin.value.recovery)
    }

    @Test
    fun `OD-15 retryPinRecovery re-reads for KEYSTORE_ERROR and clears the view once the store reads a verifier`() {
        val (h, controller) = pinLocked(
            script = listOf(PinVerifyResult.VerifierUnreadable(VerifierUnreadableCause.KEYSTORE_ERROR))
        )
        runBlocking { controller.submitUnlockPin(pin()) }
        val reads = h.pinStore.count("readState")
        runBlocking { controller.retryPinRecovery() }
        assertEquals(reads + 1, h.pinStore.count("readState"))
        assertNull(controller.pin.value.recovery)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
    }

    @Test
    fun `OD-15 retryPinRecovery never unlocks and keeps the view while the store is still unreadable`() {
        val (h, controller) = pinLocked(PinVerifierState.UNREADABLE)
        runBlocking { controller.retryPinRecovery() }
        assertEquals(2, h.pinStore.count("readState"))
        assertEquals(PinRecovery.RECORD_UNREADABLE, controller.pin.value.recovery)
        assertEquals(EffectiveLockMode.APP_PIN, controller.pin.value.effectiveLockMode)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
    }

    @Test
    fun `G24-5 after observeState emits UNREADABLE and ends a retry re-collects and recovers when the store becomes readable`() {
        val (h, controller) = pinLocked(PinVerifierState.UNREADABLE)
        assertEquals(1, h.pinStore.observeCalls)
        assertEquals(PinRecovery.RECORD_UNREADABLE, controller.pin.value.recovery)
        // The store becomes readable, but the first collection ended with UNREADABLE: nothing tells the controller yet.
        h.pinStore.setVerifier(PinVerifierState.PRESENT)
        assertEquals(PinRecovery.RECORD_UNREADABLE, controller.pin.value.recovery)
        runBlocking { controller.retryPinRecovery() }
        assertNull(controller.pin.value.recovery)
        assertEquals(2, h.pinStore.observeCalls)
        // The second collection is live: a later committed change reaches the controller without another read.
        val reads = h.pinStore.count("readState")
        h.pinStore.setVerifier(PinVerifierState.ABSENT)
        assertEquals(EffectiveLockMode.DEVICE_AUTH, controller.pin.value.effectiveLockMode)
        assertEquals(reads, h.pinStore.count("readState"))
    }

    // --- Bounded views and the frozen fingerprint hook --------------------------------------------

    @Test
    fun `INV-38 toString of presentations, rejections and results carries no PIN digits or counts`() {
        val entered = "482916"
        val presentation = PinPresentation(EffectiveLockMode.APP_PIN, PinStep.NONE, PinRecovery.KEYSTORE_ERROR, false, 27_000L)
        assertFalse(presentation.toString().any { it.isDigit() })
        val delayed = PinRejection.Delayed(PinDelayReason.FAILURES, 27_000L)
        assertFalse(delayed.toString().any { it.isDigit() })
        assertFalse(PinUnlockResult.Rejected(delayed).toString().contains("27000"))

        val (_, controller) = pinLocked(script = listOf(PinVerifyResult.Delayed(PinDelayReason.FAILURES, 27_000L)))
        val result = runBlocking { controller.submitUnlockPin(entered.toCharArray()) }
        val rendered = listOf(result, controller.state.value, controller.pin.value, controller.notice.value).joinToString(" ")
        assertFalse(rendered.contains(entered))
        assertFalse(rendered.contains("27000"))
        assertFalse(rendered.contains("27_000"))
        assertFalse(controller.pin.value.toString().any { it.isDigit() })
    }

    @Test
    fun `D-I2-7 amended the fingerprint request is a no-op while the flag is off and never resets the counter or unlocks`() {
        val (h, controller) = pinLocked() // biometricEnabled is false in this store
        assertFalse(controller.canOfferFingerprintUnlock.value)
        assertFalse(controller.pin.value.canOfferFingerprint)
        runBlocking { controller.requestFingerprintUnlock() }
        h.settle()
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertNull(controller.inFlightAttemptId)
        assertEquals(0, h.authenticator.calls.size)
        assertEquals(0, h.pinStore.count("resetAttempts"))
    }
}
