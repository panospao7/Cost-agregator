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
    fun `interpret restores merchant pseudonyms using alias maps`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        val cloudModelText =
            """{"kind":"structured","intent":{"metric":"TOTAL","grouping":"MERCHANT","comparison":"NONE","answerMode":"BOTH","ownership":"ALL","merchantNames":["merchant_abc123","category_def456"]}}"""
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
                    merchantAliasMap = mapOf("merchant_abc123" to "Lidl"),
                    categoryAliasMap = mapOf("category_def456" to "Groceries")
                )
            )
        }

        assertTrue(result is FinancialQueryInterpretationResult.Structured)
        val structured = result as FinancialQueryInterpretationResult.Structured
        assertEquals(setOf("Lidl", "Groceries"), structured.intent.filters.merchants)
    }
}
