package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudReviewExplanationServiceTest {

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `generate returns null safely when api key is absent`() {
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

        assertTrue(result is AiServiceResult.Failure)
        val failure = result as AiServiceResult.Failure
        assertTrue(failure.error is AiServiceError.Disabled)
    }

    // P8F-07 Phase 1: privacy-gate denial surfaces as a typed PrivacyDenied error.
    // The apiKeyOverride test constructor installs a fail-closed PrivacyGate, so a
    // non-blank key skips the key-missing path and the gate denies execution.
    @Test
    fun `generate returns PrivacyDenied when privacy gate blocks execution`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)

        val service = CloudReviewExplanationService(mockKeyStorage, "fake-api-key")

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

        assertTrue(result is AiServiceResult.Failure)
        val failure = result as AiServiceResult.Failure
        assertTrue(failure.error is AiServiceError.PrivacyDenied)
        val privacyDenied = failure.error as AiServiceError.PrivacyDenied
        assertEquals(PrivacyCapability.CLOUD_AI_GENERAL, privacyDenied.blocked.capability)
    }
}
