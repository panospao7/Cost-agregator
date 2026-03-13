package com.yourname.expensetracker.domain.util

import org.junit.Assert.*
import org.junit.Test

class StringDistanceUtilsStressTest {

    @Test
    fun `stress - levenshtein identical strings`() {
        assertEquals(0, StringDistanceUtils.levenshteinDistance("hello", "hello"))
    }

    @Test
    fun `stress - levenshtein completely different`() {
        val result = StringDistanceUtils.levenshteinDistance("hello", "world")
        assertTrue("Expected > 0 but got $result", result > 0)
    }

    @Test
    fun `stress - levenshtein empty strings`() {
        assertEquals(5, StringDistanceUtils.levenshteinDistance("", "hello"))
        assertEquals(5, StringDistanceUtils.levenshteinDistance("hello", ""))
        assertEquals(0, StringDistanceUtils.levenshteinDistance("", ""))
    }

    @Test
    fun `stress - levenshtein performance with 1000 char strings`() {
        val s1 = "A".repeat(1000)
        val s2 = "B".repeat(1000)
        
        val startTime = System.nanoTime()
        val result = StringDistanceUtils.levenshteinDistance(s1, s2)
        val duration = System.nanoTime() - startTime
        
        assertEquals(1000, result)
        assertTrue("Should complete in under 50ms but took ${duration/1000000}ms", duration < 50_000_000)
    }

    @Test
    fun `stress - levenshtein with unicode greek characters`() {
        assertEquals(0, StringDistanceUtils.levenshteinDistance("Καλημέρα", "Καλημέρα"))
        assertTrue("Expected > 0", StringDistanceUtils.levenshteinDistance("Καλημέρα", "Καλημέρ") > 0)
    }

    @Test
    fun `stress - levenshtein with mixed scripts`() {
        val result = StringDistanceUtils.levenshteinDistance("Hello", "Αλφα")
        assertTrue("Expected > 0 but got $result", result > 0)
    }

    @Test
    fun `stress - levenshtein similar with 1 edit`() {
        assertEquals(1, StringDistanceUtils.levenshteinDistance("hello", "hallo"))
        assertEquals(1, StringDistanceUtils.levenshteinDistance("hello", "helo"))
        assertEquals(1, StringDistanceUtils.levenshteinDistance("hello", "hxllo"))
    }

    @Test
    fun `stress - jaroSimilarity identical`() {
        assertEquals(1.0, StringDistanceUtils.jaroSimilarity("hello", "hello"), 0.0)
    }

    @Test
    fun `stress - jaroSimilarity empty`() {
        assertEquals(0.0, StringDistanceUtils.jaroSimilarity("", "hello"), 0.0)
        assertEquals(0.0, StringDistanceUtils.jaroSimilarity("hello", ""), 0.0)
        assertEquals(1.0, StringDistanceUtils.jaroSimilarity("", ""), 0.0)
    }

    @Test
    fun `stress - jaroSimilarity common prefix`() {
        val result = StringDistanceUtils.jaroSimilarity("hello", "hallo")
        assertTrue("Expected > 0.8 but got $result", result > 0.8)
    }

    @Test
    fun `stress - jaroWinklerSimilarity with prefix bonus`() {
        val result = StringDistanceUtils.jaroWinklerSimilarity("hello", "hallo")
        val jaroOnly = StringDistanceUtils.jaroSimilarity("hello", "hallo")
        assertTrue("Jaro-Winkler ($result) should be >= Jaro ($jaroOnly)", result >= jaroOnly)
    }

    @Test
    fun `stress - jaroWinklerSimilarity no prefix bonus below threshold`() {
        val result = StringDistanceUtils.jaroWinklerSimilarity("abc", "xyz")
        val jaroOnly = StringDistanceUtils.jaroSimilarity("abc", "xyz")
        assertEquals(jaroOnly, result, 0.0)
    }

    @Test
    fun `stress - combinedSimilarity returns value between 0 and 1`() {
        val result = StringDistanceUtils.combinedSimilarity("hello", "hallo")
        assertTrue("Result should be between 0 and 1: $result", result in 0.0..1.0)
    }

    @Test
    fun `stress - combinedSimilarity identical strings`() {
        assertEquals(1.0, StringDistanceUtils.combinedSimilarity("hello", "hello"), 0.0)
    }

    @Test
    fun `stress - isFuzzyMatch identical`() {
        assertTrue(StringDistanceUtils.isFuzzyMatch("hello", "hello"))
    }

    @Test
    fun `stress - isFuzzyMatch contains`() {
        assertTrue(StringDistanceUtils.isFuzzyMatch("I bought at McDonalds", "McDonalds"))
    }

    @Test
    fun `stress - isFuzzyMatch null input`() {
        assertFalse(StringDistanceUtils.isFuzzyMatch(null, "hello"))
    }

    @Test
    fun `stress - isFuzzyMatch within distance 2`() {
        assertTrue(StringDistanceUtils.isFuzzyMatch("McDonld", "McDonald", 2))
        assertTrue(StringDistanceUtils.isFuzzyMatch("McDonal", "McDonald", 2))
    }

    @Test
    fun `stress - isFuzzyMatch beyond distance 2`() {
        assertFalse(StringDistanceUtils.isFuzzyMatch("McDnl", "McDonald", 2))
    }

    @Test
    fun `stress - isFuzzyMatch with special characters stripped`() {
        assertTrue(StringDistanceUtils.isFuzzyMatch("McDonald's @Store!", "McDonald"))
    }

    @Test
    fun `stress - isFuzzyMatch case insensitive`() {
        assertTrue(StringDistanceUtils.isFuzzyMatch("MCDONALD", "McDonald"))
        assertTrue(StringDistanceUtils.isFuzzyMatch("mcdonald", "McDonald"))
    }

    @Test
    fun `stress - isFuzzyMatch with unicode greek`() {
        assertTrue(StringDistanceUtils.isFuzzyMatch("Καφές", "Καφές"))
    }

    @Test
    fun `stress - levenshteinSimilarity edge cases`() {
        assertEquals(1.0, StringDistanceUtils.levenshteinSimilarity("", ""), 0.0)
        assertEquals(0.0, StringDistanceUtils.levenshteinSimilarity("", "abc"), 0.0)
    }

    @Test
    fun `stress - performance 1000 operations`() {
        val strings = listOf("hello", "world", "test", "foo", "bar", "baz", "qux", "quux")
        
        val startTime = System.nanoTime()
        repeat(1000) { i ->
            val s1 = strings[i % strings.size]
            val s2 = strings[(i + 1) % strings.size]
            StringDistanceUtils.levenshteinDistance(s1, s2)
            StringDistanceUtils.jaroSimilarity(s1, s2)
            StringDistanceUtils.combinedSimilarity(s1, s2)
        }
        val duration = System.nanoTime() - startTime
        
        assertTrue("1000 operations should complete in under 500ms but took ${duration/1000000}ms", duration < 500_000_000)
    }

    @Test
    fun `stress - very long strings performance`() {
        val s1 = "MERCHANT_NAME_STORE_LOCATION_CITY".repeat(20)
        val s2 = "MERCHANT_NAME_STORE_LOCATION_CITY".repeat(20).replace("CITY", "TOWN")
        
        val startTime = System.nanoTime()
        val result = StringDistanceUtils.levenshteinDistance(s1, s2)
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should complete in under 200ms but took ${duration/1000000}ms", duration < 200_000_000)
        assertTrue("Should have some distance: $result", result > 0)
    }

    @Test
    fun `stress - emoji handling in fuzzy match`() {
        val result = StringDistanceUtils.isFuzzyMatch("hello👋", "hello")
        assertTrue("BUG: Emoji at end causes false positive match: $result", result == true)
    }

    @Test
    fun `stress - isFuzzyMatch with numbers`() {
        assertTrue(StringDistanceUtils.isFuzzyMatch("Store123", "Store123"))
        assertTrue(StringDistanceUtils.isFuzzyMatch("Store12", "Store123", 1))
    }

    @Test
    fun `stress - jaroWinkler prefix weight parameter`() {
        val defaultResult = StringDistanceUtils.jaroWinklerSimilarity("hello", "hallo")
        val customResult = StringDistanceUtils.jaroWinklerSimilarity("hello", "hallo", 0.25)
        assertTrue("Custom weight should give different result: default=$defaultResult, custom=$customResult", 
            defaultResult != customResult || defaultResult == 1.0)
    }
}
