package com.yourname.expensetracker.ui.applock

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.AuthPurpose
import com.yourname.expensetracker.domain.privacy.applock.InertReason
import com.yourname.expensetracker.domain.privacy.applock.LockCause
import com.yourname.expensetracker.domain.privacy.applock.LockNotice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S19: open-origin prompts (`AUTHENTICATING(ENABLE | CONFIRM_CHANGE)`, AMD-22, INV-53).
 * AE-113 (gate), AE-114, AE-115, AE-118 (UI), and the operational row per origin (spec 3.6, INV-27, INV-52).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockGateOpenOriginPromptTest : AppLockComposeTestBase() {

    private val probe = ContentSlotProbe()
    private val state = mutableStateOf<AppLockState>(AppLockState.Disabled)
    private val notice = mutableStateOf<LockNotice?>(null)
    private val inAppActionsAllowed = mutableStateOf(true)
    private var callbacks = 0

    private fun show(initial: AppLockState, initialNotice: LockNotice? = null, allowed: Boolean = true) {
        state.value = initial
        notice.value = initialNotice
        inAppActionsAllowed.value = allowed
        composeRule.setContent {
            AppLockGate(
                state = state.value,
                notice = notice.value,
                inAppActionsAllowed = inAppActionsAllowed.value,
                onUnlock = { callbacks++ },
                onOpenSecuritySettings = { callbacks++ },
                onOpenAppLockSettings = { callbacks++ },
                onReviewSettings = { callbacks++ }
            ) { ProbeContent(probe) }
        }
    }

    private fun set(next: AppLockState) {
        composeRule.runOnIdle { state.value = next }
        composeRule.waitForIdle()
    }

    private fun count(tag: String, expected: Int) =
        composeRule.onAllNodesWithTag(tag).assertCountEquals(expected)

    private fun assertOpenRendering() {
        count(AppLockUiTags.GATE_SURFACE, 0)
        count(AppLockUiTags.GATE_NEUTRAL, 0)
        count(AppLockUiTags.UNLOCK_BUTTON, 0)
        count(AppLockUiTags.GATE_CONTENT_SLOT, 1)
        count(ContentSlotProbe.TAG, 1)
    }

    private fun assertSlotUntouched() {
        assertEquals("slot entered composition more than once", 1, probe.entered)
        assertEquals("slot was disposed", 0, probe.disposed)
        assertEquals("LaunchedEffect(Unit) re-ran", 1, probe.launched)
        assertEquals("remember state was not kept", 1, probe.distinctTokens)
    }

    @Test
    fun `AE-113 entering and leaving ENABLE keeps the slot composed once with no dispose`() {
        show(AppLockState.Disabled)
        assertOpenRendering()
        assertSlotUntouched()

        set(AppLockState.Authenticating(1, AuthPurpose.ENABLE))
        assertOpenRendering()
        assertSlotUntouched()

        // A non-success outcome returns to the origin (T9, T10, T34, T35).
        set(AppLockState.Disabled)
        assertOpenRendering()
        assertSlotUntouched()

        // A retry is a new attempt; success moves to UNLOCKED (T33), still the same open branch.
        set(AppLockState.Authenticating(2, AuthPurpose.ENABLE))
        assertOpenRendering()
        set(AppLockState.Unlocked)
        assertOpenRendering()
        assertSlotUntouched()
        assertEquals(0, callbacks)
    }

    @Test
    fun `AE-114 the slot stays composed during CONFIRM_CHANGE`() {
        show(AppLockState.Unlocked)
        assertOpenRendering()

        set(AppLockState.Authenticating(1, AuthPurpose.CONFIRM_CHANGE))
        assertOpenRendering()
        assertSlotUntouched()

        set(AppLockState.Unlocked) // cancelled, failed, or confirmed with a write refused
        assertOpenRendering()
        set(AppLockState.Authenticating(2, AuthPurpose.CONFIRM_CHANGE))
        set(AppLockState.Disabled) // confirmed turn-off (T33 to DISABLED)
        assertOpenRendering()
        assertSlotUntouched()
        assertEquals(0, callbacks)
    }

    @Test
    fun `AE-115 the origin notice stays through CONFIRM_CHANGE and the unlock prompt shows none`() {
        show(AppLockState.Unlocked, initialNotice = LockNotice.ReviewSettings)
        count(AppLockUiTags.NOTICE_BANNER, 1)

        set(AppLockState.Authenticating(1, AuthPurpose.CONFIRM_CHANGE))
        count(AppLockUiTags.NOTICE_BANNER, 1)
        assertOpenRendering()

        set(AppLockState.Unlocked)
        count(AppLockUiTags.NOTICE_BANNER, 1)
        assertSlotUntouched()

        // The UNLOCK prompt: the slot is composed 0 times and no notice is shown, even if a stale notice is passed.
        composeRule.runOnIdle { notice.value = LockNotice.Inert(InertReason.HW_UNAVAILABLE) }
        set(AppLockState.Authenticating(2, AuthPurpose.UNLOCK))
        count(AppLockUiTags.NOTICE_BANNER, 0)
        count(AppLockUiTags.GATE_CONTENT_SLOT, 0)
        count(ContentSlotProbe.TAG, 0)
        count(AppLockUiTags.GATE_SURFACE, 1)
    }

    @Test
    fun `AE-118 Unlock is enabled again after host loss returns to LOCKED`() {
        show(AppLockState.Locked(LockCause.COLD_START))
        set(AppLockState.Authenticating(5, AuthPurpose.UNLOCK))
        count(AppLockUiTags.UNLOCK_BUTTON, 1)
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsNotEnabled()

        set(AppLockState.Locked(LockCause.COLD_START)) // T35: PROMPT_HOST_LOST for an UNLOCK attempt
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsEnabled()
        assertEquals(0, callbacks)
    }

    @Test
    fun `operational precedence per origin is identical for the origin and its prompt`() {
        val notices = listOf<LockNotice?>(null, LockNotice.Inert(InertReason.NO_CREDENTIAL), LockNotice.ReviewSettings)
        val tags = listOf(
            AppLockUiTags.GATE_SURFACE,
            AppLockUiTags.GATE_NEUTRAL,
            AppLockUiTags.GATE_CONTENT_SLOT,
            AppLockUiTags.NOTICE_BANNER,
            AppLockUiTags.NOTICE_ACTION_SECURITY_SETTINGS,
            AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS,
            AppLockUiTags.NOTICE_ACTION_REVIEW
        )
        fun snapshot(): List<Int> = tags.map { composeRule.onAllNodesWithTag(it).fetchSemanticsNodes().size }

        val origins = listOf(
            AppLockState.Disabled to AppLockState.Authenticating(1, AuthPurpose.ENABLE),
            AppLockState.Unlocked to AppLockState.Authenticating(2, AuthPurpose.CONFIRM_CHANGE)
        )
        show(AppLockState.Disabled)
        origins.forEach { (origin, prompt) ->
            notices.forEach { n ->
                listOf(true, false).forEach { allowed ->
                    composeRule.runOnIdle {
                        notice.value = n
                        inAppActionsAllowed.value = allowed
                    }
                    set(origin)
                    val atOrigin = snapshot()
                    set(prompt)
                    assertEquals("origin=$origin notice=$n allowed=$allowed", atOrigin, snapshot())
                    set(origin)
                    assertEquals(atOrigin, snapshot())
                }
            }
        }
        assertSlotUntouched()
        assertEquals(0, callbacks)
    }

    @Test
    fun `prompt transitions fire no gate callback`() {
        show(AppLockState.Unlocked, initialNotice = LockNotice.ReviewSettings)
        set(AppLockState.Authenticating(1, AuthPurpose.CONFIRM_CHANGE))
        set(AppLockState.Unlocked)
        set(AppLockState.Disabled)
        set(AppLockState.Authenticating(2, AuthPurpose.ENABLE))
        set(AppLockState.Unlocked)

        assertEquals(0, callbacks)
        assertSlotUntouched()
    }
}
