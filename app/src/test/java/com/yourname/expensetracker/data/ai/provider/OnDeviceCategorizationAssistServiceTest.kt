package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceCategorizationAssistServiceTest {

    private val service = OnDeviceCategorizationAssistService()

    private val sampleInput = CategorizationAssistInput(
        targetType = AiTargetType.PENDING_REVIEW,
        targetId = 42L,
        merchant = "Lidl",
        amount = 24.5,
        currency = "EUR",
        transactionType = TransactionType.PURCHASE,
        date = null,
        currentCategoryId = null,
        deterministicMatchType = "FALLBACK",
        deterministicExplanation = "weak deterministic match",
        candidateCategories = listOf(
            CategoryOption(1L, "Groceries"),
            CategoryOption(2L, "Transport"),
            CategoryOption(3L, "Entertainment")
        ),
        supportingText = null
    )

    // -----------------------------------------------------------------------
    // Prompt building
    // -----------------------------------------------------------------------

    @Test
    fun `buildPrompt includes merchant and amount`() {
        val prompt = service.buildPrompt(sampleInput)
        assertTrue("Prompt should contain merchant", prompt.contains("Lidl"))
        assertTrue("Prompt should contain amount", prompt.contains("24.5"))
        assertTrue("Prompt should contain currency", prompt.contains("EUR"))
    }

    @Test
    fun `buildPrompt includes all candidate categories`() {
        val prompt = service.buildPrompt(sampleInput)
        assertTrue(prompt.contains("1:Groceries"))
        assertTrue(prompt.contains("2:Transport"))
        assertTrue(prompt.contains("3:Entertainment"))
    }

    @Test
    fun `buildPrompt includes supporting text when present`() {
        val input = sampleInput.copy(supportingText = "purchased weekly groceries")
        val prompt = service.buildPrompt(input)
        assertTrue(prompt.contains("purchased weekly groceries"))
    }

    @Test
    fun `buildPrompt omits context line when supportingText is null`() {
        val prompt = service.buildPrompt(sampleInput)
        assertTrue("Should not contain Context line", !prompt.contains("Context:"))
    }

    @Test
    fun `buildPrompt contains JSON schema instruction`() {
        val prompt = service.buildPrompt(sampleInput)
        assertTrue(prompt.contains("categoryId"))
        assertTrue(prompt.contains("categoryName"))
        assertTrue(prompt.contains("confidence"))
        assertTrue(prompt.contains("rationale"))
    }

    // -----------------------------------------------------------------------
    // Response parsing — valid JSON
    // -----------------------------------------------------------------------

    @Test
    fun `parseResponse handles clean JSON`() {
        val json = """
            {"categoryId":1,"categoryName":"Groceries","confidence":0.92,"rationale":"Lidl is a grocery store"}
        """.trimIndent()

        val result = service.parseResponse(json)
        assertNotNull(result)
        assertEquals(1L, result!!.categoryId)
        assertEquals("Groceries", result.categoryName)
        assertEquals(0.92f, result.confidence!!, 0.01f)
        assertEquals("Lidl is a grocery store", result.rationale)
    }

    @Test
    fun `parseResponse handles JSON with markdown fences`() {
        val response = """
            ```json
            {"categoryId":2,"categoryName":"Transport","confidence":0.85,"rationale":"bus fare"}
            ```
        """.trimIndent()

        val result = service.parseResponse(response)
        assertNotNull(result)
        assertEquals(2L, result!!.categoryId)
        assertEquals("Transport", result.categoryName)
    }

    @Test
    fun `parseResponse handles JSON with leading text`() {
        val response = """
            Based on the merchant name, I suggest:
            {"categoryId":1,"categoryName":"Groceries","confidence":0.9,"rationale":"supermarket"}
        """.trimIndent()

        val result = service.parseResponse(response)
        assertNotNull(result)
        assertEquals(1L, result!!.categoryId)
    }

    @Test
    fun `parseResponse handles alternativeCategoryIds`() {
        val json = """
            {"categoryId":1,"categoryName":"Groceries","confidence":0.8,"rationale":"food","alternativeCategoryIds":[2,3]}
        """.trimIndent()

        val result = service.parseResponse(json)
        assertNotNull(result)
        assertEquals(listOf(2L, 3L), result!!.alternativeCategoryIds)
    }

    @Test
    fun `parseResponse returns null confidence when field missing`() {
        val json = """{"categoryId":1,"categoryName":"Groceries","rationale":"store"}"""

        val result = service.parseResponse(json)
        assertNotNull(result)
        assertNull(result!!.confidence)
    }

    // -----------------------------------------------------------------------
    // Response parsing — edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `parseResponse returns null for empty string`() {
        assertNull(service.parseResponse(""))
    }

    @Test
    fun `parseResponse returns null for non-JSON text`() {
        assertNull(service.parseResponse("I don't know how to categorize this"))
    }

    @Test
    fun `parseResponse returns null when categoryId missing`() {
        val json = """{"categoryName":"Groceries","confidence":0.9}"""
        assertNull(service.parseResponse(json))
    }

    @Test
    fun `parseResponse returns null when categoryName blank`() {
        val json = """{"categoryId":1,"categoryName":"","confidence":0.9}"""
        assertNull(service.parseResponse(json))
    }

    @Test
    fun `parseResponse returns null for malformed JSON`() {
        assertNull(service.parseResponse("{categoryId: 1, broken"))
    }

    @Test
    fun `parseResponse trims whitespace from categoryName`() {
        val json = """{"categoryId":1,"categoryName":"  Groceries  ","confidence":0.9,"rationale":"test"}"""

        val result = service.parseResponse(json)
        assertNotNull(result)
        assertEquals("Groceries", result!!.categoryName)
    }

    @Test
    fun `parseResponse returns empty list when alternativeCategoryIds absent`() {
        val json = """{"categoryId":1,"categoryName":"Groceries"}"""

        val result = service.parseResponse(json)
        assertNotNull(result)
        assertEquals(emptyList<Long>(), result!!.alternativeCategoryIds)
    }
}
