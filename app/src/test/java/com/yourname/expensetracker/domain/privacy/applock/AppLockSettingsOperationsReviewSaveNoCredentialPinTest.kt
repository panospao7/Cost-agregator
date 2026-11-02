package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStoreState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayTier
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifyResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix-1.0.4 J-15-1 (characterisation, test only): the review-save of a persistently unreadable store (Unreadable
 * CORRUPTED) while the effective lock mode is APP_PIN and the device capability is NO_CREDENTIAL. Expected values come
 * from the plan, not from the code: a lock-on IMMEDIATE record commits (a PIN lock only strengthens and needs no device
 * credential); a lock-off record is PIN_REQUIRED through the plain request; the PIN-confirmed request commits with the
 * right PIN, writes nothing with a wrong PIN, and zeroes the entered PIN either way. Synthetic PINs only.
 */
class AppLockSettingsOperationsReviewSaveNoCredentialPinTest {

    private fun pin(text: String = "482916"): CharArray = text.toCharArray()

    /** Effective APP_PIN over an Unreadable(CORRUPTED) store with NO_CREDENTIAL, entered through a real PIN unlock. */
    private fun rig(): SettingsOperationsRig {
        val store = FakeAppPinStore(AppPinStoreState(PinVerifierState.PRESENT, false, false))
        store.verifyScript += PinVerifyResult.Correct
        val h = AppLockControllerHarness(
            AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED),
            DeviceAuthCapability.NO_CREDENTIAL,
            pinStore = store
        )
        val controller = h.resolvedController()
        controller.onAppForegrounded()
        assertEquals(PinUnlockResult.Unlocked, runBlocking { controller.submitUnlockPin(pin()) })
        assertEquals(EffectiveLockMode.APP_PIN, controller.pin.value.effectiveLockMode)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(DeviceAuthCapability.NO_CREDENTIAL, h.capabilityProvider.capability)
        h.authenticator.calls.clear()
        h.pinStore.log.clear()
        h.emitted.clear()
        h.emittedMetadata.clear()
        return SettingsOperationsRig(h, controller)
    }

    private fun openReview(rig: SettingsOperationsRig, vararg results: PinVerifyResult) {
        rig.h.pinStore.verifyScript += results.toList()
        assertTrue(rig.controller.beginPinConfirm(PinStep.REVIEW_CONFIRM))
    }

    private val lockOnImmediate = AppLockPrivacySettings(
        appLockEnabled = true,
        autoLockTimeout = AutoLockTimeout.IMMEDIATE,
        lockMode = LockMode.APP_PIN
    )
    private val lockOff = AppLockPrivacySettings(appLockEnabled = false)

    @Test
    fun `J-15-1 corrupted APP_PIN NO_CREDENTIAL a lock-on IMMEDIATE record commits with no device prompt and no PIN check`() {
        val rig = rig()
        assertEquals(ReviewSaveResult.COMMITTED, runBlocking { rig.ops.requestReviewSave(lockOnImmediate) })
        assertEquals(listOf(lockOnImmediate), rig.h.repository.reviewSaves)
        assertEquals(0, rig.h.authenticator.calls.size)
        assertEquals(0, rig.h.pinStore.count("verify"))
        assertTrue(rig.uncaught.isEmpty())
    }

    @Test
    fun `J-15-1 corrupted APP_PIN NO_CREDENTIAL a lock-off record is PIN_REQUIRED and writes nothing`() {
        val rig = rig()
        assertEquals(ReviewSaveResult.PIN_REQUIRED, runBlocking { rig.ops.requestReviewSave(lockOff) })
        assertTrue(rig.h.repository.reviewSaves.isEmpty())
        assertTrue(rig.h.repository.timeoutWrites.isEmpty())
        assertEquals("no device prompt: nothing can authenticate", 0, rig.h.authenticator.calls.size)
        assertEquals(0, rig.h.pinStore.count("verify"))
        assertTrue(rig.uncaught.isEmpty())
    }

    @Test
    fun `J-15-1 corrupted APP_PIN NO_CREDENTIAL a lock-off record commits through requestReviewSaveWithPin with the right PIN`() {
        val rig = rig()
        openReview(rig, PinVerifyResult.Correct)
        val entered = pin()
        assertEquals(ReviewSaveResult.COMMITTED, runBlocking { rig.ops.requestReviewSaveWithPin(lockOff, entered) })
        assertEquals(listOf(lockOff), rig.h.repository.reviewSaves)
        assertEquals(1, rig.h.pinStore.count("verify"))
        assertEquals(PinStep.NONE, rig.controller.pin.value.step)
        assertEquals(0, rig.h.authenticator.calls.size)
        assertTrue("the entered PIN is zeroed", entered.all { it == '\u0000' })
        assertTrue(rig.uncaught.isEmpty())
    }

    @Test
    fun `J-15-1 corrupted APP_PIN NO_CREDENTIAL a wrong PIN writes nothing, keeps the step open and zeroes the PIN`() {
        val rig = rig()
        openReview(rig, PinVerifyResult.Wrong(PinDelayTier.S30))
        val entered = pin("111112")
        assertEquals(ReviewSaveResult.WRONG_PIN, runBlocking { rig.ops.requestReviewSaveWithPin(lockOff, entered) })
        assertTrue("nothing is written", rig.h.repository.reviewSaves.isEmpty())
        assertTrue(rig.h.repository.timeoutWrites.isEmpty())
        assertEquals(1, rig.h.pinStore.count("verify"))
        assertEquals(PinStep.REVIEW_CONFIRM, rig.controller.pin.value.step)
        assertEquals(0, rig.h.authenticator.calls.size)
        assertTrue("the entered PIN is zeroed", entered.all { it == '\u0000' })
        assertTrue(rig.uncaught.isEmpty())
    }

    @Test
    fun `J-15-1 corrupted APP_PIN NO_CREDENTIAL a lock-on IMMEDIATE record through requestReviewSaveWithPin commits without a PIN check and zeroes the PIN`() {
        val rig = rig()
        val entered = pin()
        assertEquals(ReviewSaveResult.COMMITTED, runBlocking { rig.ops.requestReviewSaveWithPin(lockOnImmediate, entered) })
        assertEquals(listOf(lockOnImmediate), rig.h.repository.reviewSaves)
        assertEquals(0, rig.h.pinStore.count("verify"))
        assertEquals(0, rig.h.authenticator.calls.size)
        assertTrue("the entered PIN is zeroed", entered.all { it == '\u0000' })
        assertTrue(rig.uncaught.isEmpty())
    }
}
