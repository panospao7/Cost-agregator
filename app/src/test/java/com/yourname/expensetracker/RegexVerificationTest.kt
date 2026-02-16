package com.yourname.expensetracker

import org.junit.Test
import org.junit.Assert.*

class RegexVerificationTest {
    @Test
    fun testCurrencyRegex() {
        val regex = Regex("""(?:€|$|EUR)?\s*(\d{1,6}[\.,]\d{2})\s*(?:€|$|EUR)?""")

        // Should match
        assertTrue(regex.containsMatchIn("€20.50"))
        assertTrue(regex.containsMatchIn("20.50€"))
        assertTrue(regex.containsMatchIn("20,50"))
        assertTrue(regex.containsMatchIn("EUR 20.50"))
        
        // Should NOT match
        assertFalse(regex.containsMatchIn("2024")) // Year
        assertFalse(regex.containsMatchIn("Version 2.0")) // Version
        
        // Extraction logic verification
        val match = regex.find("Total: 20.50€")
        assertNotNull(match)
        assertEquals("20.50", match!!.groupValues[1])
    }
}
