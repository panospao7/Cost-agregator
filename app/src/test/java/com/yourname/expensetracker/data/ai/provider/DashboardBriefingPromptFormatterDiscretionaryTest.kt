package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DomainTextKeys
import org.junit.Assert.assertTrue
import org.junit.Test

/** DSH-N1: the prompt never prints an unavailable discretionary pool as a number. */
class DashboardBriefingPromptFormatterDiscretionaryTest {

    private val formatter = DashboardBriefingPromptFormatter()

    private fun input(headline: UiText, discretionary: Double) = DashboardBriefingInput(
        dateKey = "2026-03-17",
        weatherHeadline = headline,
        weatherSummary = UiText.from("Summary"),
        discretionaryBudget = discretionary,
        totalCommitted = 80.0,
        totalLikely = 100.0,
        pendingReviewCount = 0,
        currentMonthSpent = 500.0,
        topCategories = emptyList(),
        budgetWarnings = emptyList(),
        upcomingItems = emptyList()
    )

    @Test
    fun `unavailable weather prints unavailable not 0 point 0`() {
        val prompt = formatter.buildPrompt(
            input(UiText.fromKey(DomainTextKeys.WEATHER_HEADLINE_UNAVAILABLE), discretionary = 0.0)
        )

        assertTrue(prompt.contains("Discretionary budget: unavailable"))
        assertTrue(!prompt.contains("Discretionary budget: 0.0"))
    }

    @Test
    fun `normal weather prints the discretionary number`() {
        val prompt = formatter.buildPrompt(input(UiText.from("Sunny"), discretionary = 120.0))

        assertTrue(prompt.contains("Discretionary budget: 120.0"))
    }
}
