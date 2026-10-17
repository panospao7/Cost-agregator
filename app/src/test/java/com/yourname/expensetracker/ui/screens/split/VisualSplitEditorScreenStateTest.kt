package com.yourname.expensetracker.ui.screens.split

import com.yourname.expensetracker.ui.util.AmountInputSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

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
        withLocale(Locale.US) {
            var state = SplitTextFieldState.initial(50.0)
            state = state.onExternalValueChange(25.0)
            assertEquals("25.00", state.text)
            assertEquals(25.0, state.lastCommittedValue, 0.001)
        }
    }

    @Test
    fun `external change after user edit with different value overwrites text`() {
        withLocale(Locale.US) {
            var state = SplitTextFieldState.initial(50.0)
            state = state.onUserInput("75.0")
            state = state.onExternalValueChange(33.33)
            assertEquals("33.33", state.text)
        }
    }

    @Test
    fun `unrounded template percentage is prefilled with two decimals not Double toString`() {
        withLocale(Locale.US) {
            assertEquals("33.30", SplitTextFieldState.initial(33.300000000000004).text)
            assertEquals("10000000.00", SplitTextFieldState.initial(1.0E7).text)
        }
        withLocale(Locale.forLanguageTag("el-GR")) {
            assertEquals("33,30", SplitTextFieldState.initial(33.300000000000004).text)
            assertEquals("10000000,00", SplitTextFieldState.initial(1.0E7).text)
        }
    }

    @Test
    fun `prefill round trips and a backspace keeps the value in el_GR en_US de_DE fr_FR`() {
        for (tag in listOf("el-GR", "en-US", "de-DE", "fr-FR")) {
            withLocale(Locale.forLanguageTag(tag)) {
                val text = SplitTextFieldState.initial(33.300000000000004).text
                assertEquals(tag, 33.3, AmountInputSanitizer.parseOrNull(text)!!, 0.0)
                assertEquals(tag, text, AmountInputSanitizer.sanitize(text))
                // backspace then re-sanitize must not turn the value into a thousands group
                val afterBackspace = AmountInputSanitizer.sanitize(text.dropLast(1))
                assertEquals(tag, 33.3, AmountInputSanitizer.parseOrNull(afterBackspace)!!, 0.0)
                // a third typed decimal digit is capped, never read as grouping (150.005 in el_GR)
                val prefill = SplitTextFieldState.initial(150.0).text
                assertEquals(tag, 150.0, AmountInputSanitizer.parseOrNull(AmountInputSanitizer.sanitize(prefill + "5"))!!, 0.0)
            }
        }
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

    // R16c: comma decimals now commit (previously pinned as not committing).
    @Test
    fun `locale comma decimal parses and commits`() {
        var state = SplitTextFieldState.initial(50.0)
        var committed: Double? = null
        state = state.onUserInput("3,5") { committed = it }
        assertEquals("3,5", state.text)
        assertEquals(3.5, state.lastCommittedValue, 0.001)
        assertEquals(3.5, committed!!, 0.001)
    }

    @Test
    fun `el_GR comma and dot decimals both commit and text is kept`() {
        withLocale(Locale.forLanguageTag("el-GR")) {
            var state = SplitTextFieldState.initial(50.0)
            state = state.onUserInput("12,50")
            assertEquals("12,50", state.text)
            assertEquals(12.5, state.lastCommittedValue, 0.001)
            state = state.onUserInput("7.25")
            assertEquals(7.25, state.lastCommittedValue, 0.001)
            // typed comma form must not be rewritten by the echo of its own committed value
            state = state.onUserInput("12,")
            assertEquals(12.0, state.lastCommittedValue, 0.001)
            state = state.onExternalValueChange(12.0)
            assertEquals("12,", state.text)
        }
    }

    @Test
    fun `en_US dot and comma decimals both commit`() {
        withLocale(Locale.US) {
            var state = SplitTextFieldState.initial(50.0)
            state = state.onUserInput("12.50")
            assertEquals(12.5, state.lastCommittedValue, 0.001)
            state = state.onUserInput("3,5")
            assertEquals(3.5, state.lastCommittedValue, 0.001)
        }
    }

    @Test
    fun `ambiguous grouping input does not commit`() {
        withLocale(Locale.forLanguageTag("el-GR")) {
            var state = SplitTextFieldState.initial(50.0)
            var invoked = false
            state = state.onUserInput("1,234") { invoked = true }
            assertEquals("1,234", state.text)
            assertEquals(50.0, state.lastCommittedValue, 0.001)
            assertFalse(invoked)
        }
    }

    private fun withLocale(locale: Locale, block: () -> Unit) {
        val original = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            block()
        } finally {
            Locale.setDefault(original)
        }
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
