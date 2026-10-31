package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStoreState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayTier
import com.yourname.expensetracker.domain.privacy.applock.pin.PinStoreWriteResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifyResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S26b (INV-40, INV-51, INV-54[2], AMD-2, AE-120): turning fingerprint unlock on and off in effective APP_PIN mode.
 * Real controller over the harness fakes; the key gate, PIN store and prompt are scriptable fakes. Synthetic PINs only.
 */
class AppLockControllerFingerprintEnableTest {

    private fun pin(text: String = "482916"): CharArray = text.toCharArray()

    private fun CharArray.isZeroed(): Boolean = all { it == '\u0000' }

    /** UNLOCKED in effective APP_PIN mode ([timeout]); the unlock traffic of the rig is cleared afterwards. */
    private fun pinModeRig(
        timeout: AutoLockTimeout = AutoLockTimeout.M15,
        biometricEnabled: Boolean = false,
        noticePending: Boolean = false
    ): Pair<AppLockControllerHarness, AppLockController> {
        val settings = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = timeout, lockMode = LockMode.APP_PIN)
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, biometricEnabled, noticePending))
        store.applyFlagWrites = true
        store.verifyScript += PinVerifyResult.Correct
        val h = AppLockControllerHarness(AppLockSettingsLoadState.Loaded(settings), pinStore = store)
        val controller = h.resolvedController()
        controller.onAppForegrounded()
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin()) })
        assertEquals(AppLockState.Unlocked, controller.state.value)
        h.authenticator.calls.clear()
        store.log.clear()
        store.calls.clear()
        store.biometricWrites.clear()
        h.emitted.clear()
        return h to controller
    }

    /** Opens the step, verifies [pin] as correct, lets the prompt launch; the result of [AppLockController.enableFingerprint]. */
    private fun startEnable(h: AppLockControllerHarness, c: AppLockController, pin: CharArray): FingerprintEnableResult {
        h.pinStore.verifyScript += PinVerifyResult.Correct
        assertTrue(c.beginFingerprintEnable())
        val result = runBlocking { c.enableFingerprint(pin) }
        h.settle()
        return result
    }

    /** Waits (real time, bounded) for the best-effort key delete, which runs on a background dispatcher. */
    private fun awaitDeletes(h: AppLockControllerHarness, expected: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (h.fingerprintKeyGate.deleteCalls < expected && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(expected, h.fingerprintKeyGate.deleteCalls)
    }

    // --- 1. beginFingerprintEnable ----------------------------------------------------------------

    @Test
    fun `INV-54 beginFingerprintEnable opens FINGERPRINT_CONFIRM in UNLOCKED APP_PIN with verifier present and flag off`() {
        val (h, c) = pinModeRig()
        assertTrue(c.beginFingerprintEnable())
        assertEquals(PinStep.FINGERPRINT_CONFIRM, c.pin.value.step)
        assertEquals(AppLockState.Unlocked, c.state.value)
        assertEquals(0, h.authenticator.calls.size) // a PIN step is not a prompt
        assertEquals(0, h.fingerprintKeyGate.createCalls)
        assertEquals(emptyList<Boolean>(), h.pinStore.biometricWrites)
    }

    @Test
    fun `INV-54 beginFingerprintEnable is refused when fingerprint unlock is already on`() {
        val (h, c) = pinModeRig(biometricEnabled = true)
        assertFalse(c.beginFingerprintEnable())
        assertEquals(PinStep.NONE, c.pin.value.step)
        assertEquals(0, h.pinStore.count("verify"))
    }

    @Test
    fun `INV-54 beginFingerprintEnable is refused in effective DEVICE_AUTH mode`() {
        val (_, c) = unlockedControllerFor(AutoLockTimeout.M15)
        assertEquals(EffectiveLockMode.DEVICE_AUTH, c.pin.value.effectiveLockMode)
        assertFalse(c.beginFingerprintEnable())
        assertEquals(PinStep.NONE, c.pin.value.step)
    }

    @Test
    fun `INV-54 beginFingerprintEnable is refused when the verifier is not PRESENT`() {
        val (h, c) = pinModeRig()
        h.pinStore.setVerifier(PinVerifierState.UNREADABLE)
        assertFalse(c.beginFingerprintEnable())
        assertEquals(PinStep.NONE, c.pin.value.step)
    }

    @Test
    fun `INV-54 beginFingerprintEnable is refused outside UNLOCKED`() {
        val settings = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.M15, lockMode = LockMode.APP_PIN)
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, false, false))
        val h = AppLockControllerHarness(AppLockSettingsLoadState.Loaded(settings), pinStore = store)
        val c = h.resolvedController()
        c.onAppForegrounded()
        assertTrue(c.state.value is AppLockState.Locked)
        assertFalse(c.beginFingerprintEnable())
        assertEquals(PinStep.NONE, c.pin.value.step)
    }

    @Test
    fun `INV-54 beginFingerprintEnable is refused while another step is open and while the enable prompt runs`() {
        val (h, c) = pinModeRig()
        assertTrue(c.beginPinConfirm(PinStep.TIMEOUT_CONFIRM))
        assertFalse(c.beginFingerprintEnable())
        assertEquals(PinStep.TIMEOUT_CONFIRM, c.pin.value.step)
        c.cancelPinStep()
        assertEquals(FingerprintEnableResult.Started, startEnable(h, c, pin()))
        assertTrue(c.state.value is AppLockState.Authenticating)
        assertFalse(c.beginFingerprintEnable())
    }

    // --- 2. enableFingerprint ---------------------------------------------------------------------

    @Test
    fun `INV-40 enableFingerprint with a wrong PIN is rejected through the shared counter and creates no key`() {
        val (h, c) = pinModeRig()
        h.pinStore.verifyScript += PinVerifyResult.Wrong(PinDelayTier.S30)
        assertTrue(c.beginFingerprintEnable())
        val entered = pin("000000")
        val result = runBlocking { c.enableFingerprint(entered) }
        h.settle()
        assertEquals(FingerprintEnableResult.Rejected(PinOperationResult.NotVerified(PinRejection.Wrong(PinDelayTier.S30))), result)
        assertEquals(1, h.pinStore.count("verify"))
        assertEquals(0, h.fingerprintKeyGate.createCalls)
        assertEquals(0, h.authenticator.calls.size)
        assertEquals(PinStep.FINGERPRINT_CONFIRM, c.pin.value.step) // the step stays open for a retry
        assertTrue(entered.isZeroed())
        assertFalse(c.pin.value.fingerprintEnabled)
    }

    @Test
    fun `INV-40 enableFingerprint without an open step is NotInStep and touches nothing`() {
        val (h, c) = pinModeRig()
        val entered = pin()
        val result = runBlocking { c.enableFingerprint(entered) }
        assertEquals(FingerprintEnableResult.Rejected(PinOperationResult.NotInStep), result)
        assertEquals(0, h.pinStore.count("verify"))
        assertEquals(0, h.fingerprintKeyGate.createCalls)
        assertEquals(0, h.authenticator.calls.size)
        assertTrue(entered.isZeroed())
    }

    @Test
    fun `INV-40 enableFingerprint with the correct PIN creates one key and launches the PIN_FINGERPRINT prompt`() {
        val (h, c) = pinModeRig()
        val entered = pin()
        assertEquals(FingerprintEnableResult.Started, startEnable(h, c, entered))
        assertEquals(1, h.fingerprintKeyGate.createCalls)
        assertEquals(1, h.authenticator.calls.size)
        assertEquals(AuthPromptCopy.PIN_FINGERPRINT, h.authenticator.calls.single().copy)
        val state = c.state.value as AppLockState.Authenticating
        assertEquals(AuthPurpose.CONFIRM_CHANGE, state.purpose)
        assertEquals(emptyList<Boolean>(), h.pinStore.biometricWrites) // the flag turns on only at the prompt success
        assertFalse(c.pin.value.fingerprintEnabled)
        assertTrue(entered.isZeroed())
    }

    @Test
    fun `INV-40 a prompt success turns the flag on once and ends APPLIED`() {
        val (h, c) = pinModeRig()
        assertEquals(FingerprintEnableResult.Started, startEnable(h, c, pin()))
        val attemptId = h.authenticator.calls.single().attemptId
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        h.settle()
        assertEquals(listOf(true), h.pinStore.biometricWrites)
        assertEquals(1, h.pinStore.count("setBiometricEnabled"))
        assertTrue(c.pin.value.fingerprintEnabled)
        assertEquals(AppLockState.Unlocked, c.state.value)
        assertEquals(OpenOriginEnd(attemptId, OpenOriginPrompt.FINGERPRINT_ENABLE, OpenOriginEndKind.APPLIED), c.openOriginEnd.value)
        assertEquals(0, h.fingerprintKeyGate.deleteCalls) // the key stays
    }

    @Test
    fun `INV-40 a confirmed prompt whose flag write fails deletes the key and ends NOT_COMMITTED`() {
        val (h, c) = pinModeRig()
        h.pinStore.biometricWriteResult = PinStoreWriteResult.FAILED
        assertEquals(FingerprintEnableResult.Started, startEnable(h, c, pin()))
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        h.settle()
        assertFalse(c.pin.value.fingerprintEnabled)
        // The end is published after a real Dispatchers.Default hop, so wait for it (bounded).
        val endDeadline = System.nanoTime() + 5_000_000_000L
        while (c.openOriginEnd.value == null && System.nanoTime() < endDeadline) Thread.sleep(10)
        assertEquals(OpenOriginEndKind.NOT_COMMITTED, c.openOriginEnd.value?.kind)
        awaitDeletes(h, 1)
    }

    @Test
    fun `INV-40 a cancelled, failed, locked out or unavailable prompt deletes the key, leaves the flag off and never goes INERT`() {
        val outcomes = listOf(
            DeviceAuthOutcome.Cancelled,
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.TIMEOUT),
            DeviceAuthOutcome.LockedOut(permanent = false),
            DeviceAuthOutcome.NotAvailable
        )
        for (outcome in outcomes) {
            val (h, c) = pinModeRig()
            assertEquals("$outcome", FingerprintEnableResult.Started, startEnable(h, c, pin()))
            h.authenticator.deliver(outcome)
            h.settle()
            assertEquals("$outcome", AppLockState.Unlocked, c.state.value)
            assertFalse("$outcome", c.pin.value.fingerprintEnabled)
            assertEquals("$outcome", emptyList<Boolean>(), h.pinStore.biometricWrites)
            assertEquals("$outcome", 1, h.fingerprintKeyGate.createCalls)
            awaitDeletes(h, 1)
            val end = c.openOriginEnd.value
            assertEquals("$outcome", OpenOriginPrompt.FINGERPRINT_ENABLE, end?.prompt)
            assertNotEquals("$outcome", OpenOriginEndKind.APPLIED, end?.kind)
        }
    }

    @Test
    fun `INV-40 enableFingerprint is KeyUnavailable when the key cannot be created and starts no prompt`() {
        val (h, c) = pinModeRig()
        h.fingerprintKeyGate.createResult = false
        val entered = pin()
        assertEquals(FingerprintEnableResult.KeyUnavailable, startEnable(h, c, entered))
        assertEquals(1, h.fingerprintKeyGate.createCalls)
        assertEquals(0, h.authenticator.calls.size)
        assertEquals(PinStep.NONE, c.pin.value.step) // the step is closed
        assertEquals(AppLockState.Unlocked, c.state.value)
        assertEquals(emptyList<Boolean>(), h.pinStore.biometricWrites)
        assertTrue(entered.isZeroed())
    }

    @Test
    fun `INV-40 enableFingerprint maps a throwing key create to KeyUnavailable`() {
        val (h, c) = pinModeRig()
        h.fingerprintKeyGate.createFailure = IllegalStateException("synthetic keystore failure")
        val entered = pin()
        assertEquals(FingerprintEnableResult.KeyUnavailable, startEnable(h, c, entered))
        assertEquals(0, h.authenticator.calls.size)
        assertEquals(PinStep.NONE, c.pin.value.step)
        assertTrue(entered.isZeroed())
    }

    // --- 3. AE-120: the prompt background goes to the timer ----------------------------------------

    @Test
    fun `AE-120 after a FINGERPRINT_ENABLE success the prompt background is handed to the timer and the next foreground re-locks`() {
        val (h, c) = pinModeRig(AutoLockTimeout.M1)
        assertEquals(FingerprintEnableResult.Started, startEnable(h, c, pin()))
        c.onAppBackgrounded() // the person leaves while the prompt is open
        h.clock.advance(AutoLockTimeout.M1.millis + 1_000L)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        h.settle()
        assertTrue(c.pin.value.fingerprintEnabled)
        c.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), c.state.value)
    }

    @Test
    fun `AE-120 a FINGERPRINT_ENABLE success whose background is shorter than the timeout stays UNLOCKED`() {
        val (h, c) = pinModeRig(AutoLockTimeout.M1)
        assertEquals(FingerprintEnableResult.Started, startEnable(h, c, pin()))
        c.onAppBackgrounded()
        h.clock.advance(AutoLockTimeout.M1.millis - 5_000L)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        h.settle()
        c.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, c.state.value)
    }

    // --- 4. AMD-2: no PIN bypass through the public start API ---------------------------------------

    @Test
    fun `AMD-2 the public startOpenOriginAttempt refuses FINGERPRINT_ENABLE with NotInOrigin and changes nothing`() {
        val (h, c) = pinModeRig()
        val handler = RecordingSuccessHandler()
        assertEquals(OpenOriginStartResult.NotInOrigin, c.startOpenOriginAttempt(OpenOriginPrompt.FINGERPRINT_ENABLE, handler))
        h.settle()
        assertEquals(AppLockState.Unlocked, c.state.value)
        assertEquals(0, h.authenticator.calls.size)
        assertEquals(0, h.fingerprintKeyGate.createCalls)
        assertEquals(emptyList<Pair<Long, AuthPurpose>>(), handler.delivered)
        assertFalse(c.pin.value.fingerprintEnabled)
    }

    // --- 5. disableFingerprint ----------------------------------------------------------------------

    @Test
    fun `STOP-5 disableFingerprint writes the flag off once, then deletes the key, without a PIN`() {
        val (h, c) = pinModeRig(biometricEnabled = true)
        assertTrue(c.pin.value.fingerprintEnabled)
        assertEquals(PinOperationResult.Done, runBlocking { c.disableFingerprint() })
        assertEquals(listOf(false), h.pinStore.biometricWrites)
        assertEquals(1, h.fingerprintKeyGate.deleteCalls)
        assertFalse(c.pin.value.fingerprintEnabled)
        assertEquals(0, h.pinStore.count("verify"))
        assertEquals(0, h.authenticator.calls.size)
        assertEquals(AppLockState.Unlocked, c.state.value)
    }

    @Test
    fun `STOP-5 disableFingerprint with a failed flag write keeps the key and reports Failed`() {
        val (h, c) = pinModeRig(biometricEnabled = true)
        h.pinStore.biometricWriteResult = PinStoreWriteResult.FAILED
        assertEquals(PinOperationResult.Failed, runBlocking { c.disableFingerprint() })
        assertEquals(listOf(false), h.pinStore.biometricWrites)
        assertEquals(0, h.fingerprintKeyGate.deleteCalls) // the delete follows only a committed write
        assertTrue(c.pin.value.fingerprintEnabled)
    }

    @Test
    fun `STOP-5 disableFingerprint is refused outside UNLOCKED`() {
        val settings = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.M15, lockMode = LockMode.APP_PIN)
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, true, false))
        val h = AppLockControllerHarness(AppLockSettingsLoadState.Loaded(settings), pinStore = store)
        val c = h.resolvedController()
        c.onAppForegrounded()
        assertTrue(c.state.value is AppLockState.Locked)
        assertEquals(PinOperationResult.NotInStep, runBlocking { c.disableFingerprint() })
        assertEquals(0, store.count("setBiometricEnabled"))
        assertEquals(0, h.fingerprintKeyGate.deleteCalls)
    }

    @Test
    fun `STOP-5 disableFingerprint is refused in effective DEVICE_AUTH mode`() {
        val (h, c) = unlockedControllerFor(AutoLockTimeout.M15)
        assertEquals(PinOperationResult.NotInStep, runBlocking { c.disableFingerprint() })
        assertEquals(0, h.pinStore.count("setBiometricEnabled"))
        assertEquals(0, h.fingerprintKeyGate.deleteCalls)
    }

    // --- 6. the biometric notice (INV-51) -----------------------------------------------------------

    @Test
    fun `INV-51 the BiometricInvalidated notice follows the PIN store flag and dismissBiometricNotice clears it`() {
        val (h, c) = pinModeRig(noticePending = true)
        assertEquals(LockNotice.BiometricInvalidated, c.notice.value)
        runBlocking { c.dismissBiometricNotice() }
        assertEquals(1, h.pinStore.count("dismissBiometricInvalidatedNotice"))
        assertNull(c.notice.value)
    }

    @Test
    fun `INV-51 no pending flag means no BiometricInvalidated notice`() {
        val (_, c) = pinModeRig(noticePending = false)
        assertNull(c.notice.value)
    }

    @Test
    fun `INV-51 notice priority is Inert over ReviewSettings over BiometricInvalidated`() {
        val corrupted = AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED)
        val reason = InertReason.entries.first()
        assertEquals(LockNotice.Inert(reason), LockNoticeDerivation.derive(AppLockState.Inert(reason), corrupted, true))
        assertEquals(LockNotice.ReviewSettings, LockNoticeDerivation.derive(AppLockState.Unlocked, corrupted, true))
        assertEquals(
            LockNotice.BiometricInvalidated,
            LockNoticeDerivation.derive(AppLockState.Unlocked, AppLockTestSettings.DISABLED, true)
        )
        assertNull(LockNoticeDerivation.derive(AppLockState.Unlocked, AppLockTestSettings.DISABLED, false))
    }
}
