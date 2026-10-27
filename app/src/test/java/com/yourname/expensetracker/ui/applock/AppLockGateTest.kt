package com.yourname.expensetracker.ui.applock

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.AuthPurpose
import com.yourname.expensetracker.domain.privacy.applock.InertReason
import com.yourname.expensetracker.domain.privacy.applock.LockCause
import com.yourname.expensetracker.domain.privacy.applock.LockNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S19: the gate's rendering matrix (INV-26, INV-27, INV-34, INV-35, INV-51, INV-52; AE-38, AE-44, AE-46, AE-62,
 * AE-69, AE-99, AE-101, AE-108, AE-118). The gate is driven by plain parameters: no controller, no Hilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockGateTest : AppLockComposeTestBase() {

    private val probe = ContentSlotProbe()
    private val state = mutableStateOf<AppLockState>(AppLockState.Resolving)
    private val notice = mutableStateOf<LockNotice?>(null)
    private val inAppActionsAllowed = mutableStateOf(true)
    private var unlockTaps = 0
    private var securityTaps = 0
    private var settingsTaps = 0
    private var reviewTaps = 0

    private fun show(initial: AppLockState, initialNotice: LockNotice? = null, allowed: Boolean = true) {
        state.value = initial
        notice.value = initialNotice
        inAppActionsAllowed.value = allowed
        composeRule.setContent {
            AppLockGate(
                state = state.value,
                notice = notice.value,
                inAppActionsAllowed = inAppActionsAllowed.value,
                onUnlock = { unlockTaps++ },
                onOpenSecuritySettings = { securityTaps++ },
                onOpenAppLockSettings = { settingsTaps++ },
                onReviewSettings = { reviewTaps++ }
            ) { ProbeContent(probe) }
        }
    }

    private fun set(next: AppLockState) {
        composeRule.runOnIdle { state.value = next }
        composeRule.waitForIdle()
    }

    private fun ComposeContentTestRule.count(tag: String, expected: Int) =
        onAllNodesWithTag(tag).assertCountEquals(expected)

    private fun assertClosedRendering(surface: Boolean) {
        // AE-38 / INV-26: the content slot is composed 0 times and no protected text exists.
        assertEquals(0, probe.entered)
        assertEquals(0, probe.launched)
        composeRule.count(ContentSlotProbe.TAG, 0)
        composeRule.count(AppLockUiTags.GATE_CONTENT_SLOT, 0)
        composeRule.count(AppLockUiTags.NOTICE_BANNER, 0)
        composeRule.count(AppLockUiTags.GATE_SURFACE, if (surface) 1 else 0)
        composeRule.count(AppLockUiTags.GATE_NEUTRAL, if (surface) 0 else 1)
        // No protected text anywhere, merged or unmerged tree (INV-26).
        composeRule.onAllNodesWithText(ContentSlotProbe.PROBE_TEXT, substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun `mapping covers every state and decides authenticating by purpose only`() {
        assertEquals(AppLockGateRender.NEUTRAL, AppLockState.Resolving.toGateRender())
        LockCause.values().forEach {
            assertEquals(AppLockGateRender.LOCK_SURFACE, AppLockState.Locked(it).toGateRender())
        }
        assertEquals(AppLockGateRender.OPEN, AppLockState.Disabled.toGateRender())
        assertEquals(AppLockGateRender.OPEN, AppLockState.Unlocked.toGateRender())
        InertReason.values().forEach {
            assertEquals(AppLockGateRender.OPEN, AppLockState.Inert(it).toGateRender())
        }
        // Fail-closed exhaustiveness: the `when` has NO else, so a new AuthPurpose does not compile until it is
        // classified here as LOCK_SURFACE or OPEN (a new purpose can never silently render the open branch).
        AuthPurpose.values().forEachIndexed { index, purpose ->
            val expected = when (purpose) {
                AuthPurpose.UNLOCK -> AppLockGateRender.LOCK_SURFACE
                AuthPurpose.ENABLE, AuthPurpose.CONFIRM_CHANGE -> AppLockGateRender.OPEN
            }
            assertEquals(
                "purpose $purpose",
                expected,
                AppLockState.Authenticating(index + 1L, purpose).toGateRender()
            )
        }
    }

    @Test
    fun `resolving composes the neutral surface only and no content`() {
        show(AppLockState.Resolving)

        assertClosedRendering(surface = false)
        composeRule.count(AppLockUiTags.UNLOCK_BUTTON, 0)
    }

    @Test
    fun `locked composes the lock surface only with Unlock enabled and no content`() {
        show(AppLockState.Locked(LockCause.COLD_START))

        assertClosedRendering(surface = true)
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsEnabled()
        assertEquals(0, unlockTaps) // AE-44: no auto-prompt

        LockCause.values().forEach { cause ->
            set(AppLockState.Locked(cause))
            assertClosedRendering(surface = true)
            composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsEnabled()
        }
        assertEquals(0, unlockTaps)
    }

    @Test
    fun `authenticating unlock keeps the lock surface, disables Unlock and shows no content or notice`() {
        // AE-99 / AE-115: even a stale notice passed with a closed state is never composed.
        show(AppLockState.Authenticating(7, AuthPurpose.UNLOCK), initialNotice = LockNotice.ReviewSettings)

        assertClosedRendering(surface = true)
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsNotEnabled().performClick()
        assertEquals(0, unlockTaps) // AE-46: a tap during the prompt starts nothing
    }

    @Test
    fun `Unlock is disabled during the prompt and enabled again after the prompt ends`() {
        show(AppLockState.Locked(LockCause.TIMEOUT))
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsEnabled()

        set(AppLockState.Authenticating(1, AuthPurpose.UNLOCK))
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsNotEnabled()

        // T9, T10 and T35 all return to LOCKED (AE-118: host loss included).
        set(AppLockState.Locked(LockCause.TIMEOUT))
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsEnabled().performClick()
        assertEquals(1, unlockTaps)

        set(AppLockState.Authenticating(2, AuthPurpose.UNLOCK))
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsNotEnabled()
        set(AppLockState.Locked(LockCause.TIMEOUT))
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsEnabled()
        assertEquals(1, unlockTaps)
    }

    @Test
    fun `no state composes anything that starts a prompt`() {
        listOf(
            AppLockState.Resolving,
            AppLockState.Locked(LockCause.COLD_START),
            AppLockState.Authenticating(1, AuthPurpose.UNLOCK),
            AppLockState.Disabled,
            AppLockState.Authenticating(2, AuthPurpose.ENABLE),
            AppLockState.Unlocked,
            AppLockState.Authenticating(3, AuthPurpose.CONFIRM_CHANGE),
            AppLockState.Inert(InertReason.NO_CREDENTIAL)
        ).forEachIndexed { index, next ->
            if (index == 0) show(next) else set(next)
        }

        assertEquals(0, unlockTaps)
        assertEquals(0, securityTaps)
        assertEquals(0, settingsTaps)
        assertEquals(0, reviewTaps)
    }

    @Test
    fun `settings unreadable lock cause says so and a prompt in flight shows no status line`() {
        show(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE))
        composeRule.onNodeWithTag(AppLockUiTags.STATUS_MESSAGE)
            .assertTextEquals(text(R.string.applock_msg_setting_unreadable))

        set(AppLockState.Authenticating(1, AuthPurpose.UNLOCK))
        composeRule.onNodeWithTag(AppLockUiTags.STATUS_MESSAGE).assertTextEquals("")
    }

    @Test
    fun `open states compose the content slot once with no surface`() {
        show(AppLockState.Disabled)
        assertEquals(1, probe.entered)
        composeRule.count(ContentSlotProbe.TAG, 1)
        composeRule.count(AppLockUiTags.GATE_SURFACE, 0)
        composeRule.count(AppLockUiTags.GATE_NEUTRAL, 0)

        // Moving between open states never rebuilds the slot (the branch is the same).
        set(AppLockState.Unlocked)
        set(AppLockState.Inert(InertReason.HW_UNAVAILABLE))
        set(AppLockState.Disabled)
        assertEquals(1, probe.entered)
        assertEquals(0, probe.disposed)
        assertEquals(1, probe.launched)
    }

    @Test
    fun `toggling the banner keeps the same content slot under the conditional insets modifier`() {
        show(AppLockState.Unlocked)
        assertEquals(1, probe.entered)
        assertEquals(1, probe.launched)
        assertEquals(1, probe.distinctTokens)

        repeat(2) {
            composeRule.runOnIdle { notice.value = LockNotice.ReviewSettings }
            composeRule.waitForIdle()
            composeRule.count(AppLockUiTags.NOTICE_BANNER, 1)
            composeRule.count(AppLockUiTags.GATE_CONTENT_SLOT, 1)

            composeRule.runOnIdle { notice.value = null }
            composeRule.waitForIdle()
            composeRule.count(AppLockUiTags.NOTICE_BANNER, 0)
            composeRule.count(AppLockUiTags.GATE_CONTENT_SLOT, 1)
        }

        // The banner modifier changes only; the slot is never disposed or rebuilt (INV-53).
        assertEquals(1, probe.entered)
        assertEquals(0, probe.disposed)
        assertEquals(1, probe.launched)
        assertEquals(1, probe.distinctTokens)
    }

    @Test
    fun `a notice is composed with an open state and not with a closed state`() {
        show(AppLockState.Unlocked, initialNotice = LockNotice.ReviewSettings)
        composeRule.count(AppLockUiTags.NOTICE_BANNER, 1)
        composeRule.count(AppLockUiTags.GATE_CONTENT_SLOT, 1)

        // A torn sample: the state is already locked while the notice flow still holds its old value.
        set(AppLockState.Locked(LockCause.TIMEOUT))
        composeRule.count(AppLockUiTags.NOTICE_BANNER, 0)
        composeRule.count(AppLockUiTags.GATE_CONTENT_SLOT, 0)
        assertEquals(1, probe.disposed) // re-lock removes the content (T14); this is the only legal removal
    }

    @Test
    fun `lock surface and neutral surface contain no protected text`() {
        show(AppLockState.Locked(LockCause.COLD_START))
        composeRule.count(ContentSlotProbe.TAG, 0)
        composeRule.onNodeWithTag(AppLockUiTags.GATE_SURFACE).assertExists()

        set(AppLockState.Resolving)
        composeRule.count(ContentSlotProbe.TAG, 0)
        assertEquals(0, probe.entered)
    }

    @Test
    fun `in-app notice actions are not composed while the operational lock is active`() {
        show(AppLockState.Inert(InertReason.NO_CREDENTIAL), initialNotice = LockNotice.Inert(InertReason.NO_CREDENTIAL), allowed = false)

        composeRule.count(AppLockUiTags.NOTICE_BANNER, 1)
        composeRule.count(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS, 0) // INV-52 / AE-108
        composeRule.count(AppLockUiTags.NOTICE_ACTION_SECURITY_SETTINGS, 1)
        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_SECURITY_SETTINGS).performClick()
        assertEquals(1, securityTaps)
        assertEquals(0, settingsTaps)

        composeRule.runOnIdle { inAppActionsAllowed.value = true }
        composeRule.waitForIdle()
        composeRule.count(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS, 1)
    }

    @Test
    fun `the source overload renders the published pair`() {
        val sourceState = MutableStateFlow<AppLockState>(AppLockState.Locked(LockCause.COLD_START))
        val sourceNotice = MutableStateFlow<LockNotice?>(null)
        val source = object : AppLockGateSource {
            override val state: StateFlow<AppLockState> = sourceState
            override val notice: StateFlow<LockNotice?> = sourceNotice
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
        composeRule.count(AppLockUiTags.GATE_SURFACE, 1)

        sourceNotice.value = LockNotice.ReviewSettings
        sourceState.value = AppLockState.Unlocked
        composeRule.waitForIdle()

        composeRule.count(AppLockUiTags.GATE_SURFACE, 0)
        composeRule.count(AppLockUiTags.NOTICE_BANNER, 1)
        assertEquals(1, probe.entered)
        assertEquals(0, unlockTaps)
    }

    private class MutableSource(
        initialState: AppLockState,
        initialNotice: LockNotice?
    ) : AppLockGateSource {
        val mutableState = MutableStateFlow(initialState)
        val mutableNotice = MutableStateFlow(initialNotice)
        override val state: StateFlow<AppLockState> = mutableState
        override val notice: StateFlow<LockNotice?> = mutableNotice
    }

    private fun showSource(source: AppLockGateSource) {
        composeRule.setContent {
            AppLockGate(
                source = source,
                inAppActionsAllowed = true,
                onUnlock = { unlockTaps++ },
                onOpenSecuritySettings = { securityTaps++ },
                onOpenAppLockSettings = { settingsTaps++ },
                onReviewSettings = { reviewTaps++ }
            ) { ProbeContent(probe) }
        }
    }

    private fun publish(write: () -> Unit) {
        composeRule.runOnIdle { write() }
        composeRule.waitForIdle()
    }

    /** Closed state: no banner, no live probe entry (entered minus disposed), no probe node, no probe text. */
    private fun assertNoNoticeAndNoLiveContent(step: String) {
        assertEquals("$step: banner", 0, composeRule.onAllNodesWithTag(AppLockUiTags.NOTICE_BANNER).fetchSemanticsNodes().size)
        assertEquals("$step: live probe entries", 0, probe.entered - probe.disposed)
        assertEquals("$step: probe node", 0, composeRule.onAllNodesWithTag(ContentSlotProbe.TAG).fetchSemanticsNodes().size)
        assertEquals("$step: content slot", 0, composeRule.onAllNodesWithTag(AppLockUiTags.GATE_CONTENT_SLOT).fetchSemanticsNodes().size)
        composeRule.onAllNodesWithText(ContentSlotProbe.PROBE_TEXT, substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    private fun assertOpen(step: String, expectedBanners: Int) {
        assertEquals("$step: banner", expectedBanners, composeRule.onAllNodesWithTag(AppLockUiTags.NOTICE_BANNER).fetchSemanticsNodes().size)
        assertEquals("$step: live probe entries", 1, probe.entered - probe.disposed)
        assertEquals("$step: surface", 0, composeRule.onAllNodesWithTag(AppLockUiTags.GATE_SURFACE).fetchSemanticsNodes().size)
    }

    @Test
    fun `source overload never draws a notice or content for a torn pair, notice published before state`() {
        val source = MutableSource(AppLockState.Locked(LockCause.COLD_START), LockNotice.ReviewSettings)
        showSource(source)

        // Locked with a notice already published (Finding 1 starting point).
        assertNoNoticeAndNoLiveContent("start locked + review")
        assertEquals(0, probe.entered)

        // Opening: the notice changes first while the state is still closed, then the state opens.
        publish { source.mutableNotice.value = LockNotice.Inert(InertReason.NO_CREDENTIAL) }
        assertNoNoticeAndNoLiveContent("notice first, state still locked")
        assertEquals(0, probe.entered)
        publish { source.mutableState.value = AppLockState.Unlocked }
        assertOpen("state opened", expectedBanners = 1)

        // Closing: the notice changes first while the state is still open, then the state closes.
        publish { source.mutableNotice.value = LockNotice.ReviewSettings }
        assertOpen("notice changed while open", expectedBanners = 1)
        publish { source.mutableState.value = AppLockState.Locked(LockCause.TIMEOUT) }
        assertNoNoticeAndNoLiveContent("state closed with notice still published")

        // Closed through an UNLOCK prompt as well.
        publish { source.mutableState.value = AppLockState.Authenticating(1, AuthPurpose.UNLOCK) }
        assertNoNoticeAndNoLiveContent("authenticating unlock with notice published")
        assertEquals(0, unlockTaps)
    }

    @Test
    fun `source overload never draws a notice or content for a torn pair, state published before notice`() {
        val source = MutableSource(AppLockState.Locked(LockCause.COLD_START), LockNotice.ReviewSettings)
        showSource(source)
        assertNoNoticeAndNoLiveContent("start locked + review")

        // Opening: the state opens first while the notice flow still holds the old value (legal pair: shown).
        publish { source.mutableState.value = AppLockState.Unlocked }
        assertOpen("state opened first", expectedBanners = 1)
        publish { source.mutableNotice.value = null }
        assertOpen("notice cleared after open", expectedBanners = 0)

        // Closing: the state closes first, then a notice is published into the closed state.
        publish { source.mutableState.value = AppLockState.Locked(LockCause.TIMEOUT) }
        assertNoNoticeAndNoLiveContent("state closed, notice null")
        publish { source.mutableNotice.value = LockNotice.ReviewSettings }
        assertNoNoticeAndNoLiveContent("notice published into closed state")

        // Re-open with the notice already there, then close the state and clear the notice afterwards.
        publish { source.mutableState.value = AppLockState.Unlocked }
        assertOpen("reopened", expectedBanners = 1)
        publish { source.mutableState.value = AppLockState.Resolving }
        assertNoNoticeAndNoLiveContent("resolving with notice published")
        publish { source.mutableNotice.value = null }
        assertNoNoticeAndNoLiveContent("notice cleared after close")
    }

    @Test
    fun `source overload shows no banner or content when both values change back to back in either order`() {
        val source = MutableSource(AppLockState.Locked(LockCause.COLD_START), LockNotice.ReviewSettings)
        showSource(source)
        assertNoNoticeAndNoLiveContent("start")

        // Both writes land before the next frame, in each order, across open then close.
        publish {
            source.mutableNotice.value = LockNotice.Inert(InertReason.HW_UNAVAILABLE)
            source.mutableState.value = AppLockState.Unlocked
        }
        assertOpen("notice then state (open)", expectedBanners = 1)
        publish {
            source.mutableNotice.value = LockNotice.ReviewSettings
            source.mutableState.value = AppLockState.Locked(LockCause.TIMEOUT)
        }
        assertNoNoticeAndNoLiveContent("notice then state (close)")

        publish {
            source.mutableState.value = AppLockState.Unlocked
            source.mutableNotice.value = LockNotice.Inert(InertReason.HW_UNAVAILABLE)
        }
        assertOpen("state then notice (open)", expectedBanners = 1)
        publish {
            source.mutableState.value = AppLockState.Locked(LockCause.TIMEOUT)
            source.mutableNotice.value = LockNotice.ReviewSettings
        }
        assertNoNoticeAndNoLiveContent("state then notice (close)")
        assertEquals(0, unlockTaps)
        assertEquals(0, reviewTaps)
    }
}
