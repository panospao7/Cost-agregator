package com.yourname.expensetracker.ui.screens.privacysettings.applockpin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yourname.expensetracker.ui.applock.AppLockComposeTestBase
import com.yourname.expensetracker.ui.applock.pin.PinPadTags
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S27b reviewer B1: the PIN entry steps at the DEFAULT Robolectric qualifier (a small screen, deliberately NO
 * `w411dp-h891dp` override as in [PinSetupFlowTest]). The step content is taller than the viewport here, so the
 * confirm button is only reachable when the step Column scrolls (a non-scrollable Column makes `performScrollTo` fail).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinSetupFlowDefaultQualifierTest : AppLockComposeTestBase() {

    private var state by mutableStateOf(AppPinSettingsUiState())
    private val submitted = mutableListOf<CharArray>()
    private var turnOffConfirms = 0

    private val actions = PinSetupFlowActions(
        onAcknowledge = {},
        onContinue = {},
        onBackUpNow = {},
        onSubmitEntry = { submitted += it },
        onConfirmRemove = {},
        onCancel = {},
        onConfirmTurnOff = { turnOffConfirms++ }
    )

    private fun show(initial: AppPinSettingsUiState) {
        state = initial
        composeRule.setContent { PinSetupFlow(state, actions) }
    }

    private fun typeDigits(digits: String) {
        for (digit in digits) composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + digit).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `B1 at the default qualifier the setup entry CONFIRM is reachable by scrolling and submits the typed PIN`() {
        show(AppPinSettingsUiState(step = PinFlowStep.SETUP_ENTER))
        typeDigits("739104")

        composeRule.onNodeWithTag(PinPadTags.CONFIRM).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()

        assertEquals(1, submitted.size)
        assertEquals("739104", String(submitted.single()))
    }

    @Test
    fun `B1 at the default qualifier the Cancel button of an entry step is reachable by scrolling`() {
        show(AppPinSettingsUiState(step = PinFlowStep.CHANGE_CURRENT))

        composeRule.onNodeWithTag(PinSetupFlowTags.CANCEL).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `B1 at the default qualifier the turn-off entry step CONFIRM is reachable by scrolling`() {
        show(AppPinSettingsUiState(step = PinFlowStep.TURN_OFF_ENTER))
        typeDigits("482916")

        composeRule.onNodeWithTag(PinPadTags.CONFIRM).performScrollTo().assertIsDisplayed().performClick()

        assertEquals("482916", String(submitted.single()))
    }

    @Test
    fun `S27b the turn-off dialog confirm calls onConfirmTurnOff exactly once and never the remove action`() {
        show(AppPinSettingsUiState(step = PinFlowStep.TURN_OFF_CONFIRM))

        composeRule.onNodeWithTag(PinSetupFlowTags.TURN_OFF_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(PinSetupFlowTags.TURN_OFF_CONFIRM).performClick()

        assertEquals(1, turnOffConfirms)
        assertEquals(0, submitted.size)
    }
}
