package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import org.junit.Assert.assertNull
import org.junit.Test

class CloudReviewExplanationServiceTest {

    @Test
    fun `generate returns null safely when api key is absent`() {
        val service = CloudReviewExplanationService()

        val result = kotlinx.coroutines.runBlocking {
            service.generate(
                ReviewExplanationInput(
                    reviewId = 1L,
                    merchant = "Test",
                    amount = 10.0,
                    currency = "EUR",
                    suggestedType = "PURCHASE",
                    suggestedCategoryId = null,
                    confidence = 0.4f,
                    matchType = null,
                    explanation = null,
                    packageName = "pkg",
                    notificationTitle = "Title",
                    notificationText = "Body"
                )
            )
        }

        assertNull(result)
    }
}
