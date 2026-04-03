package com.yourname.expensetracker.data.ai.provider

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion
import com.yourname.expensetracker.domain.ai.model.DuplicateVerdict
import com.yourname.expensetracker.domain.ai.service.DedupeJudgeService
import com.yourname.expensetracker.domain.config.AppConfig
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceDedupeJudgeService @Inject constructor() : DedupeJudgeService {

    @Volatile
    private var cachedModel: GenerativeModel? = null

    private fun getOrCreateModel(): GenerativeModel {
        cachedModel?.let { return it }
        return synchronized(this) {
            cachedModel ?: Generation.getClient().also { cachedModel = it }
        }
    }

    override suspend fun judge(input: DedupeJudgeInput): AiServiceResult<DedupeJudgeSuggestion> {
        return try {
            val model = getOrCreateModel()
            val request = buildRequest(input)
            val response = model.generateContent(request)
            val text = response.candidates.firstOrNull()?.text
                ?: return AiServiceResult.Failure(AiServiceError.ParseError("Empty response"))
            val parsed = parseResponse(text)
                ?: return AiServiceResult.Failure(AiServiceError.ParseError("No usable dedupe verdict in response"))
            AiServiceResult.Success(parsed)
        } catch (e: GenAiException) {
            Timber.w(e, "OnDeviceDedupeJudgeService: GenAI error (code=%d)", e.errorCode)
            AiServiceResult.Failure(AiServiceError.Unknown("GenAI error code=${e.errorCode}"))
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceDedupeJudgeService: unexpected error")
            AiServiceResult.Failure(AiServiceError.Unknown(e.message))
        }
    }

    private fun buildRequest(input: DedupeJudgeInput): GenerateContentRequest {
        val prompt = buildPrompt(input)
        val builder = GenerateContentRequest.builder(TextPart(prompt))
        builder.temperature = AppConfig.Ai.ON_DEVICE_DEDUPE_TEMPERATURE
        builder.maxOutputTokens = AppConfig.Ai.ON_DEVICE_DEDUPE_MAX_TOKENS
        return builder.build()
    }

    internal fun buildPrompt(input: DedupeJudgeInput): String {
        val candidates = input.candidates.joinToString("\n") { candidate ->
            "- targetType=${candidate.targetType}, targetId=${candidate.targetId}, merchant=${candidate.merchant}, amount=${candidate.amount} ${candidate.currency}, date=${candidate.date}, source=${candidate.sourceLabel}, preview=${candidate.textPreview ?: "none"}"
        }

        return buildString {
            appendLine("Judge whether this pending review is likely a duplicate of one bounded candidate set.")
            appendLine("Stay conservative and prefer UNCERTAIN when evidence is weak.")
            appendLine("Return ONLY one JSON object.")
            appendLine()
            appendLine("JSON schema: {\"verdict\":\"LIKELY_DUPLICATE|LIKELY_DISTINCT|UNCERTAIN\",\"matchedTargetType\":\"PENDING_REVIEW|EXPENSE|null\",\"matchedTargetId\":0,\"confidence\":0.0,\"rationale\":\"short explanation\"}")
            appendLine()
            appendLine("Subject:")
            appendLine("- targetType=${input.subject.targetType}")
            appendLine("- targetId=${input.subject.targetId}")
            appendLine("- merchant=${input.subject.merchant}")
            appendLine("- amount=${input.subject.amount} ${input.subject.currency}")
            appendLine("- date=${input.subject.date}")
            appendLine("- source=${input.subject.sourceLabel}")
            appendLine("- preview=${input.subject.textPreview ?: "none"}")
            appendLine()
            appendLine("Candidates:")
            appendLine(candidates)
        }
    }

    internal fun parseResponse(text: String): DedupeJudgeSuggestion? {
        val jsonText = extractFirstJsonObject(text.trim()) ?: return null
        return try {
            val suggestion = JSONObject(jsonText)
            DedupeJudgeSuggestion(
                verdict = DuplicateVerdict.valueOf(suggestion.optString("verdict")),
                matchedTargetType = suggestion.optString("matchedTargetType").trim()
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let { AiTargetType.valueOf(it) },
                matchedTargetId = if (suggestion.has("matchedTargetId") && !suggestion.isNull("matchedTargetId")) suggestion.optLong("matchedTargetId") else null,
                confidence = if (suggestion.has("confidence") && !suggestion.isNull("confidence")) suggestion.optDouble("confidence").toFloat() else null,
                rationale = suggestion.optString("rationale").trim().ifBlank { null }
            )
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceDedupeJudgeService: JSON parse failure")
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
