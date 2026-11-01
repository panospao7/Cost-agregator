package com.yourname.expensetracker.ui.applock

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.AuthPurpose
import com.yourname.expensetracker.domain.privacy.applock.EffectiveLockMode
import com.yourname.expensetracker.domain.privacy.applock.LockCause
import com.yourname.expensetracker.domain.privacy.applock.LockNotice
import com.yourname.expensetracker.domain.privacy.applock.PinPresentation
import com.yourname.expensetracker.domain.privacy.applock.PinStep
import com.yourname.expensetracker.domain.privacy.applock.PinUnlockResult
import com.yourname.expensetracker.ui.applock.pin.PinLockActions
import com.yourname.expensetracker.ui.applock.pin.PinLockTags
import com.yourname.expensetracker.ui.applock.pin.PinPadTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S27 a2: the gate draws the PIN surface in effective APP_PIN and never launches a prompt by itself (INV-34, INV-40). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockGatePinModeTest : AppLockComposeTestBase() {

    private class CountingActions : PinLockActions {
        var submits = 0
        var retries = 0
        var fingerprints = 0
        override suspend fun submitUnlockPin(pin: CharArray): PinUnlockResult {
            submits++
            pin.fill('\u0000')
            return PinUnlockResult.Unlocked
        }
        override suspend fun retryPinRecovery() { retries++ }
        override suspend fun requestFingerprintUnlock() { fingerprints++ }
    }

    private val appPin = PinPresentation(EffectiveLockMode.APP_PIN, PinStep.NONE, null, false)
    private val probe = ContentSlotProbe()
    private val state = mutableStateOf<AppLockState>(AppLockState.Locked(LockCause.COLD_START))
    private val pin = mutableStateOf(appPin)
    private val actions = CountingActions()
    private var unlockTaps = 0

    private fun show(withActions: PinLockActions? = actions) {
        composeRule.setContent {
            AppLockGate(
                state = state.value,
                notice = null,
                inAppActionsAllowed = true,
                onUnlock = { unlockTaps++ },
                onOpenSecuritySettings = { },
                onOpenAppLockSettings = { },
                onReviewSettings = { },
                pin = pin.value,
                pinActions = withActions
            ) { ProbeContent(probe) }
        }
    }

    private fun count(tag: String, expected: Int) = composeRule.onAllNodesWithTag(tag).assertCountEquals(expected)

    private fun assertNothingLaunched() {
        assertEquals("device-auth prompt must not be requested", 0, unlockTaps)
        assertEquals(0, actions.fingerprints)
        assertEquals(0, actions.submits)
        assertEquals(0, actions.retries)
        assertEquals(0, probe.entered)
    }

    @Test
    fun inv34_appPinLocked_rendersPinSurface_andLaunchesNoPrompt() {
        show()
        composeRule.waitForIdle()
        count(PinLockTags.SURFACE, 1)
        count(AppLockUiTags.GATE_SURFACE, 0)
        count(AppLockUiTags.UNLOCK_BUTTON, 0)
        count(AppLockUiTags.GATE_CONTENT_SLOT, 0)
        assertNothingLaunched()
    }

    @Test
    fun inv34_appPinLocked_everyLockCause_staysOnPinSurface_withoutPrompt() {
        show()
        LockCause.values().forEach { cause ->
            composeRule.runOnIdle { state.value = AppLockState.Locked(cause) }
            composeRule.waitForIdle()
            count(PinLockTags.SURFACE, 1)
            count(AppLockUiTags.UNLOCK_BUTTON, 0)
        }
        assertNothingLaunched()
    }

    @Test
    fun inv40_appPinAuthenticatingUnlock_keepsPinSurface_padDisabled_noContent() {
        state.value = AppLockState.Authenticating(7L, AuthPurpose.UNLOCK)
        show()
        count(PinLockTags.SURFACE, 1)
        count(AppLockUiTags.GATE_SURFACE, 0)
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "5").assertIsNotEnabled()
        assertEquals(0, probe.entered)
    }

    @Test
    fun inv34_appPinWithoutPinActions_fallsBackToDeviceSurface() {
        show(withActions = null)
        count(PinLockTags.SURFACE, 0)
        count(AppLockUiTags.GATE_SURFACE, 1)
    }

    @Test
    fun deviceAuthLocked_control_rendersExistingSurface_notPinSurface() {
        pin.value = PinPresentation.INITIAL
        show()
        count(AppLockUiTags.GATE_SURFACE, 1)
        count(AppLockUiTags.UNLOCK_BUTTON, 1)
        count(PinLockTags.SURFACE, 0)
        assertEquals(0, unlockTaps)
        assertEquals(0, actions.fingerprints)
    }

    @Test
    fun inv26_appPinUnlocked_rendersContent_notPinSurface() {
        state.value = AppLockState.Unlocked
        show()
        count(PinLockTags.SURFACE, 0)
        count(AppLockUiTags.GATE_CONTENT_SLOT, 1)
        assertEquals(1, probe.entered)
    }

    @Test
    fun inv34_sourceOverload_passesPinAndPinActionsThrough() {
        val source = object : AppLockGateSource {
            override val state: StateFlow<AppLockState> = MutableStateFlow(AppLockState.Locked(LockCause.TIMEOUT))
            override val notice: StateFlow<LockNotice?> = MutableStateFlow(null)
            override val pin: StateFlow<PinPresentation> = MutableStateFlow(appPin)
            override val pinActions: PinLockActions = actions
        }
        composeRule.setContent {
            AppLockGate(
                source = source,
                inAppActionsAllowed = true,
                onUnlock = { unlockTaps++ },
                onOpenSecuritySettings = { },
                onOpenAppLockSettings = { },
                onReviewSettings = { }
            ) { ProbeContent(probe) }
        }
        composeRule.waitForIdle()
        count(PinLockTags.SURFACE, 1)
        assertNothingLaunched()
    }

    @Test
    fun biometricInvalidatedNotice_dismissTap_invokesOnDismissNoticeOnce_stateOverload() {
        var dismissed = 0
        composeRule.setContent {
            AppLockGate(
                state = AppLockState.Unlocked,
                notice = LockNotice.BiometricInvalidated,
                inAppActionsAllowed = true,
                onUnlock = { unlockTaps++ },
                onOpenSecuritySettings = { },
                onOpenAppLockSettings = { },
                onReviewSettings = { },
                onDismissNotice = { dismissed++ }
            ) { ProbeContent(probe) }
        }
        composeRule.waitForIdle()
        assertEquals(0, dismissed)

        composeRule.onNodeWithTag(LOCK_NOTICE_DISMISS_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(1, dismissed)
        assertEquals(0, unlockTaps)
    }
}
