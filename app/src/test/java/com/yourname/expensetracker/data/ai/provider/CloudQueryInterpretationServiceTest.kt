package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.FakePrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AssistantUnsupportedReason
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
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
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import timber.log.Timber
import org.junit.Before
import org.junit.After

class CloudQueryInterpretationServiceTest {

    private val failureLogs = mutableListOf<Pair<Throwable?, String>>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= 5) failureLogs += t to message
        }
    }

    @Before fun captureLogs() { Timber.plant(logTree) }
    @After fun releaseLogs() {
        Timber.uproot(logTree)
        assertTrue(failureLogs.all { it.first == null && !it.second.contains("/private") && !it.second.contains("financial_data") })
    }

    private fun allowedGate(): PrivacyGate = mockk<PrivacyGate>().also { gate ->
        coEvery { gate.check(PrivacyCapability.CLOUD_AI_GENERAL) } returns PrivacyDecision.Allowed
    }

    private fun assertUnsupported(
        result: FinancialQueryInterpretationResult,
        reason: AssistantUnsupportedReason,
        diagnostic: DiagnosticReasonCode
    ) {
        val unsupported = result as FinancialQueryInterpretationResult.Unsupported
        assertEquals(reason, unsupported.reason)
        assertEquals(diagnostic, unsupported.diagnostic)
    }

    private fun testPayloadPolicy() = DefaultCloudPayloadPolicy(
        EffectiveCloudAiPolicyResolver.failClosedNoAi(), DefaultCloudPayloadRedactor()
    )

    private fun service(
        client: OkHttpClient,
        retryDelay: RecordingRetryDelay = RecordingRetryDelay()
    ): CloudQueryInterpretationService {
        val key = mockk<SecureKeyStorage>()
        every { key.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"
        return CloudQueryInterpretationService(
            key, client, allowedGate(), testPayloadPolicy(), PrivacyAuditLogger.NO_OP, retryDelay.delayer
        )
    }

    private fun input() = FinancialQueryInterpretationInput("top merchants", 1_000L, "en-US")

    private fun response(bodyText: String?, status: Int = 200): Response {
        if (bodyText == null) return mockk<Response>(relaxed = true).also { response ->
            every { response.isSuccessful } returns true
            every { response.body } returns null
            every { response.close() } returns Unit
        }
        return Response.Builder().request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1).code(status).message("Test")
            .body(bodyText.toResponseBody("application/json".toMediaType())).build()
    }

    private fun successBody(): String = JSONObject().put("candidates", JSONArray().put(
        JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put(
            "text", "{\"kind\":\"clarification\",\"clarification\":{\"prompt\":\"Need period?\",\"options\":[\"This month\"]}}"
        ))))
    )).toString()

    @Test
    fun `timeout and retryable IO preserve success and exhaustion attempt limits`() = runBlocking {
        for ((failure, code) in listOf(
            SocketTimeoutException("/private/receipt") to DiagnosticReasonCode.TIMEOUT,
            IOException("connection reset /private/receipt") to DiagnosticReasonCode.NETWORK_UNAVAILABLE
        )) {
            for (recover in listOf(true, false)) {
                val client = mockk<OkHttpClient>()
                val call = mockk<Call>()
                every { client.newCall(any()) } returns call
                var attempts = 0
                every { call.execute() } answers {
                    if (++attempts == 1 || !recover) throw failure
                    response(successBody())
                }
                val retryDelay = RecordingRetryDelay()
                val result = service(client, retryDelay).interpret(input())
                if (recover) assertFalse(result is FinancialQueryInterpretationResult.Unsupported)
                else assertUnsupported(result, AssistantUnsupportedReason.PROVIDER_UNAVAILABLE, code)
                verify(exactly = if (recover) 2 else 3) { call.execute() }
                retryDelay.assertSchedule(expectedRetries = if (recover) 1 else 2)
            }
        }
    }

    @Test
    fun `HTTP retry success exhaustion and terminal failure preserve attempt limits`() = runBlocking {
        for (status in listOf(408, 429, 500, 400)) {
            for (recover in listOf(true, false)) {
                val client = mockk<OkHttpClient>()
                val call = mockk<Call>()
                every { client.newCall(any()) } returns call
                var attempts = 0
                every { call.execute() } answers {
                    if (++attempts == 1 || !recover) response("/private/receipt", status)
                    else response(successBody())
                }
                val retryDelay = RecordingRetryDelay()
                val result = service(client, retryDelay).interpret(input())
                if (recover && status != 400) assertFalse(result is FinancialQueryInterpretationResult.Unsupported)
                else assertUnsupported(result, AssistantUnsupportedReason.PROVIDER_UNAVAILABLE, DiagnosticReasonCode.UNKNOWN_ERROR)
                verify(exactly = if (status == 400) 1 else if (recover) 2 else 3) { call.execute() }
                retryDelay.assertSchedule(expectedRetries = if (status == 400) 0 else if (recover) 1 else 2)
            }
        }
    }

    @Test
    fun `null and empty bodies return only parser reason`() = runBlocking {
        for (body in listOf(null, "")) {
            val client = mockk<OkHttpClient>()
            val call = mockk<Call>()
            every { client.newCall(any()) } returns call
            every { call.execute() } answers { response(body) }
            assertUnsupported(
                service(client).interpret(input()),
                AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID,
                DiagnosticReasonCode.PARSER_FAILED
            )
            verify(exactly = 1) { call.execute() }
        }
    }

    @Test
    fun `INV-C5 empty candidates or missing text map to PROVIDER_RESPONSE_INVALID with the default UNKNOWN_ERROR diagnostic`() = runBlocking {
        // The reason is the bounded code (PROVIDER_RESPONSE_INVALID). The log-only diagnostic is
        // whatever the code produces for these shapes: the unsupported() default, UNKNOWN_ERROR
        // (PARSER_FAILED is reserved for a null body / JSONException).
        val shapes = listOf(
            """{"candidates":[]}""",
            """{}""",
            """{"candidates":[{}]}""",
            """{"candidates":[{"content":{"parts":[]}}]}""",
            """{"candidates":[{"content":{"parts":[{}]}}]}"""
        )
        for (body in shapes) {
            val client = mockk<OkHttpClient>()
            val call = mockk<Call>()
            every { client.newCall(any()) } returns call
            every { call.execute() } answers { response(body) }
            val retryDelay = RecordingRetryDelay()

            val result = service(client, retryDelay).interpret(input())

            assertUnsupported(
                result,
                AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID,
                DiagnosticReasonCode.UNKNOWN_ERROR
            )
            verify(exactly = 1) { call.execute() }
            retryDelay.assertSchedule(expectedRetries = 0)
        }
    }

    @Test
    fun `AE-C8 cloud model unsupported text is discarded and result carries MODEL_DECLINED only`() = runBlocking {
        val modelText = """{"kind":"unsupported","unsupportedReason":"rent 4242 4242 4242 4242 at Lidl"}"""
        val body = JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", modelText)))
                )
            )
        ).toString()
        val client = mockk<OkHttpClient>()
        val call = mockk<Call>()
        every { client.newCall(any()) } returns call
        every { call.execute() } answers { response(body) }

        val result = service(client).interpret(input())

        val unsupported = result as FinancialQueryInterpretationResult.Unsupported
        assertEquals(AssistantUnsupportedReason.MODEL_DECLINED, unsupported.reason)
        assertTrue(!result.toString().contains("Lidl"))
        assertTrue(!result.toString().contains("4242"))
    }

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `interpret returns unsupported safely when api key is absent`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns ""
        
        val mockClient = mockk<OkHttpClient>()
        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, allowedGate(), testPayloadPolicy())

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

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, allowedGate(), testPayloadPolicy())

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

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, allowedGate(), testPayloadPolicy())

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

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, allowedGate(), testPayloadPolicy())

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

    /**
     * Real resolver over fakes with cloud allowed and redaction OFF in both privacy and AI
     * settings, so the prompt reaches the wire unredacted (line structure preserved).
     */
    private fun nonRedactingPayloadPolicy() = DefaultCloudPayloadPolicy(
        EffectiveCloudAiPolicyResolver(
            FakePrivacySettingsRepository(
                PrivacySettings(cloudAiEnabled = true, redactBeforeCloud = false)
            ),
            FixedAiSettingsRepository(AiSettings(allowCloudAi = true, redactBeforeCloud = false))
        ),
        DefaultCloudPayloadRedactor()
    )

    private class FixedAiSettingsRepository(initial: AiSettings) : AiSettingsRepository {
        private val state = MutableStateFlow(initial)
        override fun settings(): Flow<AiSettings> = state
        override suspend fun update(transform: (AiSettings) -> AiSettings) {
            state.value = transform(state.value)
        }
    }

    /** Runs a clarification round-trip with two prior USER turns and returns the wire prompt text. */
    private fun capturePromptWithHistory(payloadPolicy: DefaultCloudPayloadPolicy): String {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        val cloudModelText =
            """{"kind":"clarification","clarification":{"prompt":"Need period?","options":["This month"]}}"""
        val cloudResponseBody = JSONObject().put(
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
        ).toString()

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

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, allowedGate(), payloadPolicy)

        kotlinx.coroutines.runBlocking {
            service.interpret(
                FinancialQueryInterpretationInput(
                    rawQuery = "and last month",
                    currentTimeMs = 1_000L,
                    localeTag = "en-US",
                    priorUserQueries = listOf("food last week", "and transport")
                )
            )
        }

        val requestJson = JSONObject(capturedRequests.single().body!!.bodyToString())
        return requestJson
            .getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
    }

    @Test
    fun `Part C Cc cloud prompt history contains only USER lines and no ASSISTANT lines`() {
        // Redaction OFF: the prompt keeps its line structure, so assert line-based.
        val prompt = capturePromptWithHistory(nonRedactingPayloadPolicy())

        val lines = prompt.lines()
        val headerIndex = lines.indexOf("Conversation history:")
        assertTrue("Conversation history header must be present", headerIndex >= 0)
        val historyLines = lines.drop(headerIndex + 1).takeWhile { it.isNotBlank() }

        assertEquals(2, historyLines.size)
        assertTrue(historyLines.all { it.startsWith("- USER:") })
        assertEquals("- USER: food last week", historyLines[0])
        assertEquals("- USER: and transport", historyLines[1])
        assertTrue(prompt.contains("- USER:"))
        assertFalse(prompt.lines().any { it.trimStart().startsWith("- ASSISTANT:") })
        assertFalse(prompt.contains("ASSISTANT:"))
    }

    @Test
    fun `Part C Cc cloud prompt history has only USER entries and no ASSISTANT text when redaction is on`() {
        // Redaction ON (fail-closed resolver): the sanitizer collapses whitespace to a single
        // line, so assert on whitespace-collapsed substrings rather than line structure.
        val prompt = capturePromptWithHistory(testPayloadPolicy())

        assertTrue(prompt.contains("Conversation history:"))
        assertTrue(prompt.contains("- USER:"))
        assertFalse(prompt.contains("ASSISTANT"))
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

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, allowedGate(), testPayloadPolicy())

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
        verify(exactly = 1) { mockCall.execute() }
        assertTrue(failureLogs.isEmpty())
    }

    @Test
    fun `interpret network IOException still returns unsupported`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        val mockClient = mockk<OkHttpClient>()
        val mockCall = mockk<Call>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } throws IOException("Network error")

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, allowedGate(), testPayloadPolicy())

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
        assertUnsupported(result, AssistantUnsupportedReason.PROVIDER_UNAVAILABLE, DiagnosticReasonCode.NETWORK_UNAVAILABLE)
    }

    @Test
    fun `interpret SSL failure returns bounded unsupported reason`() {
        val keyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { keyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"
        val client = mockk<OkHttpClient>()
        val call = mockk<Call>()
        every { client.newCall(any()) } returns call
        every { call.execute() } throws SSLException("/private/receipts/sql secret")

        val result = runBlocking {
            CloudQueryInterpretationService(keyStorage, client, allowedGate(), testPayloadPolicy())
                .interpret(FinancialQueryInterpretationInput("top merchants", 1_000L, "en-US"))
        }

        assertUnsupported(result, AssistantUnsupportedReason.PROVIDER_UNAVAILABLE, DiagnosticReasonCode.UNKNOWN_ERROR)
        verify(exactly = 1) { call.execute() }
    }

    @Test
    fun `retryable SSL timeout retries and can return a successful result`() {
        val keyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { keyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"
        val gate = mockk<PrivacyGate>()
        coEvery { gate.check(PrivacyCapability.CLOUD_AI_GENERAL) } returns PrivacyDecision.Allowed
        val client = mockk<OkHttpClient>()
        val call = mockk<Call>()
        every { client.newCall(any()) } returns call
        val ssl = SSLException("/private/receipts/sql secret")
        ssl.initCause(SocketTimeoutException("transport timeout"))
        val modelText = """{"kind":"clarification","clarification":{"prompt":"Need period?","options":["This month"]}}"""
        val responseBody = JSONObject().put("candidates", JSONArray().put(
            JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", modelText))))
        )).toString()
        var attempts = 0
        every { call.execute() } answers {
            if (++attempts == 1) throw ssl
            Response.Builder()
                .request(Request.Builder().url("https://example.com").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType())).build()
        }
        val logs = mutableListOf<Pair<Throwable?, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += t to message
            }
        }
        Timber.plant(tree)
        try {
            val retryDelay = RecordingRetryDelay()
            val result = runBlocking {
                CloudQueryInterpretationService(
                    keyStorage, client, gate, testPayloadPolicy(), PrivacyAuditLogger.NO_OP, retryDelay.delayer
                ).interpret(FinancialQueryInterpretationInput("top merchants", 1_000L, "en-US"))
            }
            assertFalse(result is FinancialQueryInterpretationResult.Unsupported)
            assertEquals(2, attempts)
            retryDelay.assertSchedule(expectedRetries = 1)
            assertTrue(logs.any { it.second.contains("stage=ssl") })
            assertTrue(logs.all { it.first == null && !it.second.contains("/private/receipts/sql secret") })
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `retryable SSL failure exhausts three attempts with a bounded reason`() {
        val keyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { keyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"
        val gate = mockk<PrivacyGate>()
        coEvery { gate.check(PrivacyCapability.CLOUD_AI_GENERAL) } returns PrivacyDecision.Allowed
        val client = mockk<OkHttpClient>()
        val call = mockk<Call>()
        every { client.newCall(any()) } returns call
        every { call.execute() } throws SSLException("connection reset /private/receipt")

        val retryDelay = RecordingRetryDelay()
        val result = runBlocking {
            CloudQueryInterpretationService(
                keyStorage, client, gate, testPayloadPolicy(), PrivacyAuditLogger.NO_OP, retryDelay.delayer
            ).interpret(FinancialQueryInterpretationInput("top merchants", 1_000L, "en-US"))
        }

        assertUnsupported(result, AssistantUnsupportedReason.PROVIDER_UNAVAILABLE, DiagnosticReasonCode.UNKNOWN_ERROR)
        verify(exactly = 3) { call.execute() }
        retryDelay.assertSchedule(expectedRetries = 2)
    }

    @Test
    fun `generic provider failure is not classified as parsing`() {
        val keyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { keyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"
        val gate = mockk<PrivacyGate>()
        coEvery { gate.check(PrivacyCapability.CLOUD_AI_GENERAL) } returns PrivacyDecision.Allowed
        val client = mockk<OkHttpClient>()
        val call = mockk<Call>()
        every { client.newCall(any()) } returns call
        every { call.execute() } throws IllegalStateException("SELECT * FROM financial_data /private/receipt")

        val result = runBlocking {
            CloudQueryInterpretationService(keyStorage, client, gate, testPayloadPolicy())
                .interpret(FinancialQueryInterpretationInput("top merchants", 1_000L, "en-US"))
        }

        assertUnsupported(result, AssistantUnsupportedReason.PROVIDER_UNAVAILABLE, DiagnosticReasonCode.UNKNOWN_ERROR)
        verify(exactly = 1) { call.execute() }
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

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, allowedGate(), testPayloadPolicy())

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
        assertUnsupported(result, AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID, DiagnosticReasonCode.PARSER_FAILED)
    }

    @Test
    fun `interpret returns unsupported when privacy gate denies`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"

        val mockClient = mockk<OkHttpClient>()
        val mockPrivacyGate = mockk<PrivacyGate>()
        coEvery { mockPrivacyGate.check(PrivacyCapability.CLOUD_AI_GENERAL) } returns
            PrivacyDecision.Denied("blocked by test")

        val service = CloudQueryInterpretationService(mockKeyStorage, mockClient, mockPrivacyGate, testPayloadPolicy())

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
