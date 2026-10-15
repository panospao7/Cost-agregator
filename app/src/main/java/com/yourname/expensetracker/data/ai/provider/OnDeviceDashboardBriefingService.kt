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
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceDashboardBriefingService @Inject constructor(
    private val promptFormatter: DashboardBriefingPromptFormatter
) : DashboardBriefingService {

    constructor() : this(DashboardBriefingPromptFormatter())

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
            Timber.w("OnDeviceDashboardBriefingService: GenAI error class=%s (code=%d)", e::class.java.simpleName, e.errorCode)
            AiServiceResult.Failure(AiServiceError.Unknown("GenAI error code=${e.errorCode}"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // R6b privacy: bounded code + class name only; never e.message or the throwable.
            Timber.w("OnDeviceDashboardBriefingService: UNKNOWN_ERROR class=%s", e::class.java.simpleName)
            AiServiceResult.Failure(AiServiceError.Unknown("UNKNOWN_ERROR"))
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
        return promptFormatter.buildPrompt(input)
    }

    internal fun parseResponse(text: String): DashboardBriefing? {
        return DashboardBriefingResponseParser.parseResponse(text)
    }
}
