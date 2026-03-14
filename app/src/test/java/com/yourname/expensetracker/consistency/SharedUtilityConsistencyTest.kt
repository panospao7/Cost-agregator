package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.domain.util.AmountExtractionUtils
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.CommonPatterns
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ensures shared utilities (AmountUtils, AmountExtractionUtils, CommonPatterns, MerchantKeyGenerator)
 * produce consistent results across consumers. Prevents drift when multiple components use the same logic.
 */
class SharedUtilityConsistencyTest {

    // ============================================================================
    // AMOUNT PARSING CONSISTENCY
    // ============================================================================

    @Test
    fun `consistency - AmountUtils and AmountExtractionUtils agree on simple amounts`() {
        val inputs = listOf("10.00", "50.50", "100.00", "1234.56", "0.01")
        for (input in inputs) {
            val amountUtilsResult = AmountUtils.parseAmount(input)
            val extractionResult = AmountExtractionUtils.extractFirstAmount(input)
            assertNotNull("AmountUtils should parse: $input", amountUtilsResult)
            assertNotNull("AmountExtractionUtils should parse: $input", extractionResult)
            assertEquals(
                "AmountUtils and AmountExtractionUtils must agree on: $input",
                amountUtilsResult!!,
                extractionResult!!,
                0.001
            )
        }
    }

    @Test
    fun `consistency - AmountUtils and AmountExtractionUtils agree on currency-prefixed amounts`() {
        val inputs = listOf("€50.00", "$25.99", "100.00 EUR", "£75.50")
        for (input in inputs) {
            val amountUtilsResult = AmountUtils.parseAmount(input)
            val extractionResult = AmountExtractionUtils.extractFirstAmount(input)
            assertNotNull("AmountUtils should parse: $input", amountUtilsResult)
            assertNotNull("AmountExtractionUtils should parse: $input", extractionResult)
            assertEquals(
                "AmountUtils and AmountExtractionUtils must agree on: $input",
                amountUtilsResult!!,
                extractionResult!!,
                0.001
            )
        }
    }

    @Test
    fun `consistency - AmountUtils and AmountExtractionUtils agree on European format`() {
        val inputs = listOf("1.234,56", "10,50", "999.999,99")
        for (input in inputs) {
            val amountUtilsResult = AmountUtils.parseAmount(input)
            val extractionResult = AmountExtractionUtils.extractFirstAmount(input)
            assertNotNull("AmountUtils should parse European: $input", amountUtilsResult)
            assertNotNull("AmountExtractionUtils should parse European: $input", extractionResult)
            assertEquals(
                "European format must agree: $input",
                amountUtilsResult!!,
                extractionResult!!,
                0.001
            )
        }
    }

    @Test
    fun `consistency - CommonPatterns AMOUNT_REGEX matches what AmountUtils parses`() {
        val inputs = listOf(
            "€50.00",
            "50.00",
            "100,50",
            "1.234,56",
            "EUR 100.50",
            "$25.99"
        )
        var matchedCount = 0
        for (input in inputs) {
            val matcher = CommonPatterns.AMOUNT_REGEX.matcher(input)
            val amountUtilsResult = AmountUtils.parseAmount(input)
            if (matcher.find() && amountUtilsResult != null) {
                val group2 = matcher.group(2) ?: continue
                val parsedFromRegex = AmountUtils.parseAmount(group2.replace(" ", "").trim())
                if (parsedFromRegex != null) {
                    assertEquals(
                        "CommonPatterns and AmountUtils must agree: $input",
                        amountUtilsResult,
                        parsedFromRegex,
                        0.01
                    )
                    matchedCount++
                }
            }
        }
        assert(matchedCount >= 3) { "At least 3 inputs should match CommonPatterns" }
    }

    @Test
    fun `consistency - both null for invalid amounts`() {
        val invalidInputs = listOf("", "abc", "no numbers", "---", "...")
        for (input in invalidInputs) {
            val amountUtilsResult = AmountUtils.parseAmount(input)
            val extractionResult = AmountExtractionUtils.extractFirstAmount(input)
            assertNull("AmountUtils should reject: '$input'", amountUtilsResult)
            assertNull("AmountExtractionUtils should reject: '$input'", extractionResult)
        }
    }

    @Test
    fun `consistency - AmountExtractionUtils extractAmount returns same value as extractFirstAmount`() {
        val inputs = listOf("€50.00 at Starbucks", "Payment 100.50 EUR", "25.99")
        for (input in inputs) {
            val extracted = AmountExtractionUtils.extractAmount(input)
            val firstAmount = AmountExtractionUtils.extractFirstAmount(input)
            if (extracted != null && firstAmount != null) {
                assertEquals(
                    "extractAmount and extractFirstAmount must agree: $input",
                    extracted.first,
                    firstAmount,
                    0.001
                )
            }
        }
    }

    // ============================================================================
    // MERCHANT KEY CONSISTENCY
    // ============================================================================

    @Test
    fun `consistency - MerchantKeyGenerator idempotent for same input`() {
        val merchants = listOf("Starbucks", "Σκλαβενίτης", "McDonald's", "LIDL", "AB Vasilopoulos")
        for (m in merchants) {
            val key1 = MerchantKeyGenerator.generate(m)
            val key2 = MerchantKeyGenerator.generate(m)
            assertEquals("MerchantKeyGenerator must be idempotent: $m", key1, key2)
        }
    }

    @Test
    fun `consistency - MerchantKeyGenerator case insensitive`() {
        val pairs = listOf(
            "Starbucks" to "STARBUCKS",
            "starbucks" to "Starbucks",
            "LIDL" to "lidl",
            "Σκλαβενίτης" to "ΣΚΛΑΒΕΝΊΤΗΣ"
        )
        for ((a, b) in pairs) {
            val keyA = MerchantKeyGenerator.generate(a)
            val keyB = MerchantKeyGenerator.generate(b)
            assertEquals("Case should not affect key: $a vs $b", keyA, keyB)
        }
    }

    @Test
    fun `consistency - MerchantKeyGenerator Greek and Latin produce same key`() {
        // GreeklishNormalizer should make "Σκλαβενίτης" and "Sklavenitis" produce same key
        val greek = "Σκλαβενίτης"
        val latin = "Sklavenitis"
        val keyGreek = MerchantKeyGenerator.generate(greek)
        val keyLatin = MerchantKeyGenerator.generate(latin)
        assertEquals(
            "Greek and Latin spellings of same merchant must produce same key",
            keyGreek,
            keyLatin
        )
    }

    @Test
    fun `consistency - MerchantKeyGenerator strips non-alphanumeric uniformly`() {
        val variants = listOf(
            "McDonald's",
            "McDonalds",
            "McDonald's Restaurant"
        )
        val key1 = MerchantKeyGenerator.generate(variants[0])
        val key2 = MerchantKeyGenerator.generate(variants[1])
        assertEquals("Apostrophe should be stripped: McDonald's vs McDonalds", key1, key2)
    }

    // ============================================================================
    // EDGE CASES
    // ============================================================================

    @Test
    fun `edge - empty string handling consistent`() {
        assertEquals("", MerchantKeyGenerator.generate(""))
        assertNull(AmountUtils.parseAmount(""))
        assertNull(AmountExtractionUtils.extractFirstAmount(""))
    }

    @Test
    fun `edge - whitespace only`() {
        assertEquals("", MerchantKeyGenerator.generate("   "))
        assertNull(AmountUtils.parseAmount("   "))
        assertNull(AmountExtractionUtils.extractFirstAmount("   "))
    }

    @Test
    fun `edge - very long merchant produces valid key`() {
        val long = "A".repeat(500)
        val key = MerchantKeyGenerator.generate(long)
        assertNotNull(key)
        assert(key.isNotEmpty())
    }

    @Test
    fun `edge - amount with thousands separator`() {
        val input = "1,234.56"
        val a = AmountUtils.parseAmount(input)
        val b = AmountExtractionUtils.extractFirstAmount(input)
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(1234.56, a!!, 0.001)
        assertEquals(1234.56, b!!, 0.001)
    }

    @Test
    fun `edge - amount in sentence`() {
        val input = "You paid €25.50 at the store"
        val a = AmountUtils.parseAmount("25.50")
        val b = AmountExtractionUtils.extractFirstAmount(input)
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a!!, b!!, 0.001)
    }
}
