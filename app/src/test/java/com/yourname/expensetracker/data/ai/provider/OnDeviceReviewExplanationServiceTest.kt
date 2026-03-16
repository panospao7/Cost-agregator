package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceReviewExplanationServiceTest {

    private val service = OnDeviceReviewExplanationService()

    private val sampleInput = ReviewExplanationInput(
        reviewId = 1L,
        merchant = "Wolt",
        amount = 18.5,
        currency = "EUR",
        suggestedType = "PURCHASE",
        suggestedCategoryId = null,
        confidence = 0.42f,
        matchType = "WEAK_MATCH",
        explanation = "merchant text is ambiguous",
        packageName = "com.wolt.app",
        notificationTitle = "Payment notice",
        notificationText = "Your card was charged"
    )

    @Test
    fun `buildPrompt includes review facts`() {
        val prompt = service.buildPrompt(sampleInput)

        assertTrue(prompt.contains("Wolt"))
        assertTrue(prompt.contains("18.5 EUR"))
        assertTrue(prompt.contains("WEAK_MATCH"))
        assertTrue(prompt.contains("merchant text is ambiguous"))
    }

    @Test
    fun `buildPrompt includes JSON schema`() {
        val prompt = service.buildPrompt(sampleInput)

        assertTrue(prompt.contains("headline"))
        assertTrue(prompt.contains("body"))
        assertTrue(prompt.contains("caution"))
    }

    @Test
    fun `parseResponse handles clean JSON`() {
        val result = service.parseResponse(
            """{"headline":"Needs review","body":"The merchant and notification text do not fully agree.","caution":"Check the merchant before approving."}"""
        )

        assertNotNull(result)
        assertEquals("Needs review", result!!.headline)
        assertEquals("The merchant and notification text do not fully agree.", result.body)
        assertEquals("Check the merchant before approving.", result.caution)
    }

    @Test
    fun `parseResponse handles markdown fenced JSON`() {
        val result = service.parseResponse(
            """
            ```json
            {"headline":"Needs review","body":"Signals conflict.","caution":null}
            ```
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals("Needs review", result!!.headline)
        assertEquals("Signals conflict.", result.body)
        assertNull(result.caution)
    }

    @Test
    fun `parseResponse returns null when headline or body missing`() {
        assertNull(service.parseResponse("{\"headline\":\"\",\"body\":\"text\"}"))
        assertNull(service.parseResponse("{\"headline\":\"Title\",\"body\":\"\"}"))
    }

    @Test
    fun `parseResponse returns null for invalid text`() {
        assertNull(service.parseResponse("not json"))
    }
}
