package com.yourname.expensetracker.ui.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * R16: the sanitizer keeps both separators, caps the fraction by locale and validates through
 * AmountUtils.parseUserAmount so typing and saving cannot disagree.
 */
class AmountInputSanitizerTest {

    private lateinit var originalLocale: Locale

    @Before
    fun saveLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private fun useGreek() = Locale.setDefault(Locale.forLanguageTag("el-GR"))

    private fun useUs() = Locale.setDefault(Locale.US)

    @Test
    fun `Locale_US two decimal prefill round trips in el_GR en_US fr_FR and de_CH`() {
        val amounts = listOf(0.5, 12.5, 1234.56, 9999999.99)
        for (tag in listOf("el-GR", "en-US", "fr-FR", "de-CH")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            for (amount in amounts) {
                val prefill = AmountInputSanitizer.sanitize(String.format(Locale.US, "%.2f", amount))
                assertEquals("$tag $amount", amount, AmountInputSanitizer.parseOrNull(prefill)!!, 0.0)
            }
        }
    }

    @Test
    fun `formatForInput uses two decimals, locale mark, no grouping or exponent`() {
        useGreek()
        assertEquals("33,30", AmountInputSanitizer.formatForInput(33.300000000000004))
        assertEquals("10000000,00", AmountInputSanitizer.formatForInput(1.0E7))
        assertEquals("1234,50", AmountInputSanitizer.formatForInput(1234.5))
        assertEquals("0,00", AmountInputSanitizer.formatForInput(-0.001))
        assertEquals("", AmountInputSanitizer.formatForInput(Double.NaN))
        useUs()
        assertEquals("33.30", AmountInputSanitizer.formatForInput(33.300000000000004))
        assertEquals("10000000.00", AmountInputSanitizer.formatForInput(1.0E7))
    }

    @Test
    fun `formatForInput round trips and an extra digit is capped in el_GR en_US de_DE fr_FR`() {
        for (tag in listOf("el-GR", "en-US", "de-DE", "fr-FR")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            for (amount in listOf(0.5, 12.5, 150.0, 1234.56, 9999999.99)) {
                val text = AmountInputSanitizer.formatForInput(amount)
                assertEquals("$tag $amount", text, AmountInputSanitizer.sanitize(text))
                assertEquals("$tag $amount", amount, AmountInputSanitizer.parseOrNull(text)!!, 0.0)
            }
            val capped = AmountInputSanitizer.sanitize(AmountInputSanitizer.formatForInput(150.0) + "5")
            assertEquals(tag, 150.0, AmountInputSanitizer.parseOrNull(capped)!!, 0.0)
        }
    }

    // ---- el_GR (decimal ',', grouping '.') ----

    @Test
    fun `el_GR keeps comma decimal and caps fraction at two`() {
        useGreek()
        assertEquals("12,50", AmountInputSanitizer.sanitize("12,50"))
        assertEquals("12,50", AmountInputSanitizer.sanitize("12,505"))
        assertEquals("12,", AmountInputSanitizer.sanitize("12,"))
    }

    @Test
    fun `el_GR keeps dot grouping and caps digits after the dot at three`() {
        useGreek()
        assertEquals("1.234", AmountInputSanitizer.sanitize("1.234"))
        assertEquals("1.234", AmountInputSanitizer.sanitize("1.2345"))
        assertEquals("1.234,56", AmountInputSanitizer.sanitize("1.234,56"))
        assertEquals("1.234.567,89", AmountInputSanitizer.sanitize("1.234.567,89"))
    }

    @Test
    fun `el_GR never joins digit runs across a separator`() {
        useGreek()
        assertEquals("1,5", AmountInputSanitizer.sanitize("1,5"))
        assertEquals("1,5", AmountInputSanitizer.sanitize("1,,5"))
    }

    @Test
    fun `el_GR leading separator becomes zero prefix`() {
        useGreek()
        assertEquals("0,5", AmountInputSanitizer.sanitize(",5"))
        assertEquals("0.5", AmountInputSanitizer.sanitize(".5"))
        assertEquals("0,", AmountInputSanitizer.sanitize(","))
    }

    @Test
    fun `el_GR validity follows parseUserAmount`() {
        useGreek()
        assertTrue(AmountInputSanitizer.isValid("12,50"))
        assertTrue(AmountInputSanitizer.isValid("1.234"))
        assertTrue(AmountInputSanitizer.isValid("1.234,56"))
        assertTrue(AmountInputSanitizer.isValid("12.5"))
        assertFalse("ambiguous comma grouping is rejected", AmountInputSanitizer.isValid("1,234"))
        assertFalse(AmountInputSanitizer.isValid("1,23.4"))
    }

    // ---- en_US (decimal '.', grouping ',') ----

    @Test
    fun `en_US keeps dot decimal and caps fraction at two`() {
        useUs()
        assertEquals("12.50", AmountInputSanitizer.sanitize("12.505"))
        assertEquals("1.23", AmountInputSanitizer.sanitize("1.234"))
        assertEquals("1,234.56", AmountInputSanitizer.sanitize("1,234.56"))
    }

    @Test
    fun `en_US keeps comma grouping and caps digits after the comma at three`() {
        useUs()
        assertEquals("1,234", AmountInputSanitizer.sanitize("1,234"))
        assertEquals("12,500", AmountInputSanitizer.sanitize("12,5000"))
        assertEquals("0,5", AmountInputSanitizer.sanitize(",5"))
    }

    @Test
    fun `en_US validity follows parseUserAmount`() {
        useUs()
        assertTrue(AmountInputSanitizer.isValid("1,234.56"))
        assertTrue(AmountInputSanitizer.isValid("1,234"))
        assertTrue(AmountInputSanitizer.isValid("12,50"))
        assertFalse("ambiguous dot grouping is rejected", AmountInputSanitizer.isValid("1.234"))
    }

    @Test
    fun `separators are read from the default locale at call time`() {
        useUs()
        assertEquals("1.23", AmountInputSanitizer.sanitize("1.234"))
        useGreek()
        assertEquals("1.234", AmountInputSanitizer.sanitize("1.234"))
        useUs()
        assertEquals("1.23", AmountInputSanitizer.sanitize("1.234"))
    }

    // ---- locale independent ----

    @Test
    fun `leading zeros are trimmed and zero is kept`() {
        useGreek()
        assertEquals("7,5", AmountInputSanitizer.sanitize("007,5"))
        assertEquals("0", AmountInputSanitizer.sanitize("000"))
        assertEquals("0,5", AmountInputSanitizer.sanitize("000,5"))
    }

    @Test
    fun `blank and non numeric input sanitize to empty`() {
        useUs()
        assertEquals("", AmountInputSanitizer.sanitize(""))
        assertEquals("", AmountInputSanitizer.sanitize("   "))
        assertEquals("", AmountInputSanitizer.sanitize("abc"))
        assertEquals("12", AmountInputSanitizer.sanitize("1a2"))
    }

    @Test
    fun `integer digits are capped at ten`() {
        useUs()
        assertEquals("1234567890", AmountInputSanitizer.sanitize("12345678901"))
        assertEquals("1234567890.12", AmountInputSanitizer.sanitize("12345678901.12"))
        assertEquals("1234567890", AmountInputSanitizer.sanitize("1234567890,123,456"))
    }

    @Test
    fun `maxFractionDigits applies after the locale decimal separator`() {
        useUs()
        assertEquals("1.234", AmountInputSanitizer.sanitize("1.2345", maxFractionDigits = 3))
        assertEquals("1.", AmountInputSanitizer.sanitize("1.5", maxFractionDigits = 0))
    }

    @Test
    fun `zero and blank amounts are not valid`() {
        useUs()
        assertFalse(AmountInputSanitizer.isValid(""))
        assertFalse(AmountInputSanitizer.isValid("  "))
        assertFalse(AmountInputSanitizer.isValid("0"))
        assertFalse(AmountInputSanitizer.isValid("0.00"))
        assertFalse(AmountInputSanitizer.isValid("abc"))
    }

    @Test
    fun `isValid honours a custom maxFractionDigits`() {
        useUs()
        assertTrue(AmountInputSanitizer.isValid("1.5", maxFractionDigits = 2))
        assertFalse(AmountInputSanitizer.isValid("1.5", maxFractionDigits = 0))
        assertTrue(AmountInputSanitizer.isValid("1.5", maxFractionDigits = 1))
        assertEquals(1.5, AmountInputSanitizer.parseOrNull("1.5")!!, 0.0)
        assertEquals(null, AmountInputSanitizer.parseOrNull("1.234"))
    }

    @Test
    fun `sanitized output of every worked input validates the same way as the raw input`() {
        useGreek()
        for (typed in listOf("12,50", "1.234,56", "1.234", "12.5", "1.234.567,89")) {
            assertTrue(typed, AmountInputSanitizer.isValid(AmountInputSanitizer.sanitize(typed)))
        }
        useUs()
        for (typed in listOf("1,234.56", "1,234", "12,50", "1.23")) {
            assertTrue(typed, AmountInputSanitizer.isValid(AmountInputSanitizer.sanitize(typed)))
        }
    }
}
