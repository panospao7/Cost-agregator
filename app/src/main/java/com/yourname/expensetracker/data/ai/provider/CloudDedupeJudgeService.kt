package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.ai.provider.internal.CloudCorrelation
import com.yourname.expensetracker.data.ai.provider.internal.CloudRetryPolicy
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.di.CloudAiHttpClient
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion
import com.yourname.expensetracker.domain.ai.model.DuplicateVerdict
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.service.DedupeJudgeService
import com.yourname.expensetracker.domain.config.AppConfig
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
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * CRITICAL FIX (CRITICAL-1): Now uses SecureKeyStorage instead of BuildConfig.
 * API key is retrieved from encrypted storage at runtime, not compiled into APK.
 */
@Singleton
class CloudDedupeJudgeService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage,
    @CloudAiHttpClient private val client: OkHttpClient
) : DedupeJudgeService {

    // Secondary constructor for tests
    constructor(secureKeyStorage: SecureKeyStorage) : this(secureKeyStorage, OkHttpClient())

    /**
     * CRITICAL: API key is now retrieved from secure storage at runtime.
     * No longer compiled into BuildConfig, preventing APK extraction.
     */
    private val apiKey: String
        get() = secureKeyStorage.getGeminiKey() ?: ""

    override suspend fun judge(input: DedupeJudgeInput): AiServiceResult<DedupeJudgeSuggestion> {
        if (apiKey.isBlank()) {
            Timber.d("CloudDedupeJudgeService: Gemini API key missing, skipping.")
            return AiServiceResult.Failure(AiServiceError.Disabled("Gemini API key missing"))
        }

        val requestBody = buildRequestBody(input)
        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.DEDUPE_JUDGE_CLOUD_MODEL}:generateContent"
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
                                    "CloudDedupeJudgeService: retryable HTTP %d class=%s correlationId=%s (attempt %d/%d)",
                                    response.code,
                                    errorClass,
                                    correlationId,
                                    attempt,
                                    CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                                )
                                null
                            } else {
                                Timber.w(
                                    "CloudDedupeJudgeService: HTTP %d class=%s correlationId=%s",
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
                                ?: return@use AiServiceResult.Failure(AiServiceError.ParseError("No usable dedupe verdict in response"))
                            AiServiceResult.Success(parsed)
                        }
                    }

                    if (outcome != null) return@withContext outcome
                } catch (e: SocketTimeoutException) {
                    if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        Timber.w(
                            e,
                            "CloudDedupeJudgeService: timeout, retrying (%d/%d)",
                            attempt,
                            CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                        )
                    } else {
                        Timber.w(e, "CloudDedupeJudgeService: timeout")
                        return@withContext AiServiceResult.Failure(AiServiceError.Timeout)
                    }
                } catch (e: SSLException) {
                    Timber.w(e, "CloudDedupeJudgeService: SSL failure")
                    return@withContext AiServiceResult.Failure(AiServiceError.SslError)
                } catch (e: IOException) {
                    val retryable = CloudRetryPolicy.isRetryableIoException(e)
                    if (retryable && attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        Timber.w(
                            e,
                            "CloudDedupeJudgeService: connection reset, retrying (%d/%d)",
                            attempt,
                            CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                        )
                    } else {
                        Timber.w(e, "CloudDedupeJudgeService: network failure")
                        return@withContext AiServiceResult.Failure(AiServiceError.Offline)
                    }
                } catch (e: JSONException) {
                    Timber.w(e, "CloudDedupeJudgeService: parse failure")
                    return@withContext AiServiceResult.Failure(AiServiceError.ParseError(e.message))
                } catch (e: Exception) {
                    Timber.w(e, "CloudDedupeJudgeService: parse failure")
                    return@withContext AiServiceResult.Failure(AiServiceError.Unknown(e.message))
                }

                if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                    delay(CloudRetryPolicy.backoffDelayMs(attempt))
                }
            }

            AiServiceResult.Failure(AiServiceError.Unknown("Retry attempts exhausted"))
        }
    }

    private fun buildRequestBody(input: DedupeJudgeInput): String {
        val prompt = buildPrompt(input)
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
                    put("temperature", 0.1)
                    put("maxOutputTokens", AppConfig.Ai.DEDUPE_JUDGE_MAX_OUTPUT_TOKENS)
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

    private fun buildPrompt(input: DedupeJudgeInput): String {
        val candidates = input.candidates.joinToString("\n") { candidate ->
            "- targetType=${candidate.targetType}, targetId=${candidate.targetId}, merchant=${candidate.merchant}, amount=${candidate.amount} ${candidate.currency}, date=${candidate.date}, source=${candidate.sourceLabel}, preview=${candidate.textPreview ?: "none"}"
        }

        return """
            You are helping judge whether a pending finance review is likely a duplicate of one bounded candidate set.
            Stay conservative.
            Never assume certainty from weak evidence.
            Use only the subject and candidates below.
            Return JSON only.

            JSON schema:
            {
              "verdict": "LIKELY_DUPLICATE|LIKELY_DISTINCT|UNCERTAIN",
              "matchedTargetType": "PENDING_REVIEW|EXPENSE|null",
              "matchedTargetId": 0,
              "confidence": 0.0,
              "rationale": "short explanation"
            }

            Subject:
            - targetType=${input.subject.targetType}
            - targetId=${input.subject.targetId}
            - merchant=${input.subject.merchant}
            - amount=${input.subject.amount} ${input.subject.currency}
            - date=${input.subject.date}
            - source=${input.subject.sourceLabel}
            - preview=${input.subject.textPreview ?: "none"}

            Candidates:
            $candidates
        """.trimIndent()
    }

    private fun parseResponse(body: String): DedupeJudgeSuggestion? {
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

        return DedupeJudgeSuggestion(
            verdict = DuplicateVerdict.valueOf(suggestion.optString("verdict")),
            matchedTargetType = suggestion.optString("matchedTargetType").trim()
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { AiTargetType.valueOf(it) },
            matchedTargetId = suggestion.optStrictLongStrictOrNull("matchedTargetId"),
            confidence = suggestion.optFiniteDoubleStrictOrNull("confidence")?.toFloat(),
            rationale = suggestion.optString("rationale").trim().ifBlank { null }
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

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val JSON_FENCE_REGEX = Regex("""```(?:json)?\s*([\s\S]*?)\s*```""", RegexOption.IGNORE_CASE)
    }
}
