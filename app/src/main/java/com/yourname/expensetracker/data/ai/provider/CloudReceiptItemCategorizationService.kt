package com.yourname.expensetracker.data.ai.provider

import androidx.annotation.VisibleForTesting
import com.yourname.expensetracker.data.ai.provider.internal.CloudCorrelation
import com.yourname.expensetracker.data.ai.provider.internal.CloudRetryPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.data.privacy.ITEM_CATEGORIZATION_MAX_TEXT_CHARS
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.di.CloudAiHttpClient
import com.yourname.expensetracker.domain.ai.model.CategorizedReceiptItem
import com.yourname.expensetracker.domain.ai.model.CloudCategoryOption
import com.yourname.expensetracker.domain.ai.model.CategorySuggestion
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.model.ReceiptItemPlaceholder
import com.yourname.expensetracker.domain.ai.service.ReceiptItemCategorizationService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PrivacyAuditContext
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import kotlinx.coroutines.CancellationException
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
    private val cloudPayloadPolicy: CloudPayloadPolicy,
    // P8F-03: cloud-call provenance audit. Provided explicitly by AiModule's @Provides
    // factory; default keeps the @VisibleForTesting constructor fail-closed.
    private val auditLogger: PrivacyAuditLogger = PrivacyAuditLogger.NO_OP
) : ReceiptItemCategorizationService {

    @VisibleForTesting
    internal constructor(secureKeyStorage: SecureKeyStorage) : this(
        secureKeyStorage,
        OkHttpClient(),
        object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.FailClosed("PrivacyGate not configured in test constructor")
        },
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
        // PRIV-43B-03: Build full raw prompt, then prepare through policy — no empty-string probe.
        // The prompt carries whole item lines that fit the text budget; sentCount is the number
        // of leading items actually sent and drives the parse. The payload that is sent is the
        // one prepared here, in the scope of the request, and it is checked once more.
        val sentCount = fitItemCount(input, correlationId) ?: return null
        val prepared = cloudPayloadPolicy.prepareText(
            CloudPayloadPurpose.RECEIPT_ITEM_CATEGORIZATION,
            buildPrompt(input, sentCount)
        )
        if (sentCount > 0 && !promptIsComplete(prepared.text, sentCount)) {
            Timber.w(
                "CloudReceiptItemCategorizationService: PROMPT_NOT_COMPLETE sent=%d correlationId=%s",
                sentCount,
                correlationId
            )
            return null
        }
        // P8F-03: record cloud-call provenance
        auditLogger.logCloudCall(
            PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION,
            PrivacyDecision.Allowed,
            PrivacyAuditContext.forCloudCall(
                provider = "gemini",
                modelId = AppConfig.Ai.RECEIPT_ITEM_CATEGORIZATION_CLOUD_MODEL,
                purpose = CloudPayloadPurpose.RECEIPT_ITEM_CATEGORIZATION,
                payload = prepared,
                correlationId = correlationId
            )
        )

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

                        parseResponse(body, correlationId, input, sentCount)
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
                    if (e is CancellationException) throw e
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

    /**
     * How many whole leading item lines can be sent. An empty item list sends none (header
     * only, 0). A non-empty receipt of which no item fits returns null: nothing is prepared for
     * sending and no transport call is made. The text prepared here only decides the count; the
     * payload that is sent is prepared by the caller.
     *
     * The fit is decided on the raw prompt length (redacted or not: the reply limit applies to
     * both), within [MAX_PROMPT_CHARS] and at most [MAX_ITEMS_PER_CALL] items. Redaction can
     * lengthen a text, so the prepared text is verified: every marker present and the text
     * shorter than the redactor cap (a text at the cap may have been cut). On failure the count
     * shrinks (strictly, so the loop runs at most [MAX_ITEMS_PER_CALL] times) and the prompt is
     * prepared again. Logs carry counts and the correlation id only.
     */
    private suspend fun fitItemCount(
        input: ReceiptItemCategorizationInput,
        correlationId: String
    ): Int? {
        val totalItems = input.lineItems.size
        if (totalItems == 0) return 0

        var sentCount = countItemsThatFit(input)
        while (sentCount > 0) {
            val prepared = cloudPayloadPolicy.prepareText(
                CloudPayloadPurpose.RECEIPT_ITEM_CATEGORIZATION,
                buildPrompt(input, sentCount)
            )
            val leadingMarkers = leadingMarkersPresent(prepared.text, sentCount)
            val mayBeCut = prepared.text.length >= ITEM_CATEGORIZATION_MAX_TEXT_CHARS
            if (leadingMarkers == sentCount && !mayBeCut) {
                return sentCount
            }
            // Drop the last present line too when the text may be cut inside it.
            val next = minOf(sentCount - 1, if (mayBeCut) leadingMarkers - 1 else leadingMarkers)
                .coerceAtLeast(0)
            Timber.w(
                "CloudReceiptItemCategorizationService: PROMPT_SHRINK sent=%d next=%d total=%d correlationId=%s",
                sentCount,
                next,
                totalItems,
                correlationId
            )
            sentCount = next
        }

        Timber.w(
            "CloudReceiptItemCategorizationService: PROMPT_NO_ITEM_FITS total=%d correlationId=%s",
            totalItems,
            correlationId
        )
        return null
    }

    /**
     * Number of leading whole item lines that fit [MAX_PROMPT_CHARS] after the fixed header,
     * at most [MAX_ITEMS_PER_CALL]. Stops at the first line that does not fit.
     */
    private fun countItemsThatFit(input: ReceiptItemCategorizationInput): Int {
        var length = buildHeader(input).length
        var count = 0
        for (index in 0 until minOf(input.lineItems.size, MAX_ITEMS_PER_CALL)) {
            val next = length + 1 + itemLine(input, index).length // "\n" + line
            if (next > MAX_PROMPT_CHARS) break
            length = next
            count++
        }
        return count
    }

    /**
     * How many markers "[sourceIndex=0]", "[sourceIndex=1]", ... are present in order. The
     * redactor only rewrites runs of 8 or more digit/space/./,/-/() characters that start and
     * end on a digit; the marker's index (at most two digits) sits between '=' and ']', neither
     * of which is in that set, so a marker is never rewritten and neighbouring numbers cannot
     * join it.
     */
    private fun leadingMarkersPresent(text: String, sentCount: Int): Int {
        var present = 0
        while (present < sentCount && text.contains("[sourceIndex=$present]")) present++
        return present
    }

    /** All [sentCount] markers present and the text clear of the redactor cap (not cut). */
    private fun promptIsComplete(text: String, sentCount: Int): Boolean =
        leadingMarkersPresent(text, sentCount) == sentCount &&
            text.length < ITEM_CATEGORIZATION_MAX_TEXT_CHARS

    private fun buildHeader(input: ReceiptItemCategorizationInput): String {
        // PRIV-43B-03: Raw values — CloudPayloadPolicy will redact if required; no hashCode pseudonyms
        val merchant = input.merchant.trim().ifBlank { "Unknown" }.take(MAX_PROMPT_MERCHANT_CHARS)
        val categoriesList = input.userCategories.joinToString(", ") { "${it.id}=${it.name}" }
        return listOf(
            "Categorize each receipt item. Reply with JSON only.",
            "Store: $merchant",
            "Categories (id=name): $categoriesList",
            INSTRUCTION_LINE,
            FORMAT_LINE,
            "Items:"
        ).joinToString("\n")
    }

    private fun itemLine(input: ReceiptItemCategorizationInput, index: Int): String {
        val item = input.lineItems[index]
        val description = item.description.ifBlank { UNKNOWN_ITEM }
        return "- [sourceIndex=$index] $description: ${CurrencyFormatter.format(item.totalPrice, input.currency)}"
    }

    /** The header followed by one line for each of the first [sentCount] items. */
    private fun buildPrompt(input: ReceiptItemCategorizationInput, sentCount: Int): String {
        val prompt = StringBuilder(buildHeader(input))
        for (index in 0 until sentCount) {
            prompt.append('\n').append(itemLine(input, index))
        }
        return prompt.toString()
    }

    private fun buildRequestBody(promptText: String): String {
        // The caller supplies only text prepared by CloudPayloadPolicy. Thinking is off so that
        // thought tokens do not use up the reply budget (same as the sibling Gemini services).
        return JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", promptText)))))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", AppConfig.Ai.CLOUD_RECEIPT_ITEM_MAX_OUTPUT_TOKENS)
                put("responseMimeType", "application/json")
                put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            })
        }.toString()
    }

    private fun parseResponse(
        body: String,
        correlationId: String,
        input: ReceiptItemCategorizationInput,
        sentCount: Int
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
            val indexedItems = mutableMapOf<Int, CategorizedReceiptItem>()

            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.getJSONObject(i)
                // Only the first sentCount items were sent; any other index is invalid.
                val sourceIndex = item.optStrictLongStrictOrNull("sourceIndex")
                    ?.takeIf { it in 0L until sentCount.toLong() }
                    ?.toInt()
                    ?: throw JSONException("Missing or invalid 'sourceIndex' in items[$i]")
                if (indexedItems.containsKey(sourceIndex)) {
                    throw JSONException("Duplicate 'sourceIndex' $sourceIndex in items[$i]")
                }
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

                indexedItems[sourceIndex] = CategorizedReceiptItem(
                    itemDescription = item.optString("description", "").ifBlank {
                        input.lineItems[sourceIndex].description.ifBlank { UNKNOWN_ITEM }
                    },
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
                )
            }

            val newCategories = mutableListOf<String>()
            result.optJSONArray("suggestedNewCategories")?.let { array ->
                for (i in 0 until array.length()) {
                    newCategories.add(array.getString(i))
                }
            }

            // Every SENT item must be answered; the unsent tail becomes placeholders.
            if (indexedItems.size != sentCount) return null
            val answeredItems = (0 until sentCount).map { index ->
                val lineItem = input.lineItems[index]
                val item = indexedItems[index] ?: return null
                item.copy(
                    amount = lineItem.totalPrice,
                    needsReview = item.needsReview || item.amount != lineItem.totalPrice ||
                        (lineItem.totalPrice < 0.0 && !lineItem.isAdjustment),
                    isAdjustment = lineItem.isAdjustment
                )
            }
            val placeholderItems = (sentCount until input.lineItems.size).map { index ->
                val lineItem = input.lineItems[index]
                CategorizedReceiptItem(
                    itemDescription = lineItem.description.ifBlank { UNKNOWN_ITEM },
                    amount = lineItem.totalPrice,
                    suggestedCategory = null,
                    confidence = 0f,
                    rationale = ReceiptItemPlaceholder.PLACEHOLDER_RATIONALE,
                    alternatives = emptyList(),
                    needsReview = true,
                    isAdjustment = lineItem.isAdjustment
                )
            }
            val sourceItems = answeredItems + placeholderItems
            // Tax stays local: allocated over ALL items (placeholders carry no category, so their
            // share is unallocated), never requested from the model.
            val taxAllocation = com.yourname.expensetracker.domain.ai.model.ReceiptTaxAllocator.allocate(
                input.totalTax, sourceItems, input.currency
            )
            val taxDistribution = (taxAllocation as? com.yourname.expensetracker.domain.ai.model.ReceiptTaxAllocation.Available)
                ?.categoryAmounts?.mapValues { it.value.amount }.orEmpty()

            // Placeholders are not AI results: the average covers the sent items only.
            val avgConfidence = if (answeredItems.isNotEmpty()) {
                answeredItems.map { it.confidence }.average().toFloat()
            } else 0f

            ReceiptItemCategorizationResult(
                items = sourceItems,
                totalConfidence = avgConfidence,
                needsReview = sourceItems.any { it.needsReview } || taxAllocation is com.yourname.expensetracker.domain.ai.model.ReceiptTaxAllocation.Unavailable,
                suggestedNewCategories = newCategories,
                taxDistribution = taxDistribution,
                taxAllocation = taxAllocation
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(
                "CloudReceiptItemCategorizationService: RESPONSE_INVALID class=%s (bodyLength=%d, bodyHash=%s, correlationId=%s)",
                e::class.java.simpleName,
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
        /** At most this many items go into one call (reply size grows with the sent count). */
        private const val MAX_ITEMS_PER_CALL = AppConfig.Ai.CLOUD_RECEIPT_ITEM_MAX_ITEMS_PER_CALL
        /** Kept clear below the redactor cap so redaction growth rarely needs a second pass. */
        private const val PROMPT_BUDGET_MARGIN_CHARS = 100
        private const val MAX_PROMPT_CHARS = ITEM_CATEGORIZATION_MAX_TEXT_CHARS - PROMPT_BUDGET_MARGIN_CHARS
        private const val MAX_PROMPT_MERCHANT_CHARS = 80
        private const val UNKNOWN_ITEM = "unknown item"
        // No digit run in the fixed text is 8 or more characters long, so the redactor's
        // phone rule cannot rewrite it (the old "0.7-0.89" range was one such run).
        private const val INSTRUCTION_LINE =
            "Use the exact sourceIndex of each item. Use a listed category (categoryId and exact " +
                "categoryName), or set isNewCategorySuggestion true with categoryId null and a new " +
                "categoryName. Give confidence from 0 to 1 (0.9 or more: clear match, 0.7 or more: " +
                "good, under 0.7: unsure, then add up to 2 alternatives). Keep each rationale brief."
        private const val FORMAT_LINE =
            """Format: {"items":[{"sourceIndex":0,"description":"","amount":0,"categoryId":""" +
                """1,"categoryName":"","confidence":0,"rationale":"","isNewCategorySuggestion":""" +
                """false,"alternatives":[{"categoryId":1,"categoryName":"","confidence":""" +
                """0}]}],"suggestedNewCategories":[]}"""
        private val JSON_FENCE_REGEX = Regex("""```(?:json)?\s*([\s\S]*?)\s*```""", RegexOption.IGNORE_CASE)
        private val FALLBACK_REDACTED_CATEGORY_REGEX = Regex("""cat_(\d+)""", RegexOption.IGNORE_CASE)
        private val EMAIL_REGEX = Regex("""\b[\w._%+-]+@[\w.-]+\.[A-Za-z]{2,}\b""")
        private val IBAN_REGEX = Regex("""\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b""")
        private val CARD_REGEX = Regex("""\b(?:\d[ -]?){13,19}\b""")
        private val PHONE_REGEX = Regex("""\+?\d[\d\s().-]{6,}\d""")
        private val LONG_NUMBER_REGEX = Regex("""\b\d{10,}\b""")
    }

}
