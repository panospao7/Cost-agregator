package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStoreState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinChangeResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayReason
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayTier
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDeleteResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinFormatResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinSetupResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifyResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S25 PIN setup, change and removal on the real controller over fakes (INV-49, INV-50, INV-54 [2], INV-38, INV-39,
 * AMD-4; guardian dispositions G25-1 to G25-11). The PIN store and the settings store are scripted fakes.
 */
class AppLockControllerPinOperationsTest {

    private class Rig(val h: AppLockControllerHarness, val c: AppLockController) {
        val store: FakeAppPinStore get() = h.pinStore
        val repo: FakeAppLockSettingsRepository get() = h.repository
    }

    private fun pin(text: String = "739104"): CharArray = text.toCharArray()

    private fun zeroed(array: CharArray): Boolean = array.all { it == '\u0000' }

    /**
     * Logs every settings write into the PIN store's call log, in step with its operations, so a test can assert the
     * relative order of a settings write and a store call. The writes still commit to the fake settings view.
     */
    private fun Rig.logSettingsWritesInStoreLog() {
        repo.lockModeWriteResult = { mode ->
            store.log += "setLockMode:$mode"
            val next = (repo.loadState as AppLockSettingsLoadState.Loaded).settings.copy(lockMode = mode)
            repo.loadState = AppLockSettingsLoadState.Loaded(next)
            AppLockSettingsWriteResult.Committed(next)
        }
        repo.lockWriteResult = { enabled ->
            store.log += "setAppLockEnabled:$enabled"
            val next = (repo.loadState as AppLockSettingsLoadState.Loaded).settings.copy(appLockEnabled = enabled)
            repo.loadState = AppLockSettingsLoadState.Loaded(next)
            AppLockSettingsWriteResult.Committed(next)
        }
    }

    /** UNLOCKED, lock on (IMMEDIATE), no verifier (effective DEVICE_AUTH), device auth available: ready for requestPinSetup. */
    private fun deviceAuthRig(capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE): Rig {
        val settings = AppLockPrivacySettings(
            appLockEnabled = true,
            autoLockTimeout = AutoLockTimeout.IMMEDIATE,
            lockMode = LockMode.DEVICE_AUTH
        )
        val h = AppLockControllerHarness(
            AppLockSettingsLoadState.Loaded(settings),
            capability,
            pinStore = FakeAppPinStore(AppPinStoreState(PinVerifierState.ABSENT, false, false))
        )
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, controller.state.value)
        return Rig(h, controller)
    }

    /** [deviceAuthRig] with the setup step open (the CONFIRM_CHANGE prompt succeeded). */
    private fun setupStepRig(capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE): Rig {
        val rig = deviceAuthRig(capability)
        assertTrue(rig.c.requestPinSetup() is PinSetupRequestResult.Started)
        rig.h.settle()
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(PinStep.SETUP_ENTRY, rig.c.pin.value.step)
        return rig
    }

    /** UNLOCKED in effective APP_PIN mode (verifier PRESENT, stored APP_PIN), no step open. Verify script is empty again. */
    private fun pinModeRig(): Rig {
        val settings = AppLockPrivacySettings(
            appLockEnabled = true,
            autoLockTimeout = AutoLockTimeout.IMMEDIATE,
            lockMode = LockMode.APP_PIN
        )
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, false, false))
        store.verifyScript += PinVerifyResult.Correct
        val h = AppLockControllerHarness(AppLockSettingsLoadState.Loaded(settings), pinStore = store)
        val controller = h.resolvedController()
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin("482916")) })
        controller.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(EffectiveLockMode.APP_PIN, controller.pin.value.effectiveLockMode)
        return Rig(h, controller)
    }

    /** Backgrounds and foregrounds under IMMEDIATE: the state becomes LOCKED(TIMEOUT) and any open step closes. */
    private fun Rig.relock() {
        c.onAppBackgrounded()
        c.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), c.state.value)
    }

    // --- G25-1: steps ----------------------------------------------------------------------------

    @Test
    fun `G25-1 enablePin without the setup step is NotAllowed and writes nothing`() {
        val rig = deviceAuthRig()
        val entered = pin()
        assertEquals(PinSetupRequestResult.NotAllowed, runBlocking { rig.c.enablePin(entered) })
        assertTrue(rig.repo.lockModeWrites.isEmpty())
        assertEquals(0, rig.store.count("setup"))
        assertTrue(zeroed(entered))
    }

    @Test
    fun `G25-1 requestPinSetup starts the confirm prompt and opens no step until it succeeds`() {
        val rig = deviceAuthRig()
        val result = rig.c.requestPinSetup()
        assertTrue(result is PinSetupRequestResult.Started)
        rig.h.settle()
        assertEquals(PinStep.NONE, rig.c.pin.value.step)
        assertTrue(rig.repo.lockModeWrites.isEmpty()) // nothing is written by the request
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(PinStep.SETUP_ENTRY, rig.c.pin.value.step)
        assertEquals(AppLockState.Unlocked, rig.c.state.value)
    }

    @Test
    fun `G25-1 a failed or cancelled confirm prompt opens no setup step and enablePin stays NotAllowed`() {
        val outcomes = listOf(
            DeviceAuthOutcome.Cancelled,
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER),
            DeviceAuthOutcome.LockedOut(permanent = false)
        )
        for (outcome in outcomes) {
            val rig = deviceAuthRig()
            assertTrue(rig.c.requestPinSetup() is PinSetupRequestResult.Started)
            rig.h.settle()
            rig.h.authenticator.deliver(outcome)
            assertEquals("step after $outcome", PinStep.NONE, rig.c.pin.value.step)
            assertEquals(PinSetupRequestResult.NotAllowed, runBlocking { rig.c.enablePin(pin()) })
            assertEquals(0, rig.store.count("setup"))
        }
    }

    @Test
    fun `G25-1 requestPinSetup is refused in effective APP_PIN mode and with the lock off`() {
        val pinMode = pinModeRig()
        assertEquals(PinSetupRequestResult.NotAllowed, pinMode.c.requestPinSetup())
        assertEquals(0, pinMode.h.authenticator.calls.size)

        val (h, controller) = disabledControllerFor()
        assertEquals(PinSetupRequestResult.NotAllowed, controller.requestPinSetup())
        assertEquals(0, h.authenticator.calls.size)
    }

    @Test
    fun `G25-1 changePin and disablePin return NotInStep without their step and touch nothing`() {
        val rig = pinModeRig()
        // The rig already verified once while unlocking: count only what the operations below add.
        val verifiesBefore = rig.store.count("verify")
        val changesBefore = rig.store.count("changePin")
        val deletesBefore = rig.store.count("deleteAll")
        val current = pin("482916")
        val new = pin()
        assertEquals(PinOperationResult.NotInStep, runBlocking { rig.c.changePin(current, new) })
        assertTrue(zeroed(current) && zeroed(new))
        val removal = pin("482916")
        assertEquals(PinOperationResult.NotInStep, runBlocking { rig.c.disablePin(removal) })
        assertTrue(zeroed(removal))
        // Wrong step: the change step does not authorise removal and the removal step does not authorise a change.
        assertTrue(rig.c.beginPinChange())
        assertEquals(PinOperationResult.NotInStep, runBlocking { rig.c.disablePin(pin("482916")) })
        rig.c.cancelPinStep()
        assertTrue(rig.c.beginPinRemoval())
        assertEquals(PinOperationResult.NotInStep, runBlocking { rig.c.changePin(pin("482916"), pin()) })
        assertEquals(0, rig.store.count("verify") - verifiesBefore)
        assertEquals(0, rig.store.count("changePin") - changesBefore)
        assertEquals(0, rig.store.count("deleteAll") - deletesBefore)
    }

    @Test
    fun `G25-1 beginPinChange and beginPinRemoval open their step only from UNLOCKED in APP_PIN mode and cancelPinStep closes it`() {
        val rig = pinModeRig()
        assertTrue(rig.c.beginPinChange())
        assertEquals(PinStep.CHANGE_ENTRY, rig.c.pin.value.step)
        assertFalse(rig.c.beginPinRemoval()) // another step is already open
        rig.c.cancelPinStep()
        assertEquals(PinStep.NONE, rig.c.pin.value.step)
        assertTrue(rig.c.beginPinRemoval())
        assertEquals(PinStep.REMOVE_CONFIRM, rig.c.pin.value.step)
        rig.c.cancelPinStep()
        rig.c.cancelPinStep() // idempotent
        assertEquals(PinStep.NONE, rig.c.pin.value.step)

        val deviceAuth = deviceAuthRig()
        assertFalse(deviceAuth.c.beginPinChange())
        assertFalse(deviceAuth.c.beginPinRemoval())
        assertEquals(PinStep.NONE, deviceAuth.c.pin.value.step)
    }

    @Test
    fun `G25-1 a re-lock closes an open setup, change or removal step`() {
        val setup = setupStepRig()
        setup.relock()
        assertEquals(PinStep.NONE, setup.c.pin.value.step)
        assertEquals(PinSetupRequestResult.NotAllowed, runBlocking { setup.c.enablePin(pin()) })

        for (begin in listOf<(AppLockController) -> Boolean>({ it.beginPinChange() }, { it.beginPinRemoval() })) {
            val rig = pinModeRig()
            assertTrue(begin(rig.c))
            rig.relock()
            assertEquals(PinStep.NONE, rig.c.pin.value.step)
            assertEquals(PinOperationResult.NotInStep, runBlocking { rig.c.disablePin(pin("482916")) })
            assertEquals(PinOperationResult.NotInStep, runBlocking { rig.c.changePin(pin("482916"), pin()) })
        }
    }

    @Test
    fun `G25-1 changePin Done and Failed close the step`() {
        val cases = listOf(
            PinChangeResult.Changed to PinOperationResult.Done,
            PinChangeResult.WriteFailed to PinOperationResult.Failed
        )
        for ((scripted, expected) in cases) {
            val rig = pinModeRig()
            rig.store.changeResult = scripted
            assertTrue(rig.c.beginPinChange())
            assertEquals(expected, runBlocking { rig.c.changePin(pin("482916"), pin()) })
            assertEquals("step after $expected", PinStep.NONE, rig.c.pin.value.step)
            assertEquals(1, rig.store.count("changePin"))
        }
    }

    @Test
    fun `G25-1 changePin keeps the step open on a format error and on a current PIN that was not verified`() {
        val rig = pinModeRig()
        assertTrue(rig.c.beginPinChange())
        // Format error: caught before the store is called.
        val result = runBlocking { rig.c.changePin(pin("482916"), pin("111111")) }
        assertEquals(PinOperationResult.InvalidFormat(PinFormatResult.ALL_SAME), result)
        assertEquals(0, rig.store.count("changePin"))
        assertEquals(PinStep.CHANGE_ENTRY, rig.c.pin.value.step)
        // Not verified: wrong, then delayed. The shared counter belongs to the store; the step stays open.
        rig.store.changeResult = PinChangeResult.NotVerified(PinVerifyResult.Wrong(PinDelayTier.S30))
        assertEquals(
            PinOperationResult.NotVerified(PinRejection.Wrong(PinDelayTier.S30)),
            runBlocking { rig.c.changePin(pin("111112"), pin()) }
        )
        assertEquals(PinStep.CHANGE_ENTRY, rig.c.pin.value.step)
        rig.store.changeResult = PinChangeResult.NotVerified(PinVerifyResult.Delayed(PinDelayReason.FAILURES, 9_000L))
        val delayed = runBlocking { rig.c.changePin(pin("111112"), pin()) }
        assertEquals(PinOperationResult.NotVerified(PinRejection.Delayed(PinDelayReason.FAILURES, 9_000L)), delayed)
        assertEquals(PinStep.CHANGE_ENTRY, rig.c.pin.value.step)
        // The step is still usable: a correct current PIN now completes it.
        rig.store.changeResult = PinChangeResult.Changed
        assertEquals(PinOperationResult.Done, runBlocking { rig.c.changePin(pin("482916"), pin()) })
        assertEquals(PinStep.NONE, rig.c.pin.value.step)
    }

    @Test
    fun `G25-1 disablePin keeps the step open on a wrong PIN and closes it on Done`() {
        val rig = pinModeRig()
        rig.store.verifyScript += PinVerifyResult.Wrong(PinDelayTier.NONE)
        rig.store.verifyScript += PinVerifyResult.Correct
        assertTrue(rig.c.beginPinRemoval())
        assertEquals(
            PinOperationResult.NotVerified(PinRejection.Wrong(PinDelayTier.NONE)),
            runBlocking { rig.c.disablePin(pin("111112")) }
        )
        assertEquals(PinStep.REMOVE_CONFIRM, rig.c.pin.value.step)
        assertEquals(PinOperationResult.Done, runBlocking { rig.c.disablePin(pin("482916")) })
        assertEquals(PinStep.NONE, rig.c.pin.value.step)
    }

    @Test
    fun `G25-1 enablePin keeps the step open on an invalid format and closes it on Enabled and Failed`() {
        val rig = setupStepRig()
        assertEquals(PinSetupRequestResult.InvalidFormat(PinFormatResult.TOO_SHORT), runBlocking { rig.c.enablePin(pin("1357")) })
        assertEquals(PinStep.SETUP_ENTRY, rig.c.pin.value.step)
        assertEquals(PinSetupRequestResult.Enabled, runBlocking { rig.c.enablePin(pin()) })
        assertEquals(PinStep.NONE, rig.c.pin.value.step)

        val failing = setupStepRig()
        failing.store.setupResult = PinSetupResult.FAILED
        assertEquals(PinSetupRequestResult.Failed, runBlocking { failing.c.enablePin(pin()) })
        assertEquals(PinStep.NONE, failing.c.pin.value.step)
    }

    // --- enablePin: INV-50 order and rollback -----------------------------------------------------

    @Test
    fun `INV-50 enablePin writes lockMode APP_PIN before the verifier and ends with both in place`() {
        val rig = setupStepRig()
        rig.logSettingsWritesInStoreLog()
        val before = rig.store.log.size
        assertEquals(PinSetupRequestResult.Enabled, runBlocking { rig.c.enablePin(pin()) })
        val ops = rig.store.log.drop(before).filter { it == "setup" || it.startsWith("setLockMode") }
        assertEquals(listOf("setLockMode:APP_PIN", "setup"), ops)
        assertEquals(listOf(LockMode.APP_PIN), rig.repo.lockModeWrites)
        assertEquals(LockMode.APP_PIN, (rig.repo.loadState as AppLockSettingsLoadState.Loaded).settings.lockMode)
    }

    @Test
    fun `INV-50 a setup failure reverts lockMode to DEVICE_AUTH after the APP_PIN write and reports Failed`() {
        val rig = setupStepRig()
        rig.logSettingsWritesInStoreLog()
        rig.store.setupResult = PinSetupResult.FAILED
        val before = rig.store.log.size
        val entered = pin()
        assertEquals(PinSetupRequestResult.Failed, runBlocking { rig.c.enablePin(entered) })
        val ops = rig.store.log.drop(before).filter { it == "setup" || it.startsWith("setLockMode") }
        assertEquals(listOf("setLockMode:APP_PIN", "setup", "setLockMode:DEVICE_AUTH"), ops)
        assertEquals(LockMode.DEVICE_AUTH, (rig.repo.loadState as AppLockSettingsLoadState.Loaded).settings.lockMode)
        assertEquals(EffectiveLockMode.DEVICE_AUTH, rig.c.pin.value.effectiveLockMode)
        assertEquals(AppLockState.Unlocked, rig.c.state.value)
        assertTrue(zeroed(entered))
    }

    @Test
    fun `INV-50 an invalid format writes nothing and still zeroes the array`() {
        for ((text, expected) in listOf("1357" to PinFormatResult.TOO_SHORT, "a38201" to PinFormatResult.NOT_ASCII_DIGITS)) {
            val rig = setupStepRig()
            val entered = pin(text)
            assertEquals(PinSetupRequestResult.InvalidFormat(expected), runBlocking { rig.c.enablePin(entered) })
            assertTrue(rig.repo.lockModeWrites.isEmpty())
            assertEquals(0, rig.store.count("setup"))
            assertTrue(zeroed(entered))
        }
    }

    @Test
    fun `INV-50 a failed APP_PIN mode write stops before the verifier is created`() {
        val rig = setupStepRig()
        rig.repo.lockModeWriteResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.LOCK_MODE) }
        assertEquals(PinSetupRequestResult.Failed, runBlocking { rig.c.enablePin(pin()) })
        assertEquals(0, rig.store.count("setup"))
        assertEquals(listOf(LockMode.APP_PIN), rig.repo.lockModeWrites)
    }

    @Test
    fun `G25-9 cancellation after the verifier committed does not revert lockMode`() {
        val rig = setupStepRig()
        // The verifier becomes PRESENT with the mode write, then setup is cancelled: the setup had committed.
        rig.repo.lockModeWriteResult = { mode ->
            val next = (rig.repo.loadState as AppLockSettingsLoadState.Loaded).settings.copy(lockMode = mode)
            rig.repo.loadState = AppLockSettingsLoadState.Loaded(next)
            rig.store.setVerifier(PinVerifierState.PRESENT)
            AppLockSettingsWriteResult.Committed(next)
        }
        val boom = CancellationException("synthetic")
        rig.store.failures["setup"] = boom
        val entered = pin()
        var caught: CancellationException? = null
        runBlocking {
            try {
                rig.c.enablePin(entered)
                fail("expected a CancellationException")
            } catch (e: CancellationException) {
                caught = e
            }
        }
        assertTrue("the cancellation was swallowed", caught === boom)
        assertEquals(listOf(LockMode.APP_PIN), rig.repo.lockModeWrites) // no revert
        assertTrue(zeroed(entered))
    }

    @Test
    fun `G25-9 cancellation before the verifier committed reverts lockMode to DEVICE_AUTH and propagates`() {
        val rig = setupStepRig()
        val boom = CancellationException("synthetic")
        rig.store.failures["setup"] = boom // the store still reads ABSENT
        val entered = pin()
        var caught: CancellationException? = null
        runBlocking {
            try {
                rig.c.enablePin(entered)
                fail("expected a CancellationException")
            } catch (e: CancellationException) {
                caught = e
            }
        }
        assertTrue("the cancellation was swallowed", caught === boom)
        assertEquals(listOf(LockMode.APP_PIN, LockMode.DEVICE_AUTH), rig.repo.lockModeWrites)
        assertEquals(LockMode.DEVICE_AUTH, (rig.repo.loadState as AppLockSettingsLoadState.Loaded).settings.lockMode)
        assertTrue(zeroed(entered))
    }

    // --- disablePin: INV-49 -----------------------------------------------------------------------

    private fun removalRig(capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE): Rig {
        val rig = pinModeRig()
        rig.h.capabilityProvider.capability = capability // read by disablePin; APP_PIN mode never needed it to stay unlocked
        rig.store.verifyScript += PinVerifyResult.Correct
        assertTrue(rig.c.beginPinRemoval())
        return rig
    }

    @Test
    fun `INV-49 disablePin verifies first, deletes the PIN data, then writes lockMode DEVICE_AUTH with the lock on`() {
        val rig = removalRig()
        rig.logSettingsWritesInStoreLog()
        val before = rig.store.log.size
        val entered = pin("482916")
        assertEquals(PinOperationResult.Done, runBlocking { rig.c.disablePin(entered) })
        val ops = rig.store.log.drop(before).filter { it == "verify" || it == "deleteAll" || it.startsWith("set") }
        assertEquals(listOf("verify", "deleteAll", "setLockMode:DEVICE_AUTH"), ops)
        assertTrue(rig.repo.lockWrites.isEmpty())
        val settings = (rig.repo.loadState as AppLockSettingsLoadState.Loaded).settings
        assertEquals(LockMode.DEVICE_AUTH, settings.lockMode)
        assertTrue(settings.appLockEnabled)
        assertEquals(AppLockState.Unlocked, rig.c.state.value)
        assertEquals(EffectiveLockMode.DEVICE_AUTH, rig.c.pin.value.effectiveLockMode)
        assertTrue(zeroed(entered))
    }

    @Test
    fun `INV-49 a wrong or delayed current PIN deletes nothing and writes no setting`() {
        val results = listOf(
            PinVerifyResult.Wrong(PinDelayTier.S30),
            PinVerifyResult.Delayed(PinDelayReason.FAILURES, 4_000L),
            PinVerifyResult.Unavailable
        )
        for (scripted in results) {
            val rig = pinModeRig()
            rig.store.verifyScript += scripted
            assertTrue(rig.c.beginPinRemoval())
            val entered = pin("111112")
            assertTrue(runBlocking { rig.c.disablePin(entered) } is PinOperationResult.NotVerified)
            assertEquals("deleteAll after $scripted", 0, rig.store.count("deleteAll"))
            assertTrue(rig.repo.lockModeWrites.isEmpty() && rig.repo.lockWrites.isEmpty())
            assertEquals(EffectiveLockMode.APP_PIN, rig.c.pin.value.effectiveLockMode)
            assertTrue(zeroed(entered))
        }
    }

    @Test
    fun `INV-49 a deleteAll failure leaves the settings untouched and ends the step with Failed`() {
        for (failure in listOf<Throwable?>(null, IllegalStateException("synthetic"))) {
            val rig = removalRig()
            if (failure == null) rig.store.deleteResult = PinDeleteResult.FAILED else rig.store.failures["deleteAll"] = failure
            assertEquals(PinOperationResult.Failed, runBlocking { rig.c.disablePin(pin("482916")) })
            assertTrue(rig.repo.lockModeWrites.isEmpty() && rig.repo.lockWrites.isEmpty())
            assertEquals(AppLockState.Unlocked, rig.c.state.value)
            assertEquals(PinStep.NONE, rig.c.pin.value.step)
        }
    }

    @Test
    fun `INV-49 a mode write failure after the delete reports ModeWriteFailed and the effective mode is DEVICE_AUTH`() {
        val rig = removalRig()
        rig.repo.lockModeWriteResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.LOCK_MODE) }
        assertEquals(PinOperationResult.ModeWriteFailed, runBlocking { rig.c.disablePin(pin("482916")) })
        assertEquals(1, rig.store.count("deleteAll"))
        assertEquals(EffectiveLockMode.DEVICE_AUTH, rig.c.pin.value.effectiveLockMode)
        assertEquals(PinStep.NONE, rig.c.pin.value.step)
    }

    @Test
    fun `G25-7b removePin with a capability that cannot authenticate deletes the PIN, writes lockMode and the lock flag off once, and ends DISABLED`() {
        for (capability in listOf(DeviceAuthCapability.NO_CREDENTIAL, DeviceAuthCapability.HW_UNAVAILABLE)) {
            val rig = removalRig(capability)
            val ops = SettingsOperationsRig(rig.h, rig.c)
            assertEquals(PinOperationResult.Done, runBlocking { ops.ops.removePin(pin("482916")) })
            assertEquals("deleteAll for $capability", 1, rig.store.count("deleteAll"))
            assertEquals(listOf(LockMode.DEVICE_AUTH), rig.repo.lockModeWrites)
            assertEquals("lock flag writes for $capability", listOf(false), rig.repo.lockWrites)
            val settings = (rig.repo.loadState as AppLockSettingsLoadState.Loaded).settings
            assertFalse(settings.appLockEnabled)
            assertEquals(AppLockState.Disabled, rig.c.state.value)
            assertTrue(ops.uncaught.isEmpty())
        }
    }

    @Test
    fun `G25-7b control removePin with an AVAILABLE capability writes no lock flag and the lock stays on`() {
        val rig = removalRig(DeviceAuthCapability.AVAILABLE)
        val ops = SettingsOperationsRig(rig.h, rig.c)
        assertEquals(PinOperationResult.Done, runBlocking { ops.ops.removePin(pin("482916")) })
        assertEquals(1, rig.store.count("deleteAll"))
        assertEquals(listOf(LockMode.DEVICE_AUTH), rig.repo.lockModeWrites)
        assertTrue(rig.repo.lockWrites.isEmpty())
        assertTrue((rig.repo.loadState as AppLockSettingsLoadState.Loaded).settings.appLockEnabled)
        assertEquals(AppLockState.Unlocked, rig.c.state.value)
    }

    @Test
    fun `G25-7 with the device capability unavailable disablePin deletes the PIN and returns lock-off-required without writing the flag`() {
        for (capability in listOf(DeviceAuthCapability.NO_CREDENTIAL, DeviceAuthCapability.HW_UNAVAILABLE)) {
            val rig = removalRig(capability)
            rig.logSettingsWritesInStoreLog()
            val before = rig.store.log.size
            assertEquals(
                PinOperationResult.RemovedLockOffRequired,
                runBlocking { rig.c.disablePin(pin("482916")) }
            )
            val ops = rig.store.log.drop(before).filter { it == "deleteAll" || it.startsWith("set") }
            assertEquals("order for $capability", listOf("deleteAll"), ops)
            // The controller never writes the lock flag; AppLockSettingsOperations.removePin performs the lock-off.
            assertTrue(rig.repo.lockWrites.isEmpty())
            assertTrue(rig.repo.lockModeWrites.isEmpty())
            assertTrue((rig.repo.loadState as AppLockSettingsLoadState.Loaded).settings.appLockEnabled)
        }
    }

    @Test
    fun `G25-7 a transient CHECK_FAILED capability keeps the lock on and writes lockMode DEVICE_AUTH`() {
        val rig = removalRig(DeviceAuthCapability.CHECK_FAILED)
        assertEquals(PinOperationResult.Done, runBlocking { rig.c.disablePin(pin("482916")) })
        assertTrue(rig.repo.lockWrites.isEmpty())
        assertEquals(listOf(LockMode.DEVICE_AUTH), rig.repo.lockModeWrites)
        assertTrue((rig.repo.loadState as AppLockSettingsLoadState.Loaded).settings.appLockEnabled)
        assertEquals(AppLockState.Unlocked, rig.c.state.value)
    }

    // --- G25-6: a re-lock between the precondition check and the write ------------------------------

    @Test
    fun `G25-6 a re-lock after the enablePin precondition check but before the write performs no write`() = runBlocking {
        val rig = setupStepRig()
        val release = CompletableDeferred<Unit>()
        // Another settings write holds the serialiser, so enablePin passes its first check and then waits for it.
        val holder = launch(Dispatchers.Unconfined) { rig.h.writeSerializer.withExclusiveWrite { release.await() } }
        val entered = pin()
        val op = async(Dispatchers.Unconfined) { rig.c.enablePin(entered) }
        assertFalse("enablePin should be waiting for the serialiser", op.isCompleted)
        rig.relock()
        release.complete(Unit)
        assertEquals(PinSetupRequestResult.NotAllowed, op.await())
        holder.join()
        assertTrue(rig.repo.lockModeWrites.isEmpty())
        assertEquals(0, rig.store.count("setup"))
        assertTrue(zeroed(entered))
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), rig.c.state.value)
    }

    @Test
    fun `G25-6 a re-lock between the removal verify and the delete performs no delete and no write`() = runBlocking {
        val rig = removalRig()
        val gate = CompletableDeferred<Unit>()
        rig.store.verifyGate = gate
        val verifiesBefore = rig.store.count("verify")
        val entered = pin("482916")
        val op = async(Dispatchers.Unconfined) { rig.c.disablePin(entered) }
        assertEquals(1, rig.store.count("verify") - verifiesBefore)
        assertFalse("disablePin should be waiting for the verify", op.isCompleted)
        rig.relock()
        gate.complete(Unit)
        assertEquals(PinOperationResult.NotInStep, op.await())
        assertEquals(0, rig.store.count("deleteAll"))
        assertTrue(rig.repo.lockModeWrites.isEmpty() && rig.repo.lockWrites.isEmpty())
        assertTrue(zeroed(entered))
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), rig.c.state.value)
    }

    // --- G25-10: store exceptions are fail-closed results, cancellation propagates ------------------

    @Test
    fun `G25-10 a non-cancellation exception from the store maps to a failed result in every PIN operation`() {
        val boom = IllegalStateException("synthetic")

        val setup = setupStepRig()
        setup.store.failures["setup"] = boom
        assertEquals(PinSetupRequestResult.Failed, runBlocking { setup.c.enablePin(pin()) })
        assertEquals(LockMode.DEVICE_AUTH, (setup.repo.loadState as AppLockSettingsLoadState.Loaded).settings.lockMode)

        val change = pinModeRig()
        change.store.failures["changePin"] = boom
        assertTrue(change.c.beginPinChange())
        assertEquals(PinOperationResult.Failed, runBlocking { change.c.changePin(pin("482916"), pin()) })
        assertEquals(PinStep.NONE, change.c.pin.value.step)

        val removal = pinModeRig()
        removal.store.failures["verify"] = boom
        assertTrue(removal.c.beginPinRemoval())
        assertEquals(
            PinOperationResult.NotVerified(PinRejection.Unavailable),
            runBlocking { removal.c.disablePin(pin("482916")) }
        )
        assertEquals(0, removal.store.count("deleteAll"))
        assertEquals(PinStep.REMOVE_CONFIRM, removal.c.pin.value.step)
    }

    @Test
    fun `G25-10 a store exception during unlock is Rejected Unavailable and the state stays LOCKED`() {
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, false, false))
        store.failures["verify"] = IllegalStateException("synthetic")
        val settings = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.IMMEDIATE, lockMode = LockMode.APP_PIN)
        val h = AppLockControllerHarness(AppLockSettingsLoadState.Loaded(settings), pinStore = store)
        val controller = h.resolvedController()
        val entered = pin("482916")
        assertEquals(PinUnlockResult.Rejected(PinRejection.Unavailable), runBlocking { controller.submitUnlockPin(entered) })
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertTrue(zeroed(entered))
        assertTrue(h.uncaught.isEmpty())
        // Not stuck: the in-flight guard was released, so the next entry is evaluated.
        store.failures.clear()
        store.verifyScript += PinVerifyResult.Correct
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin("482916")) })
    }

    @Test
    fun `G25-10 a CancellationException from the store propagates out of changePin and disablePin and zeroes the arrays`() {
        val change = pinModeRig()
        val changeBoom = CancellationException("synthetic change")
        change.store.failures["changePin"] = changeBoom
        assertTrue(change.c.beginPinChange())
        val current = pin("482916")
        val new = pin()
        var caught: CancellationException? = null
        runBlocking {
            try {
                change.c.changePin(current, new)
                fail("expected a CancellationException")
            } catch (e: CancellationException) {
                caught = e
            }
        }
        assertTrue("changePin swallowed the cancellation", caught === changeBoom)
        assertTrue(zeroed(current) && zeroed(new))

        val removal = removalRig()
        val removalBoom = CancellationException("synthetic removal")
        removal.store.failures["verify"] = removalBoom
        val entered = pin("482916")
        caught = null
        runBlocking {
            try {
                removal.c.disablePin(entered)
                fail("expected a CancellationException")
            } catch (e: CancellationException) {
                caught = e
            }
        }
        assertTrue("disablePin swallowed the cancellation", caught === removalBoom)
        assertTrue(zeroed(entered))
        assertEquals(0, removal.store.count("deleteAll"))
        // The in-flight guard was released: the very same step is usable again.
        removal.store.failures.clear()
        removal.store.verifyScript += PinVerifyResult.Correct
        assertEquals(PinOperationResult.Done, runBlocking { removal.c.disablePin(pin("482916")) })
    }

    // --- G25-3: a stale device-auth unlock success -------------------------------------------------

    private fun lockedDeviceAuthRig(): Rig {
        val settings = AppLockPrivacySettings(
            appLockEnabled = true,
            autoLockTimeout = AutoLockTimeout.IMMEDIATE,
            lockMode = LockMode.DEVICE_AUTH
        )
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.ABSENT, false, false))
        val h = AppLockControllerHarness(AppLockSettingsLoadState.Loaded(settings), pinStore = store)
        val controller = h.resolvedController()
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        return Rig(h, controller)
    }

    @Test
    fun `G25-3 control a device-auth unlock success in effective DEVICE_AUTH mode unlocks`() {
        val rig = lockedDeviceAuthRig()
        rig.h.tap(rig.c)
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, rig.c.state.value)
        assertTrue(rig.h.emitted.contains(PrivacyDiagnosticCode.LOCK_UNLOCKED))
    }

    @Test
    fun `G25-3 an in-flight device-auth unlock success after the effective mode became APP_PIN stays LOCKED`() {
        val rig = lockedDeviceAuthRig()
        rig.h.tap(rig.c)
        rig.store.setVerifier(PinVerifierState.PRESENT) // the verifier appears while the prompt is open
        assertEquals(EffectiveLockMode.APP_PIN, rig.c.pin.value.effectiveLockMode)
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), rig.c.state.value)
        assertFalse(rig.h.emitted.contains(PrivacyDiagnosticCode.LOCK_UNLOCKED))
        assertNull(rig.c.inFlightAttemptId)
        // The PIN is now the only way in.
        rig.store.verifyScript += PinVerifyResult.Correct
        assertEquals(PinUnlockResult.Unlocked, runBlocking { rig.c.submitUnlockPin(pin("482916")) })
    }

    // --- G25-8, INV-38: bounded views and zeroing ---------------------------------------------------

    @Test
    fun `G25-8 PinRejection Wrong toString carries no delay tier for any tier`() {
        for (tier in PinDelayTier.values()) {
            val rejection = PinRejection.Wrong(tier)
            assertFalse("tier ${tier.name} leaked", rejection.toString().contains(tier.name))
            val operation = PinOperationResult.NotVerified(rejection)
            assertFalse("tier ${tier.name} leaked", operation.toString().contains(tier.name))
            assertFalse(PinUnlockResult.Rejected(rejection).toString().contains(tier.name))
        }
        // The data itself is intact: equality still distinguishes tiers.
        assertFalse(PinRejection.Wrong(PinDelayTier.S30) == PinRejection.Wrong(PinDelayTier.M5))
    }

    @Test
    fun `INV-38 a concurrent PIN operation is AlreadyInFlight and its arrays are zeroed too`() = runBlocking {
        val rig = removalRig()
        val gate = CompletableDeferred<Unit>()
        rig.store.verifyGate = gate
        val first = async(Dispatchers.Unconfined) { rig.c.disablePin(pin("482916")) }
        assertFalse(first.isCompleted)
        val current = pin("482916")
        val new = pin()
        assertEquals(PinOperationResult.AlreadyInFlight, rig.c.changePin(current, new))
        assertTrue(zeroed(current) && zeroed(new))
        val setup = pin()
        assertEquals(PinSetupRequestResult.AlreadyInFlight, rig.c.enablePin(setup))
        assertTrue(zeroed(setup))
        val removal = pin("482916")
        assertEquals(PinOperationResult.AlreadyInFlight, rig.c.disablePin(removal))
        assertTrue(zeroed(removal))
        gate.complete(Unit)
        assertEquals(PinOperationResult.Done, first.await())
    }

    @Test
    fun `INV-38 changePin and disablePin zero every array after Done, Failed, NotVerified and a format error`() {
        val changeResults = listOf<PinChangeResult>(
            PinChangeResult.Changed,
            PinChangeResult.WriteFailed,
            PinChangeResult.NotVerified(PinVerifyResult.Wrong(PinDelayTier.NONE))
        )
        for (scripted in changeResults) {
            val rig = pinModeRig()
            rig.store.changeResult = scripted
            assertTrue(rig.c.beginPinChange())
            val current = pin("482916")
            val new = pin()
            runBlocking { rig.c.changePin(current, new) }
            assertTrue("arrays after $scripted", zeroed(current) && zeroed(new))
            val call = rig.store.callsOf("changePin").single()
            assertEquals(listOf("482916", "739104"), call.contentAtCall) // the store saw the digits
            assertTrue(call.allZeroedNow())
        }
        val rig = pinModeRig()
        assertTrue(rig.c.beginPinChange())
        val current = pin("482916")
        val tooShort = pin("1357")
        assertTrue(runBlocking { rig.c.changePin(current, tooShort) } is PinOperationResult.InvalidFormat)
        assertTrue(zeroed(current) && zeroed(tooShort))

        val removal = removalRig()
        removal.store.deleteResult = PinDeleteResult.FAILED
        val entered = pin("482916")
        assertEquals(PinOperationResult.Failed, runBlocking { removal.c.disablePin(entered) })
        assertTrue(zeroed(entered))
        assertTrue(removal.store.callsOf("verify").last().allZeroedNow())
    }

    @Test
    fun `INV-38 enablePin hands the store the digits and every handed-over array ends zeroed`() {
        val rig = setupStepRig()
        val entered = pin()
        assertEquals(PinSetupRequestResult.Enabled, runBlocking { rig.c.enablePin(entered) })
        val call = rig.store.callsOf("setup").single()
        assertEquals("739104", call.contentAtCall.single())
        assertTrue(call.allZeroedNow())
        assertTrue(zeroed(entered))
    }

    // --- D-I2-7: the frozen fingerprint hook ---------------------------------------------------------

    @Test
    fun `D-I2-7 the fingerprint hook stays inert after a PIN setup, change and removal`() {
        val rig = pinModeRig()
        assertFalse(rig.c.canOfferFingerprintUnlock.value)
        assertEquals(FingerprintUnlockResult.NOT_AVAILABLE, runBlocking { rig.c.onFingerprintUnlockSucceeded() })
        assertTrue(rig.c.beginPinChange())
        assertEquals(PinOperationResult.Done, runBlocking { rig.c.changePin(pin("482916"), pin()) })
        assertFalse(rig.c.canOfferFingerprintUnlock.value)
        assertEquals(FingerprintUnlockResult.NOT_AVAILABLE, runBlocking { rig.c.onFingerprintUnlockSucceeded() })
        assertEquals(0, rig.store.count("resetAttempts"))
        assertEquals(AppLockState.Unlocked, rig.c.state.value)
    }

}
