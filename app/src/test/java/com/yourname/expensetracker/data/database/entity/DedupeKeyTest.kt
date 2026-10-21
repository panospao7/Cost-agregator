package com.yourname.expensetracker.data.database.entity

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/**
 * P0 tests for [Expense.generateDedupeKey].
 *
 * Format: "${roundedAmount}_${normalizedMerchant}_${dateBucket}_${currency}"
 * where dateBucket = date / 300_000L (5-minute window).
 *
 * Currency is a required parameter — no silent EUR fallback is allowed on this path.
 */
class DedupeKeyTest {

    @Test
    fun `key has expected format`() {
        val amount = 12.50
        val merchant = "cafe"
        val date = 300_000L // bucket 1

        val key = Expense.generateDedupeKey(amount, merchant, date, "EUR")

        // Expected: "12.50_cafe_1_EUR"
        assertEquals("12.50_cafe_1_EUR", key)
    }

    @Test
    fun `same merchant different casing produces same key`() {
        val date = 1_000_000L
        val key1 = Expense.generateDedupeKey(20.0, "Starbucks", date, "EUR")
        val key2 = Expense.generateDedupeKey(20.0, "STARBUCKS", date, "EUR")

        assertEquals(key1, key2)
    }

    @Test
    fun `Greek and Latin spelling of same merchant produce same key`() {
        // "sklavenitis" in Greek script vs Latin — MerchantKeyGenerator maps both to "sklavenitis"
        val date = 1_000_000L
        val greek  = Expense.generateDedupeKey(50.0, "Σκλαβενίτης", date, "EUR")
        val latin  = Expense.generateDedupeKey(50.0, "Sklavenitis",  date, "EUR")

        assertEquals(greek, latin)
    }

    @Test
    fun `different amounts produce different keys`() {
        val date = 1_000_000L
        val key1 = Expense.generateDedupeKey(10.0, "shop", date, "EUR")
        val key2 = Expense.generateDedupeKey(10.1, "shop", date, "EUR")

        assertNotEquals(key1, key2)
    }

    @Test
    fun `timestamps more than 5 minutes apart produce different keys`() {
        val t1 = 0L
        val t2 = 300_001L // just over one 5-min bucket boundary

        val key1 = Expense.generateDedupeKey(15.0, "baker", t1, "EUR")
        val key2 = Expense.generateDedupeKey(15.0, "baker", t2, "EUR")

        assertNotEquals(key1, key2)
    }

    @Test
    fun `timestamps within the same 5-minute bucket produce the same key`() {
        val t1 = 600_000L       // bucket 2 (600_000 / 300_000 = 2)
        val t2 = 899_999L       // bucket 2 (899_999 / 300_000 = 2)

        val key1 = Expense.generateDedupeKey(5.0, "kiosk", t1, "EUR")
        val key2 = Expense.generateDedupeKey(5.0, "kiosk", t2, "EUR")

        assertEquals(key1, key2)
    }

    @Test
    fun `timestamps straddling the exact 5-minute bucket boundary produce different keys`() {
        // 299_999 is the last ms of bucket 0; 300_000 is the first ms of bucket 1.
        val key1 = Expense.generateDedupeKey(50.0, "shop", 299_999L, "EUR")
        val key2 = Expense.generateDedupeKey(50.0, "shop", 300_000L, "EUR")

        assertNotEquals(key1, key2)
        assertEquals("50.00_shop_0_EUR", key1)
        assertEquals("50.00_shop_1_EUR", key2)
    }

    @Test
    fun `zero amount renders two decimals`() {
        assertEquals("0.00_shop_0_EUR", Expense.generateDedupeKey(0.0, "shop", 0L, "EUR"))
    }

    // ── Locale invariance: amount formatting must use Locale.ROOT (dot decimal) ──

    @Test
    fun `key is identical under German and Greek default locales`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            val rootKey = Expense.generateDedupeKey(1234.56, "shop", 1_000_000L, "EUR")

            Locale.setDefault(Locale.GERMANY)
            val deKey = Expense.generateDedupeKey(1234.56, "shop", 1_000_000L, "EUR")

            Locale.setDefault(Locale("el", "GR"))
            val elKey = Expense.generateDedupeKey(1234.56, "shop", 1_000_000L, "EUR")

            assertEquals(rootKey, deKey)
            assertEquals(rootKey, elKey)
            assertTrue("Key must use dot decimal separator: $rootKey", rootKey.startsWith("1234.56_"))
        } finally {
            Locale.setDefault(original)
        }
    }

    // ── Rounding ──

    @Test
    fun `amount 1_255 rounds to 1_26 in key`() {
        val key = Expense.generateDedupeKey(1.255, "shop", 1_000_000L, "EUR")
        assertTrue("Expected amount segment 1.26 in key: $key", key.startsWith("1.26_"))
    }

    @Test
    fun `amount 1_254 rounds down to 1_25 in key`() {
        val key = Expense.generateDedupeKey(1.254, "shop", 1_000_000L, "EUR")
        assertTrue("Expected amount segment 1.25 in key: $key", key.startsWith("1.25_"))
    }

    // ── ISSUE-6 regression: currency must be required, no silent EUR fallback ──

    @Test
    fun `different currencies produce different keys for same transaction`() {
        val date = 1_000_000L
        val eurKey = Expense.generateDedupeKey(50.0, "shop", date, "EUR")
        val usdKey = Expense.generateDedupeKey(50.0, "shop", date, "USD")
        val gbpKey = Expense.generateDedupeKey(50.0, "shop", date, "GBP")

        assertNotEquals("EUR and USD keys must differ", eurKey, usdKey)
        assertNotEquals("EUR and GBP keys must differ", eurKey, gbpKey)
        assertNotEquals("USD and GBP keys must differ", usdKey, gbpKey)
    }

    @Test
    fun `key includes currency suffix`() {
        val key = Expense.generateDedupeKey(10.0, "shop", 1_000_000L, "USD")
        assertTrue("Key must contain the currency code", key.endsWith("_USD"))
    }

    @Test
    fun `currency is case-normalized to uppercase in key`() {
        val lowerKey = Expense.generateDedupeKey(10.0, "shop", 1_000_000L, "eur")
        val upperKey = Expense.generateDedupeKey(10.0, "shop", 1_000_000L, "EUR")
        assertEquals("Currency must be normalized to uppercase", lowerKey, upperKey)
    }
}
