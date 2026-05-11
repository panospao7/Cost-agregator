package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.ai.provider.internal.CloudCorrelation
import com.yourname.expensetracker.data.ai.provider.internal.CloudRetryPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.CloudPayloadRedactor
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.di.CloudAiHttpClient
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.ReviewExplanation
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReviewExplanationService
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
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
class CloudReviewExplanationService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage,
    @CloudAiHttpClient private val client: OkHttpClient,
    private val aiSettingsRepository: AiSettingsRepository? = null,
    private val privacyGate: PrivacyGate,
    private val redactor: CloudPayloadRedactor
) : ReviewExplanationService {

    private var apiKeyOverride: String? = null

    // Secondary constructor for tests
    constructor(secureKeyStorage: SecureKeyStorage) : this(
        secureKeyStorage,
        OkHttpClient(),
        null,
        object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.Allowed
        },
        DefaultCloudPayloadRedactor()
    )

    // Secondary constructor for testing
    constructor(secureKeyStorage: SecureKeyStorage, apiKeyOverride: String) : this(
        secureKeyStorage,
        OkHttpClient(),
        null,
        object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.Allowed
        },
        DefaultCloudPayloadRedactor()
    ) {
        this.apiKeyOverride = apiKeyOverride
    }

    private val apiKey: String
        get() = apiKeyOverride ?: secureKeyStorage.getGeminiKey() ?: ""

    override suspend fun generate(input: ReviewExplanationInput): AiServiceResult<ReviewExplanation> {
        if (apiKey.isBlank()) {
            Timber.d("CloudReviewExplanationService: Gemini API key missing, skipping.")
            return AiServiceResult.Failure(AiServiceError.Disabled("Gemini API key missing"))
        }

        // PRIVACY GATE: Unified cloud AI gate — checks both PrivacySettings + AiSettings
        val gateCheck = privacyGate.check(PrivacyCapability.CLOUD_AI_GENERAL)
        if (gateCheck.blocksExecution()) {
            Timber.w("CloudReviewExplanationService: blocked by privacy gate: ${gateCheck.reason()}")
            return AiServiceResult.Failure(AiServiceError.Disabled("Blocked by privacy gate: ${gateCheck.reason()}"))
        }

        val shouldRedact = aiSettingsRepository?.settings()?.first()?.redactBeforeCloud ?: true
        val requestBody = buildRequestBody(input, shouldRedact)
        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.REVIEW_EXPLANATION_CLOUD_MODEL}:generateContent"
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
                                    "CloudReviewExplanationService: retryable HTTP %d class=%s correlationId=%s (attempt %d/%d)",
                                    response.code,
                                    errorClass,
                                    correlationId,
                                    attempt,
                                    CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                                )
                                null
                            } else {
                                Timber.w(
                                    "CloudReviewExplanationService: HTTP %d class=%s correlationId=%s",
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
                                ?: return@use AiServiceResult.Failure(AiServiceError.ParseError("No usable explanation in response"))
                            AiServiceResult.Success(parsed)
                        }
                    }

                    if (outcome != null) return@withContext outcome
                } catch (e: SocketTimeoutException) {
                    if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        Timber.w(
                            e,
                            "CloudReviewExplanationService: timeout, retrying (%d/%d)",
                            attempt,
                            CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                        )
                    } else {
                        Timber.w(e, "CloudReviewExplanationService: timeout")
                        return@withContext AiServiceResult.Failure(AiServiceError.Timeout)
                    }
                } catch (e: SSLException) {
                    Timber.w(e, "CloudReviewExplanationService: SSL failure")
                    return@withContext AiServiceResult.Failure(AiServiceError.SslError)
                } catch (e: IOException) {
                    val retryable = CloudRetryPolicy.isRetryableIoException(e)
                    if (retryable && attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        Timber.w(
                            e,
                            "CloudReviewExplanationService: connection reset, retrying (%d/%d)",
                            attempt,
                            CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                        )
                    } else {
                        Timber.w(e, "CloudReviewExplanationService: network failure")
                        return@withContext AiServiceResult.Failure(AiServiceError.Offline)
                    }
                } catch (e: JSONException) {
                    Timber.w(e, "CloudReviewExplanationService: parse failure")
                    return@withContext AiServiceResult.Failure(AiServiceError.ParseError(e.message))
                } catch (e: Exception) {
                    Timber.w(e, "CloudReviewExplanationService: parse failure")
                    return@withContext AiServiceResult.Failure(AiServiceError.Unknown(e.message))
                }

                if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                    delay(CloudRetryPolicy.backoffDelayMs(attempt))
                }
            }

            AiServiceResult.Failure(AiServiceError.Unknown("Retry attempts exhausted"))
        }
    }

    private fun buildRequestBody(input: ReviewExplanationInput, shouldRedact: Boolean): String {
        val prompt = buildPrompt(input, shouldRedact)
        return JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.2)
                    put("maxOutputTokens", AppConfig.Ai.REVIEW_EXPLANATION_MAX_OUTPUT_TOKENS)
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

    private fun buildPrompt(input: ReviewExplanationInput, shouldRedact: Boolean): String {
        // PRIVACY FIX: Sanitize PII before sending to cloud
        val safeMerchant = if (shouldRedact) {
            redactor.redactMerchant(input.merchant).value ?: "Unknown"
        } else {
            input.merchant
        }
        val safeNotificationTitle = if (shouldRedact && input.notificationTitle != null) {
            redactor.redactText(input.notificationTitle, CloudPayloadPurpose.REVIEW_EXPLANATION).text
        } else {
            input.notificationTitle ?: "none"
        }
        val safeNotificationText = if (shouldRedact && input.notificationText != null) {
            redactor.redactText(input.notificationText, CloudPayloadPurpose.REVIEW_EXPLANATION).text
        } else {
            input.notificationText ?: "none"
        }

        return """
You are helping explain why a pending expense review exists inside a finance app.
Stay cautious, concise, and non-authoritative.
Do not invent facts.
Do not tell the user the transaction is certainly correct.
Use the given signals only.
Keep the output short and plain.
Headline max 60 characters.
Body max 180 characters and one sentence.
Caution max 90 characters or null.
Do not include numbered lists.
Do not include markdown.

Return strict JSON with this schema:
{
  "headline": "short title",
  "body": "1-2 sentence explanation",
  "caution": "optional caution or null"
}

Review facts:
- merchant: $safeMerchant
- amount: ${input.amount} ${input.currency}
- type: ${input.suggestedType}
- confidence: ${input.confidence}
- matchType: ${input.matchType ?: "unknown"}
- deterministicExplanation: ${input.explanation ?: "none"}
- packageName: ${input.packageName}
- notificationTitle: $safeNotificationTitle
- notificationText: $safeNotificationText

Write a user-facing explanation for why this landed in review and what signal looks uncertain.
Return JSON only.
""".trimIndent()
    }

    private fun parseResponse(body: String): ReviewExplanation? {
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

        val explanationJson = JSONObject(extractFirstJsonObject(text) ?: return null)
        val headline = explanationJson.optString("headline").trim()
            .take(AppConfig.Ai.MAX_REVIEW_EXPLANATION_HEADLINE_CHARS)
        val bodyText = explanationJson.optString("body").trim()
            .take(AppConfig.Ai.MAX_REVIEW_EXPLANATION_BODY_CHARS)
        if (headline.isBlank() || bodyText.isBlank()) return null

        return ReviewExplanation(
            headline = headline,
            body = bodyText,
            caution = explanationJson.optString("caution").trim()
                .ifBlank { null }
                ?.take(AppConfig.Ai.MAX_REVIEW_EXPLANATION_CAUTION_CHARS)
        )
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
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val JSON_FENCE_REGEX = Regex("""```(?:json)?\s*([\s\S]*?)\s*```""", RegexOption.IGNORE_CASE)
    }
}
