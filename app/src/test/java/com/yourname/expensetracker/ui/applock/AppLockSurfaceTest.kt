package com.yourname.expensetracker.ui.applock

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S19: the lock surface (INV-34, INV-35, AE-44, AE-46, AE-69). Content-free, one explicit Unlock button, no
 * auto-prompt, bounded constant status lines, TalkBack roles, 200% font scale and RTL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockSurfaceTest : AppLockComposeTestBase() {

    private var unlocks = 0

    private fun show(
        mode: LockSurfaceMode = LockSurfaceMode.LOCK,
        enabled: Boolean = true,
        message: LockSurfaceMessage? = null,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalLayoutDirection provides layoutDirection
            ) {
                AppLockSurface(mode = mode, unlockEnabled = enabled, message = message, onUnlock = { unlocks++ })
            }
        }
    }

    @Test
    fun `neutral mode draws no text and no button`() {
        show(mode = LockSurfaceMode.NEUTRAL)

        composeRule.onAllNodesWithTag(AppLockUiTags.GATE_NEUTRAL).assertCountEquals(1)
        composeRule.onAllNodesWithTag(AppLockUiTags.UNLOCK_BUTTON).assertCountEquals(0)
        composeRule.onAllNodesWithTag(AppLockUiTags.STATUS_MESSAGE).assertCountEquals(0)
        assertEquals(0, unlocks)
    }

    @Test
    fun `lock mode shows the headline as a heading, the subtitle and an enabled Unlock`() {
        show()

        composeRule.onNodeWithText(text(R.string.applock_lock_title))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText(text(R.string.applock_lock_subtitle)).assertExists()
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON)
            .assertIsEnabled()
            .assertTextEquals(text(R.string.applock_unlock_button))
            .assertHeightIsAtLeast(56.dp)
        assertEquals(0, unlocks) // no auto-prompt (INV-34)
    }

    @Test
    fun `one tap on an enabled Unlock is one callback`() {
        show()
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).performClick()
        assertEquals(1, unlocks)
    }

    @Test
    fun `disabled Unlock is not enabled and ignores taps`() {
        show(enabled = false)
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsNotEnabled().performClick()
        assertEquals(0, unlocks)
    }

    @Test
    fun `status area is empty and not a live region by default`() {
        show()
        composeRule.onNodeWithTag(AppLockUiTags.STATUS_MESSAGE)
            .assertTextEquals("")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `a message is a constant resource in a polite live region and the set is closed`() {
        show(message = LockSurfaceMessage.BIOMETRIC_LOCKED)
        composeRule.onNodeWithTag(AppLockUiTags.STATUS_MESSAGE)
            .assertTextEquals(text(R.string.applock_msg_biometric_locked))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        assertEquals(
            listOf(
                R.string.applock_msg_try_again,
                R.string.applock_msg_too_many_attempts,
                R.string.applock_msg_biometric_locked,
                R.string.applock_msg_unavailable_now,
                R.string.applock_msg_setting_unreadable
            ),
            LockSurfaceMessage.values().map { it.textRes }
        )
    }

    @Test
    fun `surface survives 200 percent font scale`() {
        show(message = LockSurfaceMessage.TOO_MANY_ATTEMPTS, fontScale = 2f)

        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertExists().assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithText(text(R.string.applock_lock_title)).assertExists()
    }

    @Test
    fun `surface renders in a right to left layout`() {
        show(layoutDirection = LayoutDirection.Rtl)

        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `surface text holds only the constant lock strings`() {
        show(message = LockSurfaceMessage.TRY_AGAIN)

        // Content-free (INV-35): headline, subtitle, button and the status line are the only text.
        val texts = composeRule.onAllNodesWithTag(AppLockUiTags.GATE_SURFACE)
            .fetchSemanticsNodes()
            .single()
            .let { root -> collectText(root) }
        assertEquals(
            setOf(
                text(R.string.applock_lock_title),
                text(R.string.applock_lock_subtitle),
                text(R.string.applock_unlock_button),
                text(R.string.applock_msg_try_again)
            ),
            texts
        )
    }

    private fun collectText(node: androidx.compose.ui.semantics.SemanticsNode): Set<String> {
        // Visible text and accessibility descriptions both count as text the surface exposes (INV-35).
        val own = node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.map { it.text }.orEmpty()
        val descriptions = node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty()
        return (own + descriptions + node.children.flatMap { collectText(it) }).filter { it.isNotEmpty() }.toSet()
    }
}
