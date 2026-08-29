package com.yourname.expensetracker.data.ai.provider

import androidx.annotation.VisibleForTesting
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.di.CloudAiHttpClient
import com.yourname.expensetracker.data.ai.provider.internal.CloudCorrelation
import com.yourname.expensetracker.data.ai.provider.internal.CloudJsonParser
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.data.ai.provider.internal.CloudRetryPolicy
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.SuggestedValue
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.validation.AiOutputValidators
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReceiptAssistService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.privacy.PrivacyAuditContext
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyBlocked
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.toPrivacyBlocked
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
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
// PRIV-441-01: Now uses CloudPayloadPolicy for all payload preparation — no direct redactBeforeCloud access
class CloudReceiptAssistService @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val secureKeyStorage: SecureKeyStorage,
    @CloudAiHttpClient private val client: OkHttpClient,
    private val privacyGate: PrivacyGate,
    private val cloudPayloadPolicy: CloudPayloadPolicy,
    // P8F-03: cloud-call provenance audit. Hilt resolves this from the PrivacyModule
    // binding; the default keeps @VisibleForTesting/secondary constructors fail-closed.
    private val auditLogger: PrivacyAuditLogger = PrivacyAuditLogger.NO_OP,
    // G-TIME-01: reference clock for AI-output validation. Hilt injects the bound
    // TimeProvider; the default keeps the @VisibleForTesting secondary constructor
    // compiling unchanged.
    private val timeProvider: com.yourname.expensetracker.domain.util.TimeProvider =
        com.yourname.expensetracker.domain.util.SystemTimeProvider()
) : ReceiptAssistService {

    private var apiKeyOverride: String? = null

    // Secondary constructor for tests — fail-closed gate, no allow-all
    @VisibleForTesting
    internal constructor(
        aiSettingsRepository: AiSettingsRepository,
        secureKeyStorage: SecureKeyStorage
    ) : this(
        aiSettingsRepository, secureKeyStorage, OkHttpClient(),
        object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.FailClosed("PrivacyGate not configured in test constructor")
        },
        DefaultCloudPayloadPolicy(
            EffectiveCloudAiPolicyResolver.failClosedForTest(aiSettingsRepository),
            DefaultCloudPayloadRedactor()
        )
    )

    // Secondary constructor for testing with API key override — fail-closed gate
    @VisibleForTesting
    internal constructor(
        aiSettingsRepository: AiSettingsRepository,
        secureKeyStorage: SecureKeyStorage,
        apiKeyOverride: String
    ) : this(
        aiSettingsRepository, secureKeyStorage, OkHttpClient(),
        object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.FailClosed("PrivacyGate not configured in test constructor")
        },
        DefaultCloudPayloadPolicy(
            EffectiveCloudAiPolicyResolver.failClosedForTest(aiSettingsRepository),
            DefaultCloudPayloadRedactor()
        )
    ) {
        this.apiKeyOverride = apiKeyOverride
    }

    private val apiKey: String
        get() = apiKeyOverride ?: secureKeyStorage.getGeminiKey() ?: ""

    override fun usedImageInput(input: ReceiptAssistInput): Boolean =
        input.imagePath != null && input.imageMimeType != null

    override suspend fun suggest(input: ReceiptAssistInput): AiServiceResult<ReceiptAssistSuggestion> {
        // PRIVACY GUARD: Check API key before any network call
        if (apiKey.isBlank()) {
            Timber.d("CloudReceiptAssistService: Gemini API key missing, skipping.")
            return AiServiceResult.Failure(AiServiceError.Disabled("Gemini API key missing"))
        }

        // PRIVACY GATE: Check cloud AI privacy gate before proceeding
        val capability = if (input.isImageAnalysisMode) {
            PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD
        } else {
            PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST
        }
        val gateDecision = privacyGate.check(capability, mapOf("receiptId" to input.receiptId.toString()))
        if (gateDecision.blocksExecution()) {
            Timber.d("CloudReceiptAssistService: privacy gate denied: ${gateDecision.reason()}")
            return AiServiceResult.Failure(
                AiServiceError.PrivacyDenied(
                    gateDecision.toPrivacyBlocked(capability)
                        ?: PrivacyBlocked.Custom(capability, gateDecision.reason())
                )
            )
        }

        val settings = aiSettingsRepository.settings().first()

        // PR-B: Use prepareReceiptAssist — policy owns both text and image inclusion decision
        val allowImage = input.isImageAnalysisMode && settings.receiptImageCloudEnabled
        val hasImage = allowImage && input.imagePath != null
        val rawPrompt = buildRawPrompt(input, hasAttachedImage = hasImage)
        val prepared = cloudPayloadPolicy.prepareReceiptAssist(
            rawPrompt = rawPrompt,
            imagePath = input.imagePath,
            imageMimeType = input.imageMimeType,
            allowImage = allowImage
        )
        val requestPayload = buildRequestPayloadFromPrepared(prepared)
        val requestBody = requestPayload.jsonBody
        // P8F-03: record cloud-call provenance (provider/model/payloadHash/redaction flags)
        auditLogger.logCloudCall(
            capability,
            PrivacyDecision.Allowed,
            PrivacyAuditContext.forCloudCall(
                provider = "gemini",
                modelId = AppConfig.Ai.RECEIPT_ASSIST_CLOUD_MODEL,
                purpose = CloudPayloadPurpose.RECEIPT_ASSIST,
                payload = prepared,
                correlationId = CloudCorrelation.newCorrelationId()
            )
        )
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
                    if (e is kotlinx.coroutines.CancellationException) throw e
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

    /**
     * Send a text-only prompt to the cloud Gemini API and return the raw
     * response text. Skips image handling and receipt-specific prompt wrapping.
     *
     * Used by [ValidateBankStatementTransactionsUseCase] as a cloud fallback
     * for bank statement transaction validation.
     *
     * ## Privacy gate
     * This method checks [PrivacyCapability.CLOUD_AI_BANK_STATEMENT] before
     * making the cloud API call.  If the gate denies the request the method
     * returns [AiServiceResult.Failure] with [AiServiceError.Disabled].
     * Callers should **not** skip the privacy gate — this defense-in-depth
     * ensures the cloud service is self-defending regardless of which caller
     * invokes it.
     */
    suspend fun suggestFromText(prompt: String): AiServiceResult<String> {
        if (apiKey.isBlank()) {
            return AiServiceResult.Failure(AiServiceError.Disabled("Gemini API key missing"))
        }

        // PRIVACY GATE: Check cloud AI privacy gate before proceeding.
        // Defense-in-depth — the use case also checks, but this ensures the
        // cloud service is self-defending regardless of which caller invokes it.
        val gateDecision = privacyGate.check(
            PrivacyCapability.CLOUD_AI_BANK_STATEMENT,
            mapOf("caller" to "suggestFromText")
        )
        if (gateDecision.blocksExecution()) {
            Timber.d("CloudReceiptAssistService: privacy gate denied suggestFromText: ${gateDecision.reason()}")
            return AiServiceResult.Failure(
                AiServiceError.PrivacyDenied(
                    gateDecision.toPrivacyBlocked(PrivacyCapability.CLOUD_AI_BANK_STATEMENT)
                        ?: PrivacyBlocked.Custom(PrivacyCapability.CLOUD_AI_BANK_STATEMENT, gateDecision.reason())
                )
            )
        }

        // PRIV-441-01: Use CloudPayloadPolicy for bank statement validation — no direct redactBeforeCloud
        val prepared = cloudPayloadPolicy.prepareBankStatementValidation(prompt)
        val safePrompt = prepared.text
        // P8F-03: record cloud-call provenance for the bank-statement fallback path
        auditLogger.logCloudCall(
            PrivacyCapability.CLOUD_AI_BANK_STATEMENT,
            PrivacyDecision.Allowed,
            PrivacyAuditContext.forCloudCall(
                provider = "gemini",
                modelId = AppConfig.Ai.RECEIPT_ASSIST_CLOUD_MODEL,
                purpose = CloudPayloadPurpose.BANK_STATEMENT_VALIDATION,
                payload = prepared,
                correlationId = CloudCorrelation.newCorrelationId()
            )
        )

        val parts = JSONArray().put(JSONObject().put("text", safePrompt))
        val requestJson = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", parts)
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

        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.RECEIPT_ASSIST_CLOUD_MODEL}:generateContent"
        val request = Request.Builder()
            .url(url)
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .build()

        return withContext(Dispatchers.IO) {
            for (attempt in 1..CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                try {
                    // P2-25: Wrap response in .use{} to prevent connection leaks on retry path
                    val outcome = client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val correlationId = CloudCorrelation.newCorrelationId()
                            val errorClass = "HTTP_${response.code}"
                            if (CloudRetryPolicy.isRetryable(response.code) && attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                                Timber.w(
                                    "CloudReceiptAssistService: suggestFromText retryable HTTP %d class=%s correlationId=%s (attempt %d/%d)",
                                    response.code, errorClass, correlationId, attempt, CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                                )
                                null // fall through to delay + retry
                            } else {
                                AiServiceResult.Failure(
                                    AiServiceError.HttpError(response.code, "suggestFromText HTTP ${response.code} errorClass=$errorClass correlationId=$correlationId")
                                )
                            }
                        } else {
                            val body = response.body?.string()
                                ?: return@use AiServiceResult.Failure(AiServiceError.ParseError("Empty response body"))
                            val root = JSONObject(body)
                            val text = root.optJSONArray("candidates")
                                ?.optJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts")
                                ?.optJSONObject(0)
                                ?.optString("text")
                                ?.trim()
                                ?: return@use AiServiceResult.Failure(AiServiceError.ParseError("No text in Gemini response"))
                            AiServiceResult.Success(text)
                        }
                    }

                    if (outcome != null) return@withContext outcome
                } catch (e: SocketTimeoutException) {
                    if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        Timber.w(e, "CloudReceiptAssistService: suggestFromText timeout, retrying (%d/%d)", attempt, CloudRetryPolicy.MAX_RETRY_ATTEMPTS)
                    } else {
                        return@withContext AiServiceResult.Failure(AiServiceError.Timeout)
                    }
                } catch (e: SSLException) {
                    Timber.w(e, "CloudReceiptAssistService: suggestFromText SSL failure")
                    return@withContext AiServiceResult.Failure(AiServiceError.SslError)
                } catch (e: IOException) {
                    if (CloudRetryPolicy.isRetryableIoException(e) && attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        Timber.w(e, "CloudReceiptAssistService: suggestFromText network failure, retrying (%d/%d)", attempt, CloudRetryPolicy.MAX_RETRY_ATTEMPTS)
                    } else {
                        return@withContext AiServiceResult.Failure(AiServiceError.Offline)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.w(e, "CloudReceiptAssistService: suggestFromText error")
                    return@withContext AiServiceResult.Failure(AiServiceError.Unknown(e.message))
                }

                if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                    delay(CloudRetryPolicy.backoffDelayMs(attempt))
                }
            }

            AiServiceResult.Failure(AiServiceError.Unknown("suggestFromText retry attempts exhausted"))
        }
    }

    internal fun buildRequestBodyForTest(input: ReceiptAssistInput, allowImage: Boolean): String {
        val redactionApplied = input.redactBeforeCloud
        val imageWillBeIncluded = allowImage && !redactionApplied && input.imagePath != null
        val rawPrompt = buildRawPrompt(input, hasAttachedImage = imageWillBeIncluded)
        // Read image bytes for test if image will be included
        val (imageBytes, imageMimeType) = if (imageWillBeIncluded && input.imageMimeType != null) {
            runCatching { java.io.File(input.imagePath!!).readBytes() }.getOrNull() to input.imageMimeType
        } else null to null
        val prepared = com.yourname.expensetracker.domain.privacy.PreparedCloudPayload(
            purpose = CloudPayloadPurpose.RECEIPT_ASSIST,
            text = rawPrompt,
            redactionApplied = redactionApplied,
            fieldsRedacted = emptySet(),
            payloadHash = "",
            rawTextIncluded = !redactionApplied,
            rawImageIncluded = imageBytes != null,
            imageBytes = imageBytes,
            imageMimeType = imageMimeType
        )
        return buildRequestPayloadFromPrepared(prepared).jsonBody
    }

    private fun buildRequestPayloadFromPrepared(
        prepared: com.yourname.expensetracker.domain.privacy.PreparedCloudPayload
    ): RequestPayload {
        // PR-B: Image bytes come from prepared payload — provider never reads file directly
        val inlineImagePart = if (prepared.rawImageIncluded && prepared.imageBytes != null && prepared.imageMimeType != null) {
            JSONObject().put(
                "inlineData",
                JSONObject()
                    .put("mimeType", prepared.imageMimeType)
                    .put("data", java.util.Base64.getEncoder().encodeToString(prepared.imageBytes))
            )
        } else {
            if (!prepared.rawImageIncluded) {
                Timber.d("CloudReceiptAssistService: image not included — policy decision (redactionApplied=${prepared.redactionApplied})")
            }
            null
        }
        val parts = JSONArray().put(JSONObject().put("text", prepared.text))
        inlineImagePart?.let(parts::put)

        val requestJson = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", parts)
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

    private fun buildRawPrompt(input: ReceiptAssistInput, hasAttachedImage: Boolean): String {
        // PRIV-43B-02: All fields included in raw prompt — CloudPayloadPolicy will redact if required
        val safeParsedMerchant = input.parsedMerchant
        val safeLineItemsJson = input.lineItemsJson
        val safeRawOcrText = input.rawOcrText

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

        // ── AI output validation ──────────────────────────────────────────
        // Reject hallucinated or malformed values using shared validators.
        val validatedTotal = suggestion.optJSONObject("total")?.toSuggestedDoubleOrNull()
            ?.takeIf { AiOutputValidators.isPositiveAmount(it.value) }
        val validatedTaxAmount = suggestion.optJSONObject("taxAmount")?.toSuggestedDoubleOrNull()
            ?.let { if (!AiOutputValidators.isPositiveAmount(it.value)) null else it }
        val validatedDate = suggestion.optJSONObject("date")?.toSuggestedLongOrNull()
            ?.takeIf { AiOutputValidators.isPlausibleEpochMillis(it.value, timeProvider.now()) }

        return ReceiptAssistSuggestion(
            merchant = suggestion.optJSONObject("merchant")?.toSuggestedStringOrNull(),
            total = validatedTotal,
            date = validatedDate,
            taxAmount = validatedTaxAmount,
            notes = suggestion.optJSONArray("notes").toStringList()
        )
    }

    private fun JSONObject.toSuggestedStringOrNull(): SuggestedValue<String>? {
        val value = optString("value").trim()
        if (value.isBlank()) return null
        return SuggestedValue(
            value = value,
            confidence = optFiniteDoubleStrictOrNull("confidence")?.toFloat()?.coerceIn(0f, 1f),
            rationale = optString("rationale").trim().ifBlank { null }
        )
    }

    private fun JSONObject.toSuggestedDoubleOrNull(): SuggestedValue<Double>? {
        if (!has("value") || isNull("value")) return null
        val value = optFiniteDoubleStrictOrNull("value")
            ?: throw JSONException("Missing numeric value")
        return SuggestedValue(
            value = value,
            confidence = optFiniteDoubleStrictOrNull("confidence")?.toFloat()?.coerceIn(0f, 1f),
            rationale = optString("rationale").trim().ifBlank { null }
        )
    }

    private fun JSONObject.toSuggestedLongOrNull(): SuggestedValue<Long>? {
        if (!has("value") || isNull("value")) return null
        val value = optStrictLongStrictOrNull("value")
            ?: throw JSONException("Missing integer value")
        return SuggestedValue(
            value = value,
            confidence = optFiniteDoubleStrictOrNull("confidence")?.toFloat()?.coerceIn(0f, 1f),
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
    }

    private data class RequestPayload(
        val jsonBody: String,
        val actuallyUsedImageInput: Boolean
    )

}
