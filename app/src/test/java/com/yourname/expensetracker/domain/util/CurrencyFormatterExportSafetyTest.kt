package com.yourname.expensetracker.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Safety tests for [CurrencyFormatter] export and formatting edge cases.
 *
 * Ensures that non-finite amounts (NaN, Infinity) are rejected with
 * IllegalArgumentException rather than silently coerced to 0.00,
 * and that invalid currency codes are handled safely (throw or return raw code)
 * instead of silently falling back to EUR.
 */
class CurrencyFormatterExportSafetyTest {

    @Test
    fun `formatForExport_nanThrows`() {
        var caught = false
        try {
            CurrencyFormatter.formatForExport(Double.NaN)
        } catch (e: IllegalArgumentException) {
            caught = true
            assertThat(e).hasMessageThat().contains("non-finite")
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `formatForExport_infinityThrows`() {
        var caught = false
        try {
            CurrencyFormatter.formatForExport(Double.POSITIVE_INFINITY)
        } catch (e: IllegalArgumentException) {
            caught = true
            assertThat(e).hasMessageThat().contains("non-finite")
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `formatForExport_finiteWorks`() {
        val result = CurrencyFormatter.formatForExport(1234.56)
        assertThat(result).isEqualTo("1234.56")
    }

    @Test
    fun `formatMoney_validEURStillWorks`() {
        val result = CurrencyFormatter.formatMoney(100.0, "EUR")
        // Locale-sensitive format — expect Euro symbol and 2 decimal places
        assertThat(result).contains("100")
        assertThat(result).contains("€")
    }

    @Test
    fun `formatMoney_validUSDStillWorks`() {
        val result = CurrencyFormatter.formatMoney(100.0, "USD")
        // Locale-sensitive format — expect Dollar symbol and 2 decimal places
        assertThat(result).contains("100")
        assertThat(result).contains("$")
    }

    @Test
    fun `getCurrencySymbol_invalidReturnsRawCode`() {
        val result = CurrencyFormatter.getCurrencySymbol("XYZ")
        // Should return the raw code instead of a fallback symbol like €
        assertThat(result).isEqualTo("XYZ")
    }

    @Test
    fun `getCurrencySymbol_validEURStillWorks`() {
        val result = CurrencyFormatter.getCurrencySymbol("EUR")
        assertThat(result).isEqualTo("€")
    }

    @Test
    fun `formatMoney_nonFiniteThrows`() {
        var caught = false
        try {
            CurrencyFormatter.formatMoney(Double.NaN, "EUR")
        } catch (e: IllegalArgumentException) {
            caught = true
            assertThat(e).hasMessageThat().contains("non-finite")
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `formatForExport_negativeInfinityThrows`() {
        var caught = false
        try {
            CurrencyFormatter.formatForExport(Double.NEGATIVE_INFINITY)
        } catch (e: IllegalArgumentException) {
            caught = true
            assertThat(e).hasMessageThat().contains("non-finite")
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `formatForExport_negativeFiniteWorks`() {
        val result = CurrencyFormatter.formatForExport(-42.50)
        assertThat(result).isEqualTo("-42.50")
    }

    @Test
    fun `formatCompact_nanThrows`() {
        var caught = false
        try {
            CurrencyFormatter.formatCompact(Double.NaN, "EUR")
        } catch (e: IllegalArgumentException) {
            caught = true
            assertThat(e).hasMessageThat().contains("non-finite")
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `formatCompact_negativeInfinityThrows`() {
        var caught = false
        try {
            CurrencyFormatter.formatCompact(Double.NEGATIVE_INFINITY, "EUR")
        } catch (e: IllegalArgumentException) {
            caught = true
            assertThat(e).hasMessageThat().contains("non-finite")
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `formatWithSign_positiveInfinityThrows`() {
        var caught = false
        try {
            CurrencyFormatter.formatWithSign(Double.POSITIVE_INFINITY, "EUR")
        } catch (e: IllegalArgumentException) {
            caught = true
            assertThat(e).hasMessageThat().contains("non-finite")
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `formatWithSign_negativeInfinityThrows`() {
        var caught = false
        try {
            CurrencyFormatter.formatWithSign(Double.NEGATIVE_INFINITY, "EUR")
        } catch (e: IllegalArgumentException) {
            caught = true
            assertThat(e).hasMessageThat().contains("non-finite")
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `formatMoney_invalidCurrencyShowsRawCodeNotEuro`() {
        val result = CurrencyFormatter.formatMoney(100.0, "INVALID")
        // Should show raw amount + raw code, not € symbol
        assertThat(result).contains("100")
        assertThat(result).contains("INVALID")
        assertThat(result).doesNotContain("€")
    }

    @Test
    fun `getCurrencySymbol_emptyStringReturnsEmpty`() {
        val result = CurrencyFormatter.getCurrencySymbol("")
        assertThat(result).isEqualTo("")
    }

    @Test
    fun `formatMoney_positiveInfinityThrows`() {
        var caught = false
        try {
            CurrencyFormatter.formatMoney(Double.POSITIVE_INFINITY, "EUR")
        } catch (e: IllegalArgumentException) {
            caught = true
            assertThat(e).hasMessageThat().contains("non-finite")
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `formatMoneyCompact_nanThrows`() {
        var caught = false
        try {
            CurrencyFormatter.formatMoneyCompact(Double.NaN, "EUR")
        } catch (e: IllegalArgumentException) {
            caught = true
            assertThat(e).hasMessageThat().contains("non-finite")
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `formatMoneyWithSign_negativeInfinityThrows`() {
        var caught = false
        try {
            CurrencyFormatter.formatMoneyWithSign(Double.NEGATIVE_INFINITY, "USD")
        } catch (e: IllegalArgumentException) {
            caught = true
            assertThat(e).hasMessageThat().contains("non-finite")
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `formatForExport_zeroWorks`() {
        val result = CurrencyFormatter.formatForExport(0.0)
        assertThat(result).isEqualTo("0.00")
    }
}
