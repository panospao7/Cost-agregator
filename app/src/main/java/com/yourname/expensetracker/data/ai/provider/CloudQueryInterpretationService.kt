package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.ai.provider.internal.CloudCorrelation
import com.yourname.expensetracker.data.ai.provider.internal.CloudRetryPolicy
import com.yourname.expensetracker.data.ai.provider.internal.SystemRetryDelay
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.di.CloudAiHttpClient
import com.yourname.expensetracker.domain.ai.model.AssistantUnsupportedReason
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.service.QueryInterpretationService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyAuditContext
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import androidx.annotation.VisibleForTesting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import timber.log.Timber
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
// CRITICAL FIX (CRITICAL-1): Now uses SecureKeyStorage instead of BuildConfig
class CloudQueryInterpretationService @VisibleForTesting internal constructor(
    private val secureKeyStorage: SecureKeyStorage,
    private val client: OkHttpClient,
    private val privacyGate: PrivacyGate,
    private val cloudPayloadPolicy: CloudPayloadPolicy,
    private val auditLogger: PrivacyAuditLogger,
    // B4-K2: retry-delay seam; production delegates to coroutine delay(), tests record it.
    private val retryDelay: suspend (Long) -> Unit
) : QueryInterpretationService {

    // Hilt-visible constructor (unchanged dependency signature).
    @Inject
    constructor(
        secureKeyStorage: SecureKeyStorage,
        @CloudAiHttpClient client: OkHttpClient,
        privacyGate: PrivacyGate,
        cloudPayloadPolicy: CloudPayloadPolicy,
        // P8F-03: cloud-call provenance audit (Hilt resolves; default keeps test/secondary ctors inert)
        auditLogger: PrivacyAuditLogger = PrivacyAuditLogger.NO_OP
    ) : this(secureKeyStorage, client, privacyGate, cloudPayloadPolicy, auditLogger, SystemRetryDelay)

    private var apiKeyOverride: String? = null

    @VisibleForTesting
    internal constructor(secureKeyStorage: SecureKeyStorage) : this(
        secureKeyStorage,
        OkHttpClient(),
        object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.FailClosed("PrivacyGate not configured in test constructor")
        },
        DefaultCloudPayloadPolicy(EffectiveCloudAiPolicyResolver.failClosedNoAi(), DefaultCloudPayloadRedactor())
    )

    @VisibleForTesting
    internal constructor(secureKeyStorage: SecureKeyStorage, apiKeyOverride: String) : this(
        secureKeyStorage,
        OkHttpClient(),
        object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.FailClosed("PrivacyGate not configured in test constructor")
        },
        DefaultCloudPayloadPolicy(EffectiveCloudAiPolicyResolver.failClosedNoAi(), DefaultCloudPayloadRedactor())
    ) {
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
        if (gateDecision.blocksExecution()) {
            Timber.d("CloudQueryInterpretationService: PROVIDER_DISABLED stage=privacy_gate")
            return unsupported(DiagnosticReasonCode.PROVIDER_DISABLED)
        }

        val prompt = promptHelper.buildPrompt(input.toCloudPromptInput())
        val prepared = try {
            cloudPayloadPolicy.prepareText(CloudPayloadPurpose.QUERY_INTERPRETATION, prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("CloudQueryInterpretationService: UNKNOWN_ERROR stage=payload_policy class=%s", e::class.java.simpleName)
            return unsupported(DiagnosticReasonCode.UNKNOWN_ERROR)
        }
        // Audit and transport share the policy-owned payload, not separately prepared text.
        auditLogger.logCloudCall(
            PrivacyCapability.CLOUD_AI_GENERAL,
            PrivacyDecision.Allowed,
            PrivacyAuditContext.forCloudCall(
                provider = "gemini",
                modelId = AppConfig.Ai.QUERY_INTERPRETATION_CLOUD_MODEL,
                purpose = CloudPayloadPurpose.QUERY_INTERPRETATION,
                payload = prepared,
                correlationId = CloudCorrelation.newCorrelationId()
            )
        )
        val requestBody = buildRequestBody(prepared.text)
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

                        val body = response.body?.string()
                            ?: return@use unsupported(DiagnosticReasonCode.PARSER_FAILED)
                        return@use parseResponse(input, body)
                    }

                    if (result != null) {
                        return@withContext result
                    }
                    if (!retryableHttpFailure) {
                        return@withContext unsupported()
                    }
                } catch (e: SocketTimeoutException) {
                    Timber.w("CloudQueryInterpretationService: TIMEOUT class=%s attempt=%d/%d", e::class.java.simpleName, attempt, CloudRetryPolicy.MAX_RETRY_ATTEMPTS)
                    if (attempt >= CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        return@withContext unsupported(DiagnosticReasonCode.TIMEOUT)
                    }
                } catch (e: SSLException) {
                    Timber.w("CloudQueryInterpretationService: UNKNOWN_ERROR stage=ssl class=%s attempt=%d", e::class.java.simpleName, attempt)
                    if (!CloudRetryPolicy.isRetryableIoException(e) || attempt >= CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        return@withContext unsupported(DiagnosticReasonCode.UNKNOWN_ERROR)
                    }
                } catch (e: IOException) {
                    Timber.w(
                        "CloudQueryInterpretationService: NETWORK_UNAVAILABLE class=%s attempt=%d/%d",
                        e::class.java.simpleName,
                        attempt,
                        CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                    )
                    if (!CloudRetryPolicy.isRetryableIoException(e) || attempt >= CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        return@withContext unsupported(DiagnosticReasonCode.NETWORK_UNAVAILABLE)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: JSONException) {
                    Timber.w("CloudQueryInterpretationService: PARSER_FAILED class=%s attempt=%d", e::class.java.simpleName, attempt)
                    return@withContext unsupported(DiagnosticReasonCode.PARSER_FAILED)
                } catch (e: Exception) {
                    Timber.w("CloudQueryInterpretationService: UNKNOWN_ERROR class=%s attempt=%d", e::class.java.simpleName, attempt)
                    return@withContext unsupported(DiagnosticReasonCode.UNKNOWN_ERROR)
                }

                if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                    val delayMs = CloudRetryPolicy.backoffDelayMs(attempt)
                    Timber.d("CloudQueryInterpretationService: retrying after ${delayMs}ms")
                    retryDelay(delayMs)
                }
            }

            unsupported()
        }
    }

    private fun buildRequestBody(promptText: String): String {
        // The caller supplies only text returned by CloudPayloadPolicy.
        return JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", promptText))
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
        val candidates = root.optJSONArray("candidates")
            ?: return unsupported(reason = AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID)
        if (candidates.length() == 0) {
            return unsupported(reason = AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID)
        }
        val text = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.trim()
            ?: return unsupported(reason = AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID)

        return promptHelper.parseResponse(input, text)
            ?: unsupported(reason = AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID)
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

    /**
     * Typed bounded result. [diagnostic] carries the existing cloud failure code (bounded; the
     * producing site logs it); never rendered or persisted. A parser failure maps to PROVIDER_RESPONSE_INVALID,
     * every other transport/gate failure to PROVIDER_UNAVAILABLE.
     */
    private fun unsupported(
        diagnostic: DiagnosticReasonCode = DiagnosticReasonCode.UNKNOWN_ERROR,
        reason: AssistantUnsupportedReason =
            if (diagnostic == DiagnosticReasonCode.PARSER_FAILED) {
                AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID
            } else {
                AssistantUnsupportedReason.PROVIDER_UNAVAILABLE
            }
    ): FinancialQueryInterpretationResult =
        FinancialQueryInterpretationResult.Unsupported(reason = reason, diagnostic = diagnostic)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
