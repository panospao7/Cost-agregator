package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.di.CloudAiHttpClient
import com.yourname.expensetracker.data.ai.provider.internal.CloudCorrelation
import com.yourname.expensetracker.data.ai.provider.internal.CloudJsonParser
import com.yourname.expensetracker.data.ai.provider.internal.CloudPiiSanitizer
import com.yourname.expensetracker.data.ai.provider.internal.CloudRetryPolicy
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.SuggestedValue
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReceiptAssistService
import com.yourname.expensetracker.domain.config.AppConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.io.File
import java.util.Base64
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
// CRITICAL FIX (CRITICAL-1): Now uses SecureKeyStorage instead of BuildConfig
class CloudReceiptAssistService @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val secureKeyStorage: SecureKeyStorage,
    @CloudAiHttpClient private val client: OkHttpClient
) : ReceiptAssistService {

    private var apiKeyOverride: String? = null

    // Secondary constructor for tests
    constructor(
        aiSettingsRepository: AiSettingsRepository,
        secureKeyStorage: SecureKeyStorage
    ) : this(aiSettingsRepository, secureKeyStorage, OkHttpClient())

    // Secondary constructor for testing
    constructor(
        aiSettingsRepository: AiSettingsRepository,
        secureKeyStorage: SecureKeyStorage,
        apiKeyOverride: String
    ) : this(aiSettingsRepository, secureKeyStorage, OkHttpClient()) {
        this.apiKeyOverride = apiKeyOverride
    }

    private val apiKey: String
        get() = apiKeyOverride ?: secureKeyStorage.getGeminiKey() ?: ""

    override fun usedImageInput(input: ReceiptAssistInput): Boolean =
        input.imagePath != null && input.imageMimeType != null && !input.redactBeforeCloud

    override suspend fun suggest(input: ReceiptAssistInput): AiServiceResult<ReceiptAssistSuggestion> {
        if (apiKey.isBlank()) {
            Timber.d("CloudReceiptAssistService: Gemini API key missing, skipping.")
            return AiServiceResult.Failure(AiServiceError.Disabled("Gemini API key missing"))
        }

        val settings = aiSettingsRepository.settings().first()
        val allowImage = input.isImageAnalysisMode && settings.receiptImageCloudEnabled
        val requestPayload = buildRequestPayload(input, allowImage)
        val requestBody = requestPayload.jsonBody
        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.RECEIPT_ASSIST_CLOUD_MODEL}:generateContent"
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .build()

        return withContext(Dispatchers.IO) {
            for (attempt in 1..CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                try {
                    val outcome = client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val correlationId = CloudCorrelation.newCorrelationId()
                            val errorClass = "HTTP_${response.code}"
                            if (CloudRetryPolicy.isRetryable(response.code) && attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                                Timber.w(
                                    "CloudReceiptAssistService: retryable HTTP %d class=%s correlationId=%s (attempt %d/%d)",
                                    response.code,
                                    errorClass,
                                    correlationId,
                                    attempt,
                                    CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                                )
                                null
                            } else {
                                Timber.w(
                                    "CloudReceiptAssistService: HTTP %d class=%s correlationId=%s",
                                    response.code,
                                    errorClass,
                                    correlationId
                                )
                                AiServiceResult.Failure(
                                    AiServiceError.HttpError(
                                        response.code,
                                        "errorClass=$errorClass correlationId=$correlationId"
                                    )
                                )
                            }
                        } else {
                            val body = response.body?.string()
                                ?: return@use AiServiceResult.Failure(AiServiceError.ParseError("Empty response body"))
                            val parsed = parseResponse(body)
                                ?: return@use AiServiceResult.Failure(AiServiceError.ParseError("No usable suggestion in response"))
                            AiServiceResult.Success(
                                parsed.copy(usedImageInput = requestPayload.actuallyUsedImageInput)
                            )
                        }
                    }

                    if (outcome != null) return@withContext outcome
                } catch (e: SocketTimeoutException) {
                    if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        Timber.w(
                            e,
                            "CloudReceiptAssistService: timeout, retrying (%d/%d)",
                            attempt,
                            CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                        )
                    } else {
                        Timber.w(e, "CloudReceiptAssistService: timeout")
                        return@withContext AiServiceResult.Failure(AiServiceError.Timeout)
                    }
                } catch (e: SSLException) {
                    Timber.w(e, "CloudReceiptAssistService: SSL failure")
                    return@withContext AiServiceResult.Failure(AiServiceError.SslError)
                } catch (e: IOException) {
                    if (CloudRetryPolicy.isRetryableIoException(e) && attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        Timber.w(
                            e,
                            "CloudReceiptAssistService: network failure, retrying (%d/%d)",
                            attempt,
                            CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                        )
                    } else {
                        Timber.w(e, "CloudReceiptAssistService: network failure")
                        return@withContext AiServiceResult.Failure(AiServiceError.Offline)
                    }
                } catch (e: JSONException) {
                    Timber.w(e, "CloudReceiptAssistService: JSON parse failure")
                    return@withContext AiServiceResult.Failure(AiServiceError.ParseError(e.message))
                } catch (e: Exception) {
                    Timber.w(e, "CloudReceiptAssistService: parse failure")
                    return@withContext AiServiceResult.Failure(AiServiceError.Unknown(e.message))
                }

                if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                    delay(CloudRetryPolicy.backoffDelayMs(attempt))
                }
            }

            AiServiceResult.Failure(AiServiceError.Unknown("Retry attempts exhausted"))
        }
    }

    internal fun buildRequestBodyForTest(input: ReceiptAssistInput, allowImage: Boolean): String =
        buildRequestPayload(input, allowImage).jsonBody

    private fun buildRequestPayload(input: ReceiptAssistInput, allowImage: Boolean): RequestPayload {
        val inlineImagePart = buildImageInlineData(input, allowImage)
        val prompt = buildPrompt(input, hasAttachedImage = inlineImagePart != null)
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        inlineImagePart?.let(parts::put)

        val requestJson = JSONObject().apply {
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

        return RequestPayload(
            jsonBody = requestJson,
            actuallyUsedImageInput = inlineImagePart != null
        )
    }

    private fun buildPrompt(input: ReceiptAssistInput, hasAttachedImage: Boolean): String {
        val safeParsedMerchant = if (input.redactBeforeCloud) {
            input.parsedMerchant?.let { CloudPiiSanitizer.sanitizeMerchant(it, shouldRedact = true) }
        } else {
            input.parsedMerchant
        }
        val safeLineItemsJson = if (input.redactBeforeCloud) {
            input.lineItemsJson?.let {
                CloudPiiSanitizer.sanitizeText(
                    raw = it,
                    maxChars = AppConfig.Ai.MAX_CAPTURE_SUPPORTING_TEXT_CHARS,
                    fallbackPrefix = "line_items"
                )
            }
        } else {
            input.lineItemsJson
        }
        val safeRawOcrText = if (input.redactBeforeCloud) {
            CloudPiiSanitizer.sanitizeText(
                raw = input.rawOcrText,
                maxChars = AppConfig.Ai.MAX_RECEIPT_OCR_CHARS_FOR_AI,
                fallbackPrefix = "ocr"
            )
        } else {
            input.rawOcrText
        }

        val imageMode = if (hasAttachedImage) {
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

        val rulesBlock = if (hasAttachedImage) {
            """
            - Prefer null over guessing.
            - Only provide date if clearly readable on the image.
            - Keep notes short.
            - When image and OCR disagree, THE IMAGE IS CORRECT.
            """.trimIndent()
        } else {
            """
            - Prefer null over guessing.
            - Infer date only if OCR text is explicit and unambiguous.
            - Keep notes short.
            - Do not assume unseen receipt details.
            """.trimIndent()
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
            $rulesBlock

            Receipt facts (OCR - may be corrupted, especially for Greek characters):
            - currency: ${input.currency}
            - parsedMerchant: ${safeParsedMerchant ?: "none"}
            - parsedTotal: ${input.parsedTotal?.toString() ?: "none"}
            - parsedDate: ${input.parsedDate?.toString() ?: "none"}
            - parsedTaxAmount: ${input.parsedTaxAmount?.toString() ?: "none"}
            - lineItemsJson: ${safeLineItemsJson ?: "none"}
            - rawOcrText:
            $safeRawOcrText
        """.trimIndent()
    }

    private fun buildImageInlineData(input: ReceiptAssistInput, allowImage: Boolean): JSONObject? {
        if (!allowImage) return null
        if (input.redactBeforeCloud) {
            Timber.d("CloudReceiptAssistService: suppressing cloud image upload because redaction is required")
            return null
        }
        val imagePath = input.imagePath ?: return null
        val mimeType = input.imageMimeType ?: return null
        val file = File(imagePath)
        if (!file.exists()) return null
        val fileSize = file.length()
        if (fileSize > MAX_INLINE_IMAGE_BYTES) {
            Timber.d("CloudReceiptAssistService: receipt image too large for inline upload (%d bytes)", fileSize)
            return null
        }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null

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

        val jsonText = CloudJsonParser.extractFirstJsonObject(text) ?: return null
        val suggestion = JSONObject(jsonText)

        return ReceiptAssistSuggestion(
            merchant = suggestion.optJSONObject("merchant")?.toSuggestedStringOrNull(),
            total = suggestion.optJSONObject("total")?.toSuggestedDoubleOrNull(),
            date = suggestion.optJSONObject("date")?.toSuggestedLongOrNull(),
            taxAmount = suggestion.optJSONObject("taxAmount")?.toSuggestedDoubleOrNull(),
            notes = suggestion.optJSONArray("notes").toStringList()
        )
    }

    private fun JSONObject.toSuggestedStringOrNull(): SuggestedValue<String>? {
        val value = optString("value").trim()
        if (value.isBlank()) return null
        return SuggestedValue(
            value = value,
            confidence = optFiniteDoubleStrictOrNull("confidence")?.toFloat(),
            rationale = optString("rationale").trim().ifBlank { null }
        )
    }

    private fun JSONObject.toSuggestedDoubleOrNull(): SuggestedValue<Double>? {
        if (!has("value") || isNull("value")) return null
        val value = optFiniteDoubleStrictOrNull("value")
            ?: throw JSONException("Missing numeric value")
        return SuggestedValue(
            value = value,
            confidence = optFiniteDoubleStrictOrNull("confidence")?.toFloat(),
            rationale = optString("rationale").trim().ifBlank { null }
        )
    }

    private fun JSONObject.toSuggestedLongOrNull(): SuggestedValue<Long>? {
        if (!has("value") || isNull("value")) return null
        val value = optStrictLongStrictOrNull("value")
            ?: throw JSONException("Missing integer value")
        return SuggestedValue(
            value = value,
            confidence = optFiniteDoubleStrictOrNull("confidence")?.toFloat(),
            rationale = optString("rationale").trim().ifBlank { null }
        )
    }

    private fun JSONObject.optFiniteDoubleStrictOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key)
        val number = raw as? Number
            ?: throw JSONException("Expected numeric '$key' but was ${raw?.javaClass?.simpleName ?: "null"}")
        val value = number.toDouble()
        if (!value.isFinite()) {
            throw JSONException("Non-finite numeric '$key': $value")
        }
        return value
    }

    private fun JSONObject.optStrictLongStrictOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key)
        val number = raw as? Number
            ?: throw JSONException("Expected integer '$key' but was ${raw?.javaClass?.simpleName ?: "null"}")
        val asDouble = number.toDouble()
        if (!asDouble.isFinite()) {
            throw JSONException("Non-finite integer '$key': $asDouble")
        }
        val asLong = number.toLong()
        if (asLong.toDouble() != asDouble) {
            throw JSONException("Expected whole-number '$key' but was $asDouble")
        }
        return asLong
    }

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

    private data class RequestPayload(
        val jsonBody: String,
        val actuallyUsedImageInput: Boolean
    )

}
