package com.yourname.expensetracker.data.ai.provider

import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerativeModel
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

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

    // ---- R6 (RG02B-1 / RG02B-4): bounded codes, no leaked text, CancellationException propagates ----

    private inline fun <T> withPlantedLogs(block: (MutableList<Pair<Throwable?, String>>) -> T): T {
        val logs = mutableListOf<Pair<Throwable?, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += t to message
            }
        }
        Timber.plant(tree)
        try {
            return block(logs)
        } finally {
            Timber.uproot(tree)
        }
    }

    /** Seeds the production model cache (no production change; avoids Generation.getClient()). */
    private fun serviceWithModel(model: GenerativeModel): OnDeviceReviewExplanationService {
        val target = OnDeviceReviewExplanationService()
        val field = OnDeviceReviewExplanationService::class.java.getDeclaredField("cachedModel")
        field.isAccessible = true
        field.set(target, model)
        return target
    }

    @Test
    fun `parseResponse does not log throwable or sentinel on JSON failure`() {
        withPlantedLogs { logs ->
            val result = service.parseResponse("{ R6SENTINEL_9f2c }")

            assertNull(result)
            assertTrue(logs.any { it.second.contains("PARSER_FAILED") })
            assertTrue(logs.all { it.first == null && !it.second.contains("R6SENTINEL_9f2c") })
        }
    }

    @Test
    fun `generate maps unexpected exception to bounded UNKNOWN_ERROR without leaking sentinel`() {
        val model = mockk<GenerativeModel>()
        coEvery { model.generateContent(any<GenerateContentRequest>()) } throws
            IllegalStateException("R6SENTINEL_9f2c")

        withPlantedLogs { logs ->
            val result = runBlocking { serviceWithModel(model).generate(sampleInput) }

            assertEquals(
                AiServiceResult.Failure(AiServiceError.Unknown("UNKNOWN_ERROR")),
                result
            )
            assertFalse(result.toString().contains("R6SENTINEL_9f2c"))
            assertTrue(logs.all { it.first == null && !it.second.contains("R6SENTINEL_9f2c") })
        }
    }

    @Test
    fun `generate propagates CancellationException instead of mapping to Unknown`() {
        val model = mockk<GenerativeModel>()
        coEvery { model.generateContent(any<GenerateContentRequest>()) } throws
            CancellationException("R6SENTINEL_9f2c")

        withPlantedLogs { logs ->
            assertThrows(CancellationException::class.java) {
                runBlocking { serviceWithModel(model).generate(sampleInput) }
            }
            assertTrue(logs.none { it.second.contains("UNKNOWN_ERROR") })
        }
    }
}
