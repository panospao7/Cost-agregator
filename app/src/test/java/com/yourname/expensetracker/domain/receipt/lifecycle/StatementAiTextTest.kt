package com.yourname.expensetracker.domain.receipt.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [statementTextForAi]: whitespace is collapsed BEFORE scrubbing and the text is cut to the prompt
 * window at a whitespace boundary AFTER scrubbing, so no identifier fragment reaches the prompt.
 * Every IBAN and card number here is invented (zero-filled).
 */
class StatementAiTextTest {

    private val iban = "GR96 0000 0000 0000 0000 0000 123"
    private val card = "1234 5678 9012 3456"

    /** No-break space U+00A0 and narrow no-break space U+202F, built from code points. */
    private val nbsp: Char = 0xA0.toChar()
    private val narrowNbsp: Char = 0x202F.toChar()

    private fun filler(chars: Int): String = "ab ".repeat(chars / 3)

    @Test
    fun windowAndSliceConstantsMatchTheUseCaseCap() {
        assertEquals(4_000, STATEMENT_AI_PROMPT_WINDOW)
        assertTrue(STATEMENT_AI_TEXT_SLICE > STATEMENT_AI_PROMPT_WINDOW)
    }

    @Test
    fun ibanWithARunOfFiveSpacesIsMasked() {
        val text = "PAY GR96     0000 0000 0000 0000 0000 123 TO SHOP"
        val result = statementTextForAi(text)
        assertFalse(result, result.contains("GR96"))
        assertFalse(result, result.contains("0000"))
        assertEquals("PAY ****0123 TO SHOP", result)
    }

    @Test
    fun ibanAndCardSeparatedByNbspNarrowNbspAndNewlinesAreMasked() {
        val mixedIban = "GR96${nbsp}0000${narrowNbsp}0000\n0000 0000${nbsp}${nbsp}0000\n123"
        val mixedCard = "1234${nbsp}5678${narrowNbsp}9012\n3456"
        val result = statementTextForAi("A $mixedIban B $mixedCard C")
        assertEquals("A ****0123 B ****3456 C", result)
    }

    @Test
    fun whitespaceRunsAndUnicodeSpacesCollapseToSingleSpaces() {
        val result = statementTextForAi("  one \t\t two${nbsp}${nbsp}three\r\n\r\nfour${narrowNbsp} five  ")
        assertEquals("one two three four five", result)
    }

    @Test
    fun shortTextIsReturnedWhole() {
        assertEquals("SHOP -12.50 EUR", statementTextForAi("SHOP   -12.50\nEUR"))
        assertEquals("", statementTextForAi("   \n   "))
    }

    @Test
    fun ibanStraddlingTheWindowIsMaskedWholeAndNoFragmentSurvivesAtAnyOffset() {
        val allowed = setOf("ab", "****0123")
        for (offset in 3_950..4_050) {
            val text = "ab ".repeat(offset / 3) + iban + " " + "ab ".repeat(200)
            val result = statementTextForAi(text)
            assertTrue("offset $offset", result.length <= STATEMENT_AI_PROMPT_WINDOW)
            assertFalse("offset $offset", result.contains("GR96"))
            assertFalse("offset $offset", Regex("""\d{4}""").containsMatchIn(result.replace("****0123", "")))
            // Every token is complete: a whole filler word or the fully masked IBAN, never a fragment.
            assertTrue("offset $offset: ${result.takeLast(20)}", result.split(' ').all { it in allowed })
        }
    }

    @Test
    fun sixteenDigitCardStraddlingTheWindowIsMaskedWholeAndNoFragmentSurvivesAtAnyOffset() {
        val allowed = setOf("ab", "****3456")
        for (offset in 3_950..4_050) {
            val text = "ab ".repeat(offset / 3) + card + " " + "ab ".repeat(200)
            val result = statementTextForAi(text)
            assertTrue("offset $offset", result.length <= STATEMENT_AI_PROMPT_WINDOW)
            assertFalse("offset $offset", result.contains("1234"))
            assertFalse("offset $offset", result.contains("5678"))
            assertTrue("offset $offset: ${result.takeLast(20)}", result.split(' ').all { it in allowed })
        }
    }

    @Test
    fun cutDropsATrailingPartialTokenAtTheWindowBoundary() {
        val text = "ab ".repeat(1_500) + "LONGTOKEN"
        val result = statementTextForAi(text)
        assertTrue(result.length <= STATEMENT_AI_PROMPT_WINDOW)
        assertFalse(result.contains("LONGTOKEN"))
        assertTrue(result.endsWith("ab"))

        // A cut that lands exactly on a space keeps the last complete token.
        val exact = "a".repeat(3_990) + " " + "b".repeat(9) + " " + "tail ".repeat(50)
        assertEquals("a".repeat(3_990) + " " + "b".repeat(9), statementTextForAi(exact))
    }

    @Test
    fun aSingleTokenLongerThanTheWindowYieldsBlankInsteadOfAFragment() {
        assertEquals("", statementTextForAi("x".repeat(300_000)))
    }

    @Test
    fun hugeInputIsBoundedByTheSlice() {
        val result = statementTextForAi(filler(3_000_000))
        assertTrue(result.length <= STATEMENT_AI_PROMPT_WINDOW)
        assertTrue(result.length > STATEMENT_AI_PROMPT_WINDOW - 4)
    }
}
