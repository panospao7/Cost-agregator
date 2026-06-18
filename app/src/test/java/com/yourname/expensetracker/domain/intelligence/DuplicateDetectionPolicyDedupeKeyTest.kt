package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-6 regression: canonical dedupe-key helpers in [DuplicateDetectionPolicy]
 * must require explicit currency — no silent EUR fallback is allowed on the
 * canonical blocking path.
 *
 * These tests are pure-logic (no Android / Room deps) and exercise the policy
 * helpers directly to confirm the blocking helper signature enforces explicit
 * currency.
 */
class DuplicateDetectionPolicyDedupeKeyTest {

    private val amount   = 42.99
    private val merchant = "TestShop"
    private val date     = 1_800_000_000_000L

    // ── generateDedupeKey ────────────────────────────────────────────────

    @Test
    fun `generateDedupeKey - different currencies produce different keys`() {
        val eurKey = DuplicateDetectionPolicy.generateDedupeKey(amount, merchant, date, "EUR")
        val usdKey = DuplicateDetectionPolicy.generateDedupeKey(amount, merchant, date, "USD")
        val gbpKey = DuplicateDetectionPolicy.generateDedupeKey(amount, merchant, date, "GBP")

        assertNotEquals("EUR and USD must produce different keys", eurKey, usdKey)
        assertNotEquals("EUR and GBP must produce different keys", eurKey, gbpKey)
        assertNotEquals("USD and GBP must produce different keys", usdKey, gbpKey)
    }

    @Test
    fun `generateDedupeKey - key ends with normalized uppercase currency`() {
        val key = DuplicateDetectionPolicy.generateDedupeKey(amount, merchant, date, "usd")
        assertTrue("Key must end with normalized uppercase currency code", key.endsWith("_USD"))
    }

    @Test
    fun `generateDedupeKey - lowercase and uppercase currency produce same key`() {
        val lower = DuplicateDetectionPolicy.generateDedupeKey(amount, merchant, date, "eur")
        val upper = DuplicateDetectionPolicy.generateDedupeKey(amount, merchant, date, "EUR")
        // normalizeCurrency still trims + uppercases non-null input; blank string does
        // fall back to EUR — but the API no longer accepts null, so there is no
        // silent null-to-EUR fallback on the canonical blocking path.
        org.junit.Assert.assertEquals("lowercase and uppercase currency must produce the same key", lower, upper)
    }

    // ── generateDedupeKeyWithType ─────────────────────────────────────────

    @Test
    fun `generateDedupeKeyWithType - different currencies produce different keys for PURCHASE`() {
        val eurKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, "EUR", TransactionType.PURCHASE
        )
        val usdKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, "USD", TransactionType.PURCHASE
        )
        assertNotEquals(
            "EUR and USD PURCHASE keys must differ on the canonical blocking helper path",
            eurKey, usdKey
        )
    }

    @Test
    fun `generateDedupeKeyWithType - key ends with normalized currency before type suffix`() {
        val key = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, "gbp", TransactionType.DEPOSIT
        )
        assertTrue(
            "Type-aware key must contain normalized currency before the type suffix; got: $key",
            key.contains("_GBP_")
        )
    }

    @Test
    fun `generateDedupeKeyWithType - UNKNOWN type produces same key as type-blind helper for same explicit currency`() {
        val typeBlind  = DuplicateDetectionPolicy.generateDedupeKey(amount, merchant, date, "EUR")
        val unknownKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, "EUR", TransactionType.UNKNOWN
        )
        org.junit.Assert.assertEquals(
            "UNKNOWN type must preserve backward-compat type-blind key",
            typeBlind, unknownKey
        )
    }
}
