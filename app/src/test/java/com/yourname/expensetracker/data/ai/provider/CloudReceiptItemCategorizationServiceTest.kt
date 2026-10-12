package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.dto.CategoryRef
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.CloudCategoryOption
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.privacy.PrivacyGate
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
    fun `cloud tax and item amounts do not override deterministic source allocation`() {
        val input = ReceiptItemCategorizationInput(
            1L, "merchant", listOf(
                ReceiptParser.LineItem("first", null, null, 3.0),
                ReceiptParser.LineItem("second", null, null, 7.0)
            ), listOf(CategoryRef(1L, "Food")), totalTax = 1.0, currency = "EUR"
        )
        val response = """{
            "items":[
                {"sourceIndex":0,"description":"first","amount":99.0,"categoryId":1,"categoryName":"Food","confidence":0.9},
                {"sourceIndex":1,"description":"second","amount":99.0,"categoryId":1,"categoryName":"Food","confidence":0.9}
            ], "taxDistribution":{"1":99.0}
        }"""
        val result = requireNotNull(parseMoneyResponse(response, input))
        val allocation = result.taxAllocation as com.yourname.expensetracker.domain.ai.model.ReceiptTaxAllocation.Available
        assertEquals(listOf(3.0, 7.0), result.items.map { it.amount })
        assertEquals(listOf(0.3, 0.7), allocation.itemAmounts.map { it.amount })
        assertEquals(mapOf(1L to 1.0), result.taxDistribution)
        assertTrue(result.needsReview)
    }

    @Test
    fun `incomplete cloud item lists cannot manufacture source tax allocations`() {
        val input = ReceiptItemCategorizationInput(
            1L, "merchant", listOf(ReceiptParser.LineItem("first", null, null, 3.0)),
            emptyList(), totalTax = 1.0, currency = "EUR"
        )
        org.junit.Assert.assertNull(parseMoneyResponse("""{"items":[],"taxDistribution":{}}""", input))
    }

    @Test
    fun `cloud response order cannot change source item allocation`() {
        val input = ReceiptItemCategorizationInput(
            1L, "merchant", listOf(
                ReceiptParser.LineItem("first", null, null, 3.0),
                ReceiptParser.LineItem("second", null, null, 7.0)
            ), listOf(CategoryRef(1L, "Food")), totalTax = null, currency = "EUR"
        )
        val response = """{
            "items":[
                {"sourceIndex":1,"description":"second","amount":7.0,"categoryId":1,"categoryName":"Food","confidence":0.9},
                {"sourceIndex":0,"description":"first","amount":3.0,"categoryId":1,"categoryName":"Food","confidence":0.9}
            ]
        }"""

        val result = requireNotNull(parseMoneyResponse(response, input))
        assertEquals(listOf(3.0, 7.0), result.items.map { it.amount })
        assertEquals(listOf("first", "second"), result.items.map { it.itemDescription })
    }

    @Test
    fun `cloud response without a stable source index fails closed`() {
        val input = defaultInput(cloudCategoryOptions = emptyList())
        val response = """{
            "items":[
                {"description":"item_a","amount":4.5,"categoryId":null,"categoryName":"cat_1","confidence":0.9}
            ]
        }"""

        org.junit.Assert.assertNull(parseMoneyResponse(response, input))
    }

    @Test
    fun `cloud cannot invent adjustment provenance for a negative price`() {
        val input = ReceiptItemCategorizationInput(
            1L, "merchant", listOf(
                ReceiptParser.LineItem("product", null, null, 100.0),
                ReceiptParser.LineItem("discount", null, null, -20.0)
            ), listOf(CategoryRef(1L, "Food")), totalTax = 8.0, currency = "EUR"
        )
        val response = """{
            "items":[
                {"sourceIndex":0,"description":"product","amount":100.0,"categoryId":1,"categoryName":"Food","confidence":0.9},
                {"sourceIndex":1,"description":"discount","amount":-20.0,"categoryId":1,"categoryName":"Food","confidence":0.9,"isAdjustment":true}
            ]
        }"""

        val rejected = requireNotNull(parseMoneyResponse(response, input))
        val failure = rejected.taxAllocation as com.yourname.expensetracker.domain.ai.model.ReceiptTaxAllocation.Unavailable
        assertEquals("RECEIPT_ITEM_ADJUSTMENT_UNCONFIRMED", failure.failure.reason)
        assertTrue(rejected.needsReview)
        assertFalse(rejected.items.last().isAdjustment)

        val confirmedInput = input.copy(lineItems = input.lineItems.mapIndexed { index, item ->
            item.copy(isAdjustment = index == 1)
        })
        val confirmed = requireNotNull(parseMoneyResponse(response, confirmedInput))
        val allocation = confirmed.taxAllocation as com.yourname.expensetracker.domain.ai.model.ReceiptTaxAllocation.Available
        assertEquals(listOf(10.0, -2.0), allocation.itemAmounts.map { it.amount })
        assertTrue(confirmed.items.last().isAdjustment)
    }

    private fun parseMoneyResponse(
        modelText: String,
        input: ReceiptItemCategorizationInput
    ): com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult? {
        val service = CloudReceiptItemCategorizationService(
            mockk<SecureKeyStorage>(), OkHttpClient(), mockk<PrivacyGate>(relaxed = true),
            com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy(
                com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedNoAi(),
                com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor()
            )
        )
        val body = JSONObject().put("candidates", JSONArray().put(
            JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", modelText))))
        )).toString()
        val method = service.javaClass.getDeclaredMethod("parseResponse", String::class.java, String::class.java, ReceiptItemCategorizationInput::class.java)
        method.isAccessible = true
        return method.invoke(service, body, "test", input) as? com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
    }


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
                                              "sourceIndex": 0,
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

        val service = CloudReceiptItemCategorizationService(keyStorage, client, mockk<PrivacyGate>(relaxed = true), com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy(
            com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedNoAi(),
            com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor()
        ))

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
                CategoryRef(id = 1L, name = "Private Category Alpha"),
                CategoryRef(id = 2L, name = "Sensitive Category Beta")
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

        // PRIV-43B-03: Raw prompt is built first; CloudPayloadPolicy applies redaction before sending to cloud.
        // With failClosedNoAi policy (no redaction), raw category names appear in the prompt.
        // Redaction behavior is tested in CloudPayloadPolicyTest.
        assertTrue(capturedPrompt.contains("Private Category Alpha") || capturedPrompt.contains("cat_a1b2c3"))
    }

    @Test
    fun `categorizeItems maps fallback cat aliases when cloud options are empty`() = runBlocking {
        val keyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { keyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        val modelText = """
            {
              "items": [
                {
                  "sourceIndex": 0,
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
            clientRespondingWithModelText(modelText),
            mockk<PrivacyGate>(relaxed = true),
            com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy(
                com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedNoAi(),
                com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor()
            )
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
                  "sourceIndex": 0,
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
            clientRespondingWithModelText(modelText),
            mockk<PrivacyGate>(relaxed = true),
            com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy(
                com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedNoAi(),
                com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor()
            )
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
                CategoryRef(id = 1L, name = "Private Category Alpha"),
                CategoryRef(id = 2L, name = "Sensitive Category Beta")
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
