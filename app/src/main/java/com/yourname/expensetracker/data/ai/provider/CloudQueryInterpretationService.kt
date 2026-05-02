package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.ai.provider.internal.CloudRetryPolicy
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.di.CloudAiHttpClient
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.service.QueryInterpretationService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.privacy.CompositePrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Singleton
// CRITICAL FIX (CRITICAL-1): Now uses SecureKeyStorage instead of BuildConfig
class CloudQueryInterpretationService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage,
    @CloudAiHttpClient private val client: OkHttpClient,
    private val privacyGate: PrivacyGate
) : QueryInterpretationService {

    private var apiKeyOverride: String? = null

    // Secondary constructor for tests
    constructor(secureKeyStorage: SecureKeyStorage) : this(secureKeyStorage, OkHttpClient(), CompositePrivacyGate(emptyList()))

    // Secondary constructor for testing
    constructor(secureKeyStorage: SecureKeyStorage, apiKeyOverride: String) : this(secureKeyStorage, OkHttpClient(), CompositePrivacyGate(emptyList())) {
        this.apiKeyOverride = apiKeyOverride
    }

    private val promptHelper = OnDeviceQueryInterpretationService()

    private val apiKey: String
        get() = apiKeyOverride ?: secureKeyStorage.getGeminiKey() ?: ""

    override suspend fun interpret(
        input: FinancialQueryInterpretationInput
    ): FinancialQueryInterpretationResult {
        if (apiKey.isBlank()) {
            Timber.d("CloudQueryInterpretationService: Gemini API key missing, skipping.")
            return unsupported()
        }

        // PRIVACY GATE: Check cloud AI privacy gate before proceeding
        val gateDecision = privacyGate.check(PrivacyCapability.CLOUD_AI_GENERAL)
        if (gateDecision is PrivacyDecision.Denied) {
            Timber.d("CloudQueryInterpretationService: privacy gate denied: ${gateDecision.reason}")
            return unsupported("Cloud AI disabled by privacy gate")
        }

        val requestBody = buildRequestBody(input)
        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.QUERY_INTERPRETATION_CLOUD_MODEL}:generateContent"
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .build()

        return withContext(Dispatchers.IO) {
            for (attempt in 1..CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                var retryableHttpFailure = false

                try {
                    val result = client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.w(
                                "CloudQueryInterpretationService: HTTP %d (attempt %d/%d)",
                                response.code,
                                attempt,
                                CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                            )
                            if (CloudRetryPolicy.isRetryable(response.code) && attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                                retryableHttpFailure = true
                                return@use null
                            }
                            return@use unsupported()
                        }

                        val body = response.body?.string() ?: return@use unsupported()
                        return@use parseResponse(input, body)
                    }

                    if (result != null) {
                        return@withContext result
                    }
                    if (!retryableHttpFailure) {
                        return@withContext unsupported()
                    }
                } catch (e: IOException) {
                    Timber.w(
                        e,
                        "CloudQueryInterpretationService: network failure (attempt %d/%d)",
                        attempt,
                        CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                    )
                    if (!CloudRetryPolicy.isRetryableIoException(e) || attempt >= CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        return@withContext unsupported("Network error: ${e.message}")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "CloudQueryInterpretationService: parse failure")
                    return@withContext unsupported("Failed to parse response: ${e.message}")
                }

                if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                    val delayMs = CloudRetryPolicy.backoffDelayMs(attempt)
                    Timber.d("CloudQueryInterpretationService: retrying after ${delayMs}ms")
                    delay(delayMs)
                }
            }

            unsupported()
        }
    }

    private fun buildRequestBody(input: FinancialQueryInterpretationInput): String {
        val prompt = promptHelper.buildPrompt(input.toCloudPromptInput())
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
                    put("temperature", AppConfig.Ai.ON_DEVICE_QUERY_TEMPERATURE.toDouble())
                    put("maxOutputTokens", AppConfig.Ai.QUERY_INTERPRETATION_MAX_OUTPUT_TOKENS)
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

    private fun parseResponse(
        input: FinancialQueryInterpretationInput,
        body: String
    ): FinancialQueryInterpretationResult {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: return unsupported()
        if (candidates.length() == 0) return unsupported()
        val text = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.trim()
            ?: return unsupported()

        return promptHelper.parseResponse(input, text) ?: unsupported()
    }

    private fun FinancialQueryInterpretationInput.toCloudPromptInput(): FinancialQueryInterpretationInput {
        if (merchantAliasMap.isEmpty() && categoryAliasMap.isEmpty()) return this

        val aliasOnlyCategoryLookup = categoryAliasMap.keys.associateWith { alias ->
            categoryLookupMap[alias] ?: categoryNameToIdMap[alias] ?: -1L
        }.filterValues { it >= 0L }

        return copy(
            merchantLookupMap = merchantAliasMap,
            categoryLookupMap = aliasOnlyCategoryLookup,
            categoryNameToIdMap = categoryNameToIdMap.filterKeys(categoryAliasMap::containsKey)
        )
    }

    private fun unsupported(
        reason: String = "Query interpretation provider unavailable"
    ): FinancialQueryInterpretationResult = FinancialQueryInterpretationResult.Unsupported(reason)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
