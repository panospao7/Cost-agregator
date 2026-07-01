package com.yourname.expensetracker.data.ai.provider

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.service.CategorizationAssistService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.CancellationSafe
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device categorization assist using ML Kit GenAI Prompt API (Gemini Nano).
 *
 * Runs entirely on-device — no network, no data leaves the phone.
 * Advisory only: suggests a category; deterministic logic remains authoritative.
 */
@Singleton
class OnDeviceCategorizationAssistService @Inject constructor() : CategorizationAssistService {

    @Volatile
    private var cachedModel: GenerativeModel? = null

    private fun getOrCreateModel(): GenerativeModel {
        cachedModel?.let { return it }
        return synchronized(this) {
            cachedModel ?: Generation.getClient().also { cachedModel = it }
        }
    }

    override suspend fun suggest(input: CategorizationAssistInput): CategoryAssistSuggestion? {
        return try {
            val model = getOrCreateModel()
            val request = buildRequest(input)
            val response = model.generateContent(request)

            val text = response.candidates
                .firstOrNull()
                ?.text
                ?: return null

            parseResponse(text)
        } catch (e: GenAiException) {
            Timber.w(
                e,
                "OnDeviceCategorizationAssistService: GenAI error (code=%d)",
                e.errorCode
            )
            null
        } catch (e: Exception) {
            CancellationSafe.rethrowIfCancellation(e)
            Timber.w(e, "OnDeviceCategorizationAssistService: unexpected error")
            null
        }
    }

    /**
     * Build a [GenerateContentRequest] with a categorization prompt.
     *
     * The prompt is intentionally simpler than the cloud version because
     * Gemini Nano has a smaller context window and no JSON-mode guarantee.
     * We keep temperature low to maximise determinism.
     */
    private fun buildRequest(input: CategorizationAssistInput): GenerateContentRequest {
        val prompt = buildPrompt(input)
        val builder = GenerateContentRequest.builder(TextPart(prompt))
        builder.temperature = AppConfig.Ai.ON_DEVICE_CATEGORIZATION_TEMPERATURE
        builder.maxOutputTokens = AppConfig.Ai.ON_DEVICE_CATEGORIZATION_MAX_TOKENS
        return builder.build()
    }

    /**
     * Build a concise categorization prompt suitable for Gemini Nano's
     * smaller context and less reliable JSON adherence.
     */
    internal fun buildPrompt(input: CategorizationAssistInput): String {
        val categories = input.candidateCategories.joinToString(", ") { "${it.id}:${it.name}" }
        val merchantContext = buildMerchantContext(input)
        return buildString {
            appendLine("Categorize this transaction. Pick ONLY from allowed categories.")
            appendLine("Use common sense to identify merchants from abbreviations/OCR errors.")
            appendLine("Examples: amzn/amazon → Shopping, goog/google → Services, netflix → Entertainment")
            appendLine()
            appendLine("Merchant: ${input.merchant}")
            appendLine("Amount: ${input.amount?.toString() ?: "none"} ${input.currency}")
            appendLine("Type: ${input.transactionType}")
            if (input.supportingText != null) {
                appendLine("Context: ${input.supportingText}")
            }
            if (merchantContext.isNotBlank()) {
                appendLine("Known history: $merchantContext")
            }
            appendLine()
            appendLine("Allowed categories: $categories")
            appendLine()
            appendLine("JSON: {\"categoryId\":0,\"categoryName\":\"string\",\"confidence\":0.0,\"rationale\":\"string\"}")
        }
    }
    
    private fun buildMerchantContext(input: CategorizationAssistInput): String {
        return input.recentTransactionsWithSameMerchant
            .take(5)
            .joinToString("; ") { "${it.merchant} → ${it.categoryName}" }
    }

    /**
     * Parse the model response.  Gemini Nano may produce markdown fences
     * or leading text before the JSON — we extract the first `{…}` block.
     */
    internal fun parseResponse(text: String): CategoryAssistSuggestion? {
        val jsonText = extractFirstJsonObject(text.trim()) ?: return null
        return try {
            val obj = JSONObject(jsonText)
            val categoryId = StrictAiJsonParsing.run { obj.positiveIdOrNull("categoryId") } ?: return null
            val categoryName = obj.optString("categoryName").trim().takeIf { it.isNotBlank() } ?: return null
            val confidence = if (obj.has("confidence") && !obj.isNull("confidence")) {
                StrictAiJsonParsing.run { obj.boundedConfidenceOrNull("confidence") } ?: return null
            } else {
                null
            }

            CategoryAssistSuggestion(
                categoryId = categoryId,
                categoryName = categoryName,
                confidence = confidence,
                rationale = obj.optString("rationale").trim().ifBlank { null },
                alternativeCategoryIds = StrictAiJsonParsing.run {
                    obj.optJSONArray("alternativeCategoryIds").positiveLongs()
                }
            )
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceCategorizationAssistService: JSON parse failure")
            null
        }
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }

}
