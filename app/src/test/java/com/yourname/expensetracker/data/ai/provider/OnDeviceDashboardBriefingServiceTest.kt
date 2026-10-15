package com.yourname.expensetracker.data.ai.provider

import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerativeModel
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.model.DashboardBudgetWarningInput
import com.yourname.expensetracker.domain.ai.model.DashboardUpcomingItemInput
import com.yourname.expensetracker.domain.ai.model.TransactionInsightAmountBucket
import com.yourname.expensetracker.domain.ai.model.TransactionInsightPromptInput
import com.yourname.expensetracker.domain.model.UiText
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

class OnDeviceDashboardBriefingServiceTest {

    private val service = OnDeviceDashboardBriefingService()

    private val sampleInput = DashboardBriefingInput(
        dateKey = "2026-03-16",
        weatherHeadline = UiText.from("Sunny"),
        weatherSummary = UiText.from("Stable day ahead"),
        discretionaryBudget = 120.0,
        totalCommitted = 80.0,
        totalLikely = 110.0,
        pendingReviewCount = 2,
        currentMonthSpent = 430.0,
        topCategories = listOf("Groceries", "Transport"),
        budgetWarnings = listOf(
            DashboardBudgetWarningInput(
                categoryLabel = UiText.from("Groceries"),
                percentUsed = 92
            )
        ),
        upcomingItems = listOf(
            DashboardUpcomingItemInput(
                description = "Rent",
                amount = 500.0,
                dateMillis = 1_774_928_000_000,
                currencyCode = "EUR"
            )
        )
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
    fun `buildPrompt assembles redacted transaction insight in data layer`() {
        val prompt = service.buildPrompt(
            sampleInput.copy(
                weatherHeadline = UiText.from(""),
                weatherSummary = UiText.from(""),
                budgetWarnings = emptyList(),
                upcomingItems = emptyList(),
                transactionInsight = TransactionInsightPromptInput(
                    merchantName = "Secret Market",
                    promptAmount = 175.0,
                    currencyCode = "EUR",
                    redactForPrompt = true,
                    amountBucket = TransactionInsightAmountBucket.RANGE_100_249,
                    isHighValue = true
                )
            )
        )

        assertTrue(prompt.contains("merchant_"))
        assertTrue(prompt.contains("100-249 EUR"))
        assertTrue(prompt.contains("Privacy mode"))
        assertTrue(!prompt.contains("Secret Market"))
        assertTrue(!prompt.contains("175.00 EUR"))
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

    // ---- R6b: bounded UNKNOWN_ERROR, no leaked text, CancellationException propagates ----

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
    private fun serviceWithModel(model: GenerativeModel): OnDeviceDashboardBriefingService {
        val target = OnDeviceDashboardBriefingService()
        val field = OnDeviceDashboardBriefingService::class.java.getDeclaredField("cachedModel")
        field.isAccessible = true
        field.set(target, model)
        return target
    }

    @Test
    fun `generate maps unexpected exception to bounded UNKNOWN_ERROR without leaking sentinel`() {
        val model = mockk<GenerativeModel>()
        coEvery { model.generateContent(any<GenerateContentRequest>()) } throws
            IllegalStateException("R6BSENTINEL_7d41")

        withPlantedLogs { logs ->
            val result = runBlocking { serviceWithModel(model).generate(sampleInput) }

            assertEquals(
                AiServiceResult.Failure(AiServiceError.Unknown("UNKNOWN_ERROR")),
                result
            )
            assertFalse(result.toString().contains("R6BSENTINEL_7d41"))
            assertTrue(logs.any { it.second.contains("UNKNOWN_ERROR") })
            assertTrue(logs.all { it.first == null && !it.second.contains("R6BSENTINEL_7d41") })
        }
    }

    @Test
    fun `generate propagates CancellationException instead of mapping to Unknown`() {
        val model = mockk<GenerativeModel>()
        coEvery { model.generateContent(any<GenerateContentRequest>()) } throws
            CancellationException("R6BSENTINEL_7d41")

        withPlantedLogs { logs ->
            assertThrows(CancellationException::class.java) {
                runBlocking { serviceWithModel(model).generate(sampleInput) }
            }
            assertTrue(logs.none { it.second.contains("UNKNOWN_ERROR") })
        }
    }
}
