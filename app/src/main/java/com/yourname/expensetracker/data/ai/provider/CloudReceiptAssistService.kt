package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.SuggestedValue
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReceiptAssistService
import com.yourname.expensetracker.domain.config.AppConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import timber.log.Timber

@Singleton
// CRITICAL FIX (CRITICAL-1): Now uses SecureKeyStorage instead of BuildConfig
class CloudReceiptAssistService @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val secureKeyStorage: SecureKeyStorage
) : ReceiptAssistService {

    private var apiKeyOverride: String? = null

    // Secondary constructor for testing
    constructor(
        aiSettingsRepository: AiSettingsRepository,
        secureKeyStorage: SecureKeyStorage,
        apiKeyOverride: String
    ) : this(aiSettingsRepository, secureKeyStorage) {
        this.apiKeyOverride = apiKeyOverride
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(AppConfig.Ai.RECEIPT_ASSIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AppConfig.Ai.RECEIPT_ASSIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val apiKey: String
        get() = apiKeyOverride ?: secureKeyStorage.getGeminiKey() ?: ""

    override fun usedImageInput(input: ReceiptAssistInput): Boolean =
        input.imagePath != null && input.imageMimeType != null

    override suspend fun suggest(input: ReceiptAssistInput): ReceiptAssistSuggestion? {
        if (apiKey.isBlank()) {
            Timber.d("CloudReceiptAssistService: Gemini API key missing, skipping.")
            return null
        }

        val settings = aiSettingsRepository.settings().first()
        // NEW: Use input.isImageAnalysisMode to decide if we should include the image
        val useImageAnalysis = input.isImageAnalysisMode && settings.receiptImageCloudEnabled
        val requestBody = buildRequestBody(input, useImageAnalysis)
        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.RECEIPT_ASSIST_CLOUD_MODEL}:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w(
                        "CloudReceiptAssistService: HTTP ${response.code} ${response.body?.string()?.take(200)}"
                    )
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                parseResponse(body)
            }
        } catch (e: IOException) {
            Timber.w(e, "CloudReceiptAssistService: network failure")
            null
        } catch (e: Exception) {
            Timber.w(e, "CloudReceiptAssistService: parse failure")
            null
        }
    }

    internal fun buildRequestBodyForTest(input: ReceiptAssistInput, allowImage: Boolean): String =
        buildRequestBody(input, allowImage)

    private fun buildRequestBody(input: ReceiptAssistInput, allowImage: Boolean): String {
        val prompt = buildPrompt(input)
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        buildImageInlineData(input, allowImage)?.let(parts::put)
        return JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts", parts
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", AppConfig.Ai.RECEIPT_ASSIST_MAX_OUTPUT_TOKENS)
                    put("responseMimeType", "application/json")
                    put(
                        "thinkingConfig",
                        JSONObject().apply {
                            put("thinkingBudget", 0)
                        }
                    )
                }
            )
        }.toString()
    }

    private fun buildPrompt(input: ReceiptAssistInput): String {
        val imageMode = if (usedImageInput(input)) {
            """
            |CRITICAL - IMAGE IS SOURCE OF TRUTH:
            |1. Read merchant name, total amount, date, and tax DIRECTLY from the attached receipt image.
            |2. The OCR text below may be CORRUPTED or WRONG - especially for Greek receipts.
            |3. Common Greek OCR errors to watch for:
            |   - ά→α, έ→ε, ή→η, ό→ο, ύ→υ, ώ→ω (Greek accents often lost)
            |   - ΐ→ί, ΰ→ύ (dieresis marks confused)
            |   - Numbers: 3→8, 1→7, 5→6, 0→8, 2→ζ
            |   - Greek-Latin mix: μ→u, α→a, ο→o, κ→k, ε→e, ν→v
            |4. If the image shows a DIFFERENT value than OCR, TRUST THE IMAGE.
            |5. Do NOT let OCR text anchor you to wrong values.
            """.trimMargin()
        } else {
            "No receipt image available. Use OCR text only - be extra careful with Greek characters."
        }
        return """
            You are an expert at reading Greek and European receipts.
            $imageMode

            Stay conservative.
            Return compact JSON only.

            JSON schema:
            {
              "merchant": { "value": "string", "confidence": 0.0, "rationale": "string" } | null,
              "total": { "value": 0.0, "confidence": 0.0, "rationale": "string" } | null,
              "date": { "value": 0, "confidence": 0.0, "rationale": "string" } | null,
              "taxAmount": { "value": 0.0, "confidence": 0.0, "rationale": "string" } | null,
              "notes": ["short note"]
            }

            Rules:
            - Prefer null over guessing.
            - Only provide date if clearly readable on the image.
            - Keep notes short.
            - When image and OCR disagree, THE IMAGE IS CORRECT.

            Receipt facts (OCR - may be corrupted, especially for Greek characters):
            - currency: ${input.currency}
            - parsedMerchant: ${input.parsedMerchant ?: "none"}
            - parsedTotal: ${input.parsedTotal?.toString() ?: "none"}
            - parsedDate: ${input.parsedDate?.toString() ?: "none"}
            - parsedTaxAmount: ${input.parsedTaxAmount?.toString() ?: "none"}
            - lineItemsJson: ${input.lineItemsJson ?: "none"}
            - rawOcrText:
            ${input.rawOcrText}
        """.trimIndent()
    }

    private fun buildImageInlineData(input: ReceiptAssistInput, allowImage: Boolean): JSONObject? {
        if (!allowImage) return null
        val imagePath = input.imagePath ?: return null
        val mimeType = input.imageMimeType ?: return null
        val file = File(imagePath)
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        if (bytes.size > MAX_INLINE_IMAGE_BYTES) {
            Timber.d("CloudReceiptAssistService: receipt image too large for inline upload (%d bytes)", bytes.size)
            return null
        }

        return JSONObject().put(
            "inlineData",
            JSONObject()
                .put("mimeType", mimeType)
                .put("data", Base64.getEncoder().encodeToString(bytes))
        )
    }

    private fun parseResponse(body: String): ReceiptAssistSuggestion? {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val text = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.trim()
            ?: return null

        val jsonText = extractFirstJsonObject(text) ?: return null
        val suggestion = JSONObject(jsonText)

        return ReceiptAssistSuggestion(
            merchant = suggestion.optJSONObject("merchant")?.toSuggestedStringOrNull(),
            total = suggestion.optJSONObject("total")?.toSuggestedDoubleOrNull(),
            date = suggestion.optJSONObject("date")?.toSuggestedLongOrNull(),
            taxAmount = suggestion.optJSONObject("taxAmount")?.toSuggestedDoubleOrNull(),
            notes = suggestion.optJSONArray("notes").toStringList()
        )
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun JSONObject.toSuggestedStringOrNull(): SuggestedValue<String>? {
        val value = optString("value").trim()
        if (value.isBlank()) return null
        return SuggestedValue(
            value = value,
            confidence = optDoubleOrNull("confidence")?.toFloat(),
            rationale = optString("rationale").trim().ifBlank { null }
        )
    }

    private fun JSONObject.toSuggestedDoubleOrNull(): SuggestedValue<Double>? {
        if (!has("value") || isNull("value")) return null
        val value = optDouble("value")
        return SuggestedValue(
            value = value,
            confidence = optDoubleOrNull("confidence")?.toFloat(),
            rationale = optString("rationale").trim().ifBlank { null }
        )
    }

    private fun JSONObject.toSuggestedLongOrNull(): SuggestedValue<Long>? {
        if (!has("value") || isNull("value")) return null
        val value = optLong("value")
        return SuggestedValue(
            value = value,
            confidence = optDoubleOrNull("confidence")?.toFloat(),
            rationale = optString("rationale").trim().ifBlank { null }
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_INLINE_IMAGE_BYTES = 2 * 1024 * 1024
    }
}
