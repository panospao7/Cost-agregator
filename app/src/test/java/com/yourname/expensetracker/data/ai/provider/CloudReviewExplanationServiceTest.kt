package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import io.mockk.every
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudReviewExplanationServiceTest {

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `generate returns null safely when api key is absent`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns ""
        
        val service = CloudReviewExplanationService(mockKeyStorage)

        val result = kotlinx.coroutines.runBlocking {
            service.generate(
                ReviewExplanationInput(
                    reviewId = 1L,
                    merchant = "Test",
                    amount = 10.0,
                    currency = "EUR",
                    suggestedType = "PURCHASE",
                    suggestedCategoryId = null,
                    confidence = 0.4f,
                    matchType = null,
                    explanation = null,
                    packageName = "pkg",
                    notificationTitle = "Title",
                    notificationText = "Body"
                )
            )
        }

        assertTrue(result is AiServiceResult.Failure)
        val failure = result as AiServiceResult.Failure
        assertTrue(failure.error is AiServiceError.Disabled)
    }

    // P8F-07 Phase 1: privacy-gate denial surfaces as a typed PrivacyDenied error.
    // The apiKeyOverride test constructor installs a fail-closed PrivacyGate, so a
    // non-blank key skips the key-missing path and the gate denies execution.
    @Test
    fun `generate returns PrivacyDenied when privacy gate blocks execution`() {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)

        val service = CloudReviewExplanationService(mockKeyStorage, "fake-api-key")

        val result = kotlinx.coroutines.runBlocking {
            service.generate(
                ReviewExplanationInput(
                    reviewId = 1L,
                    merchant = "Test",
                    amount = 10.0,
                    currency = "EUR",
                    suggestedType = "PURCHASE",
                    suggestedCategoryId = null,
                    confidence = 0.4f,
                    matchType = null,
                    explanation = null,
                    packageName = "pkg",
                    notificationTitle = "Title",
                    notificationText = "Body"
                )
            )
        }

        assertTrue(result is AiServiceResult.Failure)
        val failure = result as AiServiceResult.Failure
        assertTrue(failure.error is AiServiceError.PrivacyDenied)
        val privacyDenied = failure.error as AiServiceError.PrivacyDenied
        assertEquals(PrivacyCapability.CLOUD_AI_GENERAL, privacyDenied.blocked.capability)
    }

    // ---- R6 (RG02B-1 / RG02B-4): bounded codes, no leaked text, CancellationException propagates ----

    private fun r6Input() = ReviewExplanationInput(
        reviewId = 1L,
        merchant = "Test",
        amount = 10.0,
        currency = "EUR",
        suggestedType = "PURCHASE",
        suggestedCategoryId = null,
        confidence = 0.4f,
        matchType = null,
        explanation = null,
        packageName = "pkg",
        notificationTitle = "Title",
        notificationText = "Body"
    )

    private fun r6AllowedService(client: okhttp3.OkHttpClient): CloudReviewExplanationService {
        val keyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { keyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "fake-api-key"
        val resolver = mockk<com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver>()
        io.mockk.coEvery { resolver.resolve() } returns com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicy(
            cloudAllowed = true,
            reason = null,
            redactBeforeCloud = false,
            receiptImageUploadAllowed = false,
            bankStatementCloudAllowed = false
        )
        val gate = mockk<com.yourname.expensetracker.domain.privacy.PrivacyGate>()
        io.mockk.coEvery { gate.check(any<PrivacyCapability>(), any()) } returns
            com.yourname.expensetracker.domain.privacy.PrivacyDecision.Allowed
        return CloudReviewExplanationService(
            secureKeyStorage = keyStorage,
            client = client,
            aiSettingsRepository = null,
            privacyGate = gate,
            cloudPayloadPolicy = com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy(
                policyResolver = resolver,
                redactor = com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor()
            ),
            effectiveCloudAiPolicyResolver = resolver
        )
    }

    private inline fun <T> withPlantedLogs(block: (MutableList<Pair<Throwable?, String>>) -> T): T {
        val logs = mutableListOf<Pair<Throwable?, String>>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += t to message
            }
        }
        timber.log.Timber.plant(tree)
        try {
            return block(logs)
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    @Test
    fun `generate maps malformed response to bounded PARSER_FAILED without leaking sentinel`() {
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{ R6SENTINEL_9f2c".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        withPlantedLogs { logs ->
            val result = kotlinx.coroutines.runBlocking { r6AllowedService(client).generate(r6Input()) }

            assertEquals(
                AiServiceResult.Failure(AiServiceError.ParseError("PARSER_FAILED")),
                result
            )
            org.junit.Assert.assertFalse(result.toString().contains("R6SENTINEL_9f2c"))
            assertTrue(logs.any { it.second.contains("PARSER_FAILED") })
            assertTrue(logs.all { it.first == null && !it.second.contains("R6SENTINEL_9f2c") })
        }
    }

    @Test
    fun `generate maps unexpected exception to bounded UNKNOWN_ERROR without leaking sentinel`() {
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { throw IllegalStateException("R6SENTINEL_9f2c") }
            .build()

        withPlantedLogs { logs ->
            val result = kotlinx.coroutines.runBlocking { r6AllowedService(client).generate(r6Input()) }

            assertEquals(
                AiServiceResult.Failure(AiServiceError.Unknown("UNKNOWN_ERROR")),
                result
            )
            org.junit.Assert.assertFalse(result.toString().contains("R6SENTINEL_9f2c"))
            assertTrue(logs.any { it.second.contains("UNKNOWN_ERROR") })
            assertTrue(logs.all { it.first == null && !it.second.contains("R6SENTINEL_9f2c") })
        }
    }

    @Test
    fun `generate does not log IO or timeout throwables`() {
        withPlantedLogs { logs ->
            listOf<Exception>(
                java.net.SocketTimeoutException("R6SENTINEL_9f2c"),
                javax.net.ssl.SSLException("R6SENTINEL_9f2c"),
                java.io.IOException("R6SENTINEL_9f2c")
            ).forEach { failure ->
                val client = okhttp3.OkHttpClient.Builder().addInterceptor { throw failure }.build()
                val result = kotlinx.coroutines.runBlocking { r6AllowedService(client).generate(r6Input()) }
                assertTrue(result is AiServiceResult.Failure)
            }
            assertTrue(logs.isNotEmpty())
            assertTrue(logs.all { it.first == null && !it.second.contains("R6SENTINEL_9f2c") })
        }
    }

    @Test
    fun `generate propagates CancellationException instead of mapping to Unknown`() {
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { throw kotlinx.coroutines.CancellationException("R6SENTINEL_9f2c") }
            .build()

        withPlantedLogs { logs ->
            org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                kotlinx.coroutines.runBlocking { r6AllowedService(client).generate(r6Input()) }
            }
            assertTrue(logs.none { it.second.contains("UNKNOWN_ERROR") })
        }
    }
}
