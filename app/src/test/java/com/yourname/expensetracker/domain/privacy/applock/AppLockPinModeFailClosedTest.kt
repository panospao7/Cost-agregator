package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStoreState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayTier
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDeleteResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifyResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Effective APP_PIN mode is fail closed (T30, AMD-2, INV-40, INV-49): a device credential never weakens a PIN lock
 * (reviewer HIGH-1), the lock-off removal order (INV-49), a PIN unlock in the background keeps the auto-lock timer
 * (LOW-2), the failed-write field (LOW-3) and the PIN setup capability rule (LOW-4). Synthetic data only.
 */
class AppLockPinModeFailClosedTest {

    private fun pin(text: String = "482916"): CharArray = text.toCharArray()

    private fun settingsOf(rig: SettingsOperationsRig): AppLockPrivacySettings =
        (rig.h.repository.loadState as AppLockSettingsLoadState.Loaded).settings

    /**
     * UNLOCKED in effective APP_PIN mode (verifier PRESENT, stored APP_PIN, lock on, [timeout]), foreground, entered
     * through a real PIN unlock. Its verify script is empty again afterwards.
     */
    private fun pinModeRig(
        capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE,
        timeout: AutoLockTimeout = AutoLockTimeout.M1
    ): SettingsOperationsRig {
        val settings = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = timeout, lockMode = LockMode.APP_PIN)
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, false, false))
        store.verifyScript += PinVerifyResult.Correct
        val h = AppLockControllerHarness(AppLockSettingsLoadState.Loaded(settings), capability, pinStore = store)
        val controller = h.resolvedController()
        controller.onAppForegrounded()
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin()) })
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(EffectiveLockMode.APP_PIN, controller.pin.value.effectiveLockMode)
        return SettingsOperationsRig(h, controller)
    }

    /** UNLOCKED in effective DEVICE_AUTH mode (no verifier), lock on, [timeout]: the control rig. */
    private fun deviceAuthRig(timeout: AutoLockTimeout = AutoLockTimeout.M1): SettingsOperationsRig {
        val (h, controller) = unlockedControllerFor(timeout)
        assertEquals(EffectiveLockMode.DEVICE_AUTH, controller.pin.value.effectiveLockMode)
        h.authenticator.calls.clear() // the rig's own unlock recorded an authenticator call; count only the test's
        return SettingsOperationsRig(h, controller)
    }

    // --- HIGH-1 (T30, AMD-2, INV-40): no device prompt in effective APP_PIN mode -------------------

    @Test
    fun `INV-40 requestDisable in effective APP_PIN returns PIN_MODE with no device prompt and the lock stays on`() {
        val rig = pinModeRig()
        val before = settingsOf(rig)
        assertEquals(SettingsRequestResult.PIN_MODE, rig.ops.requestDisable())
        rig.h.settle()
        assertEquals(0, rig.h.authenticator.calls.size)
        assertNull(rig.controller.inFlightAttemptId)
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue(rig.h.repository.lockWrites.isEmpty())
        assertEquals(before, settingsOf(rig))
        assertTrue(settingsOf(rig).appLockEnabled)
    }

    @Test
    fun `INV-40 control requestDisable in DEVICE_AUTH still opens a device prompt`() {
        val rig = deviceAuthRig()
        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
        rig.h.settle()
        assertEquals(1, rig.h.authenticator.calls.size)
    }

    @Test
    fun `INV-40 the controller refuses a DISABLE attempt in effective APP_PIN with NotInOrigin and no prompt`() {
        val rig = pinModeRig()
        val handler = RecordingSuccessHandler()
        assertEquals(OpenOriginStartResult.NotInOrigin, rig.controller.startOpenOriginAttempt(OpenOriginPrompt.DISABLE, handler))
        rig.h.settle()
        assertEquals(0, rig.h.authenticator.calls.size)
        assertTrue(handler.delivered.isEmpty())
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
    }

    @Test
    fun `INV-40 a longer timeout in effective APP_PIN returns PIN_MODE with no device prompt and no write`() {
        val rig = pinModeRig(timeout = AutoLockTimeout.M1)
        val before = settingsOf(rig)
        assertEquals(TimeoutChangeResult.PIN_MODE, runBlocking { rig.ops.requestTimeoutChange(AutoLockTimeout.M15) })
        rig.h.settle()
        assertEquals(0, rig.h.authenticator.calls.size)
        assertTrue(rig.h.repository.timeoutWrites.isEmpty())
        assertEquals(before, settingsOf(rig))
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
    }

    @Test
    fun `INV-40 boundary a shorter timeout in effective APP_PIN still commits with no prompt`() {
        val rig = pinModeRig(timeout = AutoLockTimeout.M15)
        assertEquals(TimeoutChangeResult.COMMITTED, runBlocking { rig.ops.requestTimeoutChange(AutoLockTimeout.M1) })
        rig.h.settle()
        assertEquals(0, rig.h.authenticator.calls.size)
        assertEquals(AutoLockTimeout.M1, settingsOf(rig).autoLockTimeout)
    }

    @Test
    fun `INV-40 control a longer timeout in DEVICE_AUTH still opens a device prompt`() {
        val rig = deviceAuthRig(timeout = AutoLockTimeout.M1)
        assertEquals(TimeoutChangeResult.STARTED, runBlocking { rig.ops.requestTimeoutChange(AutoLockTimeout.M15) })
        rig.h.settle()
        assertEquals(1, rig.h.authenticator.calls.size)
        assertTrue(rig.h.repository.timeoutWrites.isEmpty())
    }

    // The review-save needs a persistently unreadable store. Effective mode APP_PIN comes from the PRESENT verifier.
    private fun unreadablePinModeRig(): SettingsOperationsRig {
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, false, false))
        store.verifyScript += PinVerifyResult.Correct
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED), pinStore = store)
        val controller = h.resolvedController()
        controller.onAppForegrounded()
        assertTrue(controller.state.value is AppLockState.Locked)
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin()) })
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(EffectiveLockMode.APP_PIN, controller.pin.value.effectiveLockMode)
        return SettingsOperationsRig(h, controller)
    }

    @Test
    fun `INV-40 a weakening review-save in effective APP_PIN returns PIN_MODE with no device prompt and no write`() {
        val rig = unreadablePinModeRig()
        val weakening = AppLockPrivacySettings(appLockEnabled = false)
        assertEquals(ReviewSaveResult.PIN_MODE, runBlocking { rig.ops.requestReviewSave(weakening) })
        rig.h.settle()
        assertEquals(0, rig.h.authenticator.calls.size)
        assertTrue(rig.h.repository.reviewSaves.isEmpty())
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
    }

    @Test
    fun `INV-40 boundary a non-weakening review-save in effective APP_PIN commits with no prompt`() {
        val rig = unreadablePinModeRig()
        val notWeakening = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.IMMEDIATE)
        assertEquals(ReviewSaveResult.COMMITTED, runBlocking { rig.ops.requestReviewSave(notWeakening) })
        rig.h.settle()
        assertEquals(0, rig.h.authenticator.calls.size)
        assertEquals(listOf(notWeakening), rig.h.repository.reviewSaves)
    }

    @Test
    fun `INV-40 precondition effective APP_PIN holds while the settings are unreadable`() {
        val rig = unreadablePinModeRig()
        assertTrue(rig.controller.isPersistentUnreadable)
        assertTrue(rig.controller.isEffectiveAppPinMode())
    }

    @Test
    fun `INV-40 a lock-on IMMEDIATE review-save in effective APP_PIN commits without a capability or a prompt`() {
        for (capability in listOf(DeviceAuthCapability.NO_CREDENTIAL, DeviceAuthCapability.CHECK_FAILED)) {
            val rig = unreadablePinModeRig()
            rig.h.capabilityProvider.capability = capability // read at the request, after the PIN unlock
            val record = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.IMMEDIATE)
            val result = runBlocking { rig.ops.requestReviewSave(record) }
            assertEquals("$capability", ReviewSaveResult.COMMITTED, result)
            rig.h.settle()
            assertEquals("$capability", 0, rig.h.authenticator.calls.size)
            assertEquals("$capability", listOf(record), rig.h.repository.reviewSaves)
        }
    }

    @Test
    fun `INV-40 boundary a weakening review-save in effective APP_PIN with no capability is still PIN_MODE not committed`() {
        val rig = unreadablePinModeRig()
        rig.h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        val result = runBlocking { rig.ops.requestReviewSave(AppLockPrivacySettings(appLockEnabled = false)) }
        assertEquals(ReviewSaveResult.PIN_MODE, result)
        assertTrue(rig.h.repository.reviewSaves.isEmpty())
        assertEquals(0, rig.h.authenticator.calls.size)
    }

    @Test
    fun `INV-49 disablePin and removePin with unreadable settings refuse with NotInStep, no verify and no deleteAll`() {
        for (turnLockOff in listOf(false, true)) {
            val rig = unreadablePinModeRig()
            val store = rig.h.pinStore
            store.verifyScript += PinVerifyResult.Correct
            assertTrue(rig.controller.beginPinRemoval())
            val before = store.log.size
            val viaController = runBlocking { rig.controller.disablePin(pin(), turnLockOff) }
            val viaOperations = runBlocking { rig.ops.removePin(pin(), turnLockOff = turnLockOff) }
            assertEquals("controller $turnLockOff", PinOperationResult.NotInStep, viaController)
            assertEquals("operations $turnLockOff", PinOperationResult.NotInStep, viaOperations)
            val calls = store.log.drop(before)
            assertFalse("$turnLockOff $calls", calls.any { it == "verify" || it == "deleteAll" })
            assertTrue(rig.h.repository.lockModeWrites.isEmpty() && rig.h.repository.lockWrites.isEmpty())
            assertEquals(AppLockState.Unlocked, rig.controller.state.value)
            assertEquals(EffectiveLockMode.APP_PIN, rig.controller.pin.value.effectiveLockMode)
        }
    }

    @Test
    fun `INV-40 control a weakening review-save in DEVICE_AUTH still opens a device prompt`() {
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED))
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppForegrounded()
        h.authenticator.calls.clear() // the unlock above recorded a call; count only the review-save prompt
        val rig = SettingsOperationsRig(h, controller)
        val weakening = AppLockPrivacySettings(appLockEnabled = false)
        assertEquals(ReviewSaveResult.STARTED, runBlocking { rig.ops.requestReviewSave(weakening) })
        h.settle()
        assertEquals(1, h.authenticator.calls.size)
        assertTrue(h.repository.reviewSaves.isEmpty())
    }

    // --- INV-49 / T30: removePin(turnLockOff) with a capability that is AVAILABLE ---------------------

    /** [pinModeRig] with the removal step open, and every settings write logged into the PIN store's call log. */
    private fun removalRig(): SettingsOperationsRig {
        val rig = pinModeRig()
        val store = rig.h.pinStore
        val repo = rig.h.repository
        store.verifyScript += PinVerifyResult.Correct
        repo.lockModeWriteResult = { mode ->
            store.log += "setLockMode:$mode"
            val next = settingsOf(rig).copy(lockMode = mode)
            repo.loadState = AppLockSettingsLoadState.Loaded(next)
            AppLockSettingsWriteResult.Committed(next)
        }
        repo.lockWriteResult = { enabled ->
            store.log += "setAppLockEnabled:$enabled"
            val next = settingsOf(rig).copy(appLockEnabled = enabled)
            repo.loadState = AppLockSettingsLoadState.Loaded(next)
            AppLockSettingsWriteResult.Committed(next)
        }
        assertTrue(rig.controller.beginPinRemoval())
        return rig
    }

    @Test
    fun `INV-49 removePin with turnLockOff deletes the verifier first, then turns the lock off and emits LOCK_DISABLED`() {
        val rig = removalRig()
        val store = rig.h.pinStore
        val before = store.log.size
        val entered = pin()
        assertEquals(PinOperationResult.Done, runBlocking { rig.ops.removePin(entered, turnLockOff = true) })
        val ops = store.log.drop(before).filter { it == "verify" || it == "deleteAll" || it.startsWith("set") }
        assertEquals(listOf("verify", "deleteAll", "setLockMode:DEVICE_AUTH", "setAppLockEnabled:false"), ops)
        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertFalse(settingsOf(rig).appLockEnabled)
        assertEquals(LockMode.DEVICE_AUTH, settingsOf(rig).lockMode)
        assertTrue(rig.h.emitted.contains(PrivacyDiagnosticCode.LOCK_DISABLED))
        assertTrue(entered.all { it == '\u0000' })
        assertTrue(rig.uncaught.isEmpty())
    }

    @Test
    fun `INV-49 removePin with turnLockOff and a wrong PIN deletes nothing, writes nothing and keeps the lock on`() {
        val rig = pinModeRig()
        val store = rig.h.pinStore
        store.log.clear() // drop the rig's own unlock verify/reset; count only the removal attempt
        store.verifyScript += PinVerifyResult.Wrong(PinDelayTier.S30)
        assertTrue(rig.controller.beginPinRemoval())
        val before = settingsOf(rig)
        val result = runBlocking { rig.ops.removePin(pin("111112"), turnLockOff = true) }
        assertTrue(result is PinOperationResult.NotVerified && result.reason is PinRejection.Wrong)
        assertEquals(1, store.count("verify")) // the shared attempt counter lives behind this one verify call
        assertEquals(0, store.count("resetAttempts"))
        assertEquals(0, store.count("deleteAll"))
        assertTrue(rig.h.repository.lockModeWrites.isEmpty() && rig.h.repository.lockWrites.isEmpty())
        assertEquals(before, settingsOf(rig))
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(EffectiveLockMode.APP_PIN, rig.controller.pin.value.effectiveLockMode)
        assertFalse(rig.h.emitted.contains(PrivacyDiagnosticCode.LOCK_DISABLED))
    }

    @Test
    fun `INV-49 boundary a failed deleteAll with turnLockOff writes no setting and keeps the lock on`() {
        val rig = removalRig()
        rig.h.pinStore.deleteResult = PinDeleteResult.FAILED
        assertEquals(PinOperationResult.Failed, runBlocking { rig.ops.removePin(pin(), turnLockOff = true) })
        assertTrue(rig.h.repository.lockModeWrites.isEmpty() && rig.h.repository.lockWrites.isEmpty())
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue(settingsOf(rig).appLockEnabled)
    }

    @Test
    fun `INV-49 control removePin without turnLockOff and an AVAILABLE capability keeps the lock on in DEVICE_AUTH`() {
        val rig = removalRig()
        assertEquals(PinOperationResult.Done, runBlocking { rig.ops.removePin(pin()) })
        assertEquals(listOf(LockMode.DEVICE_AUTH), rig.h.repository.lockModeWrites)
        assertTrue(rig.h.repository.lockWrites.isEmpty())
        assertTrue(settingsOf(rig).appLockEnabled)
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(EffectiveLockMode.DEVICE_AUTH, rig.controller.pin.value.effectiveLockMode)
    }

    // --- LOW-2: a PIN unlock in the background keeps the auto-lock timer ---------------------------------

    private fun lockedPinController(): Pair<AppLockControllerHarness, AppLockController> {
        val settings = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.M1, lockMode = LockMode.APP_PIN)
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, false, false))
        store.verifyScript += PinVerifyResult.Correct
        val h = AppLockControllerHarness(AppLockSettingsLoadState.Loaded(settings), pinStore = store)
        val controller = h.resolvedController()
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        return h to controller
    }

    @Test
    fun `LOW-2 a PIN unlock that completes in the background leaves a pending background and the timer re-locks after the timeout`() {
        val (h, controller) = lockedPinController()
        controller.onAppForegrounded()
        controller.onAppBackgrounded() // the app leaves the foreground during the PIN check
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin()) })
        assertNotNull(controller.pendingBackgroundAtMillis)
        assertEquals(h.clock.nowMillis, controller.pendingBackgroundAtMillis)
        h.clock.advance(AutoLockTimeout.M1.millis + 1_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `LOW-2 boundary a background PIN unlock that returns within the timeout stays unlocked and consumes the pending background`() {
        val (h, controller) = lockedPinController()
        controller.onAppForegrounded()
        controller.onAppBackgrounded()
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin()) })
        h.clock.advance(AutoLockTimeout.M1.millis - 1_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertNull(controller.pendingBackgroundAtMillis)
    }

    @Test
    fun `LOW-2 control a PIN unlock in the foreground leaves no pending background and no re-lock`() {
        val (h, controller) = lockedPinController()
        controller.onAppForegrounded()
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin()) })
        assertNull(controller.pendingBackgroundAtMillis)
        h.clock.advance(AutoLockTimeout.M1.millis + 1_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    // --- LOW-3: the failed lockMode write is recorded with field LOCK_MODE ------------------------------

    @Test
    fun `LOW-3 a failed lockMode write during removePin records LOCK_SETTING_PERSIST_FAILED with field LOCK_MODE`() {
        val failures = mapOf<String, () -> AppLockSettingsWriteResult>(
            "failed" to { AppLockSettingsWriteResult.Failed(AppLockSettingField.LOCK_MODE) },
            "rejected" to { AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE) },
            "throwing" to { throw IllegalStateException("synthetic") }
        )
        for ((label, failure) in failures) {
            val rig = removalRig()
            rig.h.repository.lockModeWriteResult = { failure() }
            assertEquals(label, PinOperationResult.ModeWriteFailed, runBlocking { rig.ops.removePin(pin(), turnLockOff = true) })
            assertTrue(label, rig.h.repository.lockWrites.isEmpty()) // the lock flag is never written after a mode failure
            val index = rig.h.emitted.indexOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED)
            assertTrue(label, index >= 0)
            assertEquals(label, 1, rig.h.emitted.count { it == PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED })
            assertEquals(
                label,
                mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.FIELD to AppLockSettingField.LOCK_MODE),
                rig.h.emittedMetadata[index].entries
            )
            assertEquals(label, AppLockState.Unlocked, rig.controller.state.value)
        }
    }

    // --- LOW-4: PIN setup needs a capability that really is AVAILABLE ------------------------------------

    @Test
    fun `LOW-4 requestPinSetup with CHECK_FAILED or a missing credential is NotAllowed with no prompt`() {
        for (capability in listOf(DeviceAuthCapability.CHECK_FAILED, DeviceAuthCapability.NO_CREDENTIAL)) {
            val rig = deviceAuthRig(AutoLockTimeout.IMMEDIATE)
            rig.h.capabilityProvider.capability = capability // the capability read at the request, after the unlock
            assertEquals("$capability", PinSetupRequestResult.NotAllowed, rig.controller.requestPinSetup())
            rig.h.settle()
            assertEquals("$capability", 0, rig.h.authenticator.calls.size)
            assertNull("$capability", rig.controller.inFlightAttemptId)
            assertEquals(PinStep.NONE, rig.controller.pin.value.step)
        }
    }

    @Test
    fun `LOW-4 control requestPinSetup with an AVAILABLE capability starts the confirm prompt`() {
        val rig = deviceAuthRig(AutoLockTimeout.IMMEDIATE)
        assertTrue(rig.controller.requestPinSetup() is PinSetupRequestResult.Started)
        rig.h.settle()
        assertEquals(1, rig.h.authenticator.calls.size)
    }
}
