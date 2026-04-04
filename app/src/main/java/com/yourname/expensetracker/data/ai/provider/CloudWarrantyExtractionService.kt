package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyType
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.di.CloudAiHttpClient
import com.yourname.expensetracker.domain.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CRITICAL FIX (CRITICAL-1): Now uses SecureKeyStorage instead of BuildConfig.
 * API key is retrieved from encrypted storage at runtime, not compiled into APK.
 */
@Singleton
class CloudWarrantyExtractionService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage,
    @CloudAiHttpClient private val client: OkHttpClient
) {
    private var apiKeyOverride: String? = null

    internal constructor(apiKeyOverride: String, secureKeyStorage: SecureKeyStorage) : this(secureKeyStorage, OkHttpClient()) {
        this.apiKeyOverride = apiKeyOverride
    }

    /**
     * CRITICAL: API key is now retrieved from secure storage at runtime.
     * No longer compiled into BuildConfig, preventing APK extraction.
     */
    private val apiKey: String
        get() = apiKeyOverride ?: secureKeyStorage.getGeminiKey() ?: ""

    suspend fun extractWarranty(
        receipt: ScannedReceipt,
        shouldRedactBeforeCloud: Boolean
    ): Warranty? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Timber.d("CloudWarrantyExtractionService: Gemini API key missing, skipping.")
            return@withContext null
        }

        val prompt = buildPrompt(receipt, shouldRedactBeforeCloud)
        
        try {
            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
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

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("Gemini API error: ${response.code}")
                    return@withContext null
                }

                val responseBody = response.body?.string()
                parseResponse(responseBody, receipt)
            }
        } catch (e: IOException) {
            Timber.e(e, "Network error extracting warranty")
            null
        } catch (e: Exception) {
            Timber.e(e, "Error extracting warranty")
            null
        }
    }

    private fun buildPrompt(receipt: ScannedReceipt, shouldRedactBeforeCloud: Boolean): String {
        val safeReceiptText = sanitizeReceiptText(receipt.rawOcrText, shouldRedactBeforeCloud)
        val safeMerchant = sanitizeMerchant(receipt.parsedMerchant, shouldRedactBeforeCloud)

        return """
            Analyze this receipt and extract warranty information.
            
            Receipt Text:
            $safeReceiptText
            
            Merchant: $safeMerchant
            Total Amount: ${receipt.parsedTotal ?: "Unknown"}
            Date: ${receipt.parsedDate ?: "Unknown"}
            
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

    private fun parseResponse(responseBody: String?, receipt: ScannedReceipt): Warranty? {
        if (responseBody == null) return null

        return try {
            val json = JSONObject(responseBody)
            val candidates = json.getJSONArray("candidates")
            if (candidates.length() == 0) return null

            val content = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            // Extract JSON from the text (it might be wrapped in markdown)
            val jsonMatch = extractFirstJsonObject(content)
            
            if (jsonMatch == null) {
                Timber.w(
                    "No JSON found in response (contentLength=%d, contentHash=%s)",
                    content.length,
                    content.sha256Prefix()
                )
                return null
            }

            val warrantyJson = JSONObject(jsonMatch)
            
            if (!warrantyJson.optBoolean("hasWarranty", false)) {
                return null
            }

            val warrantyMonths = warrantyJson.optInt("warrantyMonths", 0)
            if (warrantyMonths <= 0) {
                return null // No valid warranty duration
            }

            val purchaseDate = receipt.parsedDate ?: receipt.createdAt
            val warrantyEndDate = purchaseDate + (warrantyMonths * 30L * 24 * 60 * 60 * 1000)

            Warranty(
                receiptId = receipt.id,
                expenseId = receipt.expenseId,
                productName = warrantyJson.optString("productName", "Unknown Product"),
                merchantName = receipt.parsedMerchant ?: "Unknown",
                purchaseDate = purchaseDate,
                warrantyDurationMonths = warrantyMonths,
                warrantyEndDate = warrantyEndDate,
                warrantyType = parseWarrantyType(warrantyJson.optString("warrantyType", "MANUFACTURER")),
                supportPhone = warrantyJson.optString("supportPhone").takeIf { it.isNotBlank() },
                supportEmail = warrantyJson.optString("supportEmail").takeIf { it.isNotBlank() },
                notes = warrantyJson.optString("returnConditions").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            val parseErrorCode = e::class.simpleName ?: "ParseError"
            Timber.e(
                e,
                "Error parsing warranty response (errorCode=%s, bodyLength=%d, bodyHash=%s)",
                parseErrorCode,
                responseBody.length,
                responseBody.sha256Prefix()
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

    private fun parseWarrantyType(type: String): WarrantyType {
        return try {
            WarrantyType.valueOf(type.uppercase())
        } catch (e: IllegalArgumentException) {
            WarrantyType.MANUFACTURER
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
    }

    private fun String.sha256Prefix(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
