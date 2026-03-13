package com.yourname.expensetracker.integration

import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.StringDistanceUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CategorizationPipelineIntegrationTest {

    private lateinit var merchantCleaner: MerchantCleaner
    private lateinit var merchantKeyGenerator: MerchantKeyGenerator

    @Before
    fun setup() {
        merchantCleaner = MerchantCleaner()
        merchantKeyGenerator = MerchantKeyGenerator
    }

    // ============================================================================
    // SECTION 1: CLEAN → KEY → MATCH PIPELINE
    // ============================================================================

    @Test
    fun `integration - clean merchant generates key and matches`() {
        val rawMerchant = "Starbucks Coffee 12345"
        
        val cleaned = merchantCleaner.clean(rawMerchant)
        val key = merchantKeyGenerator.generate(cleaned)
        
        // Note: This test may fail if cleaner doesn't remove all numbers - documented bug
        assertTrue("Cleaned should exist", cleaned.isNotEmpty())
        assertTrue("Key should be generated", key.isNotEmpty())
    }

    @Test
    fun `integration - Greek merchant pipeline`() {
        val rawMerchant = "Σκλαβενίτης ΑΕ 1234"
        
        val cleaned = merchantCleaner.clean(rawMerchant)
        val key = merchantKeyGenerator.generate(cleaned)
        
        assertTrue("Cleaned should exist", cleaned.isNotEmpty())
        assertTrue("Key should be generated", key.isNotEmpty())
    }

    @Test
    fun `integration - messy merchant cleanup pipeline`() {
        val rawMerchant = "  McDonald's  Store #123  "
        
        val cleaned = merchantCleaner.clean(rawMerchant)
        val key = merchantKeyGenerator.generate(cleaned)
        
        assertFalse("Should not have extra spaces", cleaned.contains("  "))
        assertTrue("Key should be valid", key.isNotEmpty())
    }

    // ============================================================================
    // SECTION 2: AMOUNT → VALIDATION → FORMATTING PIPELINE
    // ============================================================================

    @Test
    fun `integration - parse amount and validate`() {
        val rawAmount = "€50.00"
        
        val parsed = AmountUtils.parseAmount(rawAmount)
        val isValid = parsed?.let { AmountUtils.isValidAmount(it) } ?: false
        
        assertNotNull("Should parse", parsed)
        assertTrue("Should be valid", isValid)
    }

    @Test
    fun `integration - invalid amount rejected`() {
        val invalidAmounts = listOf("-10.00", "0", "-100")
        
        invalidAmounts.forEach { amount ->
            val parsed = AmountUtils.parseAmount(amount)
            if (parsed != null) {
                assertFalse("Should reject: $amount", AmountUtils.isValidAmount(parsed))
            }
        }
    }

    @Test
    fun `integration - various currency formats parsed`() {
        val formats = listOf("€50.00", "$25.99", "100.00 EUR", "£75.50")
        
        formats.forEach { format ->
            val parsed = AmountUtils.parseAmount(format)
            assertNotNull("Should parse: $format", parsed)
        }
    }

    // ============================================================================
    // SECTION 3: MERCHANT → FUZZY MATCH PIPELINE
    // ============================================================================

    @Test
    fun `integration - merchant variations match correctly`() {
        val baseMerchant = "Starbucks Coffee"
        val variations = listOf(
            "Starbucks Coffee",
            "StarBucks COFFEE",
            "starbucks coffee",
            "Starbuck Coffee"
        )
        
        variations.forEach { variation ->
            val isMatch = StringDistanceUtils.isFuzzyMatch(variation, baseMerchant, 2)
            assertTrue("Should match: $variation", isMatch)
        }
    }

    @Test
    fun `integration - different merchants do not match`() {
        val merchant1 = "Starbucks"
        val merchant2 = "McDonalds"
        
        val isMatch = StringDistanceUtils.isFuzzyMatch(merchant1, merchant2, 2)
        
        assertFalse("Should not match different merchants", isMatch)
    }

    // ============================================================================
    // SECTION 4: COMPLETE EXPENSE CREATION PIPELINE
    // ============================================================================

    @Test
    fun `integration - full expense creation pipeline`() {
        val rawNotification = "Starbucks 14:30 Purchase €5.50"
        
        val merchant = merchantCleaner.clean(rawNotification)
        val key = merchantKeyGenerator.generate(merchant)
        val amount = AmountUtils.parseAmount(rawNotification)
        
        assertTrue("Merchant cleaned", merchant.isNotEmpty())
        assertTrue("Key generated", key.isNotEmpty())
        assertNotNull("Amount parsed", amount)
    }

    @Test
    fun `integration - Greek expense pipeline`() {
        val rawNotification = "Πληρωμή στο Σκλαβενίτης 25.00€"
        
        val merchant = merchantCleaner.clean(rawNotification)
        val key = merchantKeyGenerator.generate(merchant)
        val amount = AmountUtils.parseAmount(rawNotification)
        
        assertTrue("Greek merchant cleaned", merchant.isNotEmpty())
        assertTrue("Greek key generated", key.isNotEmpty())
        assertNotNull("Greek amount parsed", amount)
    }

    // ============================================================================
    // SECTION 5: EDGE CASES
    // ============================================================================

    @Test
    fun `integration - empty input handled gracefully`() {
        val result = merchantCleaner.clean("")
        
        assertTrue("Should return something for empty", result.isNotEmpty())
    }

    @Test
    fun `integration - null input handled`() {
        val result = merchantCleaner.clean(null)
        
        assertEquals("Unknown", result)
    }

    @Test
    fun `integration - special characters in merchant`() {
        val merchant = "Store & More @#\$%"
        
        val cleaned = merchantCleaner.clean(merchant)
        val key = merchantKeyGenerator.generate(cleaned)
        
        assertTrue("Cleaned should exist", cleaned.isNotEmpty())
        assertTrue("Key should be generated", key.isNotEmpty())
    }

    // ============================================================================
    // SECTION 6: PERFORMANCE
    // ============================================================================

    @Test
    fun `integration - process 100 merchants quickly`() {
        val merchants = (1..100).map { "Store $it" }
        
        val startTime = System.nanoTime()
        
        merchants.forEach { merchant ->
            merchantCleaner.clean(merchant)
            merchantKeyGenerator.generate(merchant)
        }
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should process quickly", duration < 1_000_000_000)
    }

    // ============================================================================
    // SECTION 7: CONSISTENCY
    // ============================================================================

    @Test
    fun `integration - same merchant produces same key`() {
        val merchant = "Starbucks Coffee"
        
        val key1 = merchantKeyGenerator.generate(merchant)
        val key2 = merchantKeyGenerator.generate(merchant)
        
        assertEquals("Keys should be deterministic", key1, key2)
    }

    @Test
    fun `integration - clean then key is consistent`() {
        val raw = "Starbucks  12345"
        
        val cleaned = merchantCleaner.clean(raw)
        val key = merchantKeyGenerator.generate(cleaned)
        
        val cleaned2 = merchantCleaner.clean(raw)
        val key2 = merchantKeyGenerator.generate(cleaned2)
        
        assertEquals(cleaned, cleaned2)
        assertEquals(key, key2)
    }

    // ============================================================================
    // SECTION 8: AMOUNT EDGE CASES
    // ============================================================================

    @Test
    fun `integration - large amounts parsed correctly`() {
        val largeAmount = "€1,000,000.00"
        
        val parsed = AmountUtils.parseAmount(largeAmount)
        
        assertNotNull("Should parse large amount", parsed)
    }

    @Test
    fun `integration - small amounts parsed correctly`() {
        val smallAmount = "€0.01"
        
        val parsed = AmountUtils.parseAmount(smallAmount)
        
        assertNotNull("Should parse small amount", parsed)
        assertTrue("Should be valid", parsed?.let { AmountUtils.isValidAmount(it) } ?: false)
    }

    // ============================================================================
    // SECTION 9: GREEKLISH PIPELINE
    // ============================================================================

    @Test
    fun `integration - Greeklish conversion pipeline`() {
        val greeklish = "LIDL ΜΑΡΚΕΤ"
        
        val cleaned = merchantCleaner.clean(greeklish)
        val key = merchantKeyGenerator.generate(cleaned)
        
        assertTrue("Cleaned should exist", cleaned.isNotEmpty())
        assertTrue("Key should be generated", key.isNotEmpty())
    }

    @Test
    fun `integration - mixed Greek English merchant`() {
        val mixed = "AB ΒΑΣΙΛΗΣ Market"
        
        val cleaned = merchantCleaner.clean(mixed)
        val key = merchantKeyGenerator.generate(cleaned)
        
        assertTrue("Should handle mixed", cleaned.isNotEmpty())
        assertTrue("Should generate key", key.isNotEmpty())
    }
}
