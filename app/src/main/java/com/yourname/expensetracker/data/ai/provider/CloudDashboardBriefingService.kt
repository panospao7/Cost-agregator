package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.DashboardBriefing
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.service.DashboardBriefingService
import com.yourname.expensetracker.domain.config.AppConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
// CRITICAL FIX (CRITICAL-1): Now uses SecureKeyStorage instead of BuildConfig
class CloudDashboardBriefingService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage
) : DashboardBriefingService {

    private var apiKeyOverride: String? = null

    // Secondary constructor for testing
    constructor(secureKeyStorage: SecureKeyStorage, apiKeyOverride: String) : this(secureKeyStorage) {
        this.apiKeyOverride = apiKeyOverride
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(AppConfig.Ai.DASHBOARD_BRIEFING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AppConfig.Ai.DASHBOARD_BRIEFING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val promptHelper = OnDeviceDashboardBriefingService()

    private val apiKey: String
        get() = apiKeyOverride ?: secureKeyStorage.getGeminiKey() ?: ""

    override suspend fun generate(input: DashboardBriefingInput): AiServiceResult<DashboardBriefing> {
        Timber.d("CloudDashboardBriefingService: Starting generation for: ${input.weatherSummary}")
        
        if (apiKey.isBlank()) {
            Timber.w("CloudDashboardBriefingService: FAILED - Gemini API key missing/blank")
            return AiServiceResult.Failure(AiServiceError.Disabled("Gemini API key missing"))
        }
        
        // HIGH-13 FIX: Remove API key length logging (information disclosure)
        Timber.d("CloudDashboardBriefingService: API key configured: ${apiKey.isNotBlank()}")

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
                    return@use AiServiceResult.Failure(AiServiceError.HttpError(response.code, errorBody))
                }

                val body = response.body?.string() ?: run {
                    Timber.w("CloudDashboardBriefingService: Response body was null/empty")
                    return@use AiServiceResult.Failure(AiServiceError.ParseError("Empty response body"))
                }
                
                Timber.d("CloudDashboardBriefingService: Response body length: ${body.length}")
                val briefing = parseResponse(body)
                
                if (briefing != null) {
                    Timber.d("CloudDashboardBriefingService: SUCCESS - briefing text length: ${briefing.text.length}")
                    AiServiceResult.Success(briefing)
                } else {
                    Timber.w("CloudDashboardBriefingService: FAILED - parseResponse returned null")
                    AiServiceResult.Failure(AiServiceError.ParseError("No usable briefing in response"))
                }
            }
        } catch (e: SocketTimeoutException) {
            Timber.w(e, "CloudDashboardBriefingService: FAILED - timeout")
            AiServiceResult.Failure(AiServiceError.Timeout)
        } catch (e: SSLException) {
            Timber.w(e, "CloudDashboardBriefingService: FAILED - SSL error")
            AiServiceResult.Failure(AiServiceError.SslError)
        } catch (e: IOException) {
            Timber.w(e, "CloudDashboardBriefingService: FAILED - network failure")
            AiServiceResult.Failure(AiServiceError.Offline)
        } catch (e: JSONException) {
            Timber.w(e, "CloudDashboardBriefingService: FAILED - json parse failure")
            AiServiceResult.Failure(AiServiceError.ParseError(e.message))
        } catch (e: Exception) {
            Timber.w(e, "CloudDashboardBriefingService: FAILED - parse failure")
            AiServiceResult.Failure(AiServiceError.Unknown(e.message))
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
