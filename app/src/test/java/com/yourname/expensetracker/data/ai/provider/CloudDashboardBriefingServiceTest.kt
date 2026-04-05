package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import io.mockk.every
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

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

        assertTrue(result is AiServiceResult.Failure)
        val failure = result as AiServiceResult.Failure
        assertTrue(failure.error is AiServiceError.Disabled)
    }

    @Test
    fun `generate retries transient 500 and succeeds on second attempt`() = runBlocking {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-gemini-key"

        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val attempt = attempts.incrementAndGet()
                if (attempt == 1) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Internal Server Error")
                        .body("{}".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    val successBody = """
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  {
                                    "text": "{\"title\":\"Daily snapshot\",\"text\":\"You are on track today.\",\"tone\":\"neutral\",\"confidence\":0.8}"
                                  }
                                ]
                              }
                            }
                          ]
                        }
                    """.trimIndent()

                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(successBody.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val service = CloudDashboardBriefingService(mockKeyStorage, client)

        val result = service.generate(defaultInput())

        assertEquals(2, attempts.get())
        assertTrue(result is AiServiceResult.Success)
        val success = result as AiServiceResult.Success
        assertEquals("Daily snapshot", success.value.title)
        assertEquals("You are on track today.", success.value.text)
    }

    @Test
    fun `generate retries transient 429 and succeeds on second attempt`() = runBlocking {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-gemini-key"

        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val attempt = attempts.incrementAndGet()
                if (attempt == 1) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body("{}".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    val successBody = successfulResponseBody()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(successBody.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val service = CloudDashboardBriefingService(mockKeyStorage, client)

        val result = service.generate(defaultInput())

        assertEquals(2, attempts.get())
        assertTrue(result is AiServiceResult.Success)
    }

    @Test
    fun `generate retries transient 408 and succeeds on second attempt`() = runBlocking {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-gemini-key"

        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val attempt = attempts.incrementAndGet()
                if (attempt == 1) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(408)
                        .message("Request Timeout")
                        .body("{}".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    val successBody = successfulResponseBody()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(successBody.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val service = CloudDashboardBriefingService(mockKeyStorage, client)

        val result = service.generate(defaultInput())

        assertEquals(2, attempts.get())
        assertTrue(result is AiServiceResult.Success)
    }

    @Test
    fun `generate returns terminal 429 after max retries`() = runBlocking {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-gemini-key"

        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudDashboardBriefingService(mockKeyStorage, client)

        val result = service.generate(defaultInput())

        assertEquals(3, attempts.get())
        assertTrue(result is AiServiceResult.Failure)
        val failure = result as AiServiceResult.Failure
        assertTrue(failure.error is AiServiceError.HttpError)
        val httpError = failure.error as AiServiceError.HttpError
        assertEquals(429, httpError.code)
    }

    private fun defaultInput(): DashboardBriefingInput {
        return DashboardBriefingInput(
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
    }

    private fun successfulResponseBody(): String {
        return """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"title\":\"Daily snapshot\",\"text\":\"You are on track today.\",\"tone\":\"neutral\",\"confidence\":0.8}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
