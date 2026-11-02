package com.yourname.expensetracker.ui.screens.privacysettings

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.PinStep
import com.yourname.expensetracker.ui.applock.AppLockComposeTestBase
import com.yourname.expensetracker.ui.applock.pin.PinPadTags
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S27b (T30, AMD-2, INV-38): the PIN confirm dialog of a lengthening timeout / weakening review-save in PIN mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PinConfirmDialogTest : AppLockComposeTestBase() {

    private var cancels = 0
    private val submitted = mutableListOf<String>()

    private fun show(state: PinConfirmUiState): MutableState<PinConfirmUiState> {
        val holder = mutableStateOf(state)
        composeRule.setContent {
            PinConfirmDialog(
                state = holder.value,
                onSubmit = { pin -> submitted += String(pin); pin.fill('\u0000') },
                onCancel = { cancels++ }
            )
        }
        return holder
    }

    private fun press(vararg digits: Char) =
        digits.forEach { composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + it).performClick() }

    private fun countOfText(value: String) =
        composeRule.onAllNodesWithText(value).fetchSemanticsNodes().size

    @Test
    fun `the title follows the step`() {
        val state = show(PinConfirmUiState(PinStep.TIMEOUT_CONFIRM))
        val timeoutTitle = text(R.string.applock_pin_confirm_timeout_title)
        val reviewTitle = text(R.string.applock_pin_confirm_review_title)
        composeRule.onNodeWithText(timeoutTitle).assertExists()
        assertEquals(0, countOfText(reviewTitle))

        composeRule.runOnIdle { state.value = PinConfirmUiState(PinStep.REVIEW_CONFIRM) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(reviewTitle).assertExists()
        assertEquals(0, countOfText(timeoutTitle))
    }

    @Test
    fun `Cancel calls onCancel once, and is disabled while busy`() {
        val state = show(PinConfirmUiState(PinStep.TIMEOUT_CONFIRM))
        composeRule.onNodeWithTag(PinConfirmTags.CANCEL).assertIsEnabled().performClick()
        assertEquals(1, cancels)

        composeRule.runOnIdle { state.value = PinConfirmUiState(PinStep.TIMEOUT_CONFIRM, busy = true) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PinConfirmTags.CANCEL).assertIsNotEnabled()
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "5").assertIsNotEnabled()
    }

    @Test
    fun `confirm below the minimum length is disabled, and at the minimum it passes exactly the typed digits`() {
        show(PinConfirmUiState(PinStep.REVIEW_CONFIRM))
        press('1', '3', '5')
        composeRule.onNodeWithTag(PinPadTags.CONFIRM).assertIsNotEnabled()
        press('7', '9', '0')
        composeRule.onNodeWithTag(PinPadTags.CONFIRM).assertIsEnabled().performClick()

        assertEquals(listOf("135790"), submitted)
        composeRule.onNodeWithTag(PinPadTags.CONFIRM).assertIsNotEnabled() // the buffer was consumed
    }

    @Test
    fun `a wrong or delayed message is shown under the dots`() {
        val state = show(PinConfirmUiState(PinStep.TIMEOUT_CONFIRM, PinConfirmMessage.WRONG))
        composeRule.onNodeWithTag(PinConfirmTags.MESSAGE).assertTextEquals(text(R.string.applock_pin_wrong))

        composeRule.runOnIdle { state.value = PinConfirmUiState(PinStep.TIMEOUT_CONFIRM, PinConfirmMessage.DELAYED) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PinConfirmTags.MESSAGE).assertTextEquals(text(R.string.applock_pin_confirm_unavailable))
    }

    @Test
    fun `a published delay disables the pad and shows the Too many attempts countdown`() {
        composeRule.mainClock.autoAdvance = false
        show(PinConfirmUiState(PinStep.TIMEOUT_CONFIRM, PinConfirmMessage.DELAYED, delayRemainingMillis = 120_000L))
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        val prefix = text(R.string.applock_pin_wait, "\u0001").substringBefore("\u0001")
        composeRule.onNodeWithTag(PinConfirmTags.MESSAGE).assertTextContains(prefix, substring = true)
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "5").assertIsNotEnabled()
        composeRule.onNodeWithTag(PinPadTags.CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithTag(PinConfirmTags.CANCEL).assertIsEnabled()
    }

    @Test
    fun `a delay present when the dialog opens disables the pad on the first composition`() {
        composeRule.mainClock.autoAdvance = false
        show(PinConfirmUiState(PinStep.TIMEOUT_CONFIRM, PinConfirmMessage.DELAYED, delayRemainingMillis = 120_000L))
        // No frame advanced and no idle wait beyond the first composition: nothing may be enabled.
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "5").assertIsNotEnabled()
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "0").assertIsNotEnabled()
        composeRule.onNodeWithTag(PinPadTags.KEY_DELETE).assertIsNotEnabled()
        composeRule.onNodeWithTag(PinPadTags.CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `a neutral delay shows the neutral copy and never Too many attempts`() {
        composeRule.mainClock.autoAdvance = false
        show(
            PinConfirmUiState(
                PinStep.REVIEW_CONFIRM,
                PinConfirmMessage.DELAYED,
                delayRemainingMillis = 120_000L,
                delayNeutral = true
            )
        )
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        val neutral = text(R.string.applock_pin_wait_neutral, "\u0001").substringBefore("\u0001")
        val tooMany = text(R.string.applock_pin_wait, "\u0001").substringBefore("\u0001")
        composeRule.onNodeWithTag(PinConfirmTags.MESSAGE).assertTextContains(neutral, substring = true)
        composeRule.onAllNodesWithText(tooMany, substring = true).assertCountEquals(0)
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "5").assertIsNotEnabled()
    }
}
