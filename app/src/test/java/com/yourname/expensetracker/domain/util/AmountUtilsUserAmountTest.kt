package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R16: user-typed amount policy. el_GR = decimal ',' / grouping '.', en_US = decimal '.' / grouping ','.
 */
class AmountUtilsUserAmountTest {

    private fun el(text: String, maxFractionDigits: Int = 2) =
        AmountUtils.parseUserAmount(text, decimalSep = ',', groupingSep = '.', maxFractionDigits = maxFractionDigits)

    private fun us(text: String, maxFractionDigits: Int = 2) =
        AmountUtils.parseUserAmount(text, decimalSep = '.', groupingSep = ',', maxFractionDigits = maxFractionDigits)

    private fun assertValid(expected: Double, result: UserAmountResult) {
        assertTrue("Expected Valid but was $result", result is UserAmountResult.Valid)
        assertEquals(expected, (result as UserAmountResult.Valid).value, 0.0)
    }

    // ---- el_GR worked inputs ----

    @Test
    fun `el_GR comma decimal 12,50 is 12 point 50`() = assertValid(12.50, el("12,50"))

    @Test
    fun `el_GR grouped decimal 1 dot 234 comma 56 is 1234 point 56`() = assertValid(1234.56, el("1.234,56"))

    @Test
    fun `el_GR dot with exactly three digits is grouping 1 dot 234 is 1234`() = assertValid(1234.0, el("1.234"))

    @Test
    fun `el_GR dot with one digit is decimal 12 dot 5`() = assertValid(12.5, el("12.5"))

    @Test
    fun `el_GR comma with exactly three digits is ambiguous and never guessed`() {
        assertEquals(UserAmountResult.Ambiguous, el("1,234"))
    }

    // ---- en_US worked inputs ----

    @Test
    fun `en_US grouped decimal 1,234 dot 56 is 1234 point 56`() = assertValid(1234.56, us("1,234.56"))

    @Test
    fun `en_US comma with exactly three digits is grouping 1,234 is 1234`() = assertValid(1234.0, us("1,234"))

    @Test
    fun `en_US comma with two digits is decimal 12,50`() = assertValid(12.50, us("12,50"))

    @Test
    fun `en_US dot with exactly three digits is ambiguous and never guessed`() {
        assertEquals(UserAmountResult.Ambiguous, us("1.234"))
    }

    // ---- locale independent ----

    @Test
    fun `repeated grouping with decimal parses in both locales`() {
        assertValid(1234567.89, el("1.234.567,89"))
        assertValid(1234567.89, us("1.234.567,89"))
    }

    @Test
    fun `repeated single separator kind with strict groups is an integer`() {
        assertValid(1234567.0, el("1.234.567"))
        assertValid(1234567.0, us("1,234,567"))
    }

    @Test
    fun `misplaced grouping before decimal is invalid in both locales`() {
        assertEquals(UserAmountResult.Invalid, el("1,23.4"))
        assertEquals(UserAmountResult.Invalid, us("1,23.4"))
    }

    @Test
    fun `leading separator is read as zero point five`() {
        assertValid(0.5, el(",5"))
        assertValid(0.5, us(",5"))
        assertValid(0.5, us(".5"))
    }

    @Test
    fun `blank input is Empty`() {
        assertEquals(UserAmountResult.Empty, el(""))
        assertEquals(UserAmountResult.Empty, us("   "))
    }

    @Test
    fun `more than two fraction digits is invalid except the three digit grouping case`() {
        assertEquals(UserAmountResult.Invalid, el("12,5000"))
        assertEquals(UserAmountResult.Invalid, us("1,234.567"))
        assertEquals(UserAmountResult.Invalid, el("1.234,567"))
        assertEquals(UserAmountResult.Invalid, us("12.5000"))
    }

    @Test
    fun `more than ten integer digits is invalid`() {
        assertValid(9_999_999_999.0, us("9999999999"))
        assertEquals(UserAmountResult.Invalid, us("10000000000"))
        assertEquals(UserAmountResult.Invalid, el("10000000000,50"))
    }

    @Test
    fun `non numeric text sign and symbols are invalid`() {
        assertEquals(UserAmountResult.Invalid, us("abc"))
        assertEquals(UserAmountResult.Invalid, us("-5"))
        assertEquals(UserAmountResult.Invalid, us("5 EUR"))
        assertEquals(UserAmountResult.Invalid, us(","))
        assertEquals(UserAmountResult.Invalid, us("1..5"))
    }

    @Test
    fun `plain integers and trailing separator are valid`() {
        assertValid(12.0, us("12"))
        assertValid(12.0, el("12,"))
        assertValid(0.0, us("0"))
    }

    @Test
    fun `leading zero grouping is rejected rather than guessed`() {
        assertEquals(UserAmountResult.Invalid, el("0.234"))
        assertEquals(UserAmountResult.Invalid, us("0,234"))
    }

    @Test
    fun `no separator is ever deleted silently`() {
        // The old sanitizer turned "1,5" into 15; the policy keeps it as 1.5.
        assertValid(1.5, us("1,5"))
        assertValid(1.5, el("1,5"))
    }

    @Test
    fun `maxFractionDigits is honoured with BigDecimal scale check`() {
        assertValid(12.123, us("12.123", maxFractionDigits = 3))
        assertEquals(UserAmountResult.Invalid, us("12.1234", maxFractionDigits = 3))
        assertValid(12.0, us("12", maxFractionDigits = 0))
        assertEquals(UserAmountResult.Invalid, us("12.5", maxFractionDigits = 0))
    }

    @Test
    fun `identical decimal and grouping separators are invalid configuration`() {
        assertEquals(UserAmountResult.Invalid, AmountUtils.parseUserAmount("12", ',', ','))
    }

    // ---- statement amounts ----

    @Test
    fun `statement single separator with three trailing digits is grouping`() {
        assertEquals(1234.0, AmountUtils.parseStatementAmount("1,234")!!, 0.0)
        assertEquals(1234.0, AmountUtils.parseStatementAmount("1.234")!!, 0.0)
        assertEquals(1234567.0, AmountUtils.parseStatementAmount("1,234,567")!!, 0.0)
        assertEquals(1234567.0, AmountUtils.parseStatementAmount("1.234.567")!!, 0.0)
        assertEquals(-1234.0, AmountUtils.parseStatementAmount("-1,234")!!, 0.0)
    }

    @Test
    fun `statement tokens with symbols and two decimal forms delegate to parseAmount`() {
        assertEquals(1234.0, AmountUtils.parseStatementAmount("EUR1,234")!!, 0.0)
        assertEquals(1234.56, AmountUtils.parseStatementAmount("1.234,56")!!, 0.0)
        assertEquals(1234.56, AmountUtils.parseStatementAmount("1,234.56")!!, 0.0)
        assertEquals(12.5, AmountUtils.parseStatementAmount("12.50")!!, 0.0)
        assertEquals(12.5, AmountUtils.parseStatementAmount("12,50")!!, 0.0)
        assertEquals(-12.5, AmountUtils.parseStatementAmount("-12,50")!!, 0.0)
        assertEquals(1500.0, AmountUtils.parseStatementAmount("+1500,00")!!, 0.0)
        assertEquals(0.123, AmountUtils.parseStatementAmount("0.123")!!, 0.0)
        assertNull(AmountUtils.parseStatementAmount(""))
    }

    @Test
    fun `statement leading zero token reads both separators as the decimal 0_123`() {
        assertEquals(0.123, AmountUtils.parseStatementAmount("0,123")!!, 0.0)
        assertEquals(0.123, AmountUtils.parseStatementAmount("0.123")!!, 0.0)
        assertEquals(-0.123, AmountUtils.parseStatementAmount("-0,123")!!, 0.0)
        assertEquals(0.123, AmountUtils.parseStatementAmount("EUR0,123")!!, 0.0)
    }

    @Test
    fun `statement parse equals parseAmount for tokens without trailing three digit grouping`() {
        for (token in listOf("12", "12,5", "12.50", "-8,99", "1234,56", "1.234,56", "1,234.56", "+5")) {
            assertEquals(token, AmountUtils.parseAmount(token), AmountUtils.parseStatementAmount(token))
        }
    }

    @Test
    fun `parseAmount machine behavior is unchanged for grouping versus decimal inputs`() {
        // parseAmount (OCR/machine path) keeps its existing contract; R16 only adds new entry points.
        assertEquals(1.234, AmountUtils.parseAmount("1.234")!!, 0.0)
        assertEquals(1234.0, AmountUtils.parseAmount("1,234")!!, 0.0)
    }
}
