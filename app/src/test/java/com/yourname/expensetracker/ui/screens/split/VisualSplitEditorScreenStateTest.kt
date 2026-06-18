package com.yourname.expensetracker.ui.screens.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VisualSplitEditorScreenStateTest {

    @Test
    fun `trailing dot is preserved when user types partial decimal`() {
        var state = SplitTextFieldState.initial(50.0)
        state = state.onUserInput("3.")
        assertEquals("3.", state.text)
        assertEquals(3.0, state.lastCommittedValue, 0.001)
    }

    @Test
    fun `trailing dot is not overwritten by external sync for same committed value`() {
        var state = SplitTextFieldState.initial(50.0)
        state = state.onUserInput("3.")
        state = state.onExternalValueChange(3.0)
        assertEquals("3.", state.text)
    }

    @Test
    fun `external template load overwrites text for different value`() {
        var state = SplitTextFieldState.initial(50.0)
        state = state.onExternalValueChange(25.0)
        assertEquals("25.0", state.text)
        assertEquals(25.0, state.lastCommittedValue, 0.001)
    }

    @Test
    fun `external change after user edit with different value overwrites text`() {
        var state = SplitTextFieldState.initial(50.0)
        state = state.onUserInput("75.0")
        state = state.onExternalValueChange(33.33)
        assertEquals("33.33", state.text)
    }

    @Test
    fun `empty input does not commit a value`() {
        var state = SplitTextFieldState.initial(50.0)
        var parsedCallbackInvoked = false
        state = state.onUserInput("") { parsedCallbackInvoked = true }
        assertEquals("", state.text)
        assertEquals(50.0, state.lastCommittedValue, 0.001)
        assertFalse(parsedCallbackInvoked)
    }

    @Test
    fun `locale comma decimal does not parse and does not commit`() {
        var state = SplitTextFieldState.initial(50.0)
        state = state.onUserInput("3,5")
        assertEquals("3,5", state.text)
        assertEquals(50.0, state.lastCommittedValue, 0.001)
    }

    @Test
    fun `clear and retype preserves text through intermediate states`() {
        var state = SplitTextFieldState.initial(100.0)
        state = state.onUserInput("")
        assertEquals("", state.text)
        state = state.onUserInput("5")
        assertEquals("5", state.text)
        assertEquals(5.0, state.lastCommittedValue, 0.001)
        state = state.onUserInput("5.")
        assertEquals("5.", state.text)
        assertEquals(5.0, state.lastCommittedValue, 0.001)
        state = state.onUserInput("5.50")
        assertEquals("5.50", state.text)
        assertEquals(5.50, state.lastCommittedValue, 0.001)
    }

    @Test
    fun `NaN input is rejected and does not commit`() {
        var state = SplitTextFieldState.initial(50.0)
        var callbackInvoked = false
        state = state.onUserInput("NaN") { callbackInvoked = true }
        assertEquals("NaN", state.text)
        assertEquals(50.0, state.lastCommittedValue, 0.001)
        assertFalse(callbackInvoked)
    }

    @Test
    fun `Infinity input is rejected and does not commit`() {
        var state = SplitTextFieldState.initial(50.0)
        var callbackInvoked = false
        state = state.onUserInput("Infinity") { callbackInvoked = true }
        assertEquals("Infinity", state.text)
        assertEquals(50.0, state.lastCommittedValue, 0.001)
        assertFalse(callbackInvoked)
    }

    @Test
    fun `negative infinity input is rejected and does not commit`() {
        var state = SplitTextFieldState.initial(50.0)
        var callbackInvoked = false
        state = state.onUserInput("-Infinity") { callbackInvoked = true }
        assertEquals("-Infinity", state.text)
        assertEquals(50.0, state.lastCommittedValue, 0.001)
        assertFalse(callbackInvoked)
    }

    @Test
    fun `re-typing same value does not trigger false external sync`() {
        var state = SplitTextFieldState.initial(50.0)
        state = state.onUserInput("25.0")
        state = state.onExternalValueChange(25.0)
        assertEquals("25.0", state.text)
    }
}
