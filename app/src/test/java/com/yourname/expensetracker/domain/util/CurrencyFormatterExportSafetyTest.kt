package com.yourname.expensetracker.domain.util

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Safety tests for [CurrencyFormatter] export and formatting edge cases.
 *
 * Ensures that non-finite amounts (NaN, Infinity) are rejected with
 * IllegalArgumentException rather than silently coerced to 0.00,
 * and that invalid currency codes are handled safely (throw or return raw code)
 * instead of silently falling back to EUR.
 *
 * Consolidated in B6-6 into tables; the default Locale is pinned to US (and restored)
 * because the display formatters are locale-sensitive.
 */
@Suppress("DEPRECATION")
class CurrencyFormatterExportSafetyTest {

    private lateinit var originalLocale: Locale

    private lateinit var originalFormatLocale: Locale
    private lateinit var originalDisplayLocale: Locale

    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT)
        originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY)
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
        Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale)
        Locale.setDefault(Locale.Category.DISPLAY, originalDisplayLocale)
    }

    @Test
    fun `non-finite amounts are rejected by every formatter for NaN and both infinities`() {
        val formatters: List<Pair<String, (Double) -> String>> = listOf(
            Pair("formatForExport") { amount: Double -> CurrencyFormatter.formatForExport(amount) },
            Pair("formatMoney") { amount: Double -> CurrencyFormatter.formatMoney(amount, "EUR") },
            Pair("formatCompact") { amount: Double -> CurrencyFormatter.formatCompact(amount, "EUR") },
            Pair("formatWithSign") { amount: Double -> CurrencyFormatter.formatWithSign(amount, "EUR") },
            Pair("formatMoneyCompact") { amount: Double -> CurrencyFormatter.formatMoneyCompact(amount, "EUR") },
            Pair("formatMoneyWithSign") { amount: Double -> CurrencyFormatter.formatMoneyWithSign(amount, "USD") }
        )
        val nonFinite = listOf(
            "NaN" to Double.NaN,
            "POSITIVE_INFINITY" to Double.POSITIVE_INFINITY,
            "NEGATIVE_INFINITY" to Double.NEGATIVE_INFINITY
        )
        assertThat(formatters).hasSize(6)
        assertThat(nonFinite).hasSize(3)

        var rows = 0
        for ((functionName, format) in formatters) {
            for ((valueName, value) in nonFinite) {
                val row = "$functionName($valueName)"
                try {
                    val produced = format(value)
                    fail("$row: expected IllegalArgumentException but produced \"$produced\"")
                } catch (e: IllegalArgumentException) {
                    assertWithMessage(row).that(e).hasMessageThat().contains("non-finite")
                }
                rows++
            }
        }
        assertThat(rows).isEqualTo(18)
    }

    @Test
    fun `formatForExport formats finite amounts with period decimal and two digits`() {
        val rows = listOf(
            "positive" to (1234.56 to "1234.56"),
            "negative" to (-42.50 to "-42.50"),
            "zero" to (0.0 to "0.00")
        )
        assertThat(rows).hasSize(3)

        rows.forEach { (name, case) ->
            assertWithMessage(name).that(CurrencyFormatter.formatForExport(case.first)).isEqualTo(case.second)
        }
    }

    @Test
    fun `formatMoney shows symbol for valid currencies and raw code instead of euro for invalid`() {
        val rows = listOf(
            Triple("valid EUR", "EUR", "€100.00"),
            Triple("valid USD", "USD", "$100.00"),
            // Invalid code: raw amount + raw code, never the EUR symbol.
            Triple("invalid code", "INVALID", "100.00 INVALID")
        )
        assertThat(rows).hasSize(3)

        rows.forEach { (name, code, expected) ->
            assertWithMessage(name).that(CurrencyFormatter.formatMoney(100.0, code)).isEqualTo(expected)
        }
    }

    @Test
    fun `getCurrencySymbol returns symbol for valid code and raw code for invalid`() {
        val rows = listOf(
            Triple("valid EUR", "EUR", "€"),
            Triple("invalid XYZ returns raw code not euro", "XYZ", "XYZ"),
            Triple("empty returns empty", "", "")
        )
        assertThat(rows).hasSize(3)

        rows.forEach { (name, code, expected) ->
            assertWithMessage(name).that(CurrencyFormatter.getCurrencySymbol(code)).isEqualTo(expected)
        }
    }
}
