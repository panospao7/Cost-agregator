package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CloudQueryInterpretationServiceTest {

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `interpret returns unsupported safely when api key is absent`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns ""
        
        val mockClient = mockk<OkHttpClient>()
        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, mockk<PrivacyGate>(relaxed = true))

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

    // TODO: Tautological mock test — consider adding real behavior assertion
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

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, mockk<PrivacyGate>(relaxed = true))

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

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, mockk<PrivacyGate>(relaxed = true))

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

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, mockk<PrivacyGate>(relaxed = true))

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

    // ──────────────────────────────────────────────────────────────────────────
    // PR1 — No-schema hardening: cancellation safety & error handling
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `interpret rethrows CancellationException`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        val mockClient = mockk<OkHttpClient>()
        val mockCall = mockk<Call>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } throws CancellationException()

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, mockk<PrivacyGate>(relaxed = true))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                service.interpret(
                    FinancialQueryInterpretationInput(
                        rawQuery = "top merchants this month",
                        currentTimeMs = 1_000L,
                        localeTag = "en-US"
                    )
                )
            }
        }
    }

    @Test
    fun `interpret network IOException still returns unsupported`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        val mockClient = mockk<OkHttpClient>()
        val mockCall = mockk<Call>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } throws IOException("Network error")

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, mockk<PrivacyGate>(relaxed = true))

        val result = runBlocking {
            service.interpret(
                FinancialQueryInterpretationInput(
                    rawQuery = "top merchants this month",
                    currentTimeMs = 1_000L,
                    localeTag = "en-US"
                )
            )
        }

        assertTrue(result is FinancialQueryInterpretationResult.Unsupported)
    }

    @Test
    fun `interpret parse exception still returns unsupported`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        val malformedBody = "This is not valid JSON".toResponseBody("application/json".toMediaType())
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(malformedBody)
            .build()

        val mockClient = mockk<OkHttpClient>()
        val mockCall = mockk<Call>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns response

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, mockk<PrivacyGate>(relaxed = true))

        val result = runBlocking {
            service.interpret(
                FinancialQueryInterpretationInput(
                    rawQuery = "top merchants this month",
                    currentTimeMs = 1_000L,
                    localeTag = "en-US"
                )
            )
        }

        assertTrue(result is FinancialQueryInterpretationResult.Unsupported)
    }

    @Test
    fun `interpret returns unsupported when privacy gate denies`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        val mockClient = mockk<OkHttpClient>()
        val mockPrivacyGate = mockk<PrivacyGate>()
        coEvery { mockPrivacyGate.check(PrivacyCapability.CLOUD_AI_GENERAL) } returns
            PrivacyDecision.Denied("blocked by test")

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, mockPrivacyGate)

        val result = runBlocking {
            service.interpret(
                FinancialQueryInterpretationInput(
                    rawQuery = "top merchants this month",
                    currentTimeMs = 1_000L,
                    localeTag = "en-US"
                )
            )
        }

        assertTrue(result is FinancialQueryInterpretationResult.Unsupported)
        verify(exactly = 0) { mockClient.newCall(any()) }
    }

    private fun okhttp3.RequestBody.bodyToString(): String {
        val buffer = okio.Buffer()
        writeTo(buffer)
        return buffer.readUtf8()
    }
}
