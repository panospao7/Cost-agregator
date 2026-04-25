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

        assertEquals("1,234.56", parsed)
    }

    @Test
    fun `parseAmountFromClipboard does not partial-tail match grouped amount`() {
        val clipboard = clipboardWithText("Total: 11,234.56")

        val parsed = ClipboardAmountParser.parseAmountFromClipboard(clipboard)

        assertEquals("11,234.56", parsed)
    }

    @Test
    fun `parseAmountFromClipboard returns null for malformed grouped amount`() {
        val clipboard = clipboardWithText("Total 1,0000")

        val parsed = ClipboardAmountParser.parseAmountFromClipboard(clipboard)

        assertNull(parsed)
    }

    private fun clipboardWithText(text: String): ClipboardManager {
        val clipData = ClipData.newPlainText("label", text)
        return mockk {
            every { hasPrimaryClip() } returns true
            every { primaryClip } returns clipData
        }
    }
}
