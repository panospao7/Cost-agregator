package com.yourname.expensetracker.data.ai.provider

import androidx.annotation.VisibleForTesting
import com.yourname.expensetracker.data.ai.provider.internal.CloudCorrelation
import com.yourname.expensetracker.data.ai.provider.internal.CloudRetryPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.di.CloudAiHttpClient
import com.yourname.expensetracker.domain.ai.model.CategorizedReceiptItem
import com.yourname.expensetracker.domain.ai.model.CloudCategoryOption
import com.yourname.expensetracker.domain.ai.model.CategorySuggestion
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.service.ReceiptItemCategorizationService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.CompositePrivacyGate
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import java.security.MessageDigest
import javax.net.ssl.SSLException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
// PRIV-441-01: Now uses CloudPayloadPolicy for all payload preparation — no direct redactBeforeCloud access
class CloudReceiptItemCategorizationService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage,
    @CloudAiHttpClient private val client: OkHttpClient,
    private val privacyGate: PrivacyGate,
    private val cloudPayloadPolicy: CloudPayloadPolicy
) : ReceiptItemCategorizationService {

    @androidx.annotation.VisibleForTesting
    internal constructor(secureKeyStorage: SecureKeyStorage) : this(
        secureKeyStorage, OkHttpClient(),
        CompositePrivacyGate(
            emptyList(),
            PrivacyAuditLogger.NO_OP,
            com.yourname.expensetracker.domain.privacy.PrivacyCapabilityHandlingPolicy.gateHandledCapabilities
        ),
        DefaultCloudPayloadPolicy(
            EffectiveCloudAiPolicyResolver.failClosedNoAi(),
            DefaultCloudPayloadRedactor()
        )
    )
    
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    
    private val apiKey: String
        get() = secureKeyStorage.getGeminiKey() ?: ""
    
    override suspend fun categorizeItems(input: ReceiptItemCategorizationInput): ReceiptItemCategorizationResult? {
        if (apiKey.isBlank()) {
            Timber.d("CloudReceiptItemCategorizationService: Gemini API key missing, skipping.")
            return null
        }

        // PRIVACY GATE: Check cloud AI privacy gate before proceeding
        val gateDecision = privacyGate.check(PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION)
        if (gateDecision.blocksExecution()) {
            Timber.d("CloudReceiptItemCategorizationService: privacy gate denied: ${gateDecision.reason()}")
            return null
        }

        val correlationId = CloudCorrelation.newCorrelationId()
        // PRIV-43B-03: Build full raw prompt, then prepare through policy — no empty-string probe
        val rawPrompt = buildPrompt(input)
        val prepared = cloudPayloadPolicy.prepareText(CloudPayloadPurpose.ITEM_CATEGORIZATION, rawPrompt)

        return withContext(Dispatchers.IO) {
            val requestBody = buildRequestBody(prepared.text)

            val request = Request.Builder()
                .url("${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.RECEIPT_ITEM_CATEGORIZATION_CLOUD_MODEL}:generateContent")
                .post(requestBody.toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .build()

            for (attempt in 1..CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                var retryableHttpFailure = false
                try {
                    val parsed = client.newCall(request).execute().use { response ->
                        val body = response.body?.string()

                        if (!response.isSuccessful) {
                            val errorClass = "HTTP_${response.code}"
                            if (CloudRetryPolicy.isRetryable(response.code) && attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                                retryableHttpFailure = true
                                Timber.w(
                                    "CloudReceiptItemCategorizationService: retryable HTTP %d class=%s correlationId=%s (attempt %d/%d)",
                                    response.code,
                                    errorClass,
                                    correlationId,
                                    attempt,
                                    CloudRetryPolicy.MAX_RETRY_ATTEMPTS
                                )
                            } else {
                                Timber.w(
                                    "CloudReceiptItemCategorizationService: HTTP %d class=%s correlationId=%s",
                                    response.code,
                                    errorClass,
                                    correlationId
                                )
                            }
                            return@use null
                        }

                        if (body == null) {
                            Timber.w(
                                "CloudReceiptItemCategorizationService: Empty response body correlationId=%s",
                                correlationId
                            )
                            return@use null
                        }

                        parseResponse(body, correlationId, input)
                    }

                    if (parsed != null) return@withContext parsed
                    if (!retryableHttpFailure) return@withContext null
                } catch (e: SocketTimeoutException) {
                    if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        Timber.w(
                            e,
                            "CloudReceiptItemCategorizationService: timeout, retrying (%d/%d) correlationId=%s",
                            attempt,
                            CloudRetryPolicy.MAX_RETRY_ATTEMPTS,
                            correlationId
                        )
                    } else {
                        Timber.e(
                            e,
                            "CloudReceiptItemCategorizationService: timeout correlationId=%s",
                            correlationId
                        )
                        return@withContext null
                    }
                } catch (e: SSLException) {
                    Timber.e(
                        e,
                        "CloudReceiptItemCategorizationService: SSL failure correlationId=%s",
                        correlationId
                    )
                    return@withContext null
                } catch (e: IOException) {
                    val retryable = CloudRetryPolicy.isRetryableIoException(e)
                    if (retryable && attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                        Timber.w(
                            e,
                            "CloudReceiptItemCategorizationService: connection reset, retrying (%d/%d) correlationId=%s",
                            attempt,
                            CloudRetryPolicy.MAX_RETRY_ATTEMPTS,
                            correlationId
                        )
                    } else {
                        Timber.e(
                            e,
                            "CloudReceiptItemCategorizationService: network failure correlationId=%s",
                            correlationId
                        )
                        return@withContext null
                    }
                } catch (e: Exception) {
                    Timber.e(
                        e,
                        "CloudReceiptItemCategorizationService: Error calling cloud AI for receipt item categorization correlationId=%s",
                        correlationId
                    )
                    return@withContext null
                }

                if (attempt < CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
                    delay(CloudRetryPolicy.backoffDelayMs(attempt))
                }
            }

            null
        }
    }
    
    private fun buildPrompt(input: ReceiptItemCategorizationInput): String {
        // PRIV-43B-03: Raw values — CloudPayloadPolicy will redact if required; no hashCode pseudonyms
        val safeMerchant = input.merchant ?: "Unknown"

        val categoriesList = input.userCategories.joinToString(", ") { "${it.name} (id: ${it.id})" }
        
        val itemsList = input.lineItems.joinToString("\n") { item ->
            val safeDescription = item.description ?: "unknown item"
            "- $safeDescription: ${CurrencyFormatter.format(item.totalPrice, input.currency)}"
        }
        
        return """
You are a receipt item categorization assistant. Categorize each item below.

Store: $safeMerchant
Available categories: $categoriesList

Items:
$itemsList

For each item, provide:
1. categoryId (from available categories, or null if suggesting new)
2. categoryName (MUST match one of the available categories exactly, or provide a new category name)
3. confidence (0.0-1.0)
4. rationale (brief explanation)
5. isNewCategorySuggestion (true only if suggesting a category not in the list)

If an item doesn't fit existing categories, you MAY suggest a new category name (isNewCategorySuggestion: true).

Confidence guidelines:
- 0.9-1.0: Clear match (e.g., "Apples" → "Food")
- 0.7-0.89: Good match (e.g., "Detergent" → "Household")  
- 0.5-0.69: Uncertain (provide 2 alternative suggestions)
- <0.5: Very unclear

Also calculate proportional tax distribution if total tax is provided: ${input.totalTax ?: 0.0}

Output JSON format:
{
  "items": [
    {
      "description": "item name",
      "amount": 2.50,
      "categoryId": 5,
      "categoryName": "Food",
      "confidence": 0.94,
      "rationale": "Fresh produce",
      "isNewCategorySuggestion": false,
      "alternatives": [
        {"categoryId": 12, "categoryName": "Groceries", "confidence": 0.89}
      ]
    }
  ],
  "suggestedNewCategories": ["Sportswear"],
  "taxDistribution": {"5": 0.25, "12": 1.75}
}
""".trimIndent()
    }
    
    private fun buildRequestBody(prompt: String): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        
        return JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", parts)
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", AppConfig.Ai.CLOUD_RECEIPT_ITEM_MAX_TOKENS)
                put("responseMimeType", "application/json")
            })
        }.toString()
    }
    
    private fun parseResponse(
        body: String,
        correlationId: String,
        input: ReceiptItemCategorizationInput
    ): ReceiptItemCategorizationResult? {
        return try {
            val effectiveCloudCategoryOptions = cloudCategoryOptionsForPrompt(input)
            val root = JSONObject(body)
            val candidates = root.optJSONArray("candidates") ?: run {
                Timber.w(
                    "CloudReceiptItemCategorizationService: Missing candidates array correlationId=%s",
                    correlationId
                )
                return null
            }
            val text = candidates.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text") ?: ""
            
            if (text.isBlank()) {
                Timber.w(
                    "CloudReceiptItemCategorizationService: Blank model text response correlationId=%s",
                    correlationId
                )
                return null
            }
            
            // Extract JSON from response
            val jsonText = extractFirstJsonObject(text) ?: run {
                Timber.w(
                    "CloudReceiptItemCategorizationService: No JSON object found in model text (textLength=%d, textHash=%s, correlationId=%s)",
                    text.length,
                    text.sha256Prefix(),
                    correlationId
                )
                return null
            }
            val result = JSONObject(jsonText)
            
            val itemsArray = result.optJSONArray("items") ?: return null
            val items = mutableListOf<CategorizedReceiptItem>()
            
            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.getJSONObject(i)
                val amount = item.optFiniteDoubleStrictOrNull("amount")
                    ?: throw JSONException("Missing numeric 'amount' in items[$i]")
                val confidence = item.optFiniteDoubleStrictOrNull("confidence")
                    ?: throw JSONException("Missing numeric 'confidence' in items[$i]")
                val categoryId = item.optStrictLongStrictOrNull("categoryId")?.takeIf { it > 0 }
                val categoryName = mapCloudCategoryNameToRaw(
                    rawCategoryName = item.optString("categoryName", "Unknown"),
                    categoryId = categoryId,
                    input = input,
                    effectiveCloudCategoryOptions = effectiveCloudCategoryOptions
                )
                
                val alternatives = mutableListOf<CategorySuggestion>()
                item.optJSONArray("alternatives")?.let { altArray ->
                    for (j in 0 until altArray.length()) {
                        val alt = altArray.getJSONObject(j)
                        val altConfidence = alt.optFiniteDoubleStrictOrNull("confidence")
                            ?: throw JSONException("Missing numeric 'confidence' in alternatives[$j]")
                        val altCategoryId = alt.optStrictLongStrictOrNull("categoryId")?.takeIf { it > 0 }
                        alternatives.add(CategorySuggestion(
                            categoryId = altCategoryId,
                            categoryName = mapCloudCategoryNameToRaw(
                                rawCategoryName = alt.optString("categoryName", ""),
                                categoryId = altCategoryId,
                                input = input,
                                effectiveCloudCategoryOptions = effectiveCloudCategoryOptions
                            ),
                            confidence = altConfidence.toFloat()
                        ))
                    }
                }
                
                items.add(CategorizedReceiptItem(
                    itemDescription = item.optString("description", ""),
                    amount = amount,
                    suggestedCategory = CategorySuggestion(
                        categoryId = categoryId,
                        categoryName = categoryName,
                        confidence = confidence.toFloat(),
                        isNewCategorySuggestion = item.optBoolean("isNewCategorySuggestion", false)
                    ),
                    confidence = confidence.toFloat(),
                    rationale = item.optString("rationale", ""),
                    alternatives = alternatives,
                    needsReview = confidence.toFloat() < 0.7f
                ))
            }
            
            val newCategories = mutableListOf<String>()
            result.optJSONArray("suggestedNewCategories")?.let { array ->
                for (i in 0 until array.length()) {
                    newCategories.add(array.getString(i))
                }
            }
            
            val taxDistribution = mutableMapOf<Long, Double>()
            result.optJSONObject("taxDistribution")?.let { taxObj ->
                taxObj.keys().forEach { key ->
                    val categoryId = key.toLongOrNull() ?: return@forEach
                    val taxAmount = taxObj.optFiniteDoubleStrictOrNull(key)
                        ?: throw JSONException("Missing numeric taxDistribution['$key']")
                    taxDistribution[categoryId] = taxAmount
                }
            }
            
            val avgConfidence = if (items.isNotEmpty()) {
                items.map { it.confidence }.average().toFloat()
            } else 0f
            
            ReceiptItemCategorizationResult(
                items = items,
                totalConfidence = avgConfidence,
                needsReview = items.any { it.needsReview },
                suggestedNewCategories = newCategories,
                taxDistribution = taxDistribution
            )
        } catch (e: Exception) {
            Timber.e(
                e,
                "CloudReceiptItemCategorizationService: Error parsing AI response (bodyLength=%d, bodyHash=%s, correlationId=%s)",
                body.length,
                body.sha256Prefix(),
                correlationId
            )
            null
        }
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

    private fun sanitizeCloudText(raw: String, maxChars: Int, fallbackPrefix: String): String {
        val trimmed = raw.trim().take(maxChars)
        val redacted = trimmed
            .replace(EMAIL_REGEX, "[REDACTED_EMAIL]")
            .replace(IBAN_REGEX, "[REDACTED_IBAN]")
            .replace(CARD_REGEX, "[REDACTED_CARD]")
            .replace(PHONE_REGEX, "[REDACTED_PHONE]")
            .replace(LONG_NUMBER_REGEX, "[REDACTED_NUMBER]")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)

        return if (redacted.isBlank()) {
            "${fallbackPrefix}_${trimmed.sha256Prefix()}"
        } else {
            redacted
        }
    }

    private fun cloudCategoryOptionsForPrompt(input: ReceiptItemCategorizationInput): List<CloudCategoryOption> {
        if (!input.redactBeforeCloud) return emptyList()
        if (input.cloudCategoryOptions.isNotEmpty()) return input.cloudCategoryOptions

        return input.userCategories.map {
            CloudCategoryOption(
                categoryId = it.id,
                cloudName = "cat_${it.id}"
            )
        }
    }

    private fun mapCloudCategoryNameToRaw(
        rawCategoryName: String,
        categoryId: Long?,
        input: ReceiptItemCategorizationInput,
        effectiveCloudCategoryOptions: List<CloudCategoryOption>
    ): String {
        if (!input.redactBeforeCloud) return rawCategoryName

        val normalizedRawCategoryName = rawCategoryName.trim()
        val resolvedCategoryId = categoryId
            ?: effectiveCloudCategoryOptions.firstOrNull {
                it.cloudName.trim().equals(normalizedRawCategoryName, ignoreCase = true)
            }?.categoryId
            ?: extractFallbackCategoryId(normalizedRawCategoryName)

        if (resolvedCategoryId != null) {
            input.userCategories.firstOrNull { it.id == resolvedCategoryId }?.let { return it.name }
        }

        return rawCategoryName
    }

    private fun extractFallbackCategoryId(rawCategoryName: String): Long? {
        val match = FALLBACK_REDACTED_CATEGORY_REGEX.matchEntire(rawCategoryName) ?: return null
        return match.groupValues.getOrNull(1)?.toLongOrNull()
    }

    private fun String.sha256Prefix(length: Int = 12): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString(separator = "") { "%02x".format(it) }.take(length)
    }

    private companion object {
        private val JSON_FENCE_REGEX = Regex("""```(?:json)?\s*([\s\S]*?)\s*```""", RegexOption.IGNORE_CASE)
        private val FALLBACK_REDACTED_CATEGORY_REGEX = Regex("""cat_(\d+)""", RegexOption.IGNORE_CASE)
        private val EMAIL_REGEX = Regex("""\b[\w._%+-]+@[\w.-]+\.[A-Za-z]{2,}\b""")
        private val IBAN_REGEX = Regex("""\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b""")
        private val CARD_REGEX = Regex("""\b(?:\d[ -]?){13,19}\b""")
        private val PHONE_REGEX = Regex("""\+?\d[\d\s().-]{6,}\d""")
        private val LONG_NUMBER_REGEX = Regex("""\b\d{10,}\b""")
    }

}
