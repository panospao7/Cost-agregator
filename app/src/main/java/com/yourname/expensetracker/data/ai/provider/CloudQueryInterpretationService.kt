package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.service.QueryInterpretationService
import com.yourname.expensetracker.domain.config.AppConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudQueryInterpretationService @Inject constructor() : QueryInterpretationService {

    private var apiKeyOverride: String? = null

    internal constructor(apiKeyOverride: String) : this() {
        this.apiKeyOverride = apiKeyOverride
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(AppConfig.Ai.QUERY_INTERPRETATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AppConfig.Ai.QUERY_INTERPRETATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val promptHelper = OnDeviceQueryInterpretationService()

    private val apiKey: String
        get() = apiKeyOverride ?: BuildConfig.GEMINI_API_KEY

    override suspend fun interpret(
        input: FinancialQueryInterpretationInput
    ): FinancialQueryInterpretationResult {
        if (apiKey.isBlank()) {
            Timber.d("CloudQueryInterpretationService: Gemini API key missing, skipping.")
            return unsupported()
        }

        val requestBody = buildRequestBody(input)
        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.QUERY_INTERPRETATION_CLOUD_MODEL}:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w(
                        "CloudQueryInterpretationService: HTTP ${response.code} ${response.body?.string()?.take(200)}"
                    )
                    return@use unsupported()
                }

                val body = response.body?.string() ?: return@use unsupported()
                parseResponse(input, body)
            }
        } catch (e: IOException) {
            Timber.w(e, "CloudQueryInterpretationService: network failure")
            unsupported()
        } catch (e: Exception) {
            Timber.w(e, "CloudQueryInterpretationService: parse failure")
            unsupported()
        }
    }

    private fun buildRequestBody(input: FinancialQueryInterpretationInput): String {
        val prompt = promptHelper.buildPrompt(input)
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

    private fun unsupported(
        reason: String = "Query interpretation provider unavailable"
    ): FinancialQueryInterpretationResult = FinancialQueryInterpretationResult.Unsupported(reason)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
