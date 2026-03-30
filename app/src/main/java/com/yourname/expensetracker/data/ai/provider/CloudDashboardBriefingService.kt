package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.domain.ai.model.DashboardBriefing
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.service.DashboardBriefingService
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
class CloudDashboardBriefingService @Inject constructor() : DashboardBriefingService {

    private var apiKeyOverride: String? = null

    internal constructor(apiKeyOverride: String) : this() {
        this.apiKeyOverride = apiKeyOverride
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(AppConfig.Ai.DASHBOARD_BRIEFING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AppConfig.Ai.DASHBOARD_BRIEFING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val promptHelper = OnDeviceDashboardBriefingService()

    private val apiKey: String
        get() = apiKeyOverride ?: BuildConfig.GEMINI_API_KEY

    override suspend fun generate(input: DashboardBriefingInput): DashboardBriefing? {
        Timber.d("CloudDashboardBriefingService: Starting generation for: ${input.weatherSummary}")
        
        if (apiKey.isBlank()) {
            Timber.w("CloudDashboardBriefingService: FAILED - Gemini API key missing/blank")
            return null
        }
        
        Timber.d("CloudDashboardBriefingService: API key present (length=${apiKey.length})")

        val requestBody = buildRequestBody(input)
        Timber.d("CloudDashboardBriefingService: Request body built, length=${requestBody.length}")
        
        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_MODEL}:generateContent?key=$apiKey"
        Timber.d("CloudDashboardBriefingService: URL: ${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_MODEL}:generateContent")
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        return try {
            Timber.d("CloudDashboardBriefingService: Executing HTTP request...")
            client.newCall(request).execute().use { response ->
                Timber.d("CloudDashboardBriefingService: HTTP response code: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(200) ?: "empty"
                    Timber.w("CloudDashboardBriefingService: HTTP ${response.code} error: $errorBody")
                    return@use null
                }

                val body = response.body?.string() ?: run {
                    Timber.w("CloudDashboardBriefingService: Response body was null/empty")
                    return@use null
                }
                
                Timber.d("CloudDashboardBriefingService: Response body length: ${body.length}")
                val briefing = parseResponse(body)
                
                if (briefing != null) {
                    Timber.d("CloudDashboardBriefingService: SUCCESS - briefing text length: ${briefing.text.length}")
                } else {
                    Timber.w("CloudDashboardBriefingService: FAILED - parseResponse returned null")
                }
                
                briefing
            }
        } catch (e: IOException) {
            Timber.w(e, "CloudDashboardBriefingService: FAILED - network failure")
            null
        } catch (e: Exception) {
            Timber.w(e, "CloudDashboardBriefingService: FAILED - parse failure")
            null
        }
    }

    private fun buildRequestBody(input: DashboardBriefingInput): String {
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
                    put("temperature", AppConfig.Ai.ON_DEVICE_BRIEFING_TEMPERATURE.toDouble())
                    put("maxOutputTokens", AppConfig.Ai.DASHBOARD_BRIEFING_MAX_OUTPUT_TOKENS)
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

    private fun parseResponse(body: String): DashboardBriefing? {
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

        return promptHelper.parseResponse(text)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
