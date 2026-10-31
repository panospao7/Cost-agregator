package com.yourname.expensetracker.ui.screens.privacysettings.applockpin

import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockControllerHarness
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingField
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteResult
import com.yourname.expensetracker.domain.privacy.applock.AppLockWriteRejection
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapability
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthOutcome
import com.yourname.expensetracker.domain.privacy.applock.FakeAppPinStore
import com.yourname.expensetracker.domain.privacy.applock.LockCause
import com.yourname.expensetracker.domain.privacy.applock.LockMode
import com.yourname.expensetracker.domain.privacy.applock.PinStep
import com.yourname.expensetracker.domain.privacy.applock.PinUnlockResult
import com.yourname.expensetracker.domain.privacy.applock.SettingsOperationsRig
import com.yourname.expensetracker.domain.privacy.applock.disabledControllerFor
import com.yourname.expensetracker.domain.privacy.applock.lockedControllerFor
import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStoreState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinChangeResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayTier
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.yourname.expensetracker.domain.privacy.applock.AuthPromptCopy
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthErrorBucket

/**
 * S27 b1: [AppPinSettingsViewModel] end-to-end over the REAL [AppLockController] and settings operations (harness
 * fakes only for the stores and the device prompt). INV-50, UQ-4, INV-38, INV-42, AE-104, AE-119.
 * Runs on Robolectric (API 34) so the S26b fingerprint toggle's `SDK_INT >= 30` gate is satisfied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AppPinSettingsViewModelTest {

    private val eventScope = CoroutineScope(Dispatchers.Unconfined)
    private val events = mutableListOf<PinFlowEvent>()
    private var eventJob: Job? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        eventJob?.cancel()
        Dispatchers.resetMain()
    }

    private class Rig(val h: AppLockControllerHarness, val c: AppLockController) {
        val ops = SettingsOperationsRig(h, c)
        val store: FakeAppPinStore get() = h.pinStore
        fun vm() = AppPinSettingsViewModel(c, ops.ops, h.capabilityProvider)
    }

    private fun AppPinSettingsViewModel.collectEvents() {
        eventJob = eventScope.launch { flowEvents.collect { events += it } }
    }

    private fun pin(text: String = "739104") = text.toCharArray()

    private fun zeroed(a: CharArray) = a.isNotEmpty() && a.all { it == '\u0000' }

    private fun held(vm: AppPinSettingsViewModel, field: String): CharArray? =
        AppPinSettingsViewModel::class.java.getDeclaredField(field).apply { isAccessible = true }.get(vm) as CharArray?

    /** UNLOCKED, lock on, no verifier (effective DEVICE_AUTH): ready for the setup flow. */
    private fun deviceAuthRig(): Rig {
        val settings = AppLockPrivacySettings(
            appLockEnabled = true, autoLockTimeout = AutoLockTimeout.IMMEDIATE, lockMode = LockMode.DEVICE_AUTH
        )
        val h = AppLockControllerHarness(
            AppLockSettingsLoadState.Loaded(settings),
            DeviceAuthCapability.AVAILABLE,
            pinStore = FakeAppPinStore(AppPinStoreState(PinVerifierState.ABSENT, false, false))
        )
        val c = h.resolvedController()
        h.unlock(c)
        c.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, c.state.value)
        return Rig(h, c)
    }

    /** UNLOCKED in effective APP_PIN mode (verifier PRESENT, stored APP_PIN). */
    private fun pinModeRig(biometricEnabled: Boolean = false): Rig {
        val settings = AppLockPrivacySettings(
            appLockEnabled = true, autoLockTimeout = AutoLockTimeout.IMMEDIATE, lockMode = LockMode.APP_PIN
        )
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, biometricEnabled, false))
        store.verifyScript += PinVerifyResult.Correct
        val h = AppLockControllerHarness(AppLockSettingsLoadState.Loaded(settings), pinStore = store)
        val c = h.resolvedController()
        // LOW-2: a PIN unlock while backgrounded stamps a background time, so foreground first.
        c.onAppForegrounded()
        assertEquals(PinUnlockResult.Unlocked, runBlocking { c.submitUnlockPin(pin("482916")) })
        assertEquals(AppLockState.Unlocked, c.state.value)
        return Rig(h, c)
    }

    /** Opens the setup step through the VM (ack, Continue, device prompt succeeds) and returns the VM. */
    private fun Rig.openSetupEntry(): AppPinSettingsViewModel {
        val vm = vm()
        vm.collectEvents()
        vm.startSetup()
        vm.acknowledge(true)
        vm.continueSetup()
        h.settle()
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(PinFlowStep.SETUP_ENTER, vm.uiState.value.step)
        return vm
    }

    /** The settings write of lockMode=APP_PIN also makes the fake store report a PRESENT verifier (as the real setup does). */
    private fun Rig.makeSetupCommitVerifier() {
        h.repository.lockModeWriteResult = { mode ->
            if (mode == LockMode.APP_PIN) store.setVerifier(PinVerifierState.PRESENT)
            val next = (h.repository.loadState as AppLockSettingsLoadState.Loaded).settings.copy(lockMode = mode)
            h.repository.loadState = AppLockSettingsLoadState.Loaded(next)
            AppLockSettingsWriteResult.Committed(next)
        }
    }

    // --- INV-50 availability ---------------------------------------------------------------------------------

    @Test
    fun `INV-50 availability is AVAILABLE while unlocked and the setup row works`() {
        val rig = deviceAuthRig()
        val vm = rig.vm()
        assertEquals(AppPinAvailability.AVAILABLE, vm.uiState.value.availability)
        vm.startSetup()
        assertEquals(PinFlowStep.SETUP_ACK, vm.uiState.value.step)
    }

    @Test
    fun `INV-50 availability is PAUSED while the lock is inert and no flow can start`() {
        val (h, c) = lockedControllerFor(LockCause.COLD_START)
        h.tap(c)
        h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        h.authenticator.deliver(DeviceAuthOutcome.NotAvailable)
        assertTrue(c.state.value is AppLockState.Inert)
        val vm = Rig(h, c).vm()
        assertEquals(AppPinAvailability.PAUSED, vm.uiState.value.availability)
        vm.startSetup()
        vm.startChange()
        vm.startRemove()
        assertEquals(PinFlowStep.NONE, vm.uiState.value.step)
    }

    @Test
    fun `INV-50 availability is LOCK_OFF while the lock is disabled and no flow can start`() {
        val (h, c) = disabledControllerFor()
        val vm = Rig(h, c).vm()
        assertEquals(AppPinAvailability.LOCK_OFF, vm.uiState.value.availability)
        vm.startSetup()
        assertEquals(PinFlowStep.NONE, vm.uiState.value.step)
    }

    // --- UQ-4 / setup ------------------------------------------------------------------------------------------

    @Test
    fun `UQ-4 Continue without the acknowledgement requests no device prompt`() {
        val rig = deviceAuthRig()
        val vm = rig.vm()
        val before = rig.h.authenticator.calls.size
        vm.startSetup()
        vm.continueSetup()
        rig.h.settle()
        assertEquals(PinFlowStep.SETUP_ACK, vm.uiState.value.step)
        assertEquals(before, rig.h.authenticator.calls.size)
        assertEquals(PinStep.NONE, rig.c.pin.value.step)
        vm.acknowledge(true)
        vm.continueSetup()
        rig.h.settle()
        assertEquals(before + 1, rig.h.authenticator.calls.size) // the control: acknowledged, one fresh prompt
    }

    @Test
    fun `setup enter and matching confirm enables the PIN, emits PIN_SET and pinSet is true`() {
        val rig = deviceAuthRig()
        rig.makeSetupCommitVerifier()
        val vm = rig.openSetupEntry()
        vm.submitEntry(pin())
        assertEquals(PinFlowStep.SETUP_CONFIRM, vm.uiState.value.step)
        assertEquals(0, rig.store.count("setup"))
        vm.submitEntry(pin())
        assertEquals(listOf(PinFlowEvent.PIN_SET), events)
        assertEquals(1, rig.store.count("setup"))
        assertEquals(listOf(LockMode.APP_PIN), rig.h.repository.lockModeWrites)
        assertEquals(PinFlowStep.NONE, vm.uiState.value.step)
        assertTrue(vm.uiState.value.pinSet)
        assertNull(held(vm, "heldNew"))
    }

    @Test
    fun `setup mismatch returns to the first entry step with a MISMATCH message and zeroes the held first entry`() {
        val rig = deviceAuthRig()
        val vm = rig.openSetupEntry()
        val first = pin()
        vm.submitEntry(first)
        assertSame(first, held(vm, "heldNew"))
        var attempts = 0
        while (vm.uiState.value.step != PinFlowStep.SETUP_ENTER && attempts < 3) {
            vm.submitEntry(pin("739105"))
            attempts++
            assertEquals(PinFlowMessage.MISMATCH, vm.uiState.value.message)
        }
        assertEquals(PinFlowStep.SETUP_ENTER, vm.uiState.value.step)
        assertTrue(attempts in 1..2)
        assertTrue("first entry zero-filled", zeroed(first))
        assertNull(held(vm, "heldNew"))
        assertEquals(0, rig.store.count("setup"))
        assertTrue(events.isEmpty())
        assertFalse(vm.uiState.value.pinSet)
    }

    @Test
    fun `setup too weak first entry stays on the entry step and is zeroed`() {
        val rig = deviceAuthRig()
        val vm = rig.openSetupEntry()
        val weak = pin("12")
        vm.submitEntry(weak)
        assertEquals(PinFlowStep.SETUP_ENTER, vm.uiState.value.step)
        assertEquals(PinFlowMessage.TOO_WEAK, vm.uiState.value.message)
        assertTrue(zeroed(weak))
        assertNull(held(vm, "heldNew"))
    }

    // --- change ------------------------------------------------------------------------------------------------

    private fun Rig.openChange(): AppPinSettingsViewModel {
        val vm = vm()
        vm.collectEvents()
        vm.startChange()
        assertEquals(PinFlowStep.CHANGE_CURRENT, vm.uiState.value.step)
        return vm
    }

    private fun AppPinSettingsViewModel.enterChange(current: String = "482916", new: String = "739104") {
        submitEntry(pin(current))
        assertEquals(PinFlowStep.CHANGE_NEW, uiState.value.step)
        submitEntry(pin(new))
        assertEquals(PinFlowStep.CHANGE_CONFIRM, uiState.value.step)
        submitEntry(pin(new))
    }

    @Test
    fun `change with a wrong current PIN returns to the current step with the WRONG message and emits nothing`() {
        val rig = pinModeRig()
        val vm = rig.openChange()
        rig.store.changeResult = PinChangeResult.NotVerified(PinVerifyResult.Wrong(PinDelayTier.NONE))
        vm.enterChange()
        assertEquals(PinFlowStep.CHANGE_CURRENT, vm.uiState.value.step)
        assertEquals(PinFlowMessage.WRONG, vm.uiState.value.message)
        assertTrue(events.isEmpty())
        assertNull(held(vm, "heldCurrent"))
        assertNull(held(vm, "heldNew"))
        // control: the correct current PIN completes the change
        rig.store.changeResult = PinChangeResult.Changed
        vm.enterChange()
        assertEquals(listOf(PinFlowEvent.PIN_CHANGED), events)
        assertEquals(PinFlowStep.NONE, vm.uiState.value.step)
        assertEquals(PinStep.NONE, rig.c.pin.value.step)
    }

    @Test
    fun `change hands both entries to the store and leaves them zero-filled`() {
        val rig = pinModeRig()
        val vm = rig.openChange()
        vm.enterChange()
        assertEquals(listOf(PinFlowEvent.PIN_CHANGED), events)
        val call = rig.store.callsOf("changePin").single()
        assertEquals(listOf("482916", "739104"), call.contentAtCall)
        assertTrue(call.allZeroedNow())
    }

    // --- remove ------------------------------------------------------------------------------------------------

    @Test
    fun `remove confirm with the correct PIN emits PIN_REMOVED and the lock stays on`() {
        val rig = pinModeRig()
        rig.store.verifyScript += PinVerifyResult.Correct
        val vm = rig.vm()
        vm.collectEvents()
        vm.startRemove()
        assertEquals(PinFlowStep.REMOVE_ENTER, vm.uiState.value.step)
        assertFalse(vm.uiState.value.removeNoSecure)
        vm.submitEntry(pin("482916"))
        assertEquals(PinFlowStep.REMOVE_CONFIRM, vm.uiState.value.step)
        assertEquals(0, rig.store.count("deleteAll"))
        vm.confirmRemove()
        assertEquals(listOf(PinFlowEvent.PIN_REMOVED), events)
        assertEquals(1, rig.store.count("deleteAll"))
        assertEquals(PinFlowStep.NONE, vm.uiState.value.step)
        assertEquals(AppLockState.Unlocked, rig.c.state.value)
    }

    @Test
    fun `remove with no usable device screen lock emits PIN_REMOVED_LOCK_OFF and the lock turns off`() {
        val rig = pinModeRig()
        rig.store.verifyScript += PinVerifyResult.Correct
        rig.h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        val vm = rig.vm()
        vm.collectEvents()
        vm.startRemove()
        assertTrue(vm.uiState.value.removeNoSecure)
        vm.submitEntry(pin("482916"))
        vm.confirmRemove()
        assertEquals(listOf(PinFlowEvent.PIN_REMOVED_LOCK_OFF), events)
        assertEquals(AppLockState.Disabled, rig.c.state.value)
    }

    @Test
    fun `remove with a wrong PIN deletes nothing and returns to the entry step`() {
        val rig = pinModeRig()
        rig.store.verifyScript += PinVerifyResult.Wrong(PinDelayTier.NONE)
        val vm = rig.vm()
        vm.collectEvents()
        vm.startRemove()
        vm.submitEntry(pin("111112"))
        vm.confirmRemove()
        assertEquals(PinFlowStep.REMOVE_ENTER, vm.uiState.value.step)
        assertEquals(PinFlowMessage.WRONG, vm.uiState.value.message)
        assertEquals(0, rig.store.count("deleteAll"))
        assertTrue(events.isEmpty())
    }

    // --- S27b turn off app lock (T30 disable variant) -----------------------------------------------------------

    @Test
    fun `S27b turn off with the correct PIN removes the verifier, turns the lock off and emits PIN_REMOVED_LOCK_OFF`() {
        val rig = pinModeRig()
        rig.store.verifyScript += PinVerifyResult.Correct
        val vm = rig.vm()
        vm.collectEvents()
        vm.startTurnOff()
        assertEquals(PinFlowStep.TURN_OFF_ENTER, vm.uiState.value.step)
        val current = pin("482916")
        vm.submitEntry(current)
        assertEquals(PinFlowStep.TURN_OFF_CONFIRM, vm.uiState.value.step)
        assertSame("held until the operation", current, held(vm, "heldCurrent"))
        assertEquals("nothing is deleted before the confirm", 0, rig.store.count("deleteAll"))
        assertEquals(AppLockState.Unlocked, rig.c.state.value)

        vm.confirmTurnOff()

        assertEquals(listOf(PinFlowEvent.PIN_REMOVED_LOCK_OFF), events)
        assertEquals(1, rig.store.count("deleteAll"))
        assertEquals(AppLockState.Disabled, rig.c.state.value)
        assertEquals(PinFlowStep.NONE, vm.uiState.value.step)
        assertTrue("held current PIN zero-filled", zeroed(current))
        assertNull(held(vm, "heldCurrent"))
    }

    @Test
    fun `INV-49 turn off whose lock-off write fails emits TURN_OFF_INCOMPLETE and never claims PIN_REMOVED or PIN_REMOVED_LOCK_OFF`() {
        val failures = mapOf<String, () -> AppLockSettingsWriteResult>(
            "failed" to { AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED) },
            "rejected" to { AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE) }
        )
        for ((label, failure) in failures) {
            events.clear()
            val rig = pinModeRig()
            rig.store.verifyScript += PinVerifyResult.Correct
            rig.h.repository.lockWriteResult = { failure() }
            val vm = rig.vm()
            vm.collectEvents()
            vm.startTurnOff()
            vm.submitEntry(pin("482916"))
            assertEquals(label, PinFlowStep.TURN_OFF_CONFIRM, vm.uiState.value.step)
            vm.confirmTurnOff()

            assertEquals(label, listOf(PinFlowEvent.TURN_OFF_INCOMPLETE), events)
            assertFalse(label, events.contains(PinFlowEvent.PIN_REMOVED))
            assertFalse(label, events.contains(PinFlowEvent.PIN_REMOVED_LOCK_OFF))
            assertEquals(label, listOf(false), rig.h.repository.lockWrites) // the lock-off write was attempted once
            assertEquals(label, PinFlowStep.NONE, vm.uiState.value.step)
            assertNull(label, held(vm, "heldCurrent"))
            assertTrue(label, rig.c.state.value !is AppLockState.Disabled)
            eventJob?.cancel()
        }
    }

    @Test
    fun `S27b turn off with a wrong PIN deletes nothing, keeps the lock on and returns to the entry step`() {
        val rig = pinModeRig()
        rig.store.verifyScript += PinVerifyResult.Wrong(PinDelayTier.NONE)
        val vm = rig.vm()
        vm.collectEvents()
        vm.startTurnOff()
        val wrong = pin("111112")
        vm.submitEntry(wrong)
        vm.confirmTurnOff()

        assertEquals(PinFlowStep.TURN_OFF_ENTER, vm.uiState.value.step)
        assertEquals(PinFlowMessage.WRONG, vm.uiState.value.message)
        assertEquals(0, rig.store.count("deleteAll"))
        assertEquals(AppLockState.Unlocked, rig.c.state.value)
        assertTrue(events.isEmpty())
        assertTrue("the rejected entry is zero-filled", zeroed(wrong))
        assertNull(held(vm, "heldCurrent"))
    }

    @Test
    fun `S27b turn off cannot start without a PIN (device-auth mode) and confirm before an entry opens nothing`() {
        val deviceAuth = deviceAuthRig().vm()
        deviceAuth.startTurnOff()
        assertEquals(PinFlowStep.NONE, deviceAuth.uiState.value.step)

        val rig = pinModeRig()
        val vm = rig.vm()
        vm.collectEvents()
        vm.startTurnOff()
        vm.confirmTurnOff() // still on the entry step: ignored
        assertEquals(PinFlowStep.TURN_OFF_ENTER, vm.uiState.value.step)
        assertEquals(0, rig.store.count("deleteAll"))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `S27b reviewer B2 startSetup cancels a stale controller PIN step so a fresh device-auth edge reaches SETUP_ENTER`() {
        val rig = deviceAuthRig()
        val vm = rig.vm() // constructed first: its init sees no open step (the rotation-during-prompt case)
        vm.collectEvents()
        // A prompt that finished for a previous screen instance leaves a step open in the controller.
        rig.c.requestPinSetup()
        rig.h.settle()
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals("control: the stale step is open", PinStep.SETUP_ENTRY, rig.c.pin.value.step)
        assertEquals(PinFlowStep.NONE, vm.uiState.value.step)

        vm.startSetup()
        assertEquals("the stale step was closed first", PinStep.NONE, rig.c.pin.value.step)
        assertEquals(PinFlowStep.SETUP_ACK, vm.uiState.value.step)
        vm.acknowledge(true)
        vm.continueSetup()
        rig.h.settle()
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(PinStep.SETUP_ENTRY, rig.c.pin.value.step)
        assertEquals(PinFlowStep.SETUP_ENTER, vm.uiState.value.step)
        assertTrue(events.isEmpty())
    }

    // --- cancel, clear, back up ---------------------------------------------------------------------------------

    @Test
    fun `cancel closes the controller step, leaves the flow and zeroes the held entry`() {
        val rig = deviceAuthRig()
        val vm = rig.openSetupEntry()
        val first = pin()
        vm.submitEntry(first)
        assertEquals(PinStep.SETUP_ENTRY, rig.c.pin.value.step)
        vm.cancel()
        assertEquals(PinFlowStep.NONE, vm.uiState.value.step)
        assertEquals(PinStep.NONE, rig.c.pin.value.step)
        assertTrue(zeroed(first))
        assertNull(held(vm, "heldNew"))
        assertEquals(0, rig.store.count("setup"))
    }

    @Test
    fun `onCleared zeroes the held entries and closes the controller step`() {
        val rig = deviceAuthRig()
        val vm = rig.openSetupEntry()
        val first = pin()
        vm.submitEntry(first)
        AppPinSettingsViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(vm)
        assertTrue(zeroed(first))
        assertNull(held(vm, "heldNew"))
        assertEquals(PinStep.NONE, rig.c.pin.value.step)
    }

    @Test
    fun `S27 onScreenLeft zeroes the held setup entry and closes the controller step`() {
        val rig = deviceAuthRig()
        val vm = rig.openSetupEntry()
        val first = pin()
        vm.submitEntry(first)
        assertSame(first, held(vm, "heldNew")) // control: held before leaving
        assertEquals(PinStep.SETUP_ENTRY, rig.c.pin.value.step)
        vm.onScreenLeft()
        assertTrue(zeroed(first))
        assertNull(held(vm, "heldNew"))
        assertEquals(PinFlowStep.NONE, vm.uiState.value.step)
        assertEquals(PinStep.NONE, rig.c.pin.value.step)
        assertEquals(0, rig.store.count("setup"))
    }

    @Test
    fun `S27 onScreenLeft zeroes the held current PIN of the change flow`() {
        val rig = pinModeRig()
        val vm = rig.openChange()
        val current = pin("482916")
        vm.submitEntry(current)
        assertSame(current, held(vm, "heldCurrent")) // control
        vm.onScreenLeft()
        assertTrue(zeroed(current))
        assertNull(held(vm, "heldCurrent"))
        assertEquals(PinStep.NONE, rig.c.pin.value.step)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `S27 PinSetupRequestResult NotAllowed from enablePin emits OPERATION_FAILED`() {
        val rig = deviceAuthRig()
        val vm = rig.openSetupEntry()
        rig.c.cancelPinStep() // the controller step is gone, so enablePin is refused with NotAllowed
        val entry = pin()
        AppPinSettingsViewModel::class.java.getDeclaredMethod("runSetup", CharArray::class.java)
            .apply { isAccessible = true }.invoke(vm, entry)
        assertEquals(listOf(PinFlowEvent.OPERATION_FAILED), events)
        assertEquals(0, rig.store.count("setup"))
        assertTrue(zeroed(entry))
        assertFalse(vm.uiState.value.pinSet)
    }

    @Test
    fun `AE-104 Back up now only emits OPEN_BACKUP_RESTORE and calls no backup or store API`() {
        val rig = deviceAuthRig()
        val vm = rig.vm()
        vm.collectEvents()
        val storeLog = rig.store.log.toList()
        val prompts = rig.h.authenticator.calls.size
        vm.onBackUpNow()
        assertEquals(listOf(PinFlowEvent.OPEN_BACKUP_RESTORE), events)
        assertEquals(storeLog, rig.store.log.toList())
        assertEquals(prompts, rig.h.authenticator.calls.size)
        assertTrue(rig.h.repository.lockModeWrites.isEmpty() && rig.h.repository.lockWrites.isEmpty())
    }

    @Test
    fun `AE-119 a step left open by an earlier screen is closed when a new ViewModel starts`() {
        val rig = pinModeRig()
        assertTrue(rig.c.beginPinChange())
        assertEquals(PinStep.CHANGE_ENTRY, rig.c.pin.value.step)
        val vm = rig.vm()
        assertEquals(PinStep.NONE, rig.c.pin.value.step)
        assertEquals(PinFlowStep.NONE, vm.uiState.value.step)
    }

    // --- S26b fingerprint unlock (INV-40, INV-54, STOP-5) -------------------------------------------------------

    /** Opens FINGERPRINT_ENTER through the VM on an unlocked APP_PIN rig whose store applies flag writes. */
    private fun Rig.openFingerprintEnter(): AppPinSettingsViewModel {
        store.applyFlagWrites = true
        store.verifyScript += PinVerifyResult.Correct // the entry for the enable (the unlock consumed the first)
        val vm = vm()
        vm.collectEvents()
        assertTrue(vm.uiState.value.fingerprintToggleVisible)
        vm.startFingerprintEnable()
        assertEquals(PinFlowStep.FINGERPRINT_ENTER, vm.uiState.value.step)
        return vm
    }

    /** Settles and polls [condition] (bounded 5 s): enableFingerprint hops to Dispatchers.Default for the key creation. */
    private fun awaitCondition(h: AppLockControllerHarness, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            h.settle()
            if (condition()) return
            Thread.sleep(10)
        }
        h.settle()
    }

    @Test
    fun `INV-40 fingerprint enable with the correct PIN and a successful prompt emits FINGERPRINT_ON and the flag is on`() {
        val rig = pinModeRig()
        val vm = rig.openFingerprintEnter()
        assertFalse(vm.uiState.value.fingerprintEnabled)
        val promptsBefore = rig.h.authenticator.calls.size
        vm.submitEntry(pin("482916"))
        awaitCondition(rig.h) { rig.h.authenticator.calls.size > promptsBefore }
        assertEquals(1, rig.h.fingerprintKeyGate.createCalls)
        assertTrue(events.isEmpty()) // nothing is claimed before the prompt outcome
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        awaitCondition(rig.h) { events.isNotEmpty() }
        assertEquals(listOf(PinFlowEvent.FINGERPRINT_ON), events)
        assertEquals(listOf(true), rig.store.biometricWrites)
        assertTrue(vm.uiState.value.fingerprintEnabled)
    }

    @Test
    fun `INV-40 fingerprint enable whose key cannot be created emits FINGERPRINT_UNAVAILABLE and starts no prompt`() {
        val rig = pinModeRig()
        val vm = rig.openFingerprintEnter()
        rig.h.fingerprintKeyGate.createResult = false
        val promptsBefore = rig.h.authenticator.calls.size
        vm.submitEntry(pin("482916"))
        awaitCondition(rig.h) { events.isNotEmpty() }
        assertEquals(listOf(PinFlowEvent.FINGERPRINT_UNAVAILABLE), events)
        assertEquals(promptsBefore, rig.h.authenticator.calls.size)
        assertEquals(PinFlowStep.NONE, vm.uiState.value.step)
        assertFalse(vm.uiState.value.fingerprintEnabled)
        assertTrue(rig.store.biometricWrites.isEmpty())
    }

    @Test
    fun `INV-40 fingerprint enable whose prompt is NotAvailable emits FINGERPRINT_UNAVAILABLE and leaves the flag off`() {
        val rig = pinModeRig()
        val vm = rig.openFingerprintEnter()
        val promptsBefore = rig.h.authenticator.calls.size
        vm.submitEntry(pin("482916"))
        awaitCondition(rig.h) { rig.h.authenticator.calls.size > promptsBefore }
        rig.h.authenticator.deliver(DeviceAuthOutcome.NotAvailable)
        awaitCondition(rig.h) { events.isNotEmpty() }
        assertEquals(listOf(PinFlowEvent.FINGERPRINT_UNAVAILABLE), events)
        assertFalse(vm.uiState.value.fingerprintEnabled)
        assertTrue(rig.store.biometricWrites.isEmpty())
    }

    @Test
    fun `STOP-5 disableFingerprint after it was enabled emits FINGERPRINT_OFF with one biometric write of false`() {
        val rig = pinModeRig(biometricEnabled = true)
        rig.store.applyFlagWrites = true
        val vm = rig.vm()
        vm.collectEvents()
        assertTrue(vm.uiState.value.fingerprintEnabled) // control: it is on
        vm.disableFingerprint()
        rig.h.settle()
        awaitCondition(rig.h) { events.isNotEmpty() }
        assertEquals(listOf(PinFlowEvent.FINGERPRINT_OFF), events)
        assertEquals(listOf(false), rig.store.biometricWrites)
        assertFalse(vm.uiState.value.fingerprintEnabled)
    }
}
