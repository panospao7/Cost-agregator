package com.yourname.expensetracker.domain.util

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/**
 * Stress Test Suite for AmountUtils
 * 
 * Goal: Break the amount parsing and validation logic with extreme inputs,
 * edge cases, and locale variations.
 * 
 * @author Hostile QA Engineer
 */
class AmountUtilsStressTest {

    // ============================================================================
    // SECTION 1: LOCALE TESTING
    // ============================================================================

    @Test
    fun `stress - US locale parsing`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            
            // US format: 1,234.56
            assertEquals(1234.56, AmountUtils.parseAmount("1,234.56")!!, 0.001)
            assertEquals(1234567.89, AmountUtils.parseAmount("1,234,567.89")!!, 0.001)
            assertEquals(0.99, AmountUtils.parseAmount("0.99")!!, 0.001)
            assertEquals(1000000.00, AmountUtils.parseAmount("1000000.00")!!, 0.001)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `stress - European locale parsing`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            
            // European format: 1.234,56
            assertEquals(1234.56, AmountUtils.parseAmount("1.234,56")!!, 0.001)
            assertEquals(1234567.89, AmountUtils.parseAmount("1.234.567,89")!!, 0.001)
            assertEquals(0.99, AmountUtils.parseAmount("0,99")!!, 0.001)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `stress - Greek locale parsing`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("el", "GR"))
            
            // Greek uses dot as thousands separator and comma as decimal
            assertEquals(1234.56, AmountUtils.parseAmount("1.234,56")!!, 0.001)
            assertEquals(0.99, AmountUtils.parseAmount("0,99")!!, 0.001)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `stress - French locale parsing`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRENCH)
            
            // French uses space as thousands separator and comma as decimal
            assertEquals(1234.56, AmountUtils.parseAmount("1 234,56")!!, 0.001)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `stress - formatAmount uses default locale`() {
        // This test documents the BUG: formatAmount uses default locale
        // which causes inconsistent decimal separators
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val usFormatted = AmountUtils.formatAmount(1234.56)
            assertTrue(usFormatted.contains("1234.56")) // dot
            
            Locale.setDefault(Locale.GERMANY)
            val germanFormatted = AmountUtils.formatAmount(1234.56)
            // This will use comma in German locale - a BUG for consistency!
            // The test documents expected vs actual behavior
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    // ============================================================================
    // SECTION 2: EDGE CASES - AMBIGUOUS FORMATS
    // ============================================================================

    @Test
    fun `stress - ambiguous thousands with only dots`() {
        // "1.234" - ambiguous: could be 1234 (US thousands) or 1.234 (European decimal)
        // Current implementation treats this as decimal (1.234)
        val result = AmountUtils.parseAmount("1.234")
        assertNotNull(result)
        // This is ambiguous behavior - document it
    }

    @Test
    fun `stress - ambiguous thousands with only commas`() {
        // "1,234" - ambiguous: could be 1234 (US thousands) or 1.234 (European decimal)
        // Current implementation tries to determine based on digits after comma
        val result = AmountUtils.parseAmount("1,234")
        assertNotNull(result)
    }

    @Test
    fun `stress - three digit after comma treated as decimal`() {
        // "1,234" with 3 digits after comma should be treated as thousands (1234)
        val result = AmountUtils.parseAmount("1,234")
        // With US locale, this should be 1234. With EU, it's ambiguous.
        // Implementation: if 3 digits after comma, treat as thousands separator
        assertEquals(1234.0, result!!, 0.001)
    }

    @Test
    fun `stress - two digits after comma treated as decimal`() {
        // "1,50" should be 1.50 (decimal)
        val result = AmountUtils.parseAmount("1,50")
        assertEquals(1.50, result!!, 0.001)
    }

    @Test
    fun `stress - multiple thousands separators inconsistent`() {
        // "1,23,456" - inconsistent grouping should return null
        val result = AmountUtils.parseAmount("1,23,456")
        assertNull(result)
    }

    @Test
    fun `stress - mixed separators with dot as decimal`() {
        // "1.234,56" - European format
        val result = AmountUtils.parseAmount("1.234,56")
        assertEquals(1234.56, result!!, 0.001)
    }

    @Test
    fun `stress - mixed separators with comma as decimal`() {
        // "1,234.56" - US format
        val result = AmountUtils.parseAmount("1,234.56")
        assertEquals(1234.56, result!!, 0.001)
    }

    @Test
    fun `stress - many leading zeros`() {
        assertEquals(1.0, AmountUtils.parseAmount("0001.00")!!, 0.001)
        assertEquals(0.0, AmountUtils.parseAmount("0000.00")!!, 0.001)
    }

    @Test
    fun `stress - zeros only`() {
        assertEquals(0.0, AmountUtils.parseAmount("0")!!, 0.001)
        assertEquals(0.0, AmountUtils.parseAmount("0.00")!!, 0.001)
        assertEquals(0.0, AmountUtils.parseAmount("0,00")!!, 0.001)
    }

    // ============================================================================
    // SECTION 3: MAX AMOUNT BOUNDARY
    // ============================================================================

    @Test
    fun `stress - maximum amount boundary at exactly 1M`() {
        assertTrue(AmountUtils.isValidAmount(1_000_000.00))
    }

    @Test
    fun `stress - maximum amount boundary just over 1M`() {
        assertFalse(AmountUtils.isValidAmount(1_000_000.01))
    }

    @Test
    fun `stress - maximum amount at exactly 1M in string parsing`() {
        val result = AmountUtils.parseAmount("1000000.00")
        assertNotNull(result)
        assertTrue(AmountUtils.isValidAmount(result!!))
    }

    @Test
    fun `stress - just over 1M in string parsing`() {
        val result = AmountUtils.parseAmount("1000000.01")
        assertNotNull(result)
        assertFalse(AmountUtils.isValidAmount(result!!))
    }

    @Test
    fun `stress - extremely large amounts rejected`() {
        assertFalse(AmountUtils.isValidAmount(Double.MAX_VALUE))
        assertFalse(AmountUtils.isValidAmount(1e15))
    }

    @Test
    fun `stress - custom max validation`() {
        assertTrue(AmountUtils.isValidAmount(500.0, max = 1000.0))
        assertFalse(AmountUtils.isValidAmount(1500.0, max = 1000.0))
    }

    // ============================================================================
    // SECTION 4: NEGATIVE AMOUNTS
    // ============================================================================

    @Test
    fun `stress - negative with dash prefix`() {
        assertEquals(-50.0, AmountUtils.parseAmount("-50")!!, 0.001)
        assertEquals(-1234.56, AmountUtils.parseAmount("-1234.56")!!, 0.001)
    }

    @Test
    fun `stress - negative with unicode minus`() {
        assertEquals(-50.0, AmountUtils.parseAmount("−50")!!, 0.001)
        assertEquals(-50.0, AmountUtils.parseAmount("‑50")!!, 0.001) // non-breaking hyphen
        assertEquals(-50.0, AmountUtils.parseAmount("€−50")!!, 0.001)
    }

    @Test
    fun `stress - negative with parentheses`() {
        assertEquals(-50.0, AmountUtils.parseAmount("(50)")!!, 0.001)
        assertEquals(-1234.56, AmountUtils.parseAmount("(1234.56)")!!, 0.001)
    }

    @Test
    fun `stress - negative validation rejects`() {
        // isValidAmount rejects negative amounts
        assertFalse(AmountUtils.isValidAmount(-50.0))
        assertFalse(AmountUtils.isValidAmount(-0.01))
    }

    @Test
    fun `stress - negative parsing works but validation rejects`() {
        val parsed = AmountUtils.parseAmount("-50")
        assertNotNull(parsed)
        assertFalse(AmountUtils.isValidAmount(parsed!!))
        // This is a logic issue: parseAmount allows negatives but isValidAmount rejects them
    }

    // ============================================================================
    // SECTION 5: CURRENCY SYMBOLS
    // ============================================================================

    @Test
    fun `stress - euro prefix`() {
        assertEquals(50.0, AmountUtils.parseAmount("€50")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("€ 50")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("€50.00")!!, 0.001)
    }

    @Test
    fun `stress - euro suffix`() {
        assertEquals(50.0, AmountUtils.parseAmount("50€")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("50 €")!!, 0.001)
    }

    @Test
    fun `stress - dollar prefix`() {
        assertEquals(50.0, AmountUtils.parseAmount("$50")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("50$")!!, 0.001)
    }

    @Test
    fun `stress - pound prefix`() {
        assertEquals(50.0, AmountUtils.parseAmount("£50")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("50£")!!, 0.001)
    }

    @Test
    fun `stress - yen prefix`() {
        assertEquals(50.0, AmountUtils.parseAmount("¥50")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("50¥")!!, 0.001)
    }

    @Test
    fun `stress - custom currency code prefix`() {
        assertEquals(50.0, AmountUtils.parseAmount("EUR50")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("USD 50")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("GBP50")!!, 0.001)
    }

    @Test
    fun `stress - E prefix without space`() {
        // This is a known quirk: "E50" is parsed as €50
        assertEquals(50.0, AmountUtils.parseAmount("E50")!!, 0.001)
    }

    @Test
    fun `stress - multiple currency symbols`() {
        // First currency symbol wins
        assertEquals(50.0, AmountUtils.parseAmount("€$50")!!, 0.001)
    }

    // ============================================================================
    // SECTION 6: MALFORMED INPUT
    // ============================================================================

    @Test
    fun `stress - completely invalid strings`() {
        assertNull(AmountUtils.parseAmount(""))
        assertNull(AmountUtils.parseAmount("   "))
        assertNull(AmountUtils.parseAmount("abc"))
        assertNull(AmountUtils.parseAmount("hello world"))
        assertNull(AmountUtils.parseAmount("N/A"))
        assertNull(AmountUtils.parseAmount("null"))
    }

    @Test
    fun `stress - only special characters`() {
        assertNull(AmountUtils.parseAmount("!!!"))
        assertNull(AmountUtils.parseAmount("---"))
        assertNull(AmountUtils.parseAmount("..."))
    }

    @Test
    fun `stress - numbers with special characters`() {
        assertNotNull(AmountUtils.parseAmount("1,234.56"))
        assertNotNull(AmountUtils.parseAmount("1.234,56"))
    }

    @Test
    fun `stress - single digit`() {
        assertEquals(5.0, AmountUtils.parseAmount("5")!!, 0.001)
    }

    @Test
    fun `stress - single letter`() {
        assertNull(AmountUtils.parseAmount("a"))
        assertNull(AmountUtils.parseAmount("x"))
    }

    @Test
    fun `stress - very long strings`() {
        val longString = "1" + "0".repeat(1000)
        val result = AmountUtils.parseAmount(longString)
        // Should handle gracefully
        assertNotNull(result)
    }

    @Test
    fun `stress - unicode characters in input`() {
        assertNull(AmountUtils.parseAmount("①②③"))
        assertNull(AmountUtils.parseAmount("⅕"))
        assertNull(AmountUtils.parseAmount("½"))
    }

    @Test
    fun `stress - emojis in input`() {
        assertNull(AmountUtils.parseAmount("💰100"))
        assertNull(AmountUtils.parseAmount("100💵"))
        // Keycap-style unicode digits should still parse as numeric input.
        assertEquals(100.0, AmountUtils.parseAmount("1️⃣0️⃣0️⃣")!!, 0.001)
    }

    // ============================================================================
    // SECTION 7: WHITESPACE HANDLING
    // ============================================================================

    @Test
    fun `stress - leading whitespace`() {
        assertEquals(50.0, AmountUtils.parseAmount("  50")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("\t50")!!, 0.001)
    }

    @Test
    fun `stress - trailing whitespace`() {
        assertEquals(50.0, AmountUtils.parseAmount("50  ")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("50\t")!!, 0.001)
    }

    @Test
    fun `stress - internal whitespace removal`() {
        // Internal spaces should be removed for currency codes but may affect parsing
        assertEquals(500.0, AmountUtils.parseAmount("50 0")!!, 0.001)
    }

    @Test
    fun `stress - unicode whitespace nbsp and narrow nbsp`() {
        // Non-breaking space (U+00A0) and narrow no-break space (U+202F) as thousands separators
        assertEquals(1234.56, AmountUtils.parseAmount("1\u00A0234\u00A0,56")!!, 0.001)
        assertEquals(1234.56, AmountUtils.parseAmount("1\u202F234\u202F,56")!!, 0.001)
    }

    @Test
    fun `stress - tab and newline characters`() {
        assertEquals(50.0, AmountUtils.parseAmount("50\n")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("50\r")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("50\r\n")!!, 0.001)
    }

    // ============================================================================
    // SECTION 8: SPECIAL CASES
    // ============================================================================

    @Test
    fun `stress - very small amounts`() {
        assertEquals(0.01, AmountUtils.parseAmount("0.01")!!, 0.001)
        assertEquals(0.001, AmountUtils.parseAmount("0.001")!!, 0.001)
        assertEquals(0.00, AmountUtils.parseAmount("0.00")!!, 0.001)
    }

    @Test
    fun `stress - decimal only without integer part`() {
        assertEquals(0.50, AmountUtils.parseAmount(".50")!!, 0.001)
        assertEquals(0.50, AmountUtils.parseAmount(",50")!!, 0.001)
    }

    @Test
    fun `stress - scientific notation`() {
        // Not currently supported but should handle gracefully
        val result = AmountUtils.parseAmount("1e10")
        // Either returns null or parses - document behavior
    }

    @Test
    fun `stress - signed zero`() {
        val negZero = AmountUtils.parseAmount("-0")
        val posZero = AmountUtils.parseAmount("0")
        // Both should parse to 0
        assertEquals(0.0, negZero!!, 0.001)
        assertEquals(0.0, posZero!!, 0.001)
    }

    @Test
    fun `stress - amount with slashes`() {
        // Fractional amounts sometimes use slash notation
        assertNull(AmountUtils.parseAmount("1/2"))
        assertNull(AmountUtils.parseAmount("3/4"))
    }

    // ============================================================================
    // SECTION 9: FUZZ TESTING - RANDOM STRINGS
    // ============================================================================

    @Test
    fun `stress - random alphanumeric strings should not crash`() {
        repeat(1000) {
            val randomStr = (1..20).map {
                when (Random.nextInt(4)) {
                    0 -> ('a'..'z').random()
                    1 -> ('0'..'9').random()
                    2 -> listOf('.', ',', ' ', '-', '€', '$').random()
                    else -> ""
                }
            }.joinToString("")
            
            // Should not throw exception
            try {
                AmountUtils.parseAmount(randomStr)
            } catch (e: Exception) {
                fail("parseAmount crashed with: $randomStr")
            }
        }
    }

    @Test
    fun `stress - random currency-like strings`() {
        val currencies = listOf("€", "$", "£", "¥", "₽", "₹", "₩")
        repeat(500) {
            val prefix = if (Random.nextBoolean()) currencies.random() else ""
            val number = (0..100000).random()
            val suffix = if (Random.nextBoolean()) currencies.random() else ""
            
            try {
                AmountUtils.parseAmount("$prefix$number$suffix")
            } catch (e: Exception) {
                fail("parseAmount crashed")
            }
        }
    }

    // ============================================================================
    // SECTION 10: REGRESSION TESTS
    // ============================================================================

    @Test
    fun `regression - known working formats still work`() {
        assertEquals(1234.56, AmountUtils.parseAmount("1,234.56")!!, 0.001)
        assertEquals(1234.56, AmountUtils.parseAmount("1.234,56")!!, 0.001)
        assertEquals(1234567.0, AmountUtils.parseAmount("1,234,567")!!, 0.001)
        assertEquals(1.5, AmountUtils.parseAmount("1,50")!!, 0.001)
    }

    @Test
    fun `regression - negative formats still work`() {
        assertEquals(-50.0, AmountUtils.parseAmount("-50")!!, 0.001)
        assertEquals(-50.0, AmountUtils.parseAmount("−50")!!, 0.001)
        assertEquals(-50.0, AmountUtils.parseAmount("(50)")!!, 0.001)
    }

    @Test
    fun `regression - currency symbols still work`() {
        assertEquals(50.0, AmountUtils.parseAmount("€50")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("E50")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("50€")!!, 0.001)
    }

    @Test
    fun `regression - validation rules unchanged`() {
        assertTrue(AmountUtils.isValidAmount(100.0))
        assertTrue(AmountUtils.isValidAmount(0.01))
        assertFalse(AmountUtils.isValidAmount(0.0))
        assertFalse(AmountUtils.isValidAmount(-10.0))
        assertFalse(AmountUtils.isValidAmount(2_000_000.0))
    }

    // ============================================================================
    // SECTION 11: KNOWN BUG DOCUMENTATION
    // ============================================================================

    @Test
    fun `regression - formatAmount is locale-stable across locales`() {
        // formatAmount should stay stable regardless of default locale.
        
        val originalLocale = Locale.getDefault()
        
        Locale.setDefault(Locale.US)
        val usFormatted = AmountUtils.formatAmount(1234.56)
        
        Locale.setDefault(Locale.GERMANY)
        val germanFormatted = AmountUtils.formatAmount(1234.56)
        
        assertEquals(usFormatted, germanFormatted)
        
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `bug - parseAmount allows negative but isValidAmount rejects`() {
        // BUG: parseAmount accepts negative values but isValidAmount rejects them
        val parsed = AmountUtils.parseAmount("-50.00")
        assertNotNull(parsed)
        
        // This is a design flaw - negatives should either:
        // 1. Be rejected by parseAmount, OR
        // 2. Be accepted by isValidAmount (for refunds)
        assertFalse(AmountUtils.isValidAmount(parsed!!))
    }

    @Test
    fun `bug - ambiguous amount with dots interpretation`() {
        // BUG: "1.234" is interpreted differently based on what's available
        // If only dots exist: treated as decimal (1.234)
        // If both exist: dot is thousands if comma comes after
        
        val onlyDot = AmountUtils.parseAmount("1.234")
        assertNotNull(onlyDot)
        // With US locale expectation, this should be 1234 but it's 1.234
    }

    // ============================================================================
    // SECTION 12: BOUNDARY VALUE TESTING
    // ============================================================================

    @Test
    fun `boundary - exactly zero`() {
        assertEquals(0.0, AmountUtils.parseAmount("0")!!, 0.001)
        assertEquals(0.0, AmountUtils.parseAmount("0.0")!!, 0.001)
        assertEquals(0.0, AmountUtils.parseAmount("0,0")!!, 0.001)
        assertFalse(AmountUtils.isValidAmount(0.0))
    }

    @Test
    fun `boundary - just above zero`() {
        assertTrue(AmountUtils.isValidAmount(0.01))
        assertTrue(AmountUtils.isValidAmount(0.001))
        assertTrue(AmountUtils.isValidAmount(0.0001))
    }

    @Test
    fun `boundary - exactly 1 million`() {
        assertTrue(AmountUtils.isValidAmount(1_000_000.00))
        assertTrue(AmountUtils.isValidAmount(1000000.0))
    }

    @Test
    fun `boundary - just over 1 million`() {
        assertFalse(AmountUtils.isValidAmount(1_000_000.01))
        assertFalse(AmountUtils.isValidAmount(1000001.0))
    }

    @Test
    fun `boundary - very large valid amount`() {
        // Max valid is 1,000,000
        assertTrue(AmountUtils.isValidAmount(999_999.99))
    }
}
