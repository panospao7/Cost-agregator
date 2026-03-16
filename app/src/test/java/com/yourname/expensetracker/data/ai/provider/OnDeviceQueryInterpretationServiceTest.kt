package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceQueryInterpretationServiceTest {

    private val service = OnDeviceQueryInterpretationService()

    private val sampleInput = FinancialQueryInterpretationInput(
        rawQuery = "top merchants this month",
        currentTimeMs = 1_000L,
        localeTag = "en-US",
        categoryNames = listOf("Groceries", "Transport"),
        merchantNames = listOf("Lidl", "Uber")
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
}
