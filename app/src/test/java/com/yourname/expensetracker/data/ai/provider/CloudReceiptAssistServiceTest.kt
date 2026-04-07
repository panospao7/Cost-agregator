package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicInteger

class CloudReceiptAssistServiceTest {

    private fun createMockKeyStorage(apiKey: String = ""): SecureKeyStorage {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns apiKey
        return mockKeyStorage
    }

    private fun sampleInput(
        rawOcrText: String = "OCR",
        redactBeforeCloud: Boolean = false,
        parsedMerchant: String? = null,
        lineItemsJson: String? = null
    ) = ReceiptAssistInput(
        receiptId = 1L,
        rawOcrText = rawOcrText,
        imagePath = null,
        imageMimeType = null,
        redactBeforeCloud = redactBeforeCloud,
        parsedMerchant = parsedMerchant,
        parsedTotal = null,
        parsedDate = null,
        parsedTaxAmount = null,
        currency = "EUR",
        lineItemsJson = lineItemsJson,
        currentTimeMs = 1L
    )

    @Test
    fun `suggest returns null safely when api key is absent or request unsupported`() {
        val settingsRepository = mockk<AiSettingsRepository>()
        every { settingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, receiptAssistEnabled = true))
        val service = CloudReceiptAssistService(settingsRepository, createMockKeyStorage())

        val result = kotlinx.coroutines.runBlocking {
            service.suggest(
                ReceiptAssistInput(
                    receiptId = 1L,
                    rawOcrText = "LIDL HELLAS\nTOTAL 12.34\nDATE 2026-03-01",
                    imagePath = null,
                    imageMimeType = null,
                    parsedMerchant = null,
                    parsedTotal = null,
                    parsedDate = null,
                    parsedTaxAmount = null,
                    currency = "EUR",
                    lineItemsJson = null,
                    currentTimeMs = 1_000L
                )
            )
        }

        assertTrue(result is AiServiceResult.Failure)
        val failure = result as AiServiceResult.Failure
        assertTrue(failure.error is AiServiceError.Disabled)
    }

    @Test
    fun `usedImageInput only reports true when image metadata exists`() {
        val settingsRepository = mockk<AiSettingsRepository>()
        every { settingsRepository.settings() } returns flowOf(AiSettings())
        val service = CloudReceiptAssistService(settingsRepository, createMockKeyStorage())

        assertFalse(
            service.usedImageInput(
                ReceiptAssistInput(
                    receiptId = 1L,
                    rawOcrText = "OCR",
                    imagePath = null,
                    imageMimeType = null,
                    parsedMerchant = null,
                    parsedTotal = null,
                    parsedDate = null,
                    parsedTaxAmount = null,
                    currency = "EUR",
                    lineItemsJson = null,
                    currentTimeMs = 1L
                )
            )
        )
    }

    @Test
    fun `buildRequestBodyForTest includes inline image data when allowed`() {
        val settingsRepository = mockk<AiSettingsRepository>()
        every { settingsRepository.settings() } returns flowOf(AiSettings())
        val service = CloudReceiptAssistService(settingsRepository, createMockKeyStorage())
        val imageFile = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        try {
            val requestBody = service.buildRequestBodyForTest(
                ReceiptAssistInput(
                    receiptId = 1L,
                    rawOcrText = "OCR",
                    imagePath = imageFile.absolutePath,
                    imageMimeType = "image/jpeg",
                    parsedMerchant = null,
                    parsedTotal = null,
                    parsedDate = null,
                    parsedTaxAmount = null,
                    currency = "EUR",
                    lineItemsJson = null,
                    currentTimeMs = 1L
                ),
                allowImage = true
            )

            assertTrue(requestBody.contains("inlineData"))
            assertTrue(requestBody.contains("image/jpeg"))
        } finally {
            imageFile.delete()
        }
    }

    @Test
    fun `suggest retries transient http failures and succeeds on later attempt`() {
        val settingsRepository = mockk<AiSettingsRepository>()
        every { settingsRepository.settings() } returns flowOf(AiSettings())

        val attempts = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val attempt = attempts.incrementAndGet()
                val responseCode = if (attempt == 1) 500 else 200
                val body = if (responseCode == 500) {
                    "{\"error\":\"temporary\"}"
                } else {
                    """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "text": "{\"merchant\":{\"value\":\"Lidl\"},\"total\":null,\"date\":null,\"taxAmount\":null,\"notes\":[\"ok\"]}"
                              }
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                }

                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message(if (responseCode == 200) "OK" else "Server Error")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudReceiptAssistService(
            aiSettingsRepository = settingsRepository,
            secureKeyStorage = createMockKeyStorage(apiKey = "test-key"),
            client = client
        )

        val result = kotlinx.coroutines.runBlocking {
            service.suggest(sampleInput(rawOcrText = "LIDL TOTAL 12.34"))
        }

        assertTrue(result is AiServiceResult.Success)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `buildRequestBodyForTest redacts merchant and text when redactBeforeCloud enabled`() {
        val settingsRepository = mockk<AiSettingsRepository>()
        every { settingsRepository.settings() } returns flowOf(AiSettings())
        val service = CloudReceiptAssistService(settingsRepository, createMockKeyStorage(apiKey = "test-key"))

        val requestBody = service.buildRequestBodyForTest(
            sampleInput(
                rawOcrText = "Email john@example.com Card 4111 1111 1111 1111",
                redactBeforeCloud = true,
                parsedMerchant = "Acme Market",
                lineItemsJson = """[{\"description\":\"4111111111111111\",\"email\":\"john@example.com\"}]"""
            ),
            allowImage = false
        )

        assertFalse(requestBody.contains("Acme Market"))
        assertFalse(requestBody.contains("john@example.com"))
        assertFalse(requestBody.contains("4111 1111 1111 1111"))
        assertTrue(requestBody.contains("merchant_"))
        assertTrue(requestBody.contains("[REDACTED_EMAIL]"))
        assertTrue(requestBody.contains("[REDACTED_CARD]"))
    }
}
