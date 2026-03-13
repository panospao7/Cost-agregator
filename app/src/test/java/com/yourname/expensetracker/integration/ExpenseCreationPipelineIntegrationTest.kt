package com.yourname.expensetracker.integration

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.StringDistanceUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExpenseCreationPipelineIntegrationTest {

    private lateinit var merchantCleaner: MerchantCleaner

    @Before
    fun setup() {
        merchantCleaner = MerchantCleaner()
    }

    // ============================================================================
    // SECTION 1: PARSE → CLEAN PIPELINE
    // ============================================================================

    @Test
    fun `integration - clean raw notification`() {
        val rawNotification = "Starbucks 14:30 confirmed •••• 1234"
        
        val cleaned = merchantCleaner.clean(rawNotification)
        assertTrue("Cleaned should not contain time", !cleaned.contains("14:30"))
    }

    @Test
    fun `integration - Greek notification pipeline`() {
        val rawNotification = "Πληρωμή στο Σκλαβενίτης 12/01 •••• 4532"
        
        val cleaned = merchantCleaner.clean(rawNotification)
        assertTrue("Cleaned should exist", cleaned.isNotEmpty())
    }

    @Test
    fun `integration - messy notification cleanup`() {
        val rawNotification = "  McDonald's  14:30  ••••  5678  CONFIRMED  "
        
        val cleaned = merchantCleaner.clean(rawNotification)
        
        assertFalse("Cleaned should not have extra spaces", cleaned.contains("  "))
    }

    // ============================================================================
    // SECTION 2: AMOUNT PARSING → VALIDATION PIPELINE
    // ============================================================================

    @Test
    fun `integration - amount parsing with various formats`() {
        val amounts = listOf(
            "€50.00" to true,
            "50.99" to true,
            "EUR 100.50" to true,
            "$25.99" to true
        )
        
        amounts.forEach { (input, shouldParse) ->
            val parsed = AmountUtils.parseAmount(input)
            if (shouldParse) {
                assertNotNull("Should parse: $input", parsed)
            }
        }
    }

    @Test
    fun `integration - amount validation after parsing`() {
        val validAmounts = listOf("10.00", "1000.00", "0.01")
        
        validAmounts.forEach { amount ->
            val parsed = AmountUtils.parseAmount(amount)
            assertTrue("Should be valid: $amount", parsed?.let { AmountUtils.isValidAmount(it) } ?: false)
        }
    }

    // ============================================================================
    // SECTION 3: MERCHANT MATCHING PIPELINE
    // ============================================================================

    @Test
    fun `integration - fuzzy matching with variations`() {
        val knownMerchant = "McDonald's"
        val variations = listOf(
            "McDonalds",
            "McDonald",
            "MCDONALD",
            "McDonld"
        )
        
        variations.forEach { variation ->
            val isMatch = StringDistanceUtils.isFuzzyMatch(variation, knownMerchant, 2)
            assertTrue("Should match: $variation", isMatch)
        }
    }

    @Test
    fun `integration - distance calculation pipeline`() {
        val s1 = "Starbucks Coffee"
        val s2 = "Starbucks Coffe"
        
        val distance = StringDistanceUtils.levenshteinDistance(s1, s2)
        val similarity = StringDistanceUtils.levenshteinSimilarity(s1, s2)
        
        assertTrue("Should have small distance", distance <= 2)
        assertTrue("Should have high similarity", similarity > 0.9)
    }

    // ============================================================================
    // SECTION 4: COMPLETE CLEANING PIPELINE
    // ============================================================================

    @Test
    fun `integration - process notification data`() {
        val rawTitle = "Starbucks 14:30"
        val rawText = "Purchase €5.50 •••• 1234"
        
        val merchant = merchantCleaner.clean(rawTitle)
        val amount = AmountUtils.parseAmount(rawText)
        
        assertNotNull("Amount should parse", amount)
        assertTrue("Merchant should be cleaned", merchant.isNotEmpty())
    }

    @Test
    fun `integration - handle edge case notification`() {
        val rawTitle = ""
        val rawText = "!!!"
        
        val merchant = merchantCleaner.clean(rawTitle)
        val amount = AmountUtils.parseAmount(rawText)
        
        assertTrue("Should return Unknown or empty for empty input", merchant.isNotEmpty())
        assertNull("No amount in garbage", amount)
    }

    // ============================================================================
    // SECTION 5: ERROR RECOVERY PIPELINE
    // ============================================================================

    @Test
    fun `integration - recover from missing amount`() {
        val rawText = "Purchase at Store"
        
        val amount = AmountUtils.parseAmount(rawText)
        
        assertNull("Should return null for no amount", amount)
    }

    @Test
    fun `integration - recover from null inputs`() {
        val merchant = merchantCleaner.clean(null)
        
        assertEquals("Unknown", merchant)
    }

    @Test
    fun `integration - handle very long inputs gracefully`() {
        val longTitle = "A".repeat(10000)
        
        val cleaned = merchantCleaner.clean(longTitle)
        
        assertTrue("Should truncate", cleaned.length <= 40)
    }

    // ============================================================================
    // SECTION 6: PERFORMANCE UNDER LOAD
    // ============================================================================

    @Test
    fun `integration - process 100 notifications quickly`() {
        val notifications = (1..100).map { i ->
            "Store $i ${i * 10}.00 €"
        }
        
        val startTime = System.nanoTime()
        
        notifications.forEach { raw ->
            merchantCleaner.clean(raw)
            AmountUtils.parseAmount(raw)
        }
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should process 100 notifications in under 1s but took ${duration/1000000}ms", 
            duration < 1_000_000_000)
    }

    // ============================================================================
    // SECTION 7: DATA CONSISTENCY
    // ============================================================================

    @Test
    fun `integration - same input produces same output`() {
        val input = "Starbucks 14:30 confirmed"
        
        val result1 = merchantCleaner.clean(input)
        val result2 = merchantCleaner.clean(input)
        
        assertEquals("Should be deterministic", result1, result2)
    }

    // ============================================================================
    // SECTION 8: GREEK TEXT INTEGRATION
    // ============================================================================

    @Test
    fun `integration - full Greek pipeline`() {
        val raw = "Πληρωμή στο Καφέ Μακεδονία 10.50€ 12/01"
        
        val cleaned = merchantCleaner.clean(raw)
        
        assertTrue("Should produce valid result", cleaned.isNotEmpty())
    }

    @Test
    fun `integration - Greeklish mixed text`() {
        val raw = "LIDL ΑΕΒΕ 25.00€"
        
        val cleaned = merchantCleaner.clean(raw)
        
        assertTrue("Should handle mixed", cleaned.isNotEmpty())
    }
}
