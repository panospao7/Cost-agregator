package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.ai.provider.internal.CloudCorrelation
import com.yourname.expensetracker.data.ai.provider.internal.CloudRetryPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.di.CloudAiHttpClient
import com.yourname.expensetracker.domain.ai.model.WarrantyExtractionInput
import com.yourname.expensetracker.domain.ai.model.WarrantyExtractionResult
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import javax.net.ssl.SSLException
import javax.inject.Inject
import javax.inject.Singleton

// PRIV-43B-01: Now uses CloudPayloadPolicy — no direct redactBeforeCloud access
@Singleton
class CloudWarrantyExtractionService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage,
    @CloudAiHttpClient private val client: OkHttpClient,
    private val privacyGate: PrivacyGate,
    private val cloudPayloadPolicy: CloudPayloadPolicy
) {
    private var apiKeyOverride: String? = null

    @androidx.annotation.VisibleForTesting
    internal constructor(apiKeyOverride: String, secureKeyStorage: SecureKeyStorage) : this(
        secureKeyStorage, OkHttpClient(),
        object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.FailClosed("PrivacyGate not configured in test constructor")
        },
        DefaultCloudPayloadPolicy(EffectiveCloudAiPolicyResolver.failClosedNoAi(), DefaultCloudPayloadRedactor())
    ) {
        this.apiKeyOverride = apiKeyOverride
    }

    private val apiKey: String
        get() = apiKeyOverride ?: secureKeyStorage.getGeminiKey() ?: ""

    suspend fun extractWarranty(input: WarrantyExtractionInput): WarrantyExtractionResult? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null

        val gateDecision = privacyGate.check(PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION)
        if (gateDecision.blocksExecution()) return@withContext null

        val correlationId = CloudCorrelation.newCorrelationId()
        // PRIV-43B-01: Build full raw prompt, then prepare through policy
        val rawPrompt = buildRawPrompt(input)
        val prepared = cloudPayloadPolicy.prepareText(CloudPayloadPurpose.WARRANTY_EXTRACTION, rawPrompt)

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prepared.text)))))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 1024)
            })
        }

        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/gemini-2.0-flash:generateContent"
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .build()

        for (attempt in 1..CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
            var retryableHttpFailure = false
            try {
                val parsed = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (CloudRetryPolicy.isRetryable(response.code) && attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                            retryableHttpFailure = true
                            val errorClass = "HTTP_${response.code}"
                            Timber.w(
                                "CloudWarrantyExtractionService: retryable HTTP %d class=%s correlationId=%s (attempt %d/%d)",
                                response.code,
                                errorClass,
                                correlationId,
                                attempt,
                                CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                            )
                        } else {
                            val errorClass = "HTTP_${response.code}"
                            Timber.w(
                                "CloudWarrantyExtractionService: HTTP %d class=%s correlationId=%s",
                                response.code,
                                errorClass,
                                correlationId
                            )
                        }
                        return@use null
                    }

                    val responseBody = response.body?.string()
                    parseResponse(responseBody, correlationId)
                }

                if (parsed != null) return@withContext parsed
                if (!retryableHttpFailure) return@withContext null
            } catch (e: SocketTimeoutException) {
                if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                    Timber.w(
                        e,
                        "CloudWarrantyExtractionService: timeout correlationId=%s, retrying (%d/%d)",
                        correlationId,
                        attempt,
                        CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                    )
                } else {
                    Timber.e(e, "CloudWarrantyExtractionService: timeout correlationId=%s", correlationId)
                    return@withContext null
                }
            } catch (e: SSLException) {
                Timber.e(e, "CloudWarrantyExtractionService: SSL error correlationId=%s", correlationId)
                return@withContext null
            } catch (e: IOException) {
                val retryable = CloudRetryPolicy.isRetryableIoException(e)
                if (retryable && attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                    Timber.w(
                        e,
                        "CloudWarrantyExtractionService: connection reset correlationId=%s, retrying (%d/%d)",
                        correlationId,
                        attempt,
                        CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                    )
                } else {
                    Timber.e(e, "CloudWarrantyExtractionService: network error extracting warranty correlationId=%s", correlationId)
                    return@withContext null
                }
            } catch (e: Exception) {
                Timber.e(e, "CloudWarrantyExtractionService: error extracting warranty correlationId=%s", correlationId)
                return@withContext null
            }

            if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                delay(CloudRetryPolicy.backoffDelayMs(attempt))
            }
        }

        null
    }

    private fun buildRawPrompt(input: WarrantyExtractionInput): String {
        val receiptText = input.receiptText.take(AppConfig.Ai.MAX_RECEIPT_OCR_CHARS_FOR_AI)
        val merchant = input.merchant ?: "Unknown"

        return """
            Analyze this receipt and extract warranty information.
            
            Receipt Text:
            $receiptText
            
            Merchant: $merchant
            Total Amount: ${input.totalAmount ?: "Unknown"}
            Date: ${input.purchaseDate ?: "Unknown"}
            
            Extract the following warranty information:
            1. Product name (if multiple items, list the most expensive or main item)
            2. Warranty duration in months (or convert years to months)
            3. Warranty type: MANUFACTURER (default), EXTENDED, STORE, or THIRD_PARTY
            4. Support phone number (if provided)
            5. Support email (if provided)
            6. Return policy: number of days allowed for returns
            7. Return conditions (any special conditions)
            
            Return ONLY a JSON object in this exact format:
            {
                "hasWarranty": true/false,
                "productName": "Product Name",
                "warrantyMonths": 24,
                "warrantyType": "MANUFACTURER",
                "supportPhone": "+1234567890" (or null),
                "supportEmail": "support@company.com" (or null),
                "returnDays": 30,
                "returnConditions": "Original packaging required" (or null),
                "confidence": 0.95
            }
            
            If no warranty information is found, return:
            {"hasWarranty": false, "confidence": 0.0}
            
            Be precise. If uncertain, use lower confidence scores.
        """.trimIndent()
    }

    private fun parseResponse(responseBody: String?, correlationId: String): WarrantyExtractionResult? {
        if (responseBody == null) {
            Timber.w("CloudWarrantyExtractionService: empty response body correlationId=%s", correlationId)
            return null
        }

        return try {
            val json = JSONObject(responseBody)
            val candidates = json.getJSONArray("candidates")
            if (candidates.length() == 0) {
                Timber.w("CloudWarrantyExtractionService: No candidates in response correlationId=%s", correlationId)
                return null
            }

            val content = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            // Extract JSON from the text (it might be wrapped in markdown)
            val jsonMatch = extractFirstJsonObject(content)
            
            if (jsonMatch == null) {
                Timber.w(
                    "CloudWarrantyExtractionService: No JSON found in response (contentLength=%d, contentHash=%s, correlationId=%s)",
                    content.length,
                    content.sha256Prefix(),
                    correlationId
                )
                return null
            }

            val warrantyJson = JSONObject(jsonMatch)
            
            val hasWarranty = warrantyJson.optBoolean("hasWarranty", false)
            val returnDays = warrantyJson.optInt("returnDays", 0).takeIf { it > 0 }
            val returnConditions = warrantyJson.optString("returnConditions").takeIf { it.isNotBlank() }
            val warrantyMonths = warrantyJson.optInt("warrantyMonths", 0).takeIf { it > 0 }

            if (!hasWarranty && returnDays == null && returnConditions == null) {
                return null
            }

            if (hasWarranty && warrantyMonths == null && returnDays == null && returnConditions == null) {
                return null
            }

            WarrantyExtractionResult(
                productName = warrantyJson.optString("productName", "Unknown Product"),
                warrantyType = parseWarrantyType(warrantyJson.optString("warrantyType", "MANUFACTURER")),
                warrantyMonths = warrantyMonths,
                supportPhone = warrantyJson.optString("supportPhone").takeIf { it.isNotBlank() },
                supportEmail = warrantyJson.optString("supportEmail").takeIf { it.isNotBlank() },
                returnDays = returnDays,
                returnConditions = returnConditions,
                confidence = warrantyJson.optDouble("confidence", 0.0).toFloat()
            )
        } catch (e: Exception) {
            val parseErrorCode = e::class.simpleName ?: "ParseError"
            Timber.e(
                e,
                "CloudWarrantyExtractionService: Error parsing warranty response (errorCode=%s, bodyLength=%d, bodyHash=%s, correlationId=%s)",
                parseErrorCode,
                responseBody.length,
                responseBody.sha256Prefix(),
                correlationId
            )
            null
        }
    }

    private fun sanitizeMerchant(rawMerchant: String?, shouldRedact: Boolean): String {
        val merchant = rawMerchant?.trim().takeUnless { it.isNullOrBlank() } ?: "Unknown"
        if (!shouldRedact) return merchant.take(80)
        return "merchant_${merchant.sha256Prefix()}"
    }

    private fun sanitizeReceiptText(rawText: String, shouldRedact: Boolean): String {
        val trimmed = rawText.trim().take(AppConfig.Ai.MAX_RECEIPT_OCR_CHARS_FOR_AI)
        if (!shouldRedact) return trimmed

        return trimmed
            .replace(EMAIL_REGEX, "[REDACTED_EMAIL]")
            .replace(IBAN_REGEX, "[REDACTED_IBAN]")
            .replace(CARD_REGEX, "[REDACTED_CARD]")
            .replace(PHONE_REGEX, "[REDACTED_PHONE]")
            .replace(LONG_NUMBER_REGEX, "[REDACTED_NUMBER]")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(AppConfig.Ai.MAX_RECEIPT_OCR_CHARS_FOR_AI)
    }

    private fun parseWarrantyType(type: String): String {
        val normalized = type.uppercase()
        return if (normalized in SUPPORTED_WARRANTY_TYPES) {
            normalized
        } else {
            "MANUFACTURER"
        }
    }

    private fun extractFirstJsonObject(text: String): String? {
        extractFencedJsonObject(text)?.let { return it }

        var start = -1
        var depth = 0
        var inString = false
        var isEscaped = false

        for (index in text.indices) {
            val ch = text[index]

            if (start == -1) {
                if (ch == '{') {
                    start = index
                    depth = 1
                }
                continue
            }

            if (inString) {
                if (isEscaped) {
                    isEscaped = false
                } else if (ch == '\\') {
                    isEscaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(start, index + 1)
                    }
                }
            }
        }

        return null
    }

    private fun extractFencedJsonObject(text: String): String? {
        val fencedMatch = JSON_FENCE_REGEX.find(text) ?: return null
        val fencedBody = fencedMatch.groupValues.getOrNull(1)?.trim().orEmpty()
        if (fencedBody.isBlank()) return null
        return extractFirstJsonObject(fencedBody)
    }

    private companion object {
        private val JSON_FENCE_REGEX = Regex("""```(?:json)?\s*([\s\S]*?)\s*```""", RegexOption.IGNORE_CASE)
        private val EMAIL_REGEX = Regex("""\b[\w._%+-]+@[\w.-]+\.[A-Za-z]{2,}\b""")
        private val IBAN_REGEX = Regex("""\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b""")
        private val CARD_REGEX = Regex("""\b(?:\d[ -]?){13,19}\b""")
        private val PHONE_REGEX = Regex("""\+?\d[\d\s().-]{6,}\d""")
        private val LONG_NUMBER_REGEX = Regex("""\b\d{10,}\b""")
        private val SUPPORTED_WARRANTY_TYPES = setOf("MANUFACTURER", "EXTENDED", "STORE", "THIRD_PARTY")
    }

    private fun String.sha256Prefix(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
