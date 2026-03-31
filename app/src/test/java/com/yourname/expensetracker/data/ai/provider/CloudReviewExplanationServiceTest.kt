package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Test

class CloudReviewExplanationServiceTest {

    @Test
    fun `generate returns null safely when api key is absent`() {
        // Mock SecureKeyStorage to return empty key
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns ""
        
        val service = CloudReviewExplanationService(mockKeyStorage)

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
