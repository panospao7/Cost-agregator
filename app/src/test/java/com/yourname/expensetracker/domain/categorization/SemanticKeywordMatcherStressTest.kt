package com.yourname.expensetracker.domain.categorization

import org.junit.Assert.*
import org.junit.Test

class SemanticKeywordMatcherStressTest {
    private val matcher = SemanticKeywordMatcher(GreeklishNormalizer())

    @Test
    fun `finds food-related semantic match`() {
        val best = matcher.findBestMatch("Coffee house payment")
        assertNotNull(best)
        assertEquals("Food", best?.categoryName)
    }

    @Test
    fun `keyword matching is case-insensitive`() {
        val a = matcher.findBestMatch("PIZZA HOUSE")
        val b = matcher.findBestMatch("pizza house")
        assertEquals(a?.categoryName, b?.categoryName)
    }

    @Test
    fun `supports greeklish-normalized text`() {
        val best = matcher.findBestMatch("kafes freddo")
        assertNotNull(best)
        assertTrue(best!!.confidence >= 0.5)
    }

    @Test
    fun `single keyword confidence remains within range`() {
        val matches = matcher.match("coffee")
        assertTrue(matches.isNotEmpty())
        assertTrue(matches.first().confidence in 0.0..1.0)
    }

    @Test
    fun `multiple keyword text yields high confidence candidate`() {
        val best = matcher.findBestMatch("espresso coffee house")
        assertNotNull(best)
        assertTrue(best!!.confidence > 0.7)
    }

    @Test
    fun `matching is deterministic`() {
        val a = matcher.match("gas station shell")
        val b = matcher.match("gas station shell")
        assertEquals(a, b)
    }
}
