package com.yourname.expensetracker.ui.screens.cashflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** R16c: starting balance field text handling (comma, clearing, negative, echo of own commit). */
class CashFlowStartingBalanceInputTest {

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
    fun `el_GR comma decimal is sanitized and committed`() {
        withLocale(Locale.forLanguageTag("el-GR")) {
            val text = sanitizeStartingBalanceInput("12,50")
            assertEquals("12,50", text)
            assertEquals(12.5, parseStartingBalanceOrNull(text)!!, 0.0)
        }
    }

    @Test
    fun `en_US dot decimal is sanitized and committed`() {
        withLocale(Locale.US) {
            val text = sanitizeStartingBalanceInput("1,234.56")
            assertEquals("1,234.56", text)
            assertEquals(1234.56, parseStartingBalanceOrNull(text)!!, 0.0)
            assertEquals(12.5, parseStartingBalanceOrNull(sanitizeStartingBalanceInput("12,5"))!!, 0.0)
        }
    }

    @Test
    fun `blank and incomplete text commit nothing`() {
        for (tag in listOf("el-GR", "en-US")) {
            withLocale(Locale.forLanguageTag(tag)) {
                assertEquals("", sanitizeStartingBalanceInput(""))
                assertNull(parseStartingBalanceOrNull(""))
                assertEquals("-", sanitizeStartingBalanceInput("-"))
                assertNull(parseStartingBalanceOrNull("-"))
            }
        }
    }

    @Test
    fun `negative balance keeps its sign and negative zero is normalized`() {
        withLocale(Locale.forLanguageTag("el-GR")) {
            val text = sanitizeStartingBalanceInput("-150,25")
            assertEquals("-150,25", text)
            assertEquals(-150.25, parseStartingBalanceOrNull(text)!!, 0.0)
            assertEquals(0.0, parseStartingBalanceOrNull("-0")!!, 0.0)
            assertEquals("0.0", parseStartingBalanceOrNull("-0").toString())
        }
    }

    @Test
    fun `ambiguous grouping text commits nothing`() {
        withLocale(Locale.forLanguageTag("el-GR")) {
            assertNull(parseStartingBalanceOrNull("1,234"))
        }
        withLocale(Locale.US) {
            assertNull(parseStartingBalanceOrNull("1.234"))
        }
    }

    @Test
    fun `external change echoing the typed value does not rewrite the text`() {
        withLocale(Locale.forLanguageTag("el-GR")) {
            assertEquals("12,", startingBalanceTextAfterExternalChange("12,", 12.0))
            assertEquals("12,50", startingBalanceTextAfterExternalChange("12,50", 12.5))
        }
    }

    @Test
    fun `external change to a different value resets the text with the locale decimal mark`() {
        withLocale(Locale.forLanguageTag("el-GR")) {
            assertEquals("99,50", startingBalanceTextAfterExternalChange("12,50", 99.5))
            assertEquals("0,00", formatStartingBalanceText(0.0))
            assertEquals("-3,00", startingBalanceTextAfterExternalChange("", -3.0))
        }
        withLocale(Locale.US) {
            assertEquals("99.50", startingBalanceTextAfterExternalChange("12.50", 99.5))
            assertEquals("-3.00", startingBalanceTextAfterExternalChange("", -3.0))
        }
    }

    @Test
    fun `locale prefill round trips through sanitize and parser in el_GR en_US de_DE fr_FR`() {
        for (tag in listOf("el-GR", "en-US", "de-DE", "fr-FR")) {
            withLocale(Locale.forLanguageTag(tag)) {
                for (v in listOf(0.0, 12.5, -1234.56, 9999999.99, 1.0E7)) {
                    val text = formatStartingBalanceText(v)
                    assertEquals("$tag $v", text, sanitizeStartingBalanceInput(text))
                    assertEquals("$tag $v", v, parseStartingBalanceOrNull(text)!!, 0.0)
                }
            }
        }
    }

    @Test
    fun `extra typed digit after the prefill is capped and never read as thousands`() {
        for (tag in listOf("el-GR", "en-US", "de-DE", "fr-FR")) {
            withLocale(Locale.forLanguageTag(tag)) {
                val typed = sanitizeStartingBalanceInput(formatStartingBalanceText(150.0) + "5")
                assertEquals(tag, 150.0, parseStartingBalanceOrNull(typed)!!, 0.0)
            }
        }
    }
}
