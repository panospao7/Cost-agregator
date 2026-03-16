package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceDashboardBriefingServiceTest {

    private val service = OnDeviceDashboardBriefingService()

    private val sampleInput = DashboardBriefingInput(
        dateKey = "2026-03-16",
        weatherHeadline = "Sunny",
        weatherSummary = "Stable day ahead",
        discretionaryBudget = 120.0,
        totalCommitted = 80.0,
        totalLikely = 110.0,
        pendingReviewCount = 2,
        currentMonthSpent = 430.0,
        topCategories = listOf("Groceries", "Transport"),
        budgetWarnings = listOf("Groceries at 92%"),
        upcomingItems = listOf("Rent EUR 500 on 2026-03-20")
    )

    @Test
    fun `buildPrompt includes dashboard inputs`() {
        val prompt = service.buildPrompt(sampleInput)

        assertTrue(prompt.contains("2026-03-16"))
        assertTrue(prompt.contains("Sunny"))
        assertTrue(prompt.contains("Groceries"))
        assertTrue(prompt.contains("Groceries at 92%"))
    }

    @Test
    fun `parseResponse handles clean JSON`() {
        val result = service.parseResponse(
            """{"title":"Today","text":"You are close to your grocery budget, but the rest of the day looks stable.","tone":"cautious","confidence":0.8}"""
        )

        assertNotNull(result)
        assertEquals("Today", result!!.title)
        assertEquals("cautious", result.tone)
        assertEquals(0.8f, result.confidence!!, 0.001f)
    }

    @Test
    fun `parseResponse handles markdown fenced JSON`() {
        val result = service.parseResponse(
            """
            ```json
            {"title":"Today","text":"Brief text","tone":"neutral","confidence":0.5}
            ```
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals("Brief text", result!!.text)
    }

    @Test
    fun `parseResponse returns null for invalid text`() {
        assertNull(service.parseResponse("not json"))
    }
}
