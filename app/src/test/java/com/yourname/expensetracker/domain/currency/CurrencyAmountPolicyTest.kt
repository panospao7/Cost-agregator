package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.CurrencyFractionDigits
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * manual-entry-currency A1: [CurrencyAmountPolicy] (INV-12, 13, 14; AE-15..18 policy parts;
 * OD-6 fail-closed). Pure tests: no rate table is involved, so a rate being present or absent
 * cannot change any result (AE-18 last sentence).
 */
class CurrencyAmountPolicyTest {

    private fun code(value: String): CurrencyCode =
        requireNotNull(CurrencyCode.parse(value)) { "test currency code must parse" }

    private fun big(value: String) = BigDecimal(value)

    // ---- digits: all 28 catalog currencies pinned (domain spec section 1.5) ----

    /** 0 digits for JPY, KRW, ISK; 2 for every other catalog code, including HUF. */
    private val expectedDigits: Map<String, Int> = mapOf(
        "EUR" to 2, "USD" to 2, "GBP" to 2, "JPY" to 0, "CHF" to 2, "CAD" to 2, "AUD" to 2,
        "CNY" to 2, "NZD" to 2, "MXN" to 2, "SGD" to 2, "HKD" to 2, "KRW" to 0, "TRY" to 2,
        "RUB" to 2, "INR" to 2, "BRL" to 2, "ZAR" to 2, "SEK" to 2, "NOK" to 2, "DKK" to 2,
        "PLN" to 2, "CZK" to 2, "HUF" to 2, "RON" to 2, "BGN" to 2, "HRK" to 2, "ISK" to 0
    )

    @Test
    fun `the digits table covers exactly the 28 catalog currencies`() {
        assertEquals(28, SupportedCurrency.catalog.size)
        assertEquals(SupportedCurrency.catalog.map { it.code }.toSet(), expectedDigits.keys)
    }

    @Test
    fun `fractionDigits is pinned for every catalog currency`() {
        for (currency in SupportedCurrency.catalog) {
            assertEquals(
                "digits of ${currency.code}",
                expectedDigits.getValue(currency.code),
                CurrencyAmountPolicy.fractionDigits(code(currency.code))
            )
        }
    }

    @Test
    fun `fractionDigits delegates to the single accessor for catalog and non-catalog codes`() {
        for (value in SupportedCurrency.catalog.map { it.code } + listOf("BHD", "XXX", "ZZZ")) {
            assertEquals(
                "delegation for $value",
                CurrencyFractionDigits.of(code(value)),
                CurrencyAmountPolicy.fractionDigits(code(value))
            )
        }
    }

    @Test
    fun `fractionDigits is null for an unknown code and never guesses 2`() {
        assertNull(CurrencyAmountPolicy.fractionDigits(code("ZZZ")))
        assertNull(CurrencyAmountPolicy.fractionDigits(code("XXX")))
    }

    // ---- cap table (domain spec section 6; INV-14) ----

    private val expectedCaps: Map<String, BigDecimal> = buildMap {
        listOf("EUR", "USD", "GBP", "CHF", "CAD", "AUD", "NZD", "SGD", "CNY", "HKD", "DKK", "PLN", "RON", "BRL", "BGN", "HRK")
            .forEach { put(it, big("1000000")) }
        listOf("MXN", "ZAR", "SEK", "NOK", "CZK", "TRY").forEach { put(it, big("10000000")) }
        listOf("JPY", "INR", "RUB", "HUF", "ISK").forEach { put(it, big("100000000")) }
        put("KRW", big("1000000000"))
    }

    @Test
    fun `the cap table covers exactly the 28 catalog currencies`() {
        assertEquals(SupportedCurrency.catalog.map { it.code }.toSet(), expectedCaps.keys)
    }

    @Test
    fun `cap is pinned for every catalog currency`() {
        for (currency in SupportedCurrency.catalog) {
            val actual = CurrencyAmountPolicy.cap(code(currency.code))
            assertEquals(
                "cap of ${currency.code}",
                0,
                expectedCaps.getValue(currency.code).compareTo(actual)
            )
        }
    }

    @Test
    fun `a non-catalog code keeps the 1,000,000 cap`() {
        assertEquals(0, big("1000000").compareTo(CurrencyAmountPolicy.cap(code("BHD"))))
        assertEquals(0, big("1000000").compareTo(CurrencyAmountPolicy.cap(code("ZZZ"))))
        assertEquals(0, big("1000000").compareTo(CurrencyAmountPolicy.DEFAULT_CAP))
    }

    // ---- check: AE-15 (JPY whole yen) ----

    @Test
    fun `AE-15 JPY 1500 is valid and 1500,50 is precision exceeded with zero allowed digits`() {
        assertEquals(AmountCheck.Ok, CurrencyAmountPolicy.check(big("1500"), code("JPY")))
        assertEquals(AmountCheck.PrecisionExceeded(0), CurrencyAmountPolicy.check(big("1500.5"), code("JPY")))
        assertEquals(AmountCheck.PrecisionExceeded(0), CurrencyAmountPolicy.check(1500.5, code("JPY")))
    }

    @Test
    fun `trailing zeros do not count as precision`() {
        assertEquals(AmountCheck.Ok, CurrencyAmountPolicy.check(big("1500.00"), code("JPY")))
        assertEquals(AmountCheck.Ok, CurrencyAmountPolicy.check(big("12.500"), code("EUR")))
        assertEquals(AmountCheck.Ok, CurrencyAmountPolicy.check(big("1.5E+3"), code("JPY")))
    }

    // ---- check: AE-16 (currency switch re-validates the same text) ----

    @Test
    fun `AE-16 the same value 12_50 is valid in EUR, invalid in JPY, and valid again in EUR`() {
        val parsed = big("12.50")
        assertEquals(AmountCheck.Ok, CurrencyAmountPolicy.check(parsed, code("EUR")))
        assertEquals(AmountCheck.PrecisionExceeded(0), CurrencyAmountPolicy.check(parsed, code("JPY")))
        assertEquals(AmountCheck.Ok, CurrencyAmountPolicy.check(parsed, code("EUR")))
    }

    @Test
    fun `an EUR amount with three decimals is rejected and never rounded`() {
        assertEquals(AmountCheck.PrecisionExceeded(2), CurrencyAmountPolicy.check(big("12.345"), code("EUR")))
        assertEquals(AmountCheck.PrecisionExceeded(2), CurrencyAmountPolicy.check(12.345, code("EUR")))
    }

    // ---- check: AE-17 (R16 parse results) ----

    @Test
    fun `AE-17 JPY parsed from 1,500 is 1500 and valid, parsed from 1_500 is 1_5 and precision exceeded`() {
        assertEquals(AmountCheck.Ok, CurrencyAmountPolicy.check(1500.0, code("JPY")))
        assertEquals(AmountCheck.PrecisionExceeded(0), CurrencyAmountPolicy.check(1.5, code("JPY")))
    }

    // ---- check: AE-18 (caps) ----

    @Test
    fun `AE-18 KRW 1,500,000 is allowed and KRW 1,000,000,001 is above the cap`() {
        assertEquals(AmountCheck.Ok, CurrencyAmountPolicy.check(big("1500000"), code("KRW")))
        val above = CurrencyAmountPolicy.check(big("1000000001"), code("KRW"))
        assertTrue(above is AmountCheck.AboveCap)
        assertEquals(0, big("1000000000").compareTo((above as AmountCheck.AboveCap).cap))
    }

    @Test
    fun `AE-18 EUR 1,000,000_01 is above the cap and USD 1,000,000_00 is allowed`() {
        val above = CurrencyAmountPolicy.check(big("1000000.01"), code("EUR"))
        assertTrue(above is AmountCheck.AboveCap)
        assertEquals(0, big("1000000").compareTo((above as AmountCheck.AboveCap).cap))
        assertEquals(AmountCheck.Ok, CurrencyAmountPolicy.check(big("1000000.00"), code("USD")))
    }

    @Test
    fun `the cap itself is allowed and one minor unit above is not for every catalog currency`() {
        for (currency in SupportedCurrency.catalog) {
            val currencyCode = code(currency.code)
            val cap = CurrencyAmountPolicy.cap(currencyCode)
            assertEquals("at cap ${currency.code}", AmountCheck.Ok, CurrencyAmountPolicy.check(cap, currencyCode))
            val digits = requireNotNull(CurrencyAmountPolicy.fractionDigits(currencyCode))
            val oneMinorUnit = BigDecimal.ONE.movePointLeft(digits)
            assertTrue(
                "above cap ${currency.code}",
                CurrencyAmountPolicy.check(cap.add(oneMinorUnit), currencyCode) is AmountCheck.AboveCap
            )
        }
    }

    @Test
    fun `a non-catalog code with known digits uses its own digits and the default cap`() {
        assertEquals(AmountCheck.Ok, CurrencyAmountPolicy.check(big("1.234"), code("BHD")))
        assertEquals(AmountCheck.PrecisionExceeded(3), CurrencyAmountPolicy.check(big("1.2345"), code("BHD")))
        assertTrue(CurrencyAmountPolicy.check(big("1000000.001"), code("BHD")) is AmountCheck.AboveCap)
    }

    // ---- check: not positive ----

    @Test
    fun `zero and negative amounts are not positive`() {
        assertEquals(AmountCheck.NotPositive, CurrencyAmountPolicy.check(BigDecimal.ZERO, code("EUR")))
        assertEquals(AmountCheck.NotPositive, CurrencyAmountPolicy.check(big("0.00"), code("EUR")))
        assertEquals(AmountCheck.NotPositive, CurrencyAmountPolicy.check(big("-1"), code("EUR")))
        assertEquals(AmountCheck.NotPositive, CurrencyAmountPolicy.check(-0.5, code("JPY")))
    }

    @Test
    fun `non-finite doubles are not positive`() {
        assertEquals(AmountCheck.NotPositive, CurrencyAmountPolicy.check(Double.NaN, code("EUR")))
        assertEquals(AmountCheck.NotPositive, CurrencyAmountPolicy.check(Double.POSITIVE_INFINITY, code("EUR")))
        assertEquals(AmountCheck.NotPositive, CurrencyAmountPolicy.check(Double.NEGATIVE_INFINITY, code("EUR")))
    }

    // ---- check: digits unavailable (OD-6, fail closed) ----

    @Test
    fun `OD-6 a code without known digits is DigitsUnavailable and never assumed to have 2 digits`() {
        assertEquals(AmountCheck.DigitsUnavailable, CurrencyAmountPolicy.check(big("10"), code("ZZZ")))
        assertEquals(AmountCheck.DigitsUnavailable, CurrencyAmountPolicy.check(big("10.5"), code("ZZZ")))
        assertEquals(AmountCheck.DigitsUnavailable, CurrencyAmountPolicy.check(big("10.555"), code("XXX")))
        assertEquals(AmountCheck.DigitsUnavailable, CurrencyAmountPolicy.check(10.0, code("ZZZ")))
    }

    @Test
    fun `a non-positive amount is reported as NotPositive even when digits are unavailable`() {
        assertEquals(AmountCheck.NotPositive, CurrencyAmountPolicy.check(BigDecimal.ZERO, code("ZZZ")))
    }
}
