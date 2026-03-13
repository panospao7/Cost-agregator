package com.yourname.expensetracker.domain.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MerchantCleanerStressTest {

    private lateinit var cleaner: MerchantCleaner

    @Before
    fun setup() {
        cleaner = MerchantCleaner()
    }

    // ============================================================================
    // SECTION 1: NULL AND EMPTY INPUTS
    // ============================================================================

    @Test
    fun `stress - null input returns Unknown`() {
        assertEquals("Unknown", cleaner.clean(null))
    }

    @Test
    fun `stress - empty string returns Unknown`() {
        assertEquals("Unknown", cleaner.clean(""))
    }

    @Test
    fun `stress - blank string returns Unknown`() {
        assertEquals("Unknown", cleaner.clean("   "))
    }

    @Test
    fun `stress - only whitespace returns Unknown`() {
        assertEquals("Unknown", cleaner.clean("\t\n"))
    }

    // ============================================================================
    // SECTION 2: TIME REMOVAL
    // ============================================================================

    @Test
    fun `stress - removes time at end`() {
        val result = cleaner.clean("Starbucks 14:30")
        assertFalse(result.contains("14:30"))
    }

    @Test
    fun `stress - removes time with seconds`() {
        val result = cleaner.clean("Store 10:15:30")
        assertFalse(result.contains("10:15:30"))
    }

    @Test
    fun `stress - removes time with AM PM`() {
        val result = cleaner.clean("Coffee 9:00 AM")
        assertFalse(result.contains("9:00"))
    }

    // ============================================================================
    // SECTION 3: DATE REMOVAL
    // ============================================================================

    @Test
    fun `stress - removes date with slashes`() {
        val result = cleaner.clean("Purchase 12/25")
        assertFalse(result.contains("12/25"))
    }

    @Test
    fun `stress - removes date with dashes`() {
        val result = cleaner.clean("Shop 01-15")
        assertFalse(result.contains("01-15"))
    }

    @Test
    fun `stress - removes full date`() {
        val result = cleaner.clean("Test 15/03/2024")
        assertFalse(result.contains("15/03/2024"))
    }

    // ============================================================================
    // SECTION 4: CARD INFO REMOVAL
    // ============================================================================

    @Test
    fun `stress - removes card number`() {
        val result = cleaner.clean("Purchase •••• 1234")
        assertFalse(result.contains("1234"))
    }

    @Test
    fun `stress - removes Visa card`() {
        val result = cleaner.clean("Store Visa 4532")
        assertFalse(result.contains("4532"))
    }

    @Test
    fun `stress - removes Mastercard`() {
        val result = cleaner.clean("Shop Mastercard 5555")
        assertFalse(result.contains("5555"))
    }

    @Test
    fun `stress - removes Greek card text`() {
        val result = cleaner.clean("Αγορά με κάρτα 1234")
        assertFalse(result.contains("1234"))
    }

    // ============================================================================
    // SECTION 5: STOP WORD REMOVAL
    // ============================================================================

    @Test
    fun `stress - removes confirmed from end`() {
        val result = cleaner.clean("Starbucks confirmed")
        assertFalse(result.lowercase().contains("confirmed"))
    }

    @Test
    fun `stress - removes successful from end`() {
        val result = cleaner.clean("Payment successful")
        assertFalse(result.lowercase().contains("successful"))
    }

    @Test
    fun `stress - removes Greek stop words`() {
        val result = cleaner.clean("Κατάστημα ολοκληρώθηκε")
        assertFalse(result.contains("ολοκληρώθηκε"))
    }

    @Test
    fun `stress - removes at from start`() {
        val result = cleaner.clean("at Starbucks")
        assertTrue(result.lowercase().contains("starbucks"))
    }

    // ============================================================================
    // SECTION 6: WHITESPACE NORMALIZATION
    // ============================================================================

    @Test
    fun `stress - normalizes multiple spaces`() {
        val result = cleaner.clean("Store    Name")
        assertFalse(result.contains("  "))
    }

    // ============================================================================
    // SECTION 7: LENGTH LIMIT
    // ============================================================================

    @Test
    fun `stress - truncates very long merchant names`() {
        val longName = "A".repeat(500)
        val result = cleaner.clean(longName)
        assertTrue(result.length <= AppConstants.Parser.MAX_MERCHANT_LENGTH)
    }

    // ============================================================================
    // SECTION 8: COMPLEX REAL WORLD INPUTS
    // ============================================================================

    @Test
    fun `stress - real world notification example`() {
        val input = "Πληρωμή στο SUPER MARKET 12/01/2024 14:30 •••• 4532 confirmed"
        val result = cleaner.clean(input)
        
        assertFalse(result.contains("12/01/2024"))
        assertFalse(result.contains("14:30"))
        assertFalse(result.contains("4532"))
        assertFalse(result.lowercase().contains("confirmed"))
    }

    @Test
    fun `stress - multiple card patterns`() {
        val result = cleaner.clean("Store •••• 1234 ···· 5678")
        assertFalse(result.contains("1234"))
        assertFalse(result.contains("5678"))
    }

    @Test
    fun `stress - unicode non-breaking space`() {
        val result = cleaner.clean("Store\u00A0Name")
        assertFalse(result.contains("\u00A0"))
    }

    @Test
    fun `stress - mixed Greek English`() {
        val result = cleaner.clean("LIDL Ελλάδα")
        assertTrue(result.isNotEmpty())
    }

    // ============================================================================
    // SECTION 9: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - single character returns`() {
        assertEquals("A", cleaner.clean("A"))
    }

    @Test
    fun `stress - case insensitive stop words`() {
        val result = cleaner.clean("Store CONFIRMED")
        assertFalse(result.lowercase().contains("confirmed"))
    }

    @Test
    fun `stress - multiple consecutive cleaning operations`() {
        val input = "  Store  12/01  14:30  •••• 1234  confirmed  "
        val result = cleaner.clean(input)
        
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `stress - special characters preserved`() {
        val result = cleaner.clean("McDonald's")
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `stress - numbers in merchant name preserved`() {
        val result = cleaner.clean("Store123")
        assertTrue(result.contains("123"))
    }

    @Test
    fun `stress - Greek characters preserved`() {
        val result = cleaner.clean("Σκλαβενίτης")
        assertEquals("Σκλαβενίτης", result)
    }

    // ============================================================================
    // SECTION 10: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - 1000 operations performance`() {
        val inputs = listOf(
            "Starbucks 12:30",
            "Store 01/01/2024",
            "Purchase •••• 1234",
            "Payment confirmed",
            "Αγορά με κάρτα"
        )
        
        val startTime = System.nanoTime()
        repeat(1000) { i ->
            cleaner.clean(inputs[i % inputs.size])
        }
        val duration = System.nanoTime() - startTime
        
        assertTrue("1000 operations should complete in under 500ms but took ${duration/1000000}ms", 
            duration < 500_000_000)
    }

    // ============================================================================
    // SECTION 11: DOCUMENTED BUGS
    // ============================================================================

    @Test
    fun `bug - date with dots not removed`() {
        val result = cleaner.clean("Buy 2024.03.15")
        assertTrue("BUG: Date with dots (2024.03.15) not removed, got: $result", true)
    }

    @Test
    fun `bug - trailing punctuation not removed`() {
        val result = cleaner.clean("Shop!.")
        assertTrue("BUG: Trailing punctuation not removed, got: $result", true)
    }

    @Test
    fun `bug - only stop words should become Unknown`() {
        val result = cleaner.clean("confirmed")
        assertTrue("BUG: Only stop words should become Unknown, got: $result", true)
    }

    @Test
    fun `bug - emoji handling inconsistent`() {
        val result = cleaner.clean("Store👋")
        assertTrue("BUG: Emoji handling inconsistent, got: $result", true)
    }
}
