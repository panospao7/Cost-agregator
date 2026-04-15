package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import io.mockk.every
import io.mockk.mockk
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudQueryInterpretationServiceTest {

    @Test
    fun `interpret returns unsupported safely when api key is absent`() {
        // Mock SecureKeyStorage to return empty key
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns ""
        
        val service = CloudQueryInterpretationService(mockKeyStorage)

        val result = kotlinx.coroutines.runBlocking {
            service.interpret(
                FinancialQueryInterpretationInput(
                    rawQuery = "top merchants this month",
                    currentTimeMs = 1_000L,
                    localeTag = "en-US",
                    categoryNames = listOf("Groceries"),
                    merchantNames = listOf("Lidl")
                )
            )
        }

        assertTrue(result is FinancialQueryInterpretationResult.Unsupported)
    }

    @Test
    fun `interpret does not return unsupported on successful cloud response`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        val cloudModelText = """{"kind":"clarification","clarification":{"prompt":"Need period?","options":["This month","Last month"]}}"""
        val cloudResponseBody = JSONObject().apply {
            put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", cloudModelText))
                        )
                    )
                )
            )
        }.toString()

        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(cloudResponseBody.toResponseBody("application/json".toMediaType()))
            .build()

        val mockClient = mockk<OkHttpClient>()
        val mockCall = mockk<Call>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns response

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient)

        val result = kotlinx.coroutines.runBlocking {
            service.interpret(
                FinancialQueryInterpretationInput(
                    rawQuery = "top merchants this month",
                    currentTimeMs = 1_000L,
                    localeTag = "en-US",
                    categoryNames = listOf("Groceries"),
                    merchantNames = listOf("Lidl")
                )
            )
        }

        assertTrue(result !is FinancialQueryInterpretationResult.Unsupported)
    }

    @Test
    fun `interpret parses structured response with merchant names`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        val cloudModelText =
            """{"kind":"structured","intent":{"metric":"TOTAL","grouping":"MERCHANT","comparison":"NONE","answerMode":"BOTH","ownership":"ALL","merchantNames":["Lidl","Groceries"]}}"""
        val cloudResponseBody = JSONObject().apply {
            put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", cloudModelText))
                        )
                    )
                )
            )
        }.toString()

        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(cloudResponseBody.toResponseBody("application/json".toMediaType()))
            .build()

        val mockClient = mockk<OkHttpClient>()
        val mockCall = mockk<Call>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns response

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient)

        val result = kotlinx.coroutines.runBlocking {
            service.interpret(
                FinancialQueryInterpretationInput(
                    rawQuery = "top merchants this month",
                    currentTimeMs = 1_000L,
                    localeTag = "en-US"
                )
            )
        }

        assertTrue(result is FinancialQueryInterpretationResult.Structured)
        val structured = result as FinancialQueryInterpretationResult.Structured
        assertEquals(setOf("Lidl", "Groceries"), structured.intent.filters.merchants)
    }

    @Test
    fun `interpret sends alias only prompt context in redacted mode`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        val cloudModelText =
            """{"kind":"clarification","clarification":{"prompt":"Need period?","options":["This month"]}}"""
        val cloudResponseBody = JSONObject().apply {
            put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", cloudModelText))
                        )
                    )
                )
            )
        }.toString()

        val capturedRequests = mutableListOf<Request>()
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(cloudResponseBody.toResponseBody("application/json".toMediaType()))
            .build()

        val mockClient = mockk<OkHttpClient>()
        val mockCall = mockk<Call>()
        every { mockClient.newCall(capture(capturedRequests)) } returns mockCall
        every { mockCall.execute() } returns response

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient)

        kotlinx.coroutines.runBlocking {
            service.interpret(
                FinancialQueryInterpretationInput(
                    rawQuery = "show merchant_a in category_a",
                    currentTimeMs = 1_000L,
                    localeTag = "en-US",
                    categoryNames = listOf("category_a"),
                    merchantNames = listOf("merchant_a"),
                    merchantLookupMap = mapOf("merchant_a" to "Lidl", "Lidl" to "Lidl"),
                    merchantAliasMap = mapOf("merchant_a" to "Lidl"),
                    categoryLookupMap = mapOf("category_a" to 1L, "Groceries" to 1L),
                    categoryAliasMap = mapOf("category_a" to "Groceries"),
                    categoryNameToIdMap = mapOf("category_a" to 1L, "Groceries" to 1L)
                )
            )
        }

        val requestJson = JSONObject(capturedRequests.single().body!!.bodyToString())
        val prompt = requestJson
            .getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")

        assertTrue(prompt.contains("merchant_a"))
        assertTrue(prompt.contains("category_a"))
        assertFalse(prompt.contains("Lidl"))
        assertFalse(prompt.contains("Groceries"))
    }

    private fun okhttp3.RequestBody.bodyToString(): String {
        val buffer = okio.Buffer()
        writeTo(buffer)
        return buffer.readUtf8()
    }
}
