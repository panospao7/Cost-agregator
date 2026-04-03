package com.yourname.expensetracker.data.ai.provider

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.DashboardBriefing
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.service.DashboardBriefingService
import com.yourname.expensetracker.domain.config.AppConfig
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceDashboardBriefingService @Inject constructor() : DashboardBriefingService {

    @Volatile
    private var cachedModel: GenerativeModel? = null

    private fun getOrCreateModel(): GenerativeModel {
        cachedModel?.let { return it }
        return synchronized(this) {
            cachedModel ?: Generation.getClient().also { cachedModel = it }
        }
    }

    override suspend fun generate(input: DashboardBriefingInput): AiServiceResult<DashboardBriefing> {
        return try {
            val model = getOrCreateModel()
            val request = buildRequest(input)
            val response = model.generateContent(request)
            val text = response.candidates.firstOrNull()?.text
                ?: return AiServiceResult.Failure(AiServiceError.ParseError("Empty response"))
            val parsed = parseResponse(text)
                ?: return AiServiceResult.Failure(AiServiceError.ParseError("No usable briefing in response"))
            AiServiceResult.Success(parsed)
        } catch (e: GenAiException) {
            Timber.w(e, "OnDeviceDashboardBriefingService: GenAI error (code=%d)", e.errorCode)
            AiServiceResult.Failure(AiServiceError.Unknown("GenAI error code=${e.errorCode}"))
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceDashboardBriefingService: unexpected error")
            AiServiceResult.Failure(AiServiceError.Unknown(e.message))
        }
    }

    private fun buildRequest(input: DashboardBriefingInput): GenerateContentRequest {
        val prompt = buildPrompt(input)
        val builder = GenerateContentRequest.builder(TextPart(prompt))
        builder.temperature = AppConfig.Ai.ON_DEVICE_BRIEFING_TEMPERATURE
        builder.maxOutputTokens = AppConfig.Ai.ON_DEVICE_BRIEFING_MAX_TOKENS
        return builder.build()
    }

    internal fun buildPrompt(input: DashboardBriefingInput): String {
        return buildString {
            appendLine("Write a short daily finance dashboard briefing.")
            appendLine("Be concise, practical, and advisory only.")
            appendLine("Do not invent facts. Return ONLY one JSON object.")
            appendLine()
            appendLine("Date: ${input.dateKey}")
            appendLine("Weather headline: ${input.weatherHeadline}")
            appendLine("Weather summary: ${input.weatherSummary}")
            appendLine("Discretionary budget: ${input.discretionaryBudget}")
            appendLine("Total committed: ${input.totalCommitted}")
            appendLine("Total likely: ${input.totalLikely}")
            appendLine("Pending reviews: ${input.pendingReviewCount}")
            appendLine("Current month spent: ${input.currentMonthSpent}")
            appendLine("Top categories: ${input.topCategories.joinToString(", ")}")
            appendLine("Budget warnings: ${input.budgetWarnings.joinToString(", ").ifBlank { "none" }}")
            appendLine("Upcoming items: ${input.upcomingItems.joinToString(", ").ifBlank { "none" }}")
            appendLine()
            appendLine("JSON schema: {\"title\":\"short title\",\"text\":\"brief message\",\"tone\":\"calm|neutral|cautious\",\"confidence\":0.0}")
        }
    }

    internal fun parseResponse(text: String): DashboardBriefing? {
        val jsonText = extractFirstJsonObject(text.trim()) ?: return null
        return try {
            val root = JSONObject(jsonText)
            val title = root.optString("title").trim().take(60)
            val body = root.optString("text").trim().take(AppConfig.Ai.MAX_BRIEFING_LENGTH_CHARS)
            if (title.isBlank() || body.isBlank()) return null

            DashboardBriefing(
                title = title,
                text = body,
                tone = root.optString("tone").trim().ifBlank { "neutral" },
                confidence = if (root.has("confidence") && !root.isNull("confidence")) root.optDouble("confidence").toFloat() else null
            )
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceDashboardBriefingService: JSON parse failure")
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
