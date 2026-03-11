package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MerchantKeyGenerator].
 *
 * MerchantKeyGenerator is the single source of truth for canonical merchant
 * identity across the entire app.  A bug here silently corrupts grouping,
 * deduplication, and analytics, so we test every edge-case of the algorithm:
 *
 *   1. Greek → Latin transliteration (diphthong-aware)
 *   2. Latin input pass-through
 *   3. Case-folding to lowercase
 *   4. Special-character stripping (only [a-z0-9] retained)
 *   5. Empty / blank input
 *   6. Idempotency
 */
class MerchantKeyGeneratorTest {

    // ── Greek input ──────────────────────────────────────────────────────────

    @Test
    fun `Greek merchant name transliterates to correct Latin key`() {
        // "Σκλαβενίτης" is a common Greek supermarket chain
        val key = MerchantKeyGenerator.generate("Σκλαβενίτης")
        assertEquals("sklavenitis", key)
    }

    @Test
    fun `Greek diphthong mp transliterates to b`() {
        // "μπ" must map to "b" (not "m" + "p") — diphthong-first ordering
        val key = MerchantKeyGenerator.generate("Μπάρμπα Σταθης")
        // μπ→b, άρ→ar, μπ→b, α→a, space stripped, Σ→S→s, τ→t, α→a, θ→th, η→i, ς→s
        assertTrue("key should start with 'b' (μπ→b)", key.startsWith("b"))
        assertTrue("key should contain 'b' for second μπ", key.indexOf("b", 1) > 0)
        // No spaces or special chars
        assertTrue("key should be alphanumeric only", key.all { it.isLetterOrDigit() })
    }

    @Test
    fun `Greek input with accents strips diacritics correctly`() {
        // "Ελλάδα" — accent on alpha should be stripped
        val key = MerchantKeyGenerator.generate("Ελλάδα")
        assertEquals("ellada", key)
    }

    @Test
    fun `Greek and variant Latin spellings of same merchant produce identical key`() {
        // The canonical Greek "Σκλαβενίτης" vs a Latin approximation
        val greekKey = MerchantKeyGenerator.generate("Σκλαβενίτης")
        val latinKey = MerchantKeyGenerator.generate("Sklavenitis")
        assertEquals(
            "Greek and equivalent Latin spelling must produce the same key",
            greekKey, latinKey
        )
    }

    // ── Latin input ──────────────────────────────────────────────────────────

    @Test
    fun `Latin merchant name with apostrophe strips special char`() {
        val key = MerchantKeyGenerator.generate("McDonald's")
        assertEquals("mcdonalds", key)
    }

    @Test
    fun `Uppercase Latin is lowercased`() {
        val key = MerchantKeyGenerator.generate("LIDL")
        assertEquals("lidl", key)
    }

    @Test
    fun `Accented Latin chars are normalised to plain ASCII`() {
        // "Café" — é should become e via NFD decomposition + diacritic strip
        val key = MerchantKeyGenerator.generate("Café")
        assertEquals("cafe", key)
    }

    @Test
    fun `Mixed Greek and Latin merchant name`() {
        val key = MerchantKeyGenerator.generate("LIDL Ελλάδα")
        assertEquals("lidlellada", key)
    }

    // ── Special chars / numerics ─────────────────────────────────────────────

    @Test
    fun `Numeric characters are preserved`() {
        val key = MerchantKeyGenerator.generate("7-Eleven")
        assertEquals("7eleven", key)
    }

    @Test
    fun `Special characters and spaces are stripped`() {
        val key = MerchantKeyGenerator.generate("Shop @ #1 & Co.")
        // Only alphanumerics remain: "shop1co"
        assertTrue("Key must be non-empty", key.isNotEmpty())
        assertTrue("Key must contain only a-z0-9", key.matches(Regex("[a-z0-9]+")))
    }

    // ── Empty / blank input ──────────────────────────────────────────────────

    @Test
    fun `Empty string returns empty key`() {
        assertEquals("", MerchantKeyGenerator.generate(""))
    }

    @Test
    fun `Blank whitespace-only string returns empty key`() {
        assertEquals("", MerchantKeyGenerator.generate("   "))
    }

    // ── Case insensitivity ───────────────────────────────────────────────────

    @Test
    fun `Same merchant in different cases produces same key`() {
        val lower = MerchantKeyGenerator.generate("coffee shop")
        val upper = MerchantKeyGenerator.generate("COFFEE SHOP")
        val mixed = MerchantKeyGenerator.generate("Coffee Shop")
        assertEquals(lower, upper)
        assertEquals(lower, mixed)
    }

    // ── Idempotency ──────────────────────────────────────────────────────────

    @Test
    fun `generate is idempotent - applying twice gives same result as once`() {
        // If the output (already ASCII lowercase alphanumeric) is fed back in,
        // the result must not change.
        val first = MerchantKeyGenerator.generate("Σκλαβενίτης")
        val second = MerchantKeyGenerator.generate(first)
        assertEquals(
            "generate(generate(x)) must equal generate(x)",
            first, second
        )
    }

    @Test
    fun `generate is idempotent for Latin merchant`() {
        val first = MerchantKeyGenerator.generate("McDonald's")
        val second = MerchantKeyGenerator.generate(first)
        assertEquals(first, second)
    }

    // ── Distinctness ─────────────────────────────────────────────────────────

    @Test
    fun `Different merchants produce different keys`() {
        val key1 = MerchantKeyGenerator.generate("LIDL")
        val key2 = MerchantKeyGenerator.generate("Carrefour")
        assertNotEquals(key1, key2)
    }
}
