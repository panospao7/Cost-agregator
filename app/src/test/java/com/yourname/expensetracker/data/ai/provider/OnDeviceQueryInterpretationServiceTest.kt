package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceQueryInterpretationServiceTest {

    private val service = OnDeviceQueryInterpretationService()

    private val sampleInput = FinancialQueryInterpretationInput(
        rawQuery = "top merchants this month",
        currentTimeMs = 1_000L,
        localeTag = "en-US",
        categoryNames = listOf("Groceries", "Transport"),
        merchantNames = listOf("Lidl", "Uber"),
        merchantLookupMap = mapOf("Lidl" to "Lidl", "Uber" to "Uber"),
        categoryLookupMap = mapOf("Groceries" to 1L, "Transport" to 2L),
        categoryNameToIdMap = mapOf("Groceries" to 1L, "Transport" to 2L)
    )

    @Test
    fun `buildPrompt includes raw query and known dimensions`() {
        val prompt = service.buildPrompt(sampleInput)

        assertTrue(prompt.contains("top merchants this month"))
        assertTrue(prompt.contains("Groceries"))
        assertTrue(prompt.contains("Lidl"))
        assertTrue(prompt.contains("structured|clarification|unsupported"))
    }

    @Test
    fun `buildPrompt uses alias only lookup keys when redacted`() {
        val input = sampleInput.copy(
            rawQuery = "merchant_a category_a",
            categoryNames = listOf("category_a"),
            merchantNames = listOf("merchant_a"),
            merchantLookupMap = mapOf("merchant_a" to "Lidl", "Lidl" to "Lidl"),
            merchantAliasMap = mapOf("merchant_a" to "Lidl"),
            categoryLookupMap = mapOf("category_a" to 10L, "Groceries" to 10L),
            categoryAliasMap = mapOf("category_a" to "Groceries"),
            categoryNameToIdMap = mapOf("category_a" to 10L, "Groceries" to 10L)
        )

        val prompt = service.buildPrompt(input)

        assertTrue(prompt.contains("Known category lookup keys: category_a"))
        assertTrue(prompt.contains("Known merchant lookup keys: merchant_a"))
        assertTrue(!prompt.contains("Known category lookup keys: Groceries"))
        assertTrue(!prompt.contains("Known merchant lookup keys: Lidl"))
    }

    @Test
    fun `parseResponse handles structured result`() {
        val result = service.parseResponse(
            sampleInput,
            """{"kind":"structured","intent":{"metric":"TOTAL","grouping":"MERCHANT","comparison":"NONE","answerMode":"BOTH","ownership":"ALL","merchantNames":["Lidl"],"minAmount":null,"maxAmount":null}}"""
        )

        assertTrue(result is FinancialQueryInterpretationResult.Structured)
        val structured = result as FinancialQueryInterpretationResult.Structured
        assertEquals(QueryMetric.TOTAL, structured.intent.metric)
        assertEquals(QueryGrouping.MERCHANT, structured.intent.grouping)
        assertEquals(setOf("Lidl"), structured.intent.filters.merchants)
    }

    @Test
    fun `parseResponse handles clarification result`() {
        val result = service.parseResponse(
            sampleInput,
            """{"kind":"clarification","clarification":{"prompt":"Do you want a total or a list?","options":["Total","List"]}}"""
        )

        assertTrue(result is FinancialQueryInterpretationResult.Clarification)
        val clarification = result as FinancialQueryInterpretationResult.Clarification
        assertEquals("Do you want a total or a list?", clarification.prompt)
        assertEquals(listOf("Total", "List"), clarification.options)
    }

    @Test
    fun `parseResponse handles unsupported result`() {
        val result = service.parseResponse(
            sampleInput,
            """{"kind":"unsupported","unsupportedReason":"This query is not supported."}"""
        )

        assertTrue(result is FinancialQueryInterpretationResult.Unsupported)
    }

    @Test
    fun `parseResponse resolves redacted aliases and multi value filters`() {
        val input = sampleInput.copy(
            categoryNames = listOf("category_a", "category_b"),
            merchantNames = listOf("merchant_a", "merchant_b"),
            merchantLookupMap = mapOf("merchant_a" to "Lidl", "merchant_b" to "Uber"),
            merchantAliasMap = mapOf("merchant_a" to "Lidl", "merchant_b" to "Uber"),
            categoryLookupMap = mapOf(
                "category_a" to 10L,
                "category_b" to 11L,
                "Groceries" to 10L,
                "Transport" to 11L
            ),
            categoryAliasMap = mapOf("category_a" to "Groceries", "category_b" to "Transport"),
            categoryNameToIdMap = mapOf("category_a" to 10L, "category_b" to 11L)
        )

        val result = service.parseResponse(
            input,
            """{"kind":"structured","intent":{"metric":"TOTAL","grouping":"MERCHANT","comparison":"NONE","answerMode":"BOTH","ownership":"ALL","categoryNames":["category_a","category_b"],"merchantNames":["merchant_a","merchant_b"],"transactionTypes":["PURCHASE","DEPOSIT"],"periodKeyword":"THIS_MONTH"}}"""
        )

        assertTrue(result is FinancialQueryInterpretationResult.Structured)
        val structured = result as FinancialQueryInterpretationResult.Structured
        assertEquals(setOf(10L, 11L), structured.intent.filters.categoryIds)
        assertEquals(setOf("Lidl", "Uber"), structured.intent.filters.merchants)
        assertEquals(2, structured.intent.filters.transactionTypes.size)
        assertNotNull(structured.intent.filters.period)
    }

    @Test
    fun `parseResponse honors explicit period payload`() {
        val result = service.parseResponse(
            sampleInput,
            """{"kind":"structured","intent":{"metric":"TOTAL","grouping":"NONE","comparison":"NONE","answerMode":"BOTH","ownership":"ALL","period":{"startMs":123,"endMs":456}}}"""
        )

        assertTrue(result is FinancialQueryInterpretationResult.Structured)
        val structured = result as FinancialQueryInterpretationResult.Structured
        assertEquals(123L, structured.intent.filters.period?.start)
        assertEquals(456L, structured.intent.filters.period?.end)
    }
}
