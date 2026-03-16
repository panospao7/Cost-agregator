package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import org.junit.Assert.assertNull
import org.junit.Test

class CloudDashboardBriefingServiceTest {

    @Test
    fun `generate returns null safely when api key is absent`() {
        val service = CloudDashboardBriefingService("")

        val result = kotlinx.coroutines.runBlocking {
            service.generate(
                DashboardBriefingInput(
                    dateKey = "2026-03-17",
                    weatherHeadline = "Sunny",
                    weatherSummary = "Stable",
                    discretionaryBudget = 120.0,
                    totalCommitted = 80.0,
                    totalLikely = 100.0,
                    pendingReviewCount = 2,
                    currentMonthSpent = 500.0,
                    topCategories = listOf("Groceries", "Transport"),
                    budgetWarnings = listOf("Groceries near limit"),
                    upcomingItems = listOf("Rent")
                )
            )
        }

        assertNull(result)
    }
}
