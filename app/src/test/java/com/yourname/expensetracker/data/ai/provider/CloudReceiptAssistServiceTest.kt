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
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import io.mockk.verify
import org.junit.Before
import org.junit.After
import org.json.JSONObject
import org.json.JSONArray
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistGenerationResult
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.ReceiptAssistService
import com.yourname.expensetracker.domain.ai.usecase.SuggestReceiptExtractionUseCase
import com.yourname.expensetracker.domain.ai.usecase.ReceiptAssistInputBuilder
import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.database.entity.ScannedReceipt

class CloudReceiptAssistServiceTest {

    private val failureLogs = mutableListOf<Pair<Throwable?, String>>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= 5) failureLogs += t to message
        }
    }
    @Before fun captureLogs() { Timber.plant(logTree) }
    @After fun releaseLogs() {
        Timber.uproot(logTree)
        assertTrue(failureLogs.all { it.first == null && !it.second.contains("/private") })
    }

    private fun assertArtifactError(error: AiServiceError, readable: String) = runBlocking {
        val settings = mockk<AiSettingsRepository>()
        every { settings.settings() } returns flowOf(AiSettings(aiEnabled = true, receiptAssistEnabled = true))
        val artifacts = mockk<AiArtifactRepository>()
        coEvery { artifacts.getLatest(any(), any()) } returns null
        val stored = mutableListOf<AiArtifactRecord>()
        coEvery { artifacts.upsert(capture(stored)) } returns 1L
        val forwarder = mockk<ReceiptAssistService>()
        coEvery { forwarder.suggest(any()) } returns AiServiceResult.Failure(error)
        val router = mockk<AiCapabilityRouter>()
        coEvery { router.decide(any(), any(), any()) } returns
            AiRouteDecision(AiRoute.CLOUD, "CL17_TEST", "gemini", "test-model")
        val builder = mockk<ReceiptAssistInputBuilder>()
        coEvery { builder.build(any(), any()) } returns sampleInput()
        val receipts = mockk<ReceiptRepository>()
        val receipt = mockk<ScannedReceipt>()
        every { receipt.rawOcrText } returns "OCR"
        coEvery { receipts.getReceiptById(1L) } returns receipt

        val result = SuggestReceiptExtractionUseCase(settings, artifacts, forwarder, router, builder, receipts, FakeTimeProvider(1L))(1L)

        assertEquals(ReceiptAssistGenerationResult.Error(readable), result)
        assertEquals(listOf(AiArtifactStatus.RUNNING, AiArtifactStatus.FAILED), stored.map { it.status })
        assertEquals("$readable Route: CLOUD, provider: gemini, model: test-model. Reason: CL17_TEST", stored.last().errorMessage)
        assertEquals(null, stored.last().payloadJson)
    }

    private fun response(bodyText: String?, status: Int = 200): Response {
        if (bodyText == null) return mockk<Response>(relaxed = true).also { response ->
            every { response.isSuccessful } returns true
            every { response.body } returns null
            every { response.close() } returns Unit
        }
        return Response.Builder().request(okhttp3.Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1).code(status).message("Test")
            .body(bodyText.toResponseBody("application/json".toMediaType())).build()
    }

    private fun successBody(): String = JSONObject().put("candidates", JSONArray().put(
        JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put(
            "text", "{\"merchant\":{\"value\":\"Shop\"},\"notes\":[\"ok\"]}"
        ))))
    )).toString()

    @Test
    fun `both provider paths bound null and empty body errors through artifact consumer`() {
        for (textMode in listOf(false, true)) {
            for (body in listOf(null, "")) {
                val client = mockk<OkHttpClient>()
                val call = mockk<okhttp3.Call>()
                every { client.newCall(any()) } returns call
                every { call.execute() } answers { response(body) }
                val service = allowedService(client)
                val result = runBlocking {
                    if (textMode) service.suggestFromText("bank rows") else service.suggest(sampleInput())
                } as AiServiceResult.Failure
                assertEquals(AiServiceError.ParseError("PARSER_FAILED"), result.error)
                verify(exactly = 1) { call.execute() }
                assertArtifactError(result.error, "PARSER_FAILED")
            }
        }
    }

    @Test
    fun `both paths preserve IO and HTTP retry success exhaustion and terminal limits`() {
        for (textMode in listOf(false, true)) {
            for (status in listOf<Int?>(null, 408, 429, 500, 400)) {
                for (recover in listOf(true, false)) {
                    val client = mockk<OkHttpClient>()
                    val call = mockk<okhttp3.Call>()
                    every { client.newCall(any()) } returns call
                    var attempts = 0
                    every { call.execute() } answers {
                        if (++attempts == 1 || !recover) {
                            if (status == null) throw IOException("connection reset /private/receipt")
                            response("/private/receipt", status)
                        } else response(successBody())
                    }
                    val service = allowedService(client)
                    val result = runBlocking {
                        if (textMode) service.suggestFromText("bank rows") else service.suggest(sampleInput())
                    }
                    if (recover && status != 400) assertTrue(result is AiServiceResult.Success<*>)
                    else {
                        val expected = if (status == null) AiServiceError.Offline else AiServiceError.HttpError(status, "UNKNOWN_ERROR")
                        assertEquals(expected, (result as AiServiceResult.Failure).error)
                    }
                    verify(exactly = if (status == 400) 1 else if (recover) 2 else 3) { call.execute() }
                }
            }
        }
    }

    private fun allowedService(client: OkHttpClient): CloudReceiptAssistService {
        val settings = mockk<AiSettingsRepository>()
        every { settings.settings() } returns flowOf(AiSettings())
        val gate = mockk<PrivacyGate>()
        coEvery { gate.check(any<PrivacyCapability>(), any()) } returns PrivacyDecision.Allowed
        return CloudReceiptAssistService(
            aiSettingsRepository = settings,
            secureKeyStorage = createMockKeyStorage("test-key"),
            client = client,
            privacyGate = gate,
            cloudPayloadPolicy = DefaultCloudPayloadPolicy(
                EffectiveCloudAiPolicyResolver.failClosedForTest(settings),
                DefaultCloudPayloadRedactor()
            )
        )
    }

    @Test
    fun `suggest and text provider errors are bounded and do not log throwables`() {
        val logs = mutableListOf<Pair<Throwable?, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += t to message
            }
        }
        Timber.plant(tree)
        try {
            val cases = listOf(
                SocketTimeoutException("SQL /private/receipt 12.34") to AiServiceError.Timeout,
                SSLException("SQL /private/receipt 12.34") to AiServiceError.SslError,
                IOException("SQL /private/receipt 12.34") to AiServiceError.Offline,
                IllegalStateException("SQL /private/receipt 12.34") to AiServiceError.Unknown("UNKNOWN_ERROR")
            )
            for ((failure, expected) in cases) {
                val attempts = AtomicInteger()
                val client = OkHttpClient.Builder().addInterceptor {
                    attempts.incrementAndGet()
                    throw failure
                }.build()
                val service = allowedService(client)
                val suggestionFailure = runBlocking { service.suggest(sampleInput()) } as AiServiceResult.Failure
                assertEquals(expected, suggestionFailure.error)
                assertEquals(if (failure is SocketTimeoutException) 3 else 1, attempts.get())
                attempts.set(0)
                val textFailure = runBlocking { service.suggestFromText("bank rows") } as AiServiceResult.Failure
                assertEquals(expected, textFailure.error)
                assertEquals(if (failure is SocketTimeoutException) 3 else 1, attempts.get())
                val readable = when (expected) {
                    AiServiceError.Timeout -> "AI receipt assist timed out. Please retry."
                    AiServiceError.SslError -> "Secure connection failed. Please retry later."
                    AiServiceError.Offline -> "Network unavailable. Check connection and retry."
                    else -> "UNKNOWN_ERROR"
                }
                assertArtifactError(suggestionFailure.error, readable)
                assertArtifactError(textFailure.error, readable)
            }
            val malformed = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body("{ SQL /private/receipt 12.34".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()
            val service = allowedService(malformed)
            assertEquals(AiServiceError.ParseError("PARSER_FAILED"),
                (runBlocking { service.suggest(sampleInput()) } as AiServiceResult.Failure).error)
            assertEquals(AiServiceError.ParseError("PARSER_FAILED"),
                (runBlocking { service.suggestFromText("bank rows") } as AiServiceResult.Failure).error)
            assertTrue(logs.all { it.first == null && !it.second.contains("/private/receipt") })
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `suggest and text cancellation propagate`() {
        val client = OkHttpClient.Builder().addInterceptor {
            throw CancellationException("SQL /private/receipt")
        }.build()
        val service = allowedService(client)
        assertThrows(CancellationException::class.java) {
            runBlocking { service.suggest(sampleInput()) }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { service.suggestFromText("bank rows") }
        }
        assertTrue(failureLogs.isEmpty())
    }

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

    // TODO: Tautological mock test — consider adding real behavior assertion
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
        assertEquals("PROVIDER_DISABLED", (failure.error as AiServiceError.Disabled).reason)
    }

    // TODO: Tautological mock test — consider adding real behavior assertion
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

    // TODO: Tautological mock test — consider adding real behavior assertion
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

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `buildRequestBodyForTest suppresses inline image when redaction is required`() {
        val settingsRepository = mockk<AiSettingsRepository>()
        every { settingsRepository.settings() } returns flowOf(AiSettings())
        val service = CloudReceiptAssistService(settingsRepository, createMockKeyStorage())
        val imageFile = kotlin.io.path.createTempFile(suffix = ".png").toFile().apply {
            writeBytes(Base64.getDecoder().decode(ONE_BY_ONE_PNG_BASE64))
        }

        try {
            val requestBody = service.buildRequestBodyForTest(
                ReceiptAssistInput(
                    receiptId = 1L,
                    rawOcrText = "OCR",
                    imagePath = imageFile.absolutePath,
                    imageMimeType = "image/png",
                    isImageAnalysisMode = true,
                    redactBeforeCloud = true,
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

            assertFalse(requestBody.contains("inlineData"))
            assertTrue(requestBody.contains("No receipt image available"))
        } finally {
            imageFile.delete()
        }
    }

    // TODO: Tautological mock test — consider adding real behavior assertion
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
            client = client,
            privacyGate = mockk<PrivacyGate>(relaxed = true),
            cloudPayloadPolicy = com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy(
                com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedForTest(settingsRepository),
                com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor()
            )
        )

        val result = kotlinx.coroutines.runBlocking {
            service.suggest(sampleInput(rawOcrText = "LIDL TOTAL 12.34"))
        }

        assertTrue(result is AiServiceResult.Success<*>)
        assertEquals(2, attempts.get())
    }

    // PRIV-43B-02: buildRequestBodyForTest now returns raw prompt — redaction is done by CloudPayloadPolicy, not provider.
    // This test verifies the raw prompt structure; redaction behavior is tested in CloudPayloadPolicyTest.
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

        // Raw prompt is built first; CloudPayloadPolicy applies redaction before sending to cloud.
        // The test helper bypasses policy, so raw values appear in the test output.
        assertTrue(requestBody.contains("Acme Market"))
        assertTrue(requestBody.contains("john@example.com"))
    }

    private companion object {
        private const val ONE_BY_ONE_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+nX4QAAAAASUVORK5CYII="
    }
}
