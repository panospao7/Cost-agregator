package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.di.CloudAiHttpClient
import com.yourname.expensetracker.data.ai.provider.internal.CloudCorrelation
import com.yourname.expensetracker.data.ai.provider.internal.CloudJsonParser
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.CloudPayloadRedactor
import com.yourname.expensetracker.data.ai.provider.internal.CloudRetryPolicy
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.CategorizationAssistService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
// CRITICAL FIX (CRITICAL-1): Now uses SecureKeyStorage instead of BuildConfig
class CloudCategorizationAssistService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage,
    @CloudAiHttpClient private val client: OkHttpClient,
    private val aiSettingsRepository: AiSettingsRepository? = null,
    private val privacyGate: PrivacyGate,
    private val redactor: CloudPayloadRedactor
) : CategorizationAssistService {

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

    // Secondary constructor for tests with client override
    constructor(secureKeyStorage: SecureKeyStorage, client: OkHttpClient) : this(
        secureKeyStorage,
        client,
        null,
        object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.Allowed
        },
        DefaultCloudPayloadRedactor()
    )

    private val apiKey: String
        get() = secureKeyStorage.getGeminiKey() ?: ""

    override suspend fun suggest(input: CategorizationAssistInput): CategoryAssistSuggestion? {
        if (apiKey.isBlank()) {
            Timber.d("CloudCategorizationAssistService: Gemini API key missing, skipping.")
            return null
        }

        // PRIVACY GATE: Unified cloud AI gate — checks both PrivacySettings + AiSettings
        val gateCheck = privacyGate.check(PrivacyCapability.CLOUD_AI_GENERAL)
        if (gateCheck is PrivacyDecision.Denied) {
            Timber.w("CloudCategorizationAssistService: blocked by privacy gate: ${gateCheck.reason}")
            return null
        }

        val shouldRedact = aiSettingsRepository?.settings()?.first()?.redactBeforeCloud ?: true
        val requestBody = buildRequestBody(input, shouldRedact)
        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.CATEGORIZATION_ASSIST_CLOUD_MODEL}:generateContent"
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
                    val parsed = client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val correlationId = CloudCorrelation.newCorrelationId()
                            val errorClass = "HTTP_${response.code}"

                            if (CloudRetryPolicy.isRetryable(response.code) && attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                                retryableHttpFailure = true
                                Timber.w(
                                    "CloudCategorizationAssistService: retryable HTTP %d class=%s correlationId=%s (attempt %d/%d)",
                                    response.code,
                                    errorClass,
                                    correlationId,
                                    attempt,
                                    CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                                )
                                return@use null
                            }

                            Timber.w(
                                "CloudCategorizationAssistService: HTTP %d class=%s correlationId=%s",
                                response.code,
                                errorClass,
                                correlationId
                            )
                            return@use null
                        }

                        val body = response.body?.string() ?: return@use null
                        parseResponse(body, input)
                    }

                    if (parsed != null) return@withContext parsed
                    if (!retryableHttpFailure) return@withContext null
                } catch (e: IOException) {
                    val canRetry = CloudRetryPolicy.isRetryableIoException(e)
                    if (!canRetry || attempt >= CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        Timber.w(e, "CloudCategorizationAssistService: network failure")
                        return@withContext null
                    }
                    Timber.w(
                        e,
                        "CloudCategorizationAssistService: retryable network failure (attempt %d/%d)",
                        attempt,
                        CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                    )
                } catch (e: Exception) {
                    Timber.w(e, "CloudCategorizationAssistService: parse failure")
                    return@withContext null
                }

                if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                    delay(CloudRetryPolicy.backoffDelayMs(attempt))
                }
            }

            null
        }
    }

    private fun buildRequestBody(input: CategorizationAssistInput, shouldRedact: Boolean): String {
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
                    put("temperature", 0.1)
                    put("maxOutputTokens", AppConfig.Ai.CATEGORIZATION_ASSIST_MAX_OUTPUT_TOKENS)
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

    private fun buildPrompt(input: CategorizationAssistInput, shouldRedact: Boolean): String {
        val categories = input.candidateCategories.joinToString("\n") { "- ${it.id}: ${it.cloudLabel}" }
        val merchantContext = buildMerchantContext(input)

        // PRIVACY FIX: Sanitize PII before sending to cloud
        val safeMerchant = if (shouldRedact) {
            redactor.redactMerchant(input.merchant).value ?: "Unknown"
        } else {
            input.merchant
        }
        val safeSupportingText = if (shouldRedact && input.supportingText != null) {
            redactor.redactText(input.supportingText, CloudPayloadPurpose.ITEM_CATEGORIZATION).text
        } else {
            input.supportingText ?: "none"
        }

        return """
You are helping categorize a pending finance review.
Choose only from the provided category list.
Never invent a new category.
Use COMMON SENSE to identify merchants from abbreviations or OCR errors.

IMPORTANT - Merchant Identification:
- "amzn", "amzn uk", "amazon", "amazon uk" → Amazon (Online Shopping/Electronics)
- "goog", "google", "g.co" → Google (Subscriptions/Services)
- "msft", "microsoft", "xbox" → Microsoft (Subscriptions/Software)
- "netflix", "nfx" → Netflix (Subscriptions/Entertainment)
- "spotify", "sptfy" → Spotify (Subscriptions/Music)
- "fb", "facebook", "meta" → Meta/Facebook (Subscriptions/Social)
- "appl", "apple", "itunes", "appstore" → Apple (Subscriptions/Technology)
- "etsy" → Etsy (Shopping)
- "airbnb" → Airbnb (Travel/Accommodation)
- "uber", "lyft" → Ride Share (Transportation)
- "deliv", "doordash", "grubhub" → Food Delivery (Food & Dining)
$merchantContext

Return JSON only.

JSON schema:
{
  "categoryId": 0,
  "categoryName": "string",
  "confidence": 0.0,
  "rationale": "short explanation of your reasoning",
  "alternativeCategoryIds": [0]
}

Review facts:
- merchant: $safeMerchant
- amount: ${input.amount?.toString() ?: "none"} ${input.currency}
- transactionType: ${input.transactionType}
- date: ${input.date?.toString() ?: "none"}
- currentCategoryId: ${input.currentCategoryId?.toString() ?: "none"}
- deterministicMatchType: ${input.deterministicMatchType ?: "none"}
- deterministicExplanation: ${input.deterministicExplanation ?: "none"}
- supportingText: $safeSupportingText

            Allowed categories:
            $categories
        """.trimIndent()
    }
    
    private fun buildMerchantContext(input: CategorizationAssistInput): String {
        val parts = mutableListOf<String>()
        
        if (input.recentTransactionsWithSameMerchant.isNotEmpty()) {
            val examples = input.recentTransactionsWithSameMerchant
                .take(5)
                .joinToString("; ") { "${it.cloudMerchant} → ${it.cloudCategoryName}" }
            parts.add("Known merchant history: $examples")
        }
        
        return if (parts.isNotEmpty()) {
            "\nContext:\n" + parts.joinToString("\n")
        } else {
            ""
        }
    }

    private fun parseResponse(body: String, input: CategorizationAssistInput): CategoryAssistSuggestion? {
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
        if (!suggestion.has("categoryId") || suggestion.isNull("categoryId")) return null
        if (!suggestion.has("categoryName") || suggestion.optString("categoryName").isBlank()) return null

        val categoryId = StrictAiJsonParsing.run { suggestion.positiveIdOrNull("categoryId") } ?: return null
        val matchedCategory = input.candidateCategories.firstOrNull { it.id == categoryId }
            ?: input.candidateCategories.firstOrNull {
                it.name.equals(suggestion.optString("categoryName").trim(), ignoreCase = true) ||
                    it.cloudLabel.equals(suggestion.optString("categoryName").trim(), ignoreCase = true)
            }
            ?: return null

        return CategoryAssistSuggestion(
            categoryId = matchedCategory.id,
            categoryName = matchedCategory.name,
            confidence = if (suggestion.has("confidence") && !suggestion.isNull("confidence")) {
                StrictAiJsonParsing.run { suggestion.boundedConfidenceOrNull("confidence") } ?: return null
            } else {
                null
            },
            rationale = suggestion.optString("rationale").trim().ifBlank { null },
            alternativeCategoryIds = StrictAiJsonParsing.run {
                suggestion.optJSONArray("alternativeCategoryIds").positiveLongs()
            }
                .filter { altId -> input.candidateCategories.any { it.id == altId } }
        )
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
