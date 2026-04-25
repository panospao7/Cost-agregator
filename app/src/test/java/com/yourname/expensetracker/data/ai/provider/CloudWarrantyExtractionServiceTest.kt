package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.WarrantyExtractionInput
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CloudWarrantyExtractionServiceTest {

    private fun createMockKeyStorage(apiKey: String = ""): SecureKeyStorage {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns apiKey
        return mockKeyStorage
    }

    private fun sampleInput() = WarrantyExtractionInput(
        receiptText = "Receipt text",
        merchant = "Test Store",
        totalAmount = 199.99,
        purchaseDate = 1_700_000_000_000,
        currency = "EUR"
    )

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `extractWarranty returns null when API key is missing`() {
        val service = CloudWarrantyExtractionService(
            secureKeyStorage = createMockKeyStorage(apiKey = ""),
            client = OkHttpClient()
        )

        val result = runBlocking {
            service.extractWarranty(sampleInput(), shouldRedactBeforeCloud = false)
        }

        assertNull(result)
    }

    @Test
    fun `extractWarranty parses domain result from successful response`() {
        val successBody = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"hasWarranty\":true,\"productName\":\"Gaming Laptop\",\"warrantyMonths\":24,\"warrantyType\":\"EXTENDED\",\"supportPhone\":\"+1234567890\",\"supportEmail\":\"support@example.com\",\"returnDays\":14,\"returnConditions\":\"Factory seal required\",\"confidence\":0.92}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(successBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudWarrantyExtractionService(
            secureKeyStorage = createMockKeyStorage(apiKey = "test-key"),
            client = client
        )

        val result = runBlocking {
            service.extractWarranty(sampleInput(), shouldRedactBeforeCloud = false)
        }

        assertNotNull(result)
        assertEquals("Gaming Laptop", result?.productName)
        assertEquals(24, result?.warrantyMonths)
        assertEquals("EXTENDED", result?.warrantyType)
        assertEquals("+1234567890", result?.supportPhone)
        assertEquals("support@example.com", result?.supportEmail)
        assertEquals(14, result?.returnDays)
        assertEquals("Factory seal required", result?.returnConditions)
    }

    @Test
    fun `extractWarranty returns null when model reports no warranty`() {
        val noWarrantyBody = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"hasWarranty\":false,\"confidence\":0.0}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(noWarrantyBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudWarrantyExtractionService(
            secureKeyStorage = createMockKeyStorage(apiKey = "test-key"),
            client = client
        )

        val result = runBlocking {
            service.extractWarranty(sampleInput(), shouldRedactBeforeCloud = false)
        }

        assertNull(result)
    }

    @Test
    fun `extractWarranty preserves return policy only responses without warranty months`() {
        val returnPolicyBody = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"hasWarranty\":false,\"productName\":\"Shoes\",\"returnDays\":30,\"returnConditions\":\"Unworn only\",\"confidence\":0.81}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(returnPolicyBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudWarrantyExtractionService(
            secureKeyStorage = createMockKeyStorage(apiKey = "test-key"),
            client = client
        )

        val result = runBlocking {
            service.extractWarranty(sampleInput(), shouldRedactBeforeCloud = false)
        }

        assertNotNull(result)
        assertEquals(null, result?.warrantyMonths)
        assertEquals(30, result?.returnDays)
        assertEquals("Unworn only", result?.returnConditions)
    }
}
