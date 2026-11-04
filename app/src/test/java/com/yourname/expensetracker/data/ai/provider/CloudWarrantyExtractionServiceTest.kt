package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.WarrantyExtractionInput
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CloudWarrantyExtractionServiceTest {

    private fun createMockKeyStorage(apiKey: String = ""): SecureKeyStorage {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns apiKey
        return mockKeyStorage
    }

    private fun sampleInput() = WarrantyExtractionInput(
        receiptText = "Receipt text",
        merchant = "Test Store",
        totalAmount = 199.99,
        purchaseDate = 1_700_000_000_000,
        currency = "EUR"
    )

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `extractWarranty returns null when API key is missing`() {
        val service = CloudWarrantyExtractionService(
            secureKeyStorage = createMockKeyStorage(apiKey = ""),
            client = OkHttpClient(),
            privacyGate = mockk<PrivacyGate>(relaxed = true),
            cloudPayloadPolicy = com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy(
                com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedNoAi(),
                com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor()
            )
        )

        val result = runBlocking {
            service.extractWarranty(sampleInput())
        }

        assertNull(result)
    }

    @Test
    fun `extractWarranty parses domain result from successful response`() {
        val successBody = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"hasWarranty\":true,\"productName\":\"Gaming Laptop\",\"warrantyMonths\":24,\"warrantyType\":\"EXTENDED\",\"supportPhone\":\"+1234567890\",\"supportEmail\":\"support@example.com\",\"returnDays\":14,\"returnConditions\":\"Factory seal required\",\"confidence\":0.92}"
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
                    .body(successBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudWarrantyExtractionService(
            secureKeyStorage = createMockKeyStorage(apiKey = "test-key"),
            client = client,
            privacyGate = mockk<PrivacyGate>(relaxed = true),
            cloudPayloadPolicy = com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy(
                com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedNoAi(),
                com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor()
            )
        )

        val result = runBlocking {
            service.extractWarranty(sampleInput())
        }

        assertNotNull(result)
        assertEquals("Gaming Laptop", result?.productName)
        assertEquals(24, result?.warrantyMonths)
        assertEquals("EXTENDED", result?.warrantyType)
        assertEquals("+1234567890", result?.supportPhone)
        assertEquals("support@example.com", result?.supportEmail)
        assertEquals(14, result?.returnDays)
        assertEquals("Factory seal required", result?.returnConditions)
    }

    @Test
    fun `extractWarranty returns null when model reports no warranty`() {
        val noWarrantyBody = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"hasWarranty\":false,\"confidence\":0.0}"
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
                    .body(noWarrantyBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudWarrantyExtractionService(
            secureKeyStorage = createMockKeyStorage(apiKey = "test-key"),
            client = client,
            privacyGate = mockk<PrivacyGate>(relaxed = true),
            cloudPayloadPolicy = com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy(
                com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedNoAi(),
                com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor()
            )
        )

        val result = runBlocking {
            service.extractWarranty(sampleInput())
        }

        assertNull(result)
    }

    @Test
    fun `extractWarranty preserves return policy only responses without warranty months`() {
        val returnPolicyBody = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"hasWarranty\":false,\"productName\":\"Shoes\",\"returnDays\":30,\"returnConditions\":\"Unworn only\",\"confidence\":0.81}"
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
                    .body(returnPolicyBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val service = CloudWarrantyExtractionService(
            secureKeyStorage = createMockKeyStorage(apiKey = "test-key"),
            client = client,
            privacyGate = mockk<PrivacyGate>(relaxed = true),
            cloudPayloadPolicy = com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy(
                com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedNoAi(),
                com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor()
            )
        )

        val result = runBlocking {
            service.extractWarranty(sampleInput())
        }

        assertNotNull(result)
        assertEquals(null, result?.warrantyMonths)
        assertEquals(30, result?.returnDays)
        assertEquals("Unworn only", result?.returnConditions)
    }

    // ---- buildRawPrompt budget / schema / redaction (S3) ----

    private class CapturingPolicy(
        private val delegate: com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
    ) : com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy by delegate {
        var rawPrompt: String? = null
        var preparedText: String? = null

        override suspend fun prepareText(
            purpose: com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose,
            rawText: String,
            context: com.yourname.expensetracker.domain.privacy.SafePrivacyMetadata
        ): com.yourname.expensetracker.domain.privacy.PreparedCloudPayload {
            rawPrompt = rawText
            val prepared = delegate.prepareText(purpose, rawText, context)
            preparedText = prepared.text
            return prepared
        }
    }

    /** Runs extractWarranty through the real policy and redactor; the HTTP call is answered with a non-retryable 400. */
    private fun runAndCapture(input: WarrantyExtractionInput): CapturingPolicy {
        val policy = CapturingPolicy(
            com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy(
                com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedNoAi(),
                com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor()
            )
        )
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("Bad Request")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val service = CloudWarrantyExtractionService(
            secureKeyStorage = createMockKeyStorage(apiKey = "test-key"),
            client = client,
            privacyGate = mockk<PrivacyGate>(relaxed = true),
            cloudPayloadPolicy = policy
        )
        runBlocking { service.extractWarranty(input) }
        return policy
    }

    private fun oversizeReceipt(prefix: String = "RECEIPT-BODY-START"): String =
        prefix + "\n" + "Item line 12.50 EUR\n".repeat(600) + "TAIL-MARKER-END"

    @Test
    fun `oversize receipt prompt keeps full schema and instructions and stays at most 3000 chars`() {
        val captured = runAndCapture(
            WarrantyExtractionInput(oversizeReceipt(), "Test Store", 199.99, 1_700_000_000_000, "EUR")
        )
        val raw = requireNotNull(captured.rawPrompt)

        org.junit.Assert.assertTrue("raw prompt length ${raw.length}", raw.length <= 3000)
        listOf(
            "Analyze this receipt and extract warranty information.",
            "\"hasWarranty\": true/false,",
            "\"productName\": \"Product Name\",",
            "\"warrantyMonths\": 24,",
            "\"warrantyType\": \"MANUFACTURER\",",
            "\"supportPhone\": \"<phone number>\" (or null),",
            "\"supportEmail\": \"<email address>\" (or null),",
            "\"returnDays\": 30,",
            "\"returnConditions\": \"Original packaging required\" (or null),",
            "\"confidence\": 0.95",
            "If no warranty information is found, return:",
            "{\"hasWarranty\": false, \"confidence\": 0.0}",
            "Be precise. If uncertain, use lower confidence scores."
        ).forEach { fragment ->
            org.junit.Assert.assertTrue("missing: $fragment", raw.contains(fragment))
        }
        // The receipt was actually truncated, not dropped.
        org.junit.Assert.assertFalse(raw.contains("TAIL-MARKER-END"))
        org.junit.Assert.assertTrue(raw.contains("RECEIPT-BODY-START"))
    }

    @Test
    fun `email straddling the receipt cut leaves no fragment in the prepared text`() {
        val probe = requireNotNull(
            runAndCapture(WarrantyExtractionInput(oversizeReceipt(), "Test Store", 199.99, 1_700_000_000_000, "EUR")).rawPrompt
        )
        val budget = 3000 - (probe.indexOf("Receipt Text:\n") + "Receipt Text:\n".length)
        val receipt = "a ".repeat(budget).take(budget - 8) + "john@example.com" + " tail".repeat(100)

        val prepared = requireNotNull(
            runAndCapture(WarrantyExtractionInput(receipt, "Test Store", 199.99, 1_700_000_000_000, "EUR")).preparedText
        )

        org.junit.Assert.assertFalse(prepared.contains("john@"))
        org.junit.Assert.assertFalse(prepared.contains("@exam"))
    }

    @Test
    fun `echoed schema placeholders for phone and email are not stored`() {
        val body = """
            {"candidates":[{"content":{"parts":[{"text":"{\"hasWarranty\":true,\"productName\":\"TV\",\"warrantyMonths\":12,\"supportPhone\":\"<phone number>\",\"supportEmail\":\"<email address>\",\"confidence\":0.9}"}]}}]}
        """.trimIndent()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val service = CloudWarrantyExtractionService(
            secureKeyStorage = createMockKeyStorage(apiKey = "test-key"),
            client = client,
            privacyGate = mockk<PrivacyGate>(relaxed = true),
            cloudPayloadPolicy = com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy(
                com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver.failClosedNoAi(),
                com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor()
            )
        )

        val result = runBlocking { service.extractWarranty(sampleInput()) }

        assertNotNull(result)
        assertNull(result?.supportPhone)
        assertNull(result?.supportEmail)
    }

    @Test
    fun `oversize receipt text comes after the schema`() {
        val captured = runAndCapture(
            WarrantyExtractionInput(oversizeReceipt(), "Test Store", 199.99, 1_700_000_000_000, "EUR")
        )
        val raw = requireNotNull(captured.rawPrompt)

        val schemaEnd = raw.indexOf("Be precise. If uncertain, use lower confidence scores.")
        val receiptStart = raw.indexOf("RECEIPT-BODY-START")
        org.junit.Assert.assertTrue(schemaEnd >= 0)
        org.junit.Assert.assertTrue(receiptStart > schemaEnd)
        org.junit.Assert.assertTrue(raw.indexOf("Receipt Text:") in (schemaEnd + 1) until receiptStart)
    }

    @Test
    fun `merchant over 80 chars is capped in the prompt`() {
        val captured = runAndCapture(
            WarrantyExtractionInput("Short receipt", "M".repeat(200), 10.0, 1_700_000_000_000, "EUR")
        )
        val raw = requireNotNull(captured.rawPrompt)

        org.junit.Assert.assertTrue(raw.contains("Merchant: " + "M".repeat(80) + "\n"))
        org.junit.Assert.assertFalse(raw.contains("M".repeat(81)))
    }

    @Test
    fun `merchant of exactly 80 chars is kept whole`() {
        val captured = runAndCapture(
            WarrantyExtractionInput("Short receipt", "N".repeat(80), 10.0, 1_700_000_000_000, "EUR")
        )
        org.junit.Assert.assertTrue(
            requireNotNull(captured.rawPrompt).contains("Merchant: " + "N".repeat(80) + "\n")
        )
    }

    @Test
    fun `short receipt is passed in full`() {
        val receipt = "Laptop X1\n24 months manufacturer warranty\nTotal 199.99 EUR"
        val captured = runAndCapture(
            WarrantyExtractionInput(receipt, "Test Store", 199.99, 1_700_000_000_000, "EUR")
        )
        val raw = requireNotNull(captured.rawPrompt)

        org.junit.Assert.assertTrue(raw.endsWith("Receipt Text:\n$receipt"))
    }

    @Test
    fun `email and phone in receipt text are still redacted and the schema survives redaction`() {
        val receipt = "Laptop X1\nContact support@shop.example for help\nTel: +30 210 123 4567\n" +
            "Item line 12.50 EUR\n".repeat(600)
        val captured = runAndCapture(
            WarrantyExtractionInput(receipt, "Test Store", 199.99, 1_700_000_000_000, "EUR")
        )
        val prepared = requireNotNull(captured.preparedText)

        org.junit.Assert.assertFalse(prepared.contains("support@shop.example"))
        org.junit.Assert.assertFalse(prepared.contains("210 123 4567"))
        org.junit.Assert.assertTrue(prepared.contains("[REDACTED_EMAIL]"))
        org.junit.Assert.assertTrue(prepared.contains("[REDACTED_PHONE]"))
        org.junit.Assert.assertTrue(prepared.length <= 3000)

        // Schema is intact (redaction collapses whitespace, so compare single-spaced fragments) and untouched by markers.
        // The header's Date value is a 13-digit epoch-ms number, which existing policy masks as a long number/card;
        // only the schema and instructions region (before "Merchant:") must be marker-free.
        val schemaPart = prepared.substringBefore("Merchant:")
        val headerValues = prepared.substringAfter("Merchant:").substringBefore("Receipt Text:")
        org.junit.Assert.assertTrue(headerValues.contains("Test Store"))
        org.junit.Assert.assertTrue(headerValues.contains("199.99"))
        org.junit.Assert.assertFalse(headerValues.contains("1700000000000"))
        org.junit.Assert.assertTrue(headerValues.substringAfter("Date:").contains("[REDACTED_"))
        org.junit.Assert.assertTrue(schemaPart.contains("\"supportPhone\": \"<phone number>\" (or null),"))
        org.junit.Assert.assertTrue(schemaPart.contains("\"supportEmail\": \"<email address>\" (or null),"))
        org.junit.Assert.assertTrue(schemaPart.contains("\"confidence\": 0.95"))
        org.junit.Assert.assertTrue(schemaPart.contains("Be precise. If uncertain, use lower confidence scores."))
        org.junit.Assert.assertFalse(schemaPart.contains("[REDACTED"))
    }
}
