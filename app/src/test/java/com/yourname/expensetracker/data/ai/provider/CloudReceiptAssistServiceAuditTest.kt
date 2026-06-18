package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.PreparedCloudPayload
import com.yourname.expensetracker.domain.privacy.PrivacyAuditContext
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8F-03: verifies the representative cloud provider records cloud-call provenance
 * (provider/model/purpose/payloadHash/redaction flags) via [PrivacyAuditLogger.logCloudCall].
 *
 * Uses a hand-written recording logger rather than a mock so the assertion does not
 * depend on mockk's handling of interface default methods.
 */
class CloudReceiptAssistServiceAuditTest {

    /** Records the typed cloud-call provenance contexts passed to the audit logger. */
    private class RecordingAuditLogger : PrivacyAuditLogger {
        val cloudCalls = mutableListOf<Triple<PrivacyCapability, PrivacyDecision, PrivacyAuditContext>>()

        override suspend fun logDecision(
            capability: PrivacyCapability,
            decision: PrivacyDecision,
            context: Map<String, String>
        ) {
            // no-op: only cloud-call provenance is asserted here
        }

        override suspend fun logCloudCall(
            capability: PrivacyCapability,
            decision: PrivacyDecision,
            context: PrivacyAuditContext
        ) {
            cloudCalls.add(Triple(capability, decision, context))
        }
    }

    private fun createMockKeyStorage(apiKey: String): SecureKeyStorage {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns apiKey
        return mockKeyStorage
    }

    private fun allowedGate() = object : PrivacyGate {
        override suspend fun check(
            capability: PrivacyCapability,
            context: Map<String, String>
        ): PrivacyDecision = PrivacyDecision.Allowed
    }

    @Test
    fun `suggest records cloud-call provenance from prepared payload`() = runBlocking {
        val keyStorage = createMockKeyStorage(apiKey = "test-key")
        val settingsRepository = mockk<AiSettingsRepository>()
        every { settingsRepository.settings() } returns flowOf(AiSettings())

        val prepared = PreparedCloudPayload(
            purpose = CloudPayloadPurpose.RECEIPT_ASSIST,
            text = "redacted prompt",
            redactionApplied = true,
            fieldsRedacted = emptySet(),
            payloadHash = "hash-abc-123",
            rawTextIncluded = false,
            rawImageIncluded = false
        )
        val policy = mockk<CloudPayloadPolicy>()
        coEvery { policy.prepareReceiptAssist(any(), any(), any(), any(), any()) } returns prepared

        val auditLogger = RecordingAuditLogger()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              { "text": "{\"merchant\":{\"value\":\"Lidl\"},\"notes\":[]}" }
                            ]
                          }
                        }
                      ]
                    }
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudReceiptAssistService(
            settingsRepository,
            keyStorage,
            client,
            allowedGate(),
            policy,
            auditLogger
        )

        val result = service.suggest(
            ReceiptAssistInput(
                receiptId = 1L,
                rawOcrText = "LIDL TOTAL 12.34",
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

        assertTrue(result is AiServiceResult.Success<*>)
        assertEquals(1, auditLogger.cloudCalls.size)

        val (capability, decision, ctx) = auditLogger.cloudCalls.single()
        assertEquals(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST, capability)
        assertEquals(PrivacyDecision.Allowed, decision)
        assertEquals("gemini", ctx.provider)
        assertEquals(CloudPayloadPurpose.RECEIPT_ASSIST, ctx.purpose)
        assertEquals("hash-abc-123", ctx.payloadHash)
        assertEquals(true, ctx.redactionApplied)
        assertEquals(false, ctx.rawTextIncluded)
        assertEquals(false, ctx.rawImageIncluded)
        assertNotNull(ctx.modelId)
        assertNotNull(ctx.correlationId)
    }
}
