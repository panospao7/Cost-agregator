package com.yourname.expensetracker.domain.categorization

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryKeywordsTest {

    @Test
    fun `getAllKeywords returns categories`() {
        val keywords = CategoryKeywords.getAllKeywords()
        assertTrue(keywords.containsKey("Food"))
        assertTrue(keywords.containsKey("Groceries"))
        assertTrue(keywords.containsKey("Transport"))
        assertTrue(keywords.containsKey("Shopping"))
        assertTrue(keywords.containsKey("Utilities"))
    }

    @Test
    fun `food keywords contain pizza`() {
        val foodKeywords = CategoryKeywords.getKeywordsForCategory("Food")
        assertTrue(foodKeywords.containsKey("pizza"))
    }

    @Test
    fun `groceries keywords contain sklavenitis`() {
        val groceriesKeywords = CategoryKeywords.getKeywordsForCategory("Groceries")
        assertTrue(groceriesKeywords.containsKey("sklavenitis"))
    }

    @Test
    fun `keywords have confidence values between 0 and 1`() {
        val allKeywords = CategoryKeywords.getAllKeywords()
        allKeywords.forEach { (category, keywords) ->
            keywords.forEach { (keyword, confidence) ->
                assertTrue(
                    "Category $category keyword $keyword has invalid confidence",
                    confidence in 0.0..1.0
                )
            }
        }
    }

    @Test
    fun `getCategories returns category names`() {
        val categories = CategoryKeywords.getCategories()
        assertTrue(categories.contains("Food"))
        assertTrue(categories.contains("Transport"))
    }

    @Test
    fun `duplicate keyword keeps strongest confidence deterministically`() {
        val foodKeywords = CategoryKeywords.getKeywordsForCategory("Food")
        assertEquals(0.85, foodKeywords.getValue("roasters"), 0.0001)
    }

    @Test
    fun `equal confidence duplicate keyword resolves independent of declaration order`() {
        val normalizer = mockk<GreeklishNormalizer>()
        every { normalizer.normalize(any()) } answers { (invocation.args[0] as String).lowercase() }
        val matcher = SemanticKeywordMatcher(normalizer)

        val bestMatch = matcher.findBestMatch("pharmacy", minConfidence = 0.5)

        assertEquals("Health", bestMatch.bestMatch?.categoryName)
    }
}
