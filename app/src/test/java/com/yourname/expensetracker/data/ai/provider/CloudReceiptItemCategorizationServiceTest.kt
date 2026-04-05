package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.CloudCategoryOption
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudReceiptItemCategorizationServiceTest {

    @Test
    fun `categorizeItems redaction on does not include raw category names in payload`() = runBlocking {
        val keyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { keyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        var capturedPrompt = ""
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                val requestBody = buffer.readUtf8()
                capturedPrompt = JSONObject(requestBody)
                    .getJSONArray("contents")
                    .getJSONObject(0)
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                val responseJson = JSONObject().apply {
                    put("candidates", JSONArray().put(
                        JSONObject().put(
                            "content",
                            JSONObject().put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put(
                                        "text",
                                        """
                                        {
                                          "items": [
                                            {
                                              "description": "item_a",
                                              "amount": 4.5,
                                              "categoryId": 1,
                                              "categoryName": "cat_a1b2c3",
                                              "confidence": 0.9,
                                              "rationale": "matched",
                                              "isNewCategorySuggestion": false,
                                              "alternatives": []
                                            }
                                          ],
                                          "suggestedNewCategories": [],
                                          "taxDistribution": {"1": 0.0}
                                        }
                                        """.trimIndent()
                                    )
                                )
                            )
                        )
                    ))
                }

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toString().toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudReceiptItemCategorizationService(keyStorage, client)

        val input = ReceiptItemCategorizationInput(
            receiptId = 1L,
            merchant = "merchant_123",
            lineItems = listOf(
                ReceiptParser.LineItem(
                    description = "item_a",
                    quantity = null,
                    unitPrice = null,
                    totalPrice = 4.5
                )
            ),
            userCategories = listOf(
                Category(id = 1L, name = "Private Category Alpha", icon = "A", color = "#112233"),
                Category(id = 2L, name = "Sensitive Category Beta", icon = "B", color = "#445566")
            ),
            cloudCategoryOptions = listOf(
                CloudCategoryOption(categoryId = 1L, cloudName = "cat_a1b2c3"),
                CloudCategoryOption(categoryId = 2L, cloudName = "cat_d4e5f6")
            ),
            totalTax = null,
            currency = "EUR",
            redactBeforeCloud = true
        )

        service.categorizeItems(input)

        assertTrue(capturedPrompt.contains("cat_a1b2c3"))
        assertTrue(capturedPrompt.contains("cat_d4e5f6"))
        assertFalse(capturedPrompt.contains("Private Category Alpha"))
        assertFalse(capturedPrompt.contains("Sensitive Category Beta"))
    }

    @Test
    fun `categorizeItems maps fallback cat aliases when cloud options are empty`() = runBlocking {
        val keyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { keyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        val modelText = """
            {
              "items": [
                {
                  "description": "item_a",
                  "amount": 4.5,
                  "categoryId": null,
                  "categoryName": "cat_1",
                  "confidence": 0.9,
                  "rationale": "matched",
                  "isNewCategorySuggestion": false,
                  "alternatives": [
                    {
                      "categoryId": null,
                      "categoryName": "cat_2",
                      "confidence": 0.8
                    }
                  ]
                }
              ],
              "suggestedNewCategories": [],
              "taxDistribution": {}
            }
        """.trimIndent()

        val service = CloudReceiptItemCategorizationService(
            keyStorage,
            clientRespondingWithModelText(modelText)
        )

        val result = service.categorizeItems(defaultInput(cloudCategoryOptions = emptyList()))

        assertNotNull(result)
        val firstItem = result!!.items.first()
        assertEquals("Private Category Alpha", firstItem.suggestedCategory?.categoryName)
        assertEquals("Sensitive Category Beta", firstItem.alternatives.first().categoryName)
    }

    @Test
    fun `categorizeItems keeps unknown alias unchanged when no matching category exists`() = runBlocking {
        val keyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { keyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        val modelText = """
            {
              "items": [
                {
                  "description": "item_a",
                  "amount": 4.5,
                  "categoryId": null,
                  "categoryName": "cat_999",
                  "confidence": 0.9,
                  "rationale": "unknown",
                  "isNewCategorySuggestion": true,
                  "alternatives": []
                }
              ],
              "suggestedNewCategories": [],
              "taxDistribution": {}
            }
        """.trimIndent()

        val service = CloudReceiptItemCategorizationService(
            keyStorage,
            clientRespondingWithModelText(modelText)
        )

        val result = service.categorizeItems(defaultInput(cloudCategoryOptions = emptyList()))

        assertNotNull(result)
        val firstItem = result!!.items.first()
        assertEquals("cat_999", firstItem.suggestedCategory?.categoryName)
    }

    private fun defaultInput(cloudCategoryOptions: List<CloudCategoryOption>): ReceiptItemCategorizationInput {
        return ReceiptItemCategorizationInput(
            receiptId = 1L,
            merchant = "merchant_123",
            lineItems = listOf(
                ReceiptParser.LineItem(
                    description = "item_a",
                    quantity = null,
                    unitPrice = null,
                    totalPrice = 4.5
                )
            ),
            userCategories = listOf(
                Category(id = 1L, name = "Private Category Alpha", icon = "A", color = "#112233"),
                Category(id = 2L, name = "Sensitive Category Beta", icon = "B", color = "#445566")
            ),
            cloudCategoryOptions = cloudCategoryOptions,
            totalTax = null,
            currency = "EUR",
            redactBeforeCloud = true
        )
    }

    private fun clientRespondingWithModelText(modelText: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val responseJson = JSONObject().apply {
                    put("candidates", JSONArray().put(
                        JSONObject().put(
                            "content",
                            JSONObject().put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put("text", modelText)
                                )
                            )
                        )
                    ))
                }

                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toString().toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }
}
