package com.yourname.expensetracker.domain.receipt.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * RA-SI-3: [statementTextForAi] never shows the digits of a letter-adjacent account number.
 * All values are invented.
 */
class StatementAiTextLetterAdjacentDigitsTest {

    @Test
    fun accountNumberWithLetterPrefixIsMaskedInAiText() {
        val result = statementTextForAi("PAYMENT TO ACC123456789 THANK YOU")
        assertFalse(result, result.contains("123456789"))
        assertFalse(result, result.contains("6789"))
        assertEquals("PAYMENT TO ACC**** THANK YOU", result)
    }

    @Test
    fun accountNumberWithLetterSuffixIsMaskedInAiText() {
        val result = statementTextForAi("BALANCE 123456789EUR TODAY")
        assertFalse(result, result.contains("123456789"))
        assertEquals("BALANCE ****EUR TODAY", result)
    }

    @Test
    fun accountNumberOnItsOwnStatementLineIsMasked() {
        val result = statementTextForAi("SHOP -12.50 EUR\nACC123456789\n01/03/2024 COFFEE -3,20")
        assertFalse(result, result.contains("123456789"))
        assertEquals("SHOP -12.50 EUR ACC**** 01/03/2024 COFFEE -3,20", result)
    }

    @Test
    fun boundaryEightDigitsNextToLettersStayInAiText() {
        assertEquals("REF ACC12345678 OK", statementTextForAi("REF ACC12345678 OK"))
    }

    @Test
    fun datesAndAmountsStayInAiText() {
        assertEquals(
            "01/03/2024 SHOP 12,345.67 EUR",
            statementTextForAi("01/03/2024 SHOP 12,345.67 EUR")
        )
    }

    @Test
    fun storedMerchantKeepsLetterAdjacentDigitsWhileAiTextMasksThem() {
        assertEquals("ACC123456789", scrubStoredStatementText("ACC123456789"))
        assertEquals("ACC****", scrubStatementTextForAi("ACC123456789"))
        assertEquals("PAYMENT TO ACC**** OK", statementTextForAi("PAYMENT TO ACC123456789 OK"))
    }

    @Test
    fun ibanStillBecomesLastFourInAiText() {
        assertEquals(
            "PAY ****0123 TO SHOP",
            statementTextForAi("PAY GR96 0000 0000 0000 0000 0000 123 TO SHOP")
        )
    }
}
