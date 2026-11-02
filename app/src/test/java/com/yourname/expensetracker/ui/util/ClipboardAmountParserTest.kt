package com.yourname.expensetracker.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardAmountParserTest {

    @Test
    fun `parseAmountFromClipboard captures grouped amount as whole token`() {
        val clipboard = clipboardWithText("Paid 1,234.56 yesterday")

        val parsed = ClipboardAmountParser.parseAmountFromClipboard(clipboard)

        assertEquals("1234.56", parsed)
    }

    @Test
    fun `parseAmountFromClipboard does not partial-tail match grouped amount and returns canonical text`() {
        val clipboard = clipboardWithText("Total: 11,234.56")

        val parsed = ClipboardAmountParser.parseAmountFromClipboard(clipboard)

        assertEquals("11234.56", parsed)
    }

    @Test
    fun `parseAmountFromClipboard canonicalises comma decimal 12,50 to 12_50 never 1250`() {
        assertEquals("12.50", parse("12,50"))
    }

    @Test
    fun `parseAmountFromClipboard keeps canonical 12_50`() {
        assertEquals("12.50", parse("12.50"))
    }

    @Test
    fun `parseAmountFromClipboard canonicalises European grouping 1_234,56`() {
        assertEquals("1234.56", parse("1.234,56"))
    }

    @Test
    fun `parseAmountFromClipboard ignores currency symbol and canonicalises`() {
        assertEquals("12.50", parse("€12,50"))
    }

    @Test
    fun `parseAmountFromClipboard returns null for ambiguous 1_500`() {
        assertNull(parse("1.500"))
    }

    @Test
    fun `parseAmountFromClipboard returns null for ambiguous 3,500`() {
        assertNull(parse("3,500"))
    }

    @Test
    fun `parseAmountFromClipboard returns null for ambiguous 1,234`() {
        assertNull(parse("1,234"))
    }

    @Test
    fun `parseAmountFromClipboard returns null for more than two decimals`() {
        assertNull(parse("12.505"))
    }

    @Test
    fun `parseAmountFromClipboard returns null above the 100000 range`() {
        assertNull(parse("150000.00"))
        assertNull(parse("100000.01"))
    }

    @Test
    fun `parseAmountFromClipboard accepts the 100000 upper bound`() {
        assertEquals("100000.00", parse("100000.00"))
    }

    @Test
    fun `parseAmountFromClipboard accepts the 0_01 lower bound`() {
        assertEquals("0.01", parse("0.01"))
    }

    @Test
    fun `parseAmountFromClipboard returns null for zero 0_00`() {
        assertNull(parse("0.00"))
    }

    @Test
    fun `parseAmountFromClipboard returns null for leading-zero 007`() {
        assertNull(parse("007"))
    }

    @Test
    fun `parseAmountFromClipboard returns null for malformed grouped amount`() {
        val clipboard = clipboardWithText("Total 1,0000")

        val parsed = ClipboardAmountParser.parseAmountFromClipboard(clipboard)

        assertNull(parsed)
    }

    @Test
    fun `parseAmountFromClipboard returns null for bank SMS with balance before purchase`() {
        assertNull(parse("Eurobank: Available balance EUR 1,245.67. Purchase of EUR 12.50 at COFFEE SHOP"))
        assertNull(parse("NBG: Διαθέσιμο υπόλοιπο 1.245,67 EUR. Αγορά 15,20 EUR στο SKLAVENITIS"))
    }

    @Test
    fun `parseAmountFromClipboard returns null for multi-amount text`() {
        assertNull(parse("Purchase EUR 12.50 at COFFEE, remaining limit EUR 500.00"))
        assertNull(parse("Subtotal 10.00 Total 12.50"))
    }

    @Test
    fun `parseAmountFromClipboard returns null for balance-only text`() {
        assertNull(parse("Available balance: €1,245.67"))
        assertNull(parse("Διαθέσιμο υπόλοιπο: 850,00 EUR"))
    }

    @Test
    fun `parseAmountFromClipboard still parses clean single-amount strings`() {
        assertEquals("12.50", parse("Purchase of EUR 12.50 at COFFEE"))
        assertEquals("42.80", parse("42.80"))
    }

    private fun parse(text: String): String? =
        ClipboardAmountParser.parseAmountFromClipboard(clipboardWithText(text))

    private fun clipboardWithText(text: String): ClipboardManager {
        val item = mockk<ClipData.Item>()
        every { item.text } returns text
        val clipData = mockk<ClipData>()
        every { clipData.getItemAt(0) } returns item
        return mockk {
            every { hasPrimaryClip() } returns true
            every { primaryClip } returns clipData
        }
    }
}
