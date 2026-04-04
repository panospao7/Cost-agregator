package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.di.CloudAiHttpClient
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.service.CategorizationAssistService
import com.yourname.expensetracker.domain.config.AppConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import timber.log.Timber

@Singleton
// CRITICAL FIX (CRITICAL-1): Now uses SecureKeyStorage instead of BuildConfig
class CloudCategorizationAssistService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage,
    @CloudAiHttpClient private val client: OkHttpClient
) : CategorizationAssistService {

    // Secondary constructor for tests
    constructor(secureKeyStorage: SecureKeyStorage) : this(secureKeyStorage, OkHttpClient())

    private val apiKey: String
        get() = secureKeyStorage.getGeminiKey() ?: ""

    override suspend fun suggest(input: CategorizationAssistInput): CategoryAssistSuggestion? {
        if (apiKey.isBlank()) {
            Timber.d("CloudCategorizationAssistService: Gemini API key missing, skipping.")
            return null
        }

        val requestBody = buildRequestBody(input)
        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.CATEGORIZATION_ASSIST_CLOUD_MODEL}:generateContent"
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val correlationId = newCorrelationId()
                    val errorClass = "HTTP_${response.code}"
                    Timber.w(
                        "CloudCategorizationAssistService: HTTP %d class=%s correlationId=%s",
                        response.code,
                        errorClass,
                        correlationId
                    )
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                parseResponse(body)
            }
        } catch (e: IOException) {
            Timber.w(e, "CloudCategorizationAssistService: network failure")
            null
        } catch (e: Exception) {
            Timber.w(e, "CloudCategorizationAssistService: parse failure")
            null
        }
    }

    private fun buildRequestBody(input: CategorizationAssistInput): String {
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

    private fun buildPrompt(input: CategorizationAssistInput): String {
        val categories = input.candidateCategories.joinToString("\n") { "- ${it.id}: ${it.name}" }
        val merchantContext = buildMerchantContext(input)
        
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
            - merchant: ${input.merchant}
            - amount: ${input.amount} ${input.currency}
            - transactionType: ${input.transactionType}
            - date: ${input.date?.toString() ?: "none"}
            - currentCategoryId: ${input.currentCategoryId?.toString() ?: "none"}
            - deterministicMatchType: ${input.deterministicMatchType ?: "none"}
            - deterministicExplanation: ${input.deterministicExplanation ?: "none"}
            - supportingText: ${input.supportingText ?: "none"}

            Allowed categories:
            $categories
        """.trimIndent()
    }
    
    private fun buildMerchantContext(input: CategorizationAssistInput): String {
        val parts = mutableListOf<String>()
        
        if (input.recentTransactionsWithSameMerchant.isNotEmpty()) {
            val examples = input.recentTransactionsWithSameMerchant
                .take(5)
                .joinToString("; ") { "${it.merchant} → ${it.categoryName}" }
            parts.add("Known merchant history: $examples")
        }
        
        return if (parts.isNotEmpty()) {
            "\nContext:\n" + parts.joinToString("\n")
        } else {
            ""
        }
    }

    private fun parseResponse(body: String): CategoryAssistSuggestion? {
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
        if (!suggestion.has("categoryId") || suggestion.isNull("categoryId")) return null
        if (!suggestion.has("categoryName") || suggestion.optString("categoryName").isBlank()) return null

        return CategoryAssistSuggestion(
            categoryId = suggestion.optLong("categoryId"),
            categoryName = suggestion.optString("categoryName").trim(),
            confidence = if (suggestion.has("confidence") && !suggestion.isNull("confidence")) suggestion.optDouble("confidence").toFloat() else null,
            rationale = suggestion.optString("rationale").trim().ifBlank { null },
            alternativeCategoryIds = suggestion.optJSONArray("alternativeCategoryIds").toLongList()
        )
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun JSONArray?.toLongList(): List<Long> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                add(optLong(index))
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private fun newCorrelationId(): String = UUID.randomUUID().toString().take(8)
}
