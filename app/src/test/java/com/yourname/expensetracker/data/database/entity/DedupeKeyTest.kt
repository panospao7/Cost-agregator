package com.yourname.expensetracker.data.database.entity

import org.junit.Assert.*
import org.junit.Test

/**
 * P0 tests for [Expense.generateDedupeKey].
 *
 * Format: "${roundedAmount}_${normalizedMerchant}_${dateBucket}"
 * where dateBucket = date / 300_000L (5-minute window).
 */
class DedupeKeyTest {

    @Test
    fun `key has expected format`() {
        val amount = 12.50
        val merchant = "cafe"
        val date = 300_000L // bucket 1

        val key = Expense.generateDedupeKey(amount, merchant, date)

        // Expected: "12.50_cafe_1"
        assertEquals("12.50_cafe_1", key)
    }

    @Test
    fun `same merchant different casing produces same key`() {
        val date = 1_000_000L
        val key1 = Expense.generateDedupeKey(20.0, "Starbucks", date)
        val key2 = Expense.generateDedupeKey(20.0, "STARBUCKS", date)

        assertEquals(key1, key2)
    }

    @Test
    fun `Greek and Latin spelling of same merchant produce same key`() {
        // "sklavenitis" in Greek script vs Latin — MerchantKeyGenerator maps both to "sklavenitis"
        val date = 1_000_000L
        val greek  = Expense.generateDedupeKey(50.0, "Σκλαβενίτης", date)
        val latin  = Expense.generateDedupeKey(50.0, "Sklavenitis",  date)

        assertEquals(greek, latin)
    }

    @Test
    fun `different amounts produce different keys`() {
        val date = 1_000_000L
        val key1 = Expense.generateDedupeKey(10.0, "shop", date)
        val key2 = Expense.generateDedupeKey(10.1, "shop", date)

        assertNotEquals(key1, key2)
    }

    @Test
    fun `timestamps more than 5 minutes apart produce different keys`() {
        val t1 = 0L
        val t2 = 300_001L // just over one 5-min bucket boundary

        val key1 = Expense.generateDedupeKey(15.0, "baker", t1)
        val key2 = Expense.generateDedupeKey(15.0, "baker", t2)

        assertNotEquals(key1, key2)
    }

    @Test
    fun `timestamps within the same 5-minute bucket produce the same key`() {
        val t1 = 600_000L       // bucket 2 (600_000 / 300_000 = 2)
        val t2 = 899_999L       // bucket 2 (899_999 / 300_000 = 2)

        val key1 = Expense.generateDedupeKey(5.0, "kiosk", t1)
        val key2 = Expense.generateDedupeKey(5.0, "kiosk", t2)

        assertEquals(key1, key2)
    }
}
