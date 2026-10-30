package com.yourname.expensetracker.ui.screens.privacysettings.applockpin

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.applock.AppLockComposeTestBase
import com.yourname.expensetracker.ui.applock.pin.PinPadTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S27 b1: the in-app PIN steps (UQ-4 acknowledgement, entry pad, AE-119 empty-after-recreate, INV-38). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PinSetupFlowTest : AppLockComposeTestBase() {

    private var state by mutableStateOf(AppPinSettingsUiState())
    private val acknowledged = mutableListOf<Boolean>()
    private var continues = 0
    private val submitted = mutableListOf<CharArray>()

    private val actions = PinSetupFlowActions(
        onAcknowledge = { acknowledged += it },
        onContinue = { continues++ },
        onBackUpNow = {},
        onSubmitEntry = { submitted += it },
        onConfirmRemove = {},
        onCancel = {}
    )

    private fun show(initial: AppPinSettingsUiState) {
        state = initial
        composeRule.setContent { PinSetupFlow(state, actions) }
    }

    private fun digitsDescription(): String? =
        composeRule.onNodeWithTag(PinPadTags.DOT_ROW).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription)

    private fun expectedDigits(count: Int): String =
        ApplicationProvider.getApplicationContext<Context>().resources
            .getQuantityString(R.plurals.applock_pin_digits_state, count, count)

    @Test
    fun `UQ-4 the acknowledgement step shows the checkbox row and Continue is disabled until it is checked`() {
        show(AppPinSettingsUiState(step = PinFlowStep.SETUP_ACK, ackChecked = false))
        composeRule.onNodeWithTag(PinSetupFlowTags.ACK_ROW).assertIsDisplayed()
        composeRule.onNodeWithTag(PinSetupFlowTags.CONTINUE).assertIsNotEnabled()
        composeRule.onNodeWithTag(PinSetupFlowTags.ACK_ROW).performClick()
        assertEquals(listOf(true), acknowledged)
        state = state.copy(ackChecked = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PinSetupFlowTags.CONTINUE).assertIsEnabled()
    }

    @Test
    fun `UQ-4 a disabled Continue does not call the host and an enabled one does`() {
        show(AppPinSettingsUiState(step = PinFlowStep.SETUP_ACK, ackChecked = false))
        composeRule.onNodeWithTag(PinSetupFlowTags.CONTINUE).performClick()
        assertEquals(0, continues)
        state = state.copy(ackChecked = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PinSetupFlowTags.CONTINUE).performClick()
        assertEquals(1, continues)
    }

    @Test
    fun `UQ-4 a busy flow keeps Continue disabled even when the box is checked`() {
        show(AppPinSettingsUiState(step = PinFlowStep.SETUP_ACK, ackChecked = true, busy = true))
        composeRule.onNodeWithTag(PinSetupFlowTags.CONTINUE).assertIsNotEnabled()
    }

    @Test
    fun `entry steps show the dot row and the pad and no acknowledgement row`() {
        show(AppPinSettingsUiState(step = PinFlowStep.SETUP_ENTER))
        composeRule.onNodeWithTag(PinPadTags.DOT_ROW).assertIsDisplayed()
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "5").assertIsDisplayed()
        composeRule.onNodeWithTag(PinSetupFlowTags.CANCEL).assertIsDisplayed()
        composeRule.onAllNodesWithTag(PinSetupFlowTags.ACK_ROW).assertCountEquals(0)
        for (step in listOf(PinFlowStep.SETUP_CONFIRM, PinFlowStep.CHANGE_CURRENT, PinFlowStep.CHANGE_NEW)) {
            state = AppPinSettingsUiState(step = step)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(PinPadTags.DOT_ROW).assertIsDisplayed()
            assertEquals("step $step", expectedDigits(0), digitsDescription())
        }
    }

    @Test
    fun `AE-119 a new step starts with empty dots and typed digits are gone after the content is removed and re-added`() {
        show(AppPinSettingsUiState(step = PinFlowStep.SETUP_ENTER))
        assertEquals(expectedDigits(0), digitsDescription())
        for (digit in listOf("1", "2", "3")) composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + digit).performClick()
        composeRule.waitForIdle()
        assertEquals(expectedDigits(3), digitsDescription())
        state = AppPinSettingsUiState(step = PinFlowStep.NONE) // the screen leaves composition (recreate)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(PinPadTags.DOT_ROW).assertCountEquals(0)
        state = AppPinSettingsUiState(step = PinFlowStep.SETUP_ENTER)
        composeRule.waitForIdle()
        assertEquals(expectedDigits(0), digitsDescription())
        assertTrue("nothing was submitted by re-creation", submitted.isEmpty())
    }

    @Test
    fun `AE-119 moving to the next step discards digits typed on the previous step`() {
        show(AppPinSettingsUiState(step = PinFlowStep.SETUP_ENTER))
        for (digit in listOf("4", "5")) composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + digit).performClick()
        composeRule.waitForIdle()
        assertEquals(expectedDigits(2), digitsDescription())
        state = AppPinSettingsUiState(step = PinFlowStep.SETUP_CONFIRM)
        composeRule.waitForIdle()
        assertEquals(expectedDigits(0), digitsDescription())
    }
}
