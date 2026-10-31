package com.yourname.expensetracker.domain.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for the single minor-unit digits accessor [CurrencyFractionDigits]. */
class CurrencyFractionDigitsTest {

    private fun code(value: String): CurrencyCode =
        requireNotNull(CurrencyCode.parse(value)) { "test currency code must parse" }

    @Test
    fun `EUR has 2 digits`() {
        assertEquals(2, CurrencyFractionDigits.of(code("EUR")))
    }

    @Test
    fun `JPY has 0 digits`() {
        assertEquals(0, CurrencyFractionDigits.of(code("JPY")))
    }

    @Test
    fun `KRW has 0 digits`() {
        assertEquals(0, CurrencyFractionDigits.of(code("KRW")))
    }

    @Test
    fun `BHD has 3 digits`() {
        assertEquals(3, CurrencyFractionDigits.of(code("BHD")))
    }

    @Test
    fun `XXX has no digits and is unavailable`() {
        assertNull(CurrencyFractionDigits.of(code("XXX")))
    }

    @Test
    fun `a well-formed but unknown code is unavailable`() {
        assertNull(CurrencyFractionDigits.of(code("ZZZ")))
    }

    @Test
    fun `KWD outside the app catalog still resolves to 3 digits`() {
        assertEquals(3, CurrencyFractionDigits.of(code("KWD")))
    }

    @Test
    fun `well-formed non-ISO codes QQQ and AAA are unavailable`() {
        assertNull(CurrencyFractionDigits.of(code("QQQ")))
        assertNull(CurrencyFractionDigits.of(code("AAA")))
    }
}
