package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Test

class CloudDashboardBriefingServiceTest {

    @Test
    fun `generate returns null safely when api key is absent`() {
        // Mock SecureKeyStorage to return empty key (simulating missing API key)
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns ""
        
        val service = CloudDashboardBriefingService(mockKeyStorage)

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
