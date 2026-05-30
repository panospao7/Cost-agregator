package com.yourname.expensetracker.data.ai.provider

import androidx.annotation.VisibleForTesting
import com.yourname.expensetracker.data.ai.provider.internal.CloudCorrelation
import com.yourname.expensetracker.data.ai.provider.internal.CloudRetryPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.di.CloudAiHttpClient
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.DashboardBriefing
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.DashboardBriefingService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PrivacyAuditContext
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
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
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
// PRIV-441-01: Now uses CloudPayloadPolicy for all payload preparation — no direct redactBeforeCloud access
class CloudDashboardBriefingService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage,
    @CloudAiHttpClient private val client: OkHttpClient,
    private val promptFormatter: DashboardBriefingPromptFormatter,
    private val aiSettingsRepository: AiSettingsRepository? = null,
    private val privacyGate: PrivacyGate,
    private val cloudPayloadPolicy: CloudPayloadPolicy,
    // P8F-03: cloud-call provenance audit (Hilt resolves; default keeps test ctors fail-closed)
    private val auditLogger: PrivacyAuditLogger = PrivacyAuditLogger.NO_OP
) : DashboardBriefingService {

    private var apiKeyOverride: String? = null

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        fun failClosedGate() = object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.FailClosed("PrivacyGate not configured in test constructor")
        }
    }

    @VisibleForTesting
    internal constructor(secureKeyStorage: SecureKeyStorage) : this(
        secureKeyStorage = secureKeyStorage,
        client = OkHttpClient(),
        promptFormatter = DashboardBriefingPromptFormatter(),
        aiSettingsRepository = null,
        privacyGate = failClosedGate(),
        cloudPayloadPolicy = DefaultCloudPayloadPolicy(
            EffectiveCloudAiPolicyResolver.failClosedNoAi(),
            DefaultCloudPayloadRedactor()
        )
    )

    @VisibleForTesting
    internal constructor(secureKeyStorage: SecureKeyStorage, client: OkHttpClient) : this(
        secureKeyStorage = secureKeyStorage,
        client = client,
        promptFormatter = DashboardBriefingPromptFormatter(),
        aiSettingsRepository = null,
        privacyGate = failClosedGate(),
        cloudPayloadPolicy = DefaultCloudPayloadPolicy(
            EffectiveCloudAiPolicyResolver.failClosedNoAi(),
            DefaultCloudPayloadRedactor()
        )
    )

    @VisibleForTesting
    internal constructor(secureKeyStorage: SecureKeyStorage, apiKeyOverride: String) : this(
        secureKeyStorage = secureKeyStorage,
        client = OkHttpClient(),
        promptFormatter = DashboardBriefingPromptFormatter(),
        aiSettingsRepository = null,
        privacyGate = failClosedGate(),
        cloudPayloadPolicy = DefaultCloudPayloadPolicy(
            EffectiveCloudAiPolicyResolver.failClosedNoAi(),
            DefaultCloudPayloadRedactor()
        )
    ) {
        this.apiKeyOverride = apiKeyOverride
    }

    private val apiKey: String
        get() = apiKeyOverride ?: secureKeyStorage.getGeminiKey() ?: ""

    override suspend fun generate(input: DashboardBriefingInput): AiServiceResult<DashboardBriefing> {
        Timber.d(
            "CloudDashboardBriefingService: Starting generation (dateKey=%s, topCategoriesCount=%d, pendingReviewCount=%d)",
            input.dateKey,
            input.topCategories.size,
            input.pendingReviewCount
        )
        
        if (apiKey.isBlank()) {
            Timber.w("CloudDashboardBriefingService: FAILED - Gemini API key missing/blank")
            return AiServiceResult.Failure(AiServiceError.Disabled("Gemini API key missing"))
        }

        // PRIVACY GUARD: Cloud must not be used if user has disabled it.
        val settings = aiSettingsRepository?.settings()?.first()
        if (settings != null && !settings.allowCloudAi) {
            Timber.d("CloudDashboardBriefingService: Cloud AI disabled in settings, skipping.")
            return AiServiceResult.Failure(AiServiceError.Disabled("Cloud AI is disabled in settings"))
        }

        // PRIVACY GATE: Check privacy gate before cloud AI call
        val gateCheck = privacyGate.check(PrivacyCapability.CLOUD_AI_DAILY_BRIEFING)
        if (gateCheck.blocksExecution()) {
            Timber.w("CloudDashboardBriefingService: blocked by privacy gate: ${gateCheck.reason()}")
            return AiServiceResult.Failure(AiServiceError.Disabled("Blocked by privacy gate: ${gateCheck.reason()}"))
        }

        // HIGH-13 FIX: Remove API key length logging (information disclosure)
        Timber.d("CloudDashboardBriefingService: API key configured: ${apiKey.isNotBlank()}")

        val requestBody = buildRequestBody(input, cloudPayloadPolicy)
        Timber.d("CloudDashboardBriefingService: Request body built, length=${requestBody.length}")        
        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_MODEL}:generateContent"
        Timber.d("CloudDashboardBriefingService: URL: ${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_MODEL}:generateContent")
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .build()

        return withContext(Dispatchers.IO) {
            for (attempt in 1..CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                try {
                    Timber.d(
                        "CloudDashboardBriefingService: Executing HTTP request (attempt %d/%d)...",
                        attempt,
                        CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                    )
                    val outcome = client.newCall(request).execute().use { response ->
                        Timber.d("CloudDashboardBriefingService: HTTP response code: ${response.code}")

                        if (!response.isSuccessful) {
                            val correlationId = CloudCorrelation.newCorrelationId()
                            val errorClass = "HTTP_${response.code}"
                            if (CloudRetryPolicy.isRetryable(response.code) && attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                                Timber.w(
                                    "CloudDashboardBriefingService: retryable HTTP %d class=%s correlationId=%s (attempt %d/%d)",
                                    response.code,
                                    errorClass,
                                    correlationId,
                                    attempt,
                                    CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                                )
                                null
                            } else {
                                Timber.w(
                                    "CloudDashboardBriefingService: HTTP %d class=%s correlationId=%s",
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
                    }

                    if (outcome != null) return@withContext outcome
                } catch (e: SocketTimeoutException) {
                    if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        Timber.w(
                            e,
                            "CloudDashboardBriefingService: timeout, retrying (%d/%d)",
                            attempt,
                            CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                        )
                    } else {
                        Timber.w(e, "CloudDashboardBriefingService: FAILED - timeout")
                        return@withContext AiServiceResult.Failure(AiServiceError.Timeout)
                    }
                } catch (e: SSLException) {
                    Timber.w(e, "CloudDashboardBriefingService: FAILED - SSL error")
                    return@withContext AiServiceResult.Failure(AiServiceError.SslError)
                } catch (e: IOException) {
                    if (CloudRetryPolicy.isRetryableIoException(e) && attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        Timber.w(
                            e,
                            "CloudDashboardBriefingService: network failure, retrying (%d/%d)",
                            attempt,
                            CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                        )
                    } else {
                        Timber.w(e, "CloudDashboardBriefingService: FAILED - network failure")
                        return@withContext AiServiceResult.Failure(AiServiceError.Offline)
                    }
                } catch (e: JSONException) {
                    Timber.w(e, "CloudDashboardBriefingService: FAILED - json parse failure")
                    return@withContext AiServiceResult.Failure(AiServiceError.ParseError(e.message))
                } catch (e: Exception) {
                    Timber.w(e, "CloudDashboardBriefingService: FAILED - parse failure")
                    return@withContext AiServiceResult.Failure(AiServiceError.Unknown(e.message))
                }

                if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                    delay(CloudRetryPolicy.backoffDelayMs(attempt))
                }
            }

            AiServiceResult.Failure(AiServiceError.Unknown("Retry attempts exhausted"))
        }
    }

    private suspend fun buildRequestBody(input: DashboardBriefingInput, policy: CloudPayloadPolicy): String {
        val rawPrompt = promptFormatter.buildPrompt(input, shouldRedact = false)
        val prepared = policy.prepareText(CloudPayloadPurpose.DASHBOARD_BRIEFING, rawPrompt)
        val prompt = prepared.text
        // P8F-03: record cloud-call provenance
        auditLogger.logCloudCall(
            PrivacyCapability.CLOUD_AI_DAILY_BRIEFING,
            PrivacyDecision.Allowed,
            PrivacyAuditContext.forCloudCall(
                provider = "gemini",
                modelId = AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_MODEL,
                purpose = CloudPayloadPurpose.DASHBOARD_BRIEFING,
                payload = prepared,
                correlationId = CloudCorrelation.newCorrelationId()
            )
        )
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

        return DashboardBriefingResponseParser.parseResponse(text)
    }
}
