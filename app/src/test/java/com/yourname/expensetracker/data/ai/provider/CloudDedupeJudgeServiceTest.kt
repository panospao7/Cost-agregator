package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.DedupeCandidateSummary
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.DuplicateVerdict
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CloudDedupeJudgeServiceTest {

    private fun sampleInput(): DedupeJudgeInput {
        return DedupeJudgeInput(
            subject = DedupeCandidateSummary(
                targetType = AiTargetType.PENDING_REVIEW,
                targetId = 1L,
                merchant = "Lidl",
                amount = 24.5,
                currency = "EUR",
                date = 1_000L,
                sourceLabel = "bank",
                textPreview = "Card purchase at Lidl"
            ),
            candidates = listOf(
                DedupeCandidateSummary(
                    targetType = AiTargetType.EXPENSE,
                    targetId = 2L,
                    merchant = "Lidl",
                    amount = 24.5,
                    currency = "EUR",
                    date = 1_010L,
                    sourceLabel = "expense",
                    textPreview = "Card purchase at Lidl"
                )
            )
        )
    }

    @Test
    fun `judge parses successful cloud JSON response`() = runTest {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "fake-api-key"

        val responseBody =
            """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"verdict\":\"LIKELY_DUPLICATE\",\"matchedTargetType\":\"EXPENSE\",\"matchedTargetId\":2,\"confidence\":0.93,\"rationale\":\"Same merchant and amount\"}"
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
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudDedupeJudgeService(
            secureKeyStorage = mockKeyStorage,
            client = client,
            aiSettingsRepository = null,
            privacyGate = mockk(relaxed = true),
            redactor = com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor(),
            policyResolver = com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedNoAi()
        )

        val result = service.judge(sampleInput())

        assertTrue(result is AiServiceResult.Success<*>)
        val success = result as AiServiceResult.Success<com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion>
        assertEquals(DuplicateVerdict.LIKELY_DUPLICATE, success.value.verdict)
        assertEquals(AiTargetType.EXPENSE, success.value.matchedTargetType)
        assertEquals(2L, success.value.matchedTargetId)
        assertEquals(0.93f, success.value.confidence)
        assertEquals("Same merchant and amount", success.value.rationale)
    }

    @Test
    fun `judge returns offline failure when http client throws IOException`() = runTest {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "fake-api-key"

        val client = OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("network down")
            }
            .build()

        val service = CloudDedupeJudgeService(
            secureKeyStorage = mockKeyStorage,
            client = client,
            aiSettingsRepository = null,
            privacyGate = mockk(relaxed = true),
            redactor = com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor(),
            policyResolver = com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedNoAi()
        )

        val result = service.judge(sampleInput())

        assertTrue(result is AiServiceResult.Failure)
        val failure = result as AiServiceResult.Failure
        assertTrue(failure.error is AiServiceError.Offline)
    }

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `judge returns disabled when api key is missing`() = runTest {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns ""

        val service = CloudDedupeJudgeService(mockKeyStorage)
        val result = service.judge(sampleInput())

        assertTrue(result is AiServiceResult.Failure)
        val failure = result as AiServiceResult.Failure
        assertTrue(failure.error is AiServiceError.Disabled)
    }

    @Test
    fun `judge returns parse error for malformed verdict enum`() = runTest {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "fake-api-key"

        val responseBody =
            """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"verdict\":\"MAYBE\",\"matchedTargetType\":\"EXPENSE\",\"matchedTargetId\":2,\"confidence\":0.93}"
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
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudDedupeJudgeService(
            secureKeyStorage = mockKeyStorage,
            client = client,
            aiSettingsRepository = null,
            privacyGate = mockk(relaxed = true),
            redactor = com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor(),
            policyResolver = com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedNoAi()
        )

        val result = service.judge(sampleInput())

        assertTrue(result is AiServiceResult.Failure)
        val failure = result as AiServiceResult.Failure
        assertTrue(failure.error is AiServiceError.ParseError)
    }

    @Test
    fun `judge maps malformed target type enum and zero id to null`() = runTest {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "fake-api-key"

        val responseBody =
            """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"verdict\":\"UNCERTAIN\",\"matchedTargetType\":\"OTHER\",\"matchedTargetId\":0,\"confidence\":0.4}"
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
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudDedupeJudgeService(
            secureKeyStorage = mockKeyStorage,
            client = client,
            aiSettingsRepository = null,
            privacyGate = mockk(relaxed = true),
            redactor = com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor(),
            policyResolver = com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedNoAi()
        )

        val result = service.judge(sampleInput())

        assertTrue(result is AiServiceResult.Success<*>)
        val success = result as AiServiceResult.Success<com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion>
        assertNull(success.value.matchedTargetType)
        assertNull(success.value.matchedTargetId)
    }
}
