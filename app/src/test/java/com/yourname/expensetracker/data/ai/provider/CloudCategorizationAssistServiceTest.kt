package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryOption
import com.yourname.expensetracker.domain.model.DomainTransactionType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

class CloudCategorizationAssistServiceTest {

    @Test
    fun `suggest returns null safely when api key is absent`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns ""

        val service = CloudCategorizationAssistService(mockKeyStorage)

        val result = runBlocking { service.suggest(defaultInput()) }

        assertNull(result)
    }

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `suggest retries transient 500 and succeeds on second attempt`() = runBlocking {
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
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(successfulResponseBody().toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val service = CloudCategorizationAssistService(mockKeyStorage, client)

        val result = service.suggest(defaultInput())

        assertEquals(2, attempts.get())
        assertTrue(result != null)
        assertEquals(1L, result?.categoryId)
        assertEquals("Groceries", result?.categoryName)
    }

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `suggest retries timeout once and succeeds on second attempt`() = runBlocking {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-gemini-key"

        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val attempt = attempts.incrementAndGet()
                if (attempt == 1) {
                    throw SocketTimeoutException("timeout")
                }

                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(successfulResponseBody().toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudCategorizationAssistService(mockKeyStorage, client)

        val result = service.suggest(defaultInput())

        assertEquals(2, attempts.get())
        assertTrue(result != null)
    }

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `suggest does not retry non-retryable 400`() = runBlocking {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-gemini-key"

        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("Bad Request")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudCategorizationAssistService(mockKeyStorage, client)

        val result = service.suggest(defaultInput())

        assertNull(result)
        assertEquals(1, attempts.get())
    }

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `suggest retries 429 up to max attempts then returns null`() = runBlocking {
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

        val service = CloudCategorizationAssistService(mockKeyStorage, client)

        val result = service.suggest(defaultInput())

        assertNull(result)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `suggest extracts first json object when multiple objects are present`() = runBlocking {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-gemini-key"

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "text": "first {\"categoryId\":2,\"categoryName\":\"Transport\",\"confidence\":0.4,\"rationale\":\"first\",\"alternativeCategoryIds\":[1]} second {\"categoryId\":1,\"categoryName\":\"Groceries\"}"
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
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudCategorizationAssistService(mockKeyStorage, client)

        val result = service.suggest(defaultInput())

        assertTrue(result != null)
        assertEquals(2L, result?.categoryId)
        assertEquals("Transport", result?.categoryName)
    }

    @Test
    fun `suggest builds redacted prompt and maps alias response back to real category`() = runBlocking {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-gemini-key"

        var capturedBody = ""
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body!!.writeTo(buffer)
                capturedBody = buffer.readUtf8()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  {
                                    "text": "{\"categoryId\":1,\"categoryName\":\"category_alias_groceries\",\"confidence\":0.9,\"alternativeCategoryIds\":[2]}"
                                  }
                                ]
                              }
                            }
                          ]
                        }
                        """.trimIndent().toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()

        val service = CloudCategorizationAssistService(mockKeyStorage, client)

        val result = service.suggest(
            defaultInput().copy(
                merchant = "merchant_123",
                candidateCategories = listOf(
                    CategoryOption(1L, "Groceries", cloudLabel = "category_alias_groceries"),
                    CategoryOption(2L, "Transport", cloudLabel = "category_alias_transport")
                ),
                recentTransactionsWithSameMerchant = listOf(
                    com.yourname.expensetracker.domain.ai.model.MerchantTransactionHint(
                        merchant = "Lidl",
                        categoryName = "Groceries",
                        cloudMerchant = "merchant_123",
                        cloudCategoryName = "category_alias_groceries"
                    )
                )
            )
        )

        assertTrue(capturedBody.contains("category_alias_groceries"))
        assertTrue(capturedBody.contains("merchant_123"))
        assertTrue(!capturedBody.contains("Lidl"))
        assertTrue(!capturedBody.contains("Known merchant history: Lidl → Groceries"))
        assertEquals(1L, result?.categoryId)
        assertEquals("Groceries", result?.categoryName)
    }

    @Test
    fun `suggest returns null when confidence is malformed`() = runBlocking {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-gemini-key"

        val malformedConfidenceValues = listOf("\"NaN\"", "\"Infinity\"", "1.1", "-0.1")

        malformedConfidenceValues.forEach { confidenceLiteral ->
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(responseBodyWithConfidenceLiteral(confidenceLiteral).toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()

            val service = CloudCategorizationAssistService(mockKeyStorage, client)

            val result = service.suggest(defaultInput())

            assertNull("Expected null for malformed confidence: $confidenceLiteral", result)
        }
    }

    @Test
    fun `suggest returns null when categoryId is zero or non-numeric`() = runBlocking {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-gemini-key"

        val malformedCategoryIds = listOf("0", "\"abc\"")

        malformedCategoryIds.forEach { categoryIdLiteral ->
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(responseBodyWithCategoryIdLiteral(categoryIdLiteral).toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()

            val service = CloudCategorizationAssistService(mockKeyStorage, client)

            val result = service.suggest(defaultInput())

            assertNull("Expected null for malformed categoryId: $categoryIdLiteral", result)
        }
    }

    @Test
    fun `suggest ignores invalid alternativeCategoryIds values without coercing to zero`() = runBlocking {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-gemini-key"

        val malformedAlternativeIds = listOf("[0]", "[\"abc\"]")

        malformedAlternativeIds.forEach { alternativeCategoryIdsLiteral ->
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            responseBodyWithAlternativeCategoryIdsLiteral(alternativeCategoryIdsLiteral)
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build()

            val service = CloudCategorizationAssistService(mockKeyStorage, client)

            val result = service.suggest(
                defaultInput().copy(
                    candidateCategories = listOf(
                        CategoryOption(0L, "Invalid Zero Id"),
                        CategoryOption(1L, "Groceries"),
                        CategoryOption(2L, "Transport")
                    )
                )
            )

            assertTrue("Expected a valid suggestion for malformed list: $alternativeCategoryIdsLiteral", result != null)
            assertEquals(
                "Expected malformed alternative IDs to be discarded: $alternativeCategoryIdsLiteral",
                emptyList<Long>(),
                result?.alternativeCategoryIds
            )
        }
    }

    private fun defaultInput(): CategorizationAssistInput {
        return CategorizationAssistInput(
            targetType = AiTargetType.PENDING_REVIEW,
            targetId = 1L,
            merchant = "Lidl",
            amount = 24.5,
            currency = "EUR",
            transactionType = DomainTransactionType.PURCHASE,
            date = null,
            currentCategoryId = null,
            deterministicMatchType = "FALLBACK",
            deterministicExplanation = "weak deterministic match",
            candidateCategories = listOf(
                CategoryOption(1L, "Groceries"),
                CategoryOption(2L, "Transport")
            ),
            supportingText = null
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
                        "text": "{\"categoryId\":1,\"categoryName\":\"Groceries\",\"confidence\":0.9,\"rationale\":\"merchant hint\",\"alternativeCategoryIds\":[2]}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
    }

    private fun responseBodyWithConfidenceLiteral(confidenceLiteral: String): String {
        return """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"categoryId\":1,\"categoryName\":\"Groceries\",\"confidence\":$confidenceLiteral,\"rationale\":\"merchant hint\",\"alternativeCategoryIds\":[2]}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
    }

    private fun responseBodyWithCategoryIdLiteral(categoryIdLiteral: String): String {
        return """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"categoryId\":$categoryIdLiteral,\"categoryName\":\"Groceries\",\"confidence\":0.9,\"rationale\":\"merchant hint\",\"alternativeCategoryIds\":[2]}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
    }

    private fun responseBodyWithAlternativeCategoryIdsLiteral(alternativeCategoryIdsLiteral: String): String {
        return """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"categoryId\":1,\"categoryName\":\"Groceries\",\"confidence\":0.9,\"rationale\":\"merchant hint\",\"alternativeCategoryIds\":$alternativeCategoryIdsLiteral}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
