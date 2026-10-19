package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact-value tables for [StringDistanceUtils]. Expected values are hand-derived from the
 * Levenshtein / Jaro / Jaro-Winkler definitions (see per-row comments), not computed by the
 * code under test. Each table is size-guarded so rows cannot be silently dropped.
 */
class StringDistanceUtilsTest {

    private data class DistanceRow(val a: String, val b: String, val expected: Int, val why: String)

    private data class SimilarityRow(val a: String, val b: String, val expected: Double, val why: String)

    private data class FuzzyRow(
        val ocr: String?,
        val target: String,
        val maxDistance: Int,
        val expected: Boolean,
        val why: String
    )

    // Tolerance only for rows whose true value is not a representable double (e.g. 13/15).
    private val tolerance = 1e-12

    // ---------------------------------------------------------------------------------------
    // Levenshtein distance
    // ---------------------------------------------------------------------------------------

    @Test
    fun `levenshteinDistance table - exact distances`() {
        val greek = "Καλημέρα"
        val rows = listOf(
            DistanceRow("hello", "hello", 0, "identical"),
            DistanceRow("hello", "world", 4, "four substitutions; only one common subsequence char"),
            DistanceRow("", "hello", 5, "empty vs 5 chars"),
            DistanceRow("hello", "", 5, "5 chars vs empty"),
            DistanceRow("", "", 0, "both empty"),
            DistanceRow("A".repeat(1000), "B".repeat(1000), 1000, "1000 chars, no common characters"),
            DistanceRow(greek, greek, 0, "identical greek"),
            DistanceRow(greek, greek.dropLast(1), 1, "greek, last character deleted"),
            DistanceRow("Hello", "Αλφα", 5, "mixed scripts, no common chars, max length wins"),
            DistanceRow("hello", "hallo", 1, "one substitution"),
            DistanceRow("hello", "helo", 1, "one deletion"),
            DistanceRow("hello", "hxllo", 1, "one substitution mid-word"),
            DistanceRow("Starbucks Coffee", "Starbucks Coffe", 1, "single trailing deletion"),
            DistanceRow("STORE_CITY", "STORE_TOWN", 4, "common prefix stripped; CITY vs TOWN needs 4 edits")
        )
        assertEquals("levenshtein table size guard", 14, rows.size)
        for (row in rows) {
            assertEquals(
                "levenshteinDistance(${row.a.take(20)}, ${row.b.take(20)}) [${row.why}]",
                row.expected,
                StringDistanceUtils.levenshteinDistance(row.a, row.b)
            )
        }
    }

    @Test
    fun `levenshteinDistance - long strings with differing suffixes are bounded by substitutions`() {
        val s1 = "MERCHANT_NAME_STORE_LOCATION_CITY".repeat(20)
        val s2 = s1.replace("CITY", "TOWN")

        val result = StringDistanceUtils.levenshteinDistance(s1, s2)

        // 20 occurrences x 4 substituted characters is an upper bound; strings differ so > 0.
        assertTrue("distance must be in 1..80 but was $result", result in 1..80)
    }

    // ---------------------------------------------------------------------------------------
    // Similarity measures
    // ---------------------------------------------------------------------------------------

    @Test
    fun `levenshteinSimilarity table - exact ratios`() {
        val rows = listOf(
            SimilarityRow("", "", 1.0, "both empty is identical"),
            SimilarityRow("", "abc", 0.0, "empty vs 3 chars: 1 - 3/3"),
            SimilarityRow("Starbucks Coffee", "Starbucks Coffe", 0.9375, "1 - 1/16")
        )
        assertEquals("levenshteinSimilarity table size guard", 3, rows.size)
        for (row in rows) {
            assertEquals(
                "levenshteinSimilarity(${row.a}, ${row.b}) [${row.why}]",
                row.expected,
                StringDistanceUtils.levenshteinSimilarity(row.a, row.b),
                0.0
            )
        }
    }

    @Test
    fun `jaroSimilarity table - exact values`() {
        val rows = listOf(
            SimilarityRow("hello", "hello", 1.0, "identical"),
            SimilarityRow("", "hello", 0.0, "first empty"),
            SimilarityRow("hello", "", 0.0, "second empty"),
            SimilarityRow("", "", 1.0, "both empty identical"),
            SimilarityRow("abc", "xyz", 0.0, "no matches"),
            // matches=4 (h,l,l,o), transpositions=0: (4/5 + 4/5 + 4/4) / 3 = 13/15
            SimilarityRow("hello", "hallo", 13.0 / 15.0, "4 matches of 5, no transpositions")
        )
        assertEquals("jaroSimilarity table size guard", 6, rows.size)
        for (row in rows) {
            assertEquals(
                "jaroSimilarity(${row.a}, ${row.b}) [${row.why}]",
                row.expected,
                StringDistanceUtils.jaroSimilarity(row.a, row.b),
                tolerance
            )
        }
    }

    @Test
    fun `jaroWinklerSimilarity table - prefix bonus and threshold`() {
        class JwRow(val a: String, val b: String, val weight: Double?, val expected: Double, val why: String)
        val rows = listOf(
            // jaro=13/15, prefix=1 ('h'), default weight 0.1: 13/15 + 0.1 * (2/15) = 0.88
            JwRow("hello", "hallo", null, 0.88, "default weight 0.1, prefix length 1"),
            // 13/15 + 0.25 * (2/15) = 0.9
            JwRow("hello", "hallo", 0.25, 0.9, "custom weight 0.25 changes the bonus"),
            // jaro below 0.7 threshold: no prefix bonus, equals jaro (0.0)
            JwRow("abc", "xyz", null, 0.0, "below 0.7 threshold returns jaro unchanged")
        )
        assertEquals("jaroWinkler table size guard", 3, rows.size)
        for (row in rows) {
            val actual = if (row.weight == null) {
                StringDistanceUtils.jaroWinklerSimilarity(row.a, row.b)
            } else {
                StringDistanceUtils.jaroWinklerSimilarity(row.a, row.b, row.weight)
            }
            assertEquals("jaroWinkler(${row.a}, ${row.b}, ${row.weight}) [${row.why}]", row.expected, actual, tolerance)
        }
    }

    @Test
    fun `combinedSimilarity table - exact blend of jaroWinkler and levenshtein`() {
        val rows = listOf(
            // 0.7 * 0.88 + 0.3 * (1 - 1/5) = 0.616 + 0.24
            SimilarityRow("hello", "hallo", 0.856, "one substitution"),
            SimilarityRow("hello", "hello", 1.0, "identical strings")
        )
        assertEquals("combinedSimilarity table size guard", 2, rows.size)
        for (row in rows) {
            assertEquals(
                "combinedSimilarity(${row.a}, ${row.b}) [${row.why}]",
                row.expected,
                StringDistanceUtils.combinedSimilarity(row.a, row.b),
                tolerance
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Fuzzy matching
    // ---------------------------------------------------------------------------------------

    @Test
    fun `isFuzzyMatch table - match and non-match decisions`() {
        val rows = listOf(
            FuzzyRow("hello", "hello", 2, true, "identical"),
            FuzzyRow("I bought at McDonalds", "McDonalds", 2, true, "target contained in text"),
            FuzzyRow(null, "hello", 2, false, "null input never matches"),
            FuzzyRow("McDonld", "McDonald", 2, true, "distance 1 within 2"),
            FuzzyRow("McDonal", "McDonald", 2, true, "distance 1 within 2"),
            FuzzyRow("McDnl", "McDonald", 2, false, "distance 3 beyond 2"),
            FuzzyRow("McDnl", "McDonald", 3, true, "distance 3 at boundary 3"),
            FuzzyRow("McDonld", "McDonald", 0, false, "distance 1 beyond 0"),
            FuzzyRow("Starbucks", "McDonalds", 2, false, "distinct merchants"),
            FuzzyRow("Starbuck Coffee", "Starbucks Coffee", 2, true, "distance 1 within 2"),
            FuzzyRow("McDonalds", "McDonald's", 2, true, "apostrophe stripped, distance 0"),
            FuzzyRow("McDonald", "McDonald's", 2, true, "apostrophe stripped, distance 1"),
            FuzzyRow("MCDONALD", "McDonald's", 2, true, "case-insensitive, distance 1"),
            FuzzyRow("McDonld", "McDonald's", 2, true, "apostrophe stripped, distance 2"),
            FuzzyRow("McDonald's @Store!", "McDonald", 2, true, "target contained despite special characters"),
            FuzzyRow("MCDONALD", "McDonald", 2, true, "case-insensitive contains (upper)"),
            FuzzyRow("mcdonald", "McDonald", 2, true, "case-insensitive contains (lower)"),
            FuzzyRow("Καφές", "Καφές", 2, true, "identical greek"),
            FuzzyRow("Store123", "Store123", 2, true, "identical with digits"),
            FuzzyRow("Store12", "Store123", 1, true, "distance 1 at boundary 1")
        )
        assertEquals("isFuzzyMatch table size guard", 20, rows.size)
        for (row in rows) {
            assertEquals(
                "isFuzzyMatch(${row.ocr}, ${row.target}, ${row.maxDistance}) [${row.why}]",
                row.expected,
                StringDistanceUtils.isFuzzyMatch(row.ocr, row.target, row.maxDistance)
            )
        }
    }

    @Test
    fun `isFuzzyMatch - text with trailing emoji still matches target contained in it`() {
        val result = StringDistanceUtils.isFuzzyMatch("hello\uD83D\uDC4B", "hello")
        assertTrue("Text with trailing emoji should match contained target: $result", result == true)
    }
}
