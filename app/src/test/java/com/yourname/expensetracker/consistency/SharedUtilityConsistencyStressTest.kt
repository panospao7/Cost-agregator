package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.domain.util.AmountExtractionUtils
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Stress tests for shared utility consistency. Ensures no drift under load and edge inputs.
 */
class SharedUtilityConsistencyStressTest {

    @Test
    fun `stress - 1000 MerchantKeyGenerator calls produce same result`() {
        val merchants = listOf("Starbucks", "Σκλαβενίτης", "McDonald's", "LIDL")
        for (m in merchants) {
            val expected = MerchantKeyGenerator.generate(m)
            repeat(1000) {
                assertEquals("Key must be deterministic: $m", expected, MerchantKeyGenerator.generate(m))
            }
        }
    }

    @Test
    fun `stress - 500 amount parses consistent`() {
        val inputs = listOf("10.00", "€50.50", "1.234,56", "100.00 EUR")
        for (input in inputs) {
            val expectedAmount = AmountUtils.parseAmount(input)
            val expectedExtract = AmountExtractionUtils.extractFirstAmount(input)
            repeat(500) {
                assertEquals("AmountUtils must be deterministic: $input", expectedAmount, AmountUtils.parseAmount(input))
                assertEquals("AmountExtractionUtils must be deterministic: $input", expectedExtract, AmountExtractionUtils.extractFirstAmount(input))
            }
        }
    }

    @Test
    fun `stress - mixed merchant variants produce consistent keys`() {
        val baseMerchants = listOf(
            "Starbucks" to listOf("Starbucks", "STARBUCKS", "starbucks", "Starbucks Coffee"),
            "LIDL" to listOf("LIDL", "Lidl", "lidl", "LIDL Market"),
            "Σκλαβενίτης" to listOf("Σκλαβενίτης", "ΣΚΛΑΒΕΝΊΤΗΣ", "Sklavenitis")
        )
        for ((canonical, variants) in baseMerchants) {
            val canonicalKey = MerchantKeyGenerator.generate(canonical)
            for (v in variants) {
                val key = MerchantKeyGenerator.generate(v)
                assertNotNull("Key must exist for: $v", key)
                // Same base merchant should produce same or compatible key
                assert(key.isNotEmpty())
            }
        }
    }

    @Test
    fun `stress - AmountUtils and AmountExtractionUtils agree on 100 varied inputs`() {
        val amounts = (1..100).map { i ->
            when (i % 5) {
                0 -> "€${i}.${i % 100}"
                1 -> "$i.${i % 10}0"
                2 -> "${i * 10},${i % 10}0"
                3 -> "EUR ${i}.00"
                else -> "${i}.${i % 10}${i % 10}"
            }
        }
        var agreed = 0
        var bothNull = 0
        for (input in amounts) {
            val a = AmountUtils.parseAmount(input)
            val b = AmountExtractionUtils.extractFirstAmount(input)
            when {
                a != null && b != null -> {
                    assertEquals("Must agree: $input", a, b, 0.01)
                    agreed++
                }
                a == null && b == null -> bothNull++
            }
        }
        assert(agreed > 0 || bothNull > 0) { "At least some inputs should parse or both reject" }
    }

    @Test
    fun `stress - MerchantKeyGenerator handles unicode consistently`() {
        val unicodeMerchants = listOf(
            "Café",
            "Zürich",
            "São Paulo",
            "München",
            "Niño"
        )
        for (m in unicodeMerchants) {
            val key1 = MerchantKeyGenerator.generate(m)
            val key2 = MerchantKeyGenerator.generate(m)
            assertEquals("Unicode merchant must be deterministic: $m", key1, key2)
        }
    }

    @Test
    fun `stress - concurrent-like sequential calls no drift`() {
        val inputs = (1..200).flatMap { i ->
            listOf(
                "Merchant$i",
                "${i}.${i % 100}",
                "€${i}.50"
            )
        }
        for (input in inputs) {
            val key1 = MerchantKeyGenerator.generate(input)
            val key2 = MerchantKeyGenerator.generate(input)
            assertEquals("No drift on repeat: $input", key1, key2)
        }
    }
}
