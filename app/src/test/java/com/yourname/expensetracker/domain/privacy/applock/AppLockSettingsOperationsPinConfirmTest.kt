package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStoreState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayReason
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayTier
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifyResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S27b A3 (AMD-2, T30, INV-40, INV-54): [AppLockSettingsOperations.requestTimeoutChangeWithPin] and
 * [AppLockSettingsOperations.requestReviewSaveWithPin] against the real controller, with the PIN confirm steps. Synthetic
 * PINs only; nothing here reads or prints a real credential.
 */
class AppLockSettingsOperationsPinConfirmTest {

    private fun pin(text: String = "482916"): CharArray = text.toCharArray()

    private fun settingsOf(rig: SettingsOperationsRig): AppLockPrivacySettings =
        (rig.h.repository.loadState as AppLockSettingsLoadState.Loaded).settings

    private fun cleared(rig: SettingsOperationsRig): SettingsOperationsRig {
        rig.h.authenticator.calls.clear()
        rig.h.pinStore.log.clear()
        rig.h.emitted.clear()
        rig.h.emittedMetadata.clear()
        return rig
    }

    /** UNLOCKED in effective APP_PIN mode (lock on, [timeout]), entered through a real PIN unlock; traffic cleared. */
    private fun timeoutRig(timeout: AutoLockTimeout = AutoLockTimeout.M1): SettingsOperationsRig {
        val settings = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = timeout, lockMode = LockMode.APP_PIN)
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, false, false))
        store.verifyScript += PinVerifyResult.Correct
        val h = AppLockControllerHarness(AppLockSettingsLoadState.Loaded(settings), pinStore = store)
        val controller = h.resolvedController()
        controller.onAppForegrounded()
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin()) })
        assertEquals(EffectiveLockMode.APP_PIN, controller.pin.value.effectiveLockMode)
        return cleared(SettingsOperationsRig(h, controller))
    }

    /** Effective APP_PIN over a persistently unreadable store (the review-save precondition); traffic cleared. */
    private fun reviewRig(): SettingsOperationsRig {
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, false, false))
        store.verifyScript += PinVerifyResult.Correct
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED), pinStore = store)
        val controller = h.resolvedController()
        controller.onAppForegrounded()
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin()) })
        assertEquals(EffectiveLockMode.APP_PIN, controller.pin.value.effectiveLockMode)
        return cleared(SettingsOperationsRig(h, controller))
    }

    private val weakening = AppLockPrivacySettings(appLockEnabled = false)

    /** Opens the TIMEOUT_CONFIRM step with [results] scripted for the confirm verify. */
    private fun openTimeout(rig: SettingsOperationsRig, vararg results: PinVerifyResult) {
        rig.h.pinStore.verifyScript += results.toList()
        assertTrue(rig.controller.beginPinConfirm(PinStep.TIMEOUT_CONFIRM))
    }

    /** True when a background of [awayMillis] followed by a foreground locks (the view holds a timeout below it). */
    private fun relocksAfter(rig: SettingsOperationsRig, awayMillis: Long): Boolean {
        rig.controller.onAppBackgrounded()
        rig.h.clock.advance(awayMillis)
        rig.controller.onAppForegrounded()
        return rig.controller.state.value != AppLockState.Unlocked
    }

    private fun assertNothingWritten(rig: SettingsOperationsRig) {
        assertTrue(rig.h.repository.timeoutWrites.isEmpty())
        assertTrue(rig.h.repository.reviewSaves.isEmpty())
        assertEquals(0, rig.h.authenticator.calls.size)
    }

    @Test
    fun `INV-40 a lengthening timeout with the correct PIN commits one unconditional write, updates the view and closes the step`() {
        val rig = timeoutRig(AutoLockTimeout.M1)
        openTimeout(rig, PinVerifyResult.Correct)
        val entered = pin()
        assertEquals(TimeoutChangeResult.COMMITTED, runBlocking { rig.ops.requestTimeoutChangeWithPin(AutoLockTimeout.M15, entered) })
        assertEquals(listOf(AutoLockTimeout.M15), rig.h.repository.timeoutWrites)
        assertTrue("the PIN authorises it: not the conditional write", rig.h.repository.conditionalTimeoutWrites.isEmpty())
        assertEquals(AutoLockTimeout.M15, settingsOf(rig).autoLockTimeout)
        assertEquals(PinStep.NONE, rig.controller.pin.value.step)
        assertEquals(0, rig.h.authenticator.calls.size)
        assertTrue(rig.h.emitted.isEmpty())
        assertTrue(entered.all { it == '\u0000' })
        assertFalse("the view holds 15 minutes now", relocksAfter(rig, AutoLockTimeout.M5.millis))
        assertTrue(rig.uncaught.isEmpty())
    }

    @Test
    fun `INV-40 a lengthening timeout with a wrong PIN is WRONG_PIN, writes nothing and keeps the step open`() {
        val rig = timeoutRig(AutoLockTimeout.M1)
        openTimeout(rig, PinVerifyResult.Wrong(PinDelayTier.S30))
        val entered = pin("111112")
        assertEquals(TimeoutChangeResult.WRONG_PIN, runBlocking { rig.ops.requestTimeoutChangeWithPin(AutoLockTimeout.M15, entered) })
        assertNothingWritten(rig)
        assertEquals(1, rig.h.pinStore.count("verify"))
        assertEquals(PinStep.TIMEOUT_CONFIRM, rig.controller.pin.value.step)
        assertFalse(rig.controller.pinStepAuthorised(PinStep.TIMEOUT_CONFIRM))
        assertEquals(AutoLockTimeout.M1, settingsOf(rig).autoLockTimeout)
        assertTrue(entered.all { it == '\u0000' })
    }

    @Test
    fun `INV-40 a lengthening timeout while the PIN check is delayed is PIN_DELAYED and writes nothing`() {
        val rig = timeoutRig(AutoLockTimeout.M1)
        openTimeout(rig, PinVerifyResult.Delayed(PinDelayReason.FAILURES, 9_000L))
        val entered = pin()
        assertEquals(TimeoutChangeResult.PIN_DELAYED, runBlocking { rig.ops.requestTimeoutChangeWithPin(AutoLockTimeout.M15, entered) })
        assertNothingWritten(rig)
        assertEquals(PinStep.TIMEOUT_CONFIRM, rig.controller.pin.value.step)
        assertEquals(AutoLockTimeout.M1, settingsOf(rig).autoLockTimeout)
        assertTrue(entered.all { it == '\u0000' })
    }

    @Test
    fun `INV-40 boundary a lengthening timeout with no step begun is NOT_IN_ORIGIN with no verify and no write`() {
        val rig = timeoutRig(AutoLockTimeout.M1)
        rig.h.pinStore.verifyScript += PinVerifyResult.Correct
        val entered = pin()
        assertEquals(TimeoutChangeResult.NOT_IN_ORIGIN, runBlocking { rig.ops.requestTimeoutChangeWithPin(AutoLockTimeout.M15, entered) })
        assertNothingWritten(rig)
        assertEquals(0, rig.h.pinStore.count("verify"))
        assertTrue(entered.all { it == '\u0000' })
    }

    @Test
    fun `INV-40 a failed timeout write is not committed, keeps the step and records the persist-failed diagnostic`() {
        val rig = timeoutRig(AutoLockTimeout.M1)
        openTimeout(rig, PinVerifyResult.Correct)
        rig.h.repository.timeoutWriteResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT) }
        val entered = pin()
        assertEquals(TimeoutChangeResult.FAILED, runBlocking { rig.ops.requestTimeoutChangeWithPin(AutoLockTimeout.M15, entered) })
        assertEquals(PinStep.TIMEOUT_CONFIRM, rig.controller.pin.value.step)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), rig.h.emitted)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.FIELD to AppLockSettingField.AUTO_LOCK_TIMEOUT),
            rig.h.emittedMetadata[0].entries
        )
        assertEquals(AutoLockTimeout.M1, settingsOf(rig).autoLockTimeout)
        assertTrue(entered.all { it == '\u0000' })
        assertTrue("the view kept 1 minute", relocksAfter(rig, AutoLockTimeout.M1.millis + 1_000L))
    }

    private fun assertCancellationRethrown(rig: SettingsOperationsRig, newTimeout: AutoLockTimeout, entered: CharArray) {
        try {
            runBlocking { rig.ops.requestTimeoutChangeWithPin(newTimeout, entered) }
            fail("the cancellation was swallowed")
        } catch (e: CancellationException) {
            // propagated after the settle
        }
        assertTrue(rig.uncaught.isEmpty())
        assertTrue(entered.all { it == '\u0000' })
    }

    @Test
    fun `INV-40 F7 a timeout write cancelled after it committed is settled as committed, then rethrown`() {
        val rig = timeoutRig(AutoLockTimeout.M1)
        openTimeout(rig, PinVerifyResult.Correct)
        rig.h.repository.timeoutWriteResult = { t ->
            rig.h.repository.loadState = AppLockSettingsLoadState.Loaded(settingsOf(rig).copy(autoLockTimeout = t))
            throw CancellationException("cancelled")
        }
        assertCancellationRethrown(rig, AutoLockTimeout.M15, pin())
        assertEquals(AutoLockTimeout.M15, settingsOf(rig).autoLockTimeout)
        assertEquals(PinStep.NONE, rig.controller.pin.value.step)
        assertTrue(rig.h.emitted.isEmpty())
        assertFalse("the view took the re-read value", relocksAfter(rig, AutoLockTimeout.M5.millis))
    }

    @Test
    fun `INV-40 F7 a timeout write cancelled before it committed is settled as failed, then rethrown`() {
        val rig = timeoutRig(AutoLockTimeout.M1)
        openTimeout(rig, PinVerifyResult.Correct)
        rig.h.repository.timeoutWriteFailure = CancellationException("cancelled")
        assertCancellationRethrown(rig, AutoLockTimeout.M15, pin())
        assertEquals(AutoLockTimeout.M1, settingsOf(rig).autoLockTimeout)
        assertEquals(PinStep.TIMEOUT_CONFIRM, rig.controller.pin.value.step)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), rig.h.emitted)
    }

    @Test
    fun `INV-40 a re-lock between the verify and the write clears the authority and writes nothing`() {
        val rig = timeoutRig(AutoLockTimeout.M1)
        openTimeout(rig, PinVerifyResult.Correct)
        val gate = CompletableDeferred<Unit>()
        val entered = pin()
        var result: TimeoutChangeResult? = null
        runBlocking {
            // Another writer holds the serializer, so the request passes its verify and then waits for the write section.
            val holder = launch(start = CoroutineStart.UNDISPATCHED) { rig.serializer.withExclusiveWrite { gate.await() } }
            val request = launch(start = CoroutineStart.UNDISPATCHED) {
                result = rig.ops.requestTimeoutChangeWithPin(AutoLockTimeout.M15, entered)
            }
            assertEquals(1, rig.h.pinStore.count("verify"))
            assertTrue(rig.controller.pinStepAuthorised(PinStep.TIMEOUT_CONFIRM))
            assertTrue(relocksAfter(rig, AutoLockTimeout.M1.millis + 1_000L))
            assertFalse(rig.controller.pinStepAuthorised(PinStep.TIMEOUT_CONFIRM))
            gate.complete(Unit)
            holder.join()
            request.join()
        }
        assertEquals(TimeoutChangeResult.NOT_IN_ORIGIN, result)
        assertNothingWritten(rig)
        assertEquals(AutoLockTimeout.M1, settingsOf(rig).autoLockTimeout)
        assertTrue(entered.all { it == '\u0000' })
    }

    @Test
    fun `INV-40 boundary a shorter timeout in APP_PIN delegates to the direct path with no PIN check`() {
        val rig = timeoutRig(AutoLockTimeout.M15)
        rig.h.pinStore.verifyScript += PinVerifyResult.Correct
        val entered = pin()
        assertEquals(TimeoutChangeResult.COMMITTED, runBlocking { rig.ops.requestTimeoutChangeWithPin(AutoLockTimeout.M1, entered) })
        assertEquals(0, rig.h.pinStore.count("verify"))
        assertEquals(listOf(AutoLockTimeout.M1), rig.h.repository.conditionalTimeoutWrites)
        assertEquals(AutoLockTimeout.M1, settingsOf(rig).autoLockTimeout)
        assertEquals(0, rig.h.authenticator.calls.size)
        assertTrue(entered.all { it == '\u0000' })
    }

    @Test
    fun `INV-40 control a longer timeout in DEVICE_AUTH delegates and gives the same result as requestTimeoutChange`() {
        val direct = unlockedControllerFor(AutoLockTimeout.M1).let { (h, c) -> SettingsOperationsRig(h, c) }
        val viaPin = unlockedControllerFor(AutoLockTimeout.M1).let { (h, c) -> SettingsOperationsRig(h, c) }
        direct.h.authenticator.calls.clear()
        viaPin.h.authenticator.calls.clear()
        val expected = runBlocking { direct.ops.requestTimeoutChange(AutoLockTimeout.M15) }
        val entered = pin()
        assertEquals(TimeoutChangeResult.STARTED, expected)
        assertEquals(expected, runBlocking { viaPin.ops.requestTimeoutChangeWithPin(AutoLockTimeout.M15, entered) })
        viaPin.h.settle()
        assertEquals(1, viaPin.h.authenticator.calls.size)
        assertEquals(0, viaPin.h.pinStore.count("verify"))
        assertTrue(viaPin.h.repository.timeoutWrites.isEmpty())
        assertTrue(entered.all { it == '\u0000' })
    }

    // --- requestReviewSaveWithPin -------------------------------------------------------------------

    private fun openReview(rig: SettingsOperationsRig, vararg results: PinVerifyResult) {
        rig.h.pinStore.verifyScript += results.toList()
        assertTrue(rig.controller.beginPinConfirm(PinStep.REVIEW_CONFIRM))
    }

    private fun save(rig: SettingsOperationsRig, record: AppLockPrivacySettings, entered: CharArray): ReviewSaveResult =
        runBlocking { rig.ops.requestReviewSaveWithPin(record, entered) }

    @Test
    fun `INV-40 a weakening review-save with the correct PIN commits once, closes the step and never consults the device capability`() {
        val rig = reviewRig()
        rig.h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL // a lock-on record would be REJECTED_CAPABILITY
        openReview(rig, PinVerifyResult.Correct)
        val record = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.M15, lockMode = LockMode.APP_PIN)
        val entered = pin()
        assertEquals(ReviewSaveResult.COMMITTED, save(rig, record, entered))
        assertEquals(listOf(record), rig.h.repository.reviewSaves)
        assertEquals(PinStep.NONE, rig.controller.pin.value.step)
        assertEquals(0, rig.h.authenticator.calls.size)
        assertTrue(entered.all { it == '\u0000' })
        assertTrue(rig.uncaught.isEmpty())
    }

    @Test
    fun `INV-40 a weakening review-save with a wrong or delayed PIN saves nothing and keeps the step open`() {
        val cases = mapOf(
            PinVerifyResult.Wrong(PinDelayTier.S30) to ReviewSaveResult.WRONG_PIN,
            PinVerifyResult.Delayed(PinDelayReason.FAILURES, 9_000L) to ReviewSaveResult.PIN_DELAYED
        )
        for ((scripted, expected) in cases) {
            val rig = reviewRig()
            openReview(rig, scripted)
            val entered = pin("111112")
            assertEquals("$scripted", expected, save(rig, weakening, entered))
            assertNothingWritten(rig)
            assertEquals(PinStep.REVIEW_CONFIRM, rig.controller.pin.value.step)
            assertTrue("$scripted", entered.all { it == '\u0000' })
        }
    }

    @Test
    fun `INV-40 boundary a weakening review-save with no step begun is NOT_IN_ORIGIN with no verify and no save`() {
        val rig = reviewRig()
        rig.h.pinStore.verifyScript += PinVerifyResult.Correct
        val entered = pin()
        assertEquals(ReviewSaveResult.NOT_IN_ORIGIN, save(rig, weakening, entered))
        assertNothingWritten(rig)
        assertEquals(0, rig.h.pinStore.count("verify"))
        assertTrue(entered.all { it == '\u0000' })
    }

    @Test
    fun `INV-40 a failed review-save keeps the step and records the persist-failed diagnostic`() {
        val rig = reviewRig()
        openReview(rig, PinVerifyResult.Correct)
        rig.h.repository.reviewSaveResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.REVIEW_SAVE) }
        val entered = pin()
        assertEquals(ReviewSaveResult.FAILED, save(rig, weakening, entered))
        assertEquals(PinStep.REVIEW_CONFIRM, rig.controller.pin.value.step)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), rig.h.emitted)
        assertTrue(entered.all { it == '\u0000' })
    }

    @Test
    fun `INV-40 boundary a non-weakening review-save in APP_PIN delegates with no PIN check`() {
        val rig = reviewRig()
        rig.h.pinStore.verifyScript += PinVerifyResult.Correct
        val record = AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = AutoLockTimeout.IMMEDIATE)
        val entered = pin()
        assertEquals(ReviewSaveResult.COMMITTED, save(rig, record, entered))
        assertEquals(listOf(record), rig.h.repository.reviewSaves)
        assertEquals(0, rig.h.pinStore.count("verify"))
        assertTrue(entered.all { it == '\u0000' })
    }

    @Test
    fun `INV-40 boundary a weakening review-save while INERT delegates to the direct path with no PIN check`() {
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()
        assertEquals(AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), controller.state.value)
        val rig = SettingsOperationsRig(h, controller)
        val entered = pin()
        assertEquals(ReviewSaveResult.COMMITTED, save(rig, weakening, entered))
        assertEquals(listOf(weakening), h.repository.reviewSaves)
        assertEquals(0, h.pinStore.count("verify"))
        assertTrue(entered.all { it == '\u0000' })
    }

    @Test
    fun `INV-40 a review-save whose authority was cleared between the verify and the write saves nothing`() {
        val rig = reviewRig()
        openReview(rig, PinVerifyResult.Correct)
        val gate = CompletableDeferred<Unit>()
        val entered = pin()
        var result: ReviewSaveResult? = null
        runBlocking {
            val holder = launch(start = CoroutineStart.UNDISPATCHED) { rig.serializer.withExclusiveWrite { gate.await() } }
            val request = launch(start = CoroutineStart.UNDISPATCHED) { result = rig.ops.requestReviewSaveWithPin(weakening, entered) }
            assertTrue(rig.controller.pinStepAuthorised(PinStep.REVIEW_CONFIRM))
            rig.controller.consumePinStepAuthority(PinStep.REVIEW_CONFIRM) // the authority cleared, as a re-lock or step close does
            gate.complete(Unit)
            holder.join()
            request.join()
        }
        assertEquals(ReviewSaveResult.NOT_IN_ORIGIN, result)
        assertNothingWritten(rig)
        assertTrue(entered.all { it == '\u0000' })
    }
}
