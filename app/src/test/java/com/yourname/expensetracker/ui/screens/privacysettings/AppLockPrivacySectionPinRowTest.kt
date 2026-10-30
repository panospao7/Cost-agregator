package com.yourname.expensetracker.ui.screens.privacysettings

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.AuthPurpose
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.InertReason
import com.yourname.expensetracker.ui.applock.AppLockComposeTestBase
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S27 b2 (ux 4.2, INV-50): the "App PIN" row of the App lock section. Rendered only while the committed lock is on;
 * enabled only for [AppLockRowState.On] with no write in flight; the paused (InertOn) variant is disabled, shows the
 * unavailable text and never navigates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockPrivacySectionPinRowTest : AppLockComposeTestBase() {

    private var pinOpens = 0

    private fun ready(
        lock: AppLockRowState,
        isWriting: Boolean = false
    ): AppLockPrivacySectionState {
        val lockOn = lock is AppLockRowState.On || lock is AppLockRowState.InertOn ||
            (lock is AppLockRowState.Waiting && lock.committedOn)
        return AppLockPrivacySectionState.Ready(
            discreetOn = lockOn,
            isWriting = isWriting,
            notice = null,
            discreetForced = lockOn,
            lock = lock,
            timeoutRow = if (lockOn) AutoLockTimeoutRowState(AutoLockTimeout.M1, paused = lock is AppLockRowState.InertOn) else null,
            secureOn = true,
            secureForced = lockOn
        )
    }

    private fun show(state: AppLockPrivacySectionState): MutableState<AppLockPrivacySectionState> {
        val holder = mutableStateOf(state)
        composeRule.setContent {
            AppLockPrivacySectionContent(
                state = holder.value,
                onDiscreetChange = {},
                onRetry = {},
                onReviewDiscreetChange = {},
                onReviewSave = {},
                onEnableConfirmed = {},
                onTurnOff = {},
                onLockTryAgain = {},
                onOpenSecuritySettings = {},
                showApi30ActionsPoint = false,
                onOpenAppPin = { pinOpens++ }
            )
        }
        return holder
    }

    private fun MutableState<AppLockPrivacySectionState>.set(value: AppLockPrivacySectionState) {
        composeRule.runOnIdle { this.value = value }
        composeRule.waitForIdle()
    }

    private fun pinRowTagCount(): Int =
        composeRule.onAllNodesWithTag(AppLockPrivacySectionTags.PIN_ROW).fetchSemanticsNodes().size

    @Test
    fun `the PIN row is hidden while the lock is Off`() {
        show(ready(AppLockRowState.Off))
        assertEquals(0, pinRowTagCount())
    }

    @Test
    fun `the PIN row is hidden for every Unavailable reason`() {
        val state = show(ready(AppLockRowState.Off))
        AppLockUnavailableReason.values().forEach { reason ->
            state.set(ready(AppLockRowState.Unavailable(reason)))
            assertEquals("$reason", 0, pinRowTagCount())
        }
    }

    @Test
    fun `the PIN row is hidden while a not-yet-committed enable is Waiting`() {
        show(ready(AppLockRowState.Waiting(AuthPurpose.ENABLE, committedOn = false)))
        assertEquals(0, pinRowTagCount())
    }

    @Test
    fun `the PIN row is hidden while Loading and while TransientUnreadable`() {
        val state = show(AppLockPrivacySectionState.Loading)
        assertEquals(0, pinRowTagCount())
        state.set(AppLockPrivacySectionState.TransientUnreadable)
        assertEquals(0, pinRowTagCount())
    }

    @Test
    fun `the PIN row is enabled with title and support text when the lock is On, and a tap navigates once`() {
        show(ready(AppLockRowState.On))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.PIN_ROW)
            .assertIsEnabled()
            .assert(hasText(text(R.string.applock_pin_row_title)))
            .assert(hasText(text(R.string.applock_pin_row_support)))
            .assert(!hasText(text(R.string.applock_pin_row_unavailable_paused)))
            .performClick()

        assertEquals(1, pinOpens)
    }

    @Test
    fun `INV-50 the PIN row is disabled with the paused text and never navigates for every InertOn reason`() {
        val state = show(ready(AppLockRowState.On))
        InertReason.values().forEach { reason ->
            pinOpens = 0
            state.set(ready(AppLockRowState.InertOn(reason)))

            composeRule.onNodeWithTag(AppLockPrivacySectionTags.PIN_ROW)
                .assertIsNotEnabled()
                .assert(hasText(text(R.string.applock_pin_row_unavailable_paused)))
                .assert(!hasText(text(R.string.applock_pin_row_support)))
                .performClick()

            assertEquals("$reason must not navigate", 0, pinOpens)
        }
    }

    @Test
    fun `the PIN row is disabled while a write is in flight and does not navigate`() {
        show(ready(AppLockRowState.On, isWriting = true))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.PIN_ROW)
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.applock_pin_row_support)))
            .performClick()

        assertEquals(0, pinOpens)
    }

    @Test
    fun `the PIN row is disabled while the lock row is Waiting over a committed-on lock`() {
        show(ready(AppLockRowState.Waiting(AuthPurpose.CONFIRM_CHANGE, committedOn = true)))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.PIN_ROW).assertIsNotEnabled().performClick()

        assertEquals(0, pinOpens)
    }
}
