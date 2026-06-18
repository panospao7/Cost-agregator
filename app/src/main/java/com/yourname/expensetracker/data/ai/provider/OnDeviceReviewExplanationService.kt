package com.yourname.expensetracker.data.ai.provider

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.ReviewExplanation
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.ai.service.ReviewExplanationService
import com.yourname.expensetracker.domain.config.AppConfig
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceReviewExplanationService @Inject constructor() : ReviewExplanationService {

    @Volatile
    private var cachedModel: GenerativeModel? = null

    private fun getOrCreateModel(): GenerativeModel {
        cachedModel?.let { return it }
        return synchronized(this) {
            cachedModel ?: Generation.getClient().also { cachedModel = it }
        }
    }

    override suspend fun generate(input: ReviewExplanationInput): AiServiceResult<ReviewExplanation> {
        return try {
            val model = getOrCreateModel()
            val request = buildRequest(input)
            val response = model.generateContent(request)
            val text = response.candidates.firstOrNull()?.text
                ?: return AiServiceResult.Failure(AiServiceError.ParseError("Empty response"))
            val parsed = parseResponse(text)
                ?: return AiServiceResult.Failure(AiServiceError.ParseError("No usable explanation in response"))
            AiServiceResult.Success(parsed)
        } catch (e: GenAiException) {
            Timber.w(e, "OnDeviceReviewExplanationService: GenAI error (code=%d)", e.errorCode)
            AiServiceResult.Failure(AiServiceError.Unknown("GenAI error code=${e.errorCode}"))
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceReviewExplanationService: unexpected error")
            AiServiceResult.Failure(AiServiceError.Unknown(e.message))
        }
    }

    private fun buildRequest(input: ReviewExplanationInput): GenerateContentRequest {
        val prompt = buildPrompt(input)
        val builder = GenerateContentRequest.builder(TextPart(prompt))
        builder.temperature = AppConfig.Ai.ON_DEVICE_REVIEW_TEMPERATURE
        builder.maxOutputTokens = AppConfig.Ai.ON_DEVICE_REVIEW_MAX_TOKENS
        return builder.build()
    }

    internal fun buildPrompt(input: ReviewExplanationInput): String {
        return buildString {
            appendLine("Explain briefly why this expense is in review.")
            appendLine("Be cautious and non-authoritative.")
            appendLine("Use only the given facts.")
            appendLine("Return ONLY one JSON object.")
            appendLine()
            appendLine("Merchant: ${input.merchant}")
            appendLine("Amount: ${input.amount} ${input.currency}")
            appendLine("Suggested type: ${input.suggestedType}")
            appendLine("Confidence: ${input.confidence}")
            appendLine("Match type: ${input.matchType ?: "unknown"}")
            appendLine("Deterministic explanation: ${input.explanation ?: "none"}")
            appendLine("Package: ${input.packageName}")
            appendLine("Notification title: ${input.notificationTitle ?: "none"}")
            appendLine("Notification text: ${input.notificationText ?: "none"}")
            appendLine()
            appendLine("JSON schema: {\"headline\":\"short title\",\"body\":\"short explanation\",\"caution\":\"optional text or null\"}")
        }
    }

    internal fun parseResponse(text: String): ReviewExplanation? {
        val jsonText = extractFirstJsonObject(text.trim()) ?: return null
        return try {
            val explanationJson = JSONObject(jsonText)
            val headline = explanationJson.optString("headline").trim()
                .take(AppConfig.Ai.MAX_REVIEW_EXPLANATION_HEADLINE_CHARS)
            val bodyText = explanationJson.optString("body").trim()
                .take(AppConfig.Ai.MAX_REVIEW_EXPLANATION_BODY_CHARS)
            if (headline.isBlank() || bodyText.isBlank()) return null

            ReviewExplanation(
                headline = headline,
                body = bodyText,
                caution = explanationJson.optString("caution").trim()
                    .ifBlank { null }
                    ?.take(AppConfig.Ai.MAX_REVIEW_EXPLANATION_CAUTION_CHARS)
            )
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceReviewExplanationService: JSON parse failure")
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
