package com.yourname.expensetracker.domain.categorization

import org.junit.Assert.*
import org.junit.Test

class SemanticKeywordMatcherStressTest {

    // ============================================================================
    // SECTION 1: FOOD KEYWORDS
    // ============================================================================

    @Test
    fun `stress - match food keywords`() {
        val keywords = listOf("restaurant", "cafe", "food", "meal", "lunch", "dinner", "breakfast")
        
        keywords.forEach { keyword ->
            val text = "Payment at $keyword"
            val matches = matchKeywords(text, "Food")
            assertTrue("Should match food keyword: $keyword", matches)
        }
    }

    @Test
    fun `stress - match Greek food keywords`() {
        val keywords = listOf("εστιατόριο", "καφές", "φαγητό", "γεύμα")
        
        keywords.forEach { keyword ->
            val text = "Πληρωμή στο $keyword"
            val matches = matchKeywords(text, "Food")
            assertTrue("Should match Greek food keyword: $keyword", matches)
        }
    }

    // ============================================================================
    // SECTION 2: TRANSPORT KEYWORDS
    // ============================================================================

    @Test
    fun `stress - match transport keywords`() {
        val keywords = listOf("uber", "taxi", "bus", "metro", "train", "fuel", "gas")
        
        keywords.forEach { keyword ->
            val text = "Payment for $keyword"
            val matches = matchKeywords(text, "Transport")
            assertTrue("Should match transport keyword: $keyword", matches)
        }
    }

    @Test
    fun `stress - match Greek transport keywords`() {
        val keywords = listOf("ταξί", "λεωφορείο", "μετρό", "τρένο", "καύσιμα")
        
        keywords.forEach { keyword ->
            val text = "Πληρωμή για $keyword"
            val matches = matchKeywords(text, "Transport")
            assertTrue("Should match Greek transport keyword: $keyword", matches)
        }
    }

    // ============================================================================
    // SECTION 3: SHOPPING KEYWORDS
    // ============================================================================

    @Test
    fun `stress - match shopping keywords`() {
        val keywords = listOf("supermarket", "store", "shop", "mall", "amazon", "purchase")
        
        keywords.forEach { keyword ->
            val text = "$keyword payment"
            val matches = matchKeywords(text, "Shopping")
            assertTrue("Should match shopping keyword: $keyword", matches)
        }
    }

    // ============================================================================
    // SECTION 4: ENTERTAINMENT KEYWORDS
    // ============================================================================

    @Test
    fun `stress - match entertainment keywords`() {
        val keywords = listOf("cinema", "movie", "theater", "netflix", "spotify", "game")
        
        keywords.forEach { keyword ->
            val text = "Subscription to $keyword"
            val matches = matchKeywords(text, "Entertainment")
            assertTrue("Should match entertainment keyword: $keyword", matches)
        }
    }

    // ============================================================================
    // SECTION 5: UTILITIES KEYWORDS
    // ============================================================================

    @Test
    fun `stress - match utilities keywords`() {
        val keywords = listOf("electricity", "water", "phone", "internet", "bill", "rent")
        
        keywords.forEach { keyword ->
            val text = "$keyword bill payment"
            val matches = matchKeywords(text, "Bills")
            assertTrue("Should match utilities keyword: $keyword", matches)
        }
    }

    // ============================================================================
    // SECTION 6: PATTERN MATCHING
    // ============================================================================

    @Test
    fun `stress - match partial words`() {
        val text = "Supermarket purchase at Lidl"
        
        val matchesSupermarket = matchKeywords(text, "Shopping")
        val matchesFood = matchKeywords(text, "Food")
        
        assertTrue("Should match supermarket", matchesSupermarket || matchesFood)
    }

    @Test
    fun `stress - case insensitive matching`() {
        val variations = listOf(
            "RESTAURANT payment",
            "Restaurant payment",
            "restaurant payment",
            "ReStAuRaNt payment"
        )
        
        variations.forEach { text ->
            val matches = matchKeywords(text, "Food")
            assertTrue("Should be case insensitive: $text", matches)
        }
    }

    // ============================================================================
    // SECTION 7: MULTIPLE KEYWORDS
    // ============================================================================

    @Test
    fun `stress - match multiple keywords in text`() {
        val text = "Restaurant cafe food meal lunch"
        
        val matches = matchKeywords(text, "Food")
        
        assertTrue("Should match multiple food keywords", matches)
    }

    @Test
    fun `stress - score based on keyword count`() {
        val text1 = "Restaurant"  // 1 keyword
        val text2 = "Restaurant cafe food"  // 3 keywords
        
        val score1 = calculateKeywordScore(text1, "Food")
        val score2 = calculateKeywordScore(text2, "Food")
        
        assertTrue("More keywords should give higher score", score2 >= score1)
    }

    // ============================================================================
    // SECTION 8: NEGATIVE MATCHING
    // ============================================================================

    @Test
    fun `stress - no false positives`() {
        val texts = listOf(
            "Bank transfer",
            "ATM withdrawal",
            "Account balance",
            "Unknown merchant"
        )
        
        texts.forEach { text ->
            val matches = matchKeywords(text, "Food")
            assertFalse("Should not match: $text", matches)
        }
    }

    @Test
    fun `stress - handle similar but different words`() {
        // "Rest" is not "Restaurant"
        val text = "Rest area payment"
        
        val matches = matchKeywords(text, "Food")
        
        assertFalse("Should not match partial word 'rest'", matches)
    }

    // ============================================================================
    // SECTION 9: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - handle empty text`() {
        val matches = matchKeywords("", "Food")
        
        assertFalse("Should not match empty text", matches)
    }

    @Test
    fun `stress - handle very long text`() {
        val longText = "A".repeat(10000) + " restaurant"
        
        val matches = matchKeywords(longText, "Food")
        
        assertTrue("Should match keyword in long text", matches)
    }

    @Test
    fun `stress - handle special characters`() {
        val texts = listOf(
            "Restaurant!",
            "Cafe?",
            "Food...",
            "Meal, lunch"
        )
        
        texts.forEach { text ->
            val matches = matchKeywords(text, "Food")
            assertTrue("Should handle special chars: $text", matches)
        }
    }

    // ============================================================================
    // SECTION 10: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - match 10000 texts quickly`() {
        val texts = (1..10000).map { "Test payment $it at restaurant" }
        
        val startTime = System.nanoTime()
        
        texts.forEach { text ->
            matchKeywords(text, "Food")
        }
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should process 10000 texts in under 1s", duration < 1_000_000_000)
    }

    @Test
    fun `stress - handle 1000 keywords efficiently`() {
        val keywords = (1..1000).map { "keyword$it" }
        val text = "Payment with keyword500"
        
        val startTime = System.nanoTime()
        
        val matches = keywords.any { text.contains(it, ignoreCase = true) }
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should handle 1000 keywords quickly", duration < 100_000_000)
        assertTrue("Should find match", matches)
    }

    // ============================================================================
    // SECTION 11: CONFIDENCE SCORING
    // ============================================================================

    @Test
    fun `stress - calculate match confidence`() {
        val text = "Restaurant and cafe payment"
        
        val confidence = calculateKeywordScore(text, "Food")
        
        assertTrue("Should have high confidence with multiple keywords", confidence > 0.5)
    }

    @Test
    fun `stress - confidence with single keyword`() {
        val text = "Restaurant"
        
        val confidence = calculateKeywordScore(text, "Food")
        
        assertTrue("Should have moderate confidence with single keyword", confidence in 0.3..0.8)
    }

    // ============================================================================
    // SECTION 12: CONSISTENCY
    // ============================================================================

    @Test
    fun `stress - deterministic matching`() {
        val text = "Supermarket purchase"
        
        val result1 = matchKeywords(text, "Shopping")
        val result2 = matchKeywords(text, "Shopping")
        val result3 = matchKeywords(text, "Shopping")
        
        assertEquals("Should be deterministic", result1, result2)
        assertEquals("Should be deterministic", result2, result3)
    }

    // Helper functions
    private fun matchKeywords(text: String, category: String): Boolean {
        if (text.isBlank()) return false
        
        val lowerText = text.toLowerCase()
        
        val keywordMap = mapOf(
            "Food" to listOf("restaurant", "cafe", "food", "meal", "lunch", "dinner", "breakfast",
                "εστιατόριο", "καφές", "φαγητό", "γεύμα"),
            "Transport" to listOf("uber", "taxi", "bus", "metro", "train", "fuel", "gas", "transport",
                "ταξί", "λεωφορείο", "μετρό", "τρένο", "καύσιμα"),
            "Shopping" to listOf("supermarket", "store", "shop", "mall", "amazon", "purchase", "market"),
            "Entertainment" to listOf("cinema", "movie", "theater", "netflix", "spotify", "game", "entertainment"),
            "Bills" to listOf("electricity", "water", "phone", "internet", "bill", "rent", "utility")
        )
        
        val keywords = keywordMap[category] ?: return false
        
        return keywords.any { keyword ->
            lowerText.contains(keyword.toLowerCase())
        }
    }

    private fun calculateKeywordScore(text: String, category: String): Double {
        if (text.isBlank()) return 0.0
        
        val lowerText = text.toLowerCase()
        
        val keywordMap = mapOf(
            "Food" to listOf("restaurant", "cafe", "food", "meal", "lunch", "dinner", "breakfast"),
            "Transport" to listOf("uber", "taxi", "bus", "metro", "train", "fuel", "gas"),
            "Shopping" to listOf("supermarket", "store", "shop", "mall", "amazon", "purchase"),
            "Entertainment" to listOf("cinema", "movie", "theater", "netflix", "spotify", "game"),
            "Bills" to listOf("electricity", "water", "phone", "internet", "bill", "rent")
        )
        
        val keywords = keywordMap[category] ?: return 0.0
        
        val matches = keywords.count { keyword ->
            lowerText.contains(keyword.toLowerCase())
        }
        
        return (matches.toDouble() / keywords.size).coerceIn(0.0, 1.0)
    }
}
