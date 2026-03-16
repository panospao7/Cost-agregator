package com.yourname.expensetracker.data.ai.provider

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.SuggestedValue
import com.yourname.expensetracker.domain.ai.service.ReceiptAssistService
import com.yourname.expensetracker.domain.config.AppConfig
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceReceiptAssistService @Inject constructor() : ReceiptAssistService {

    @Volatile
    private var cachedModel: GenerativeModel? = null

    private fun getOrCreateModel(): GenerativeModel {
        cachedModel?.let { return it }
        return synchronized(this) {
            cachedModel ?: Generation.getClient().also { cachedModel = it }
        }
    }

    override suspend fun suggest(input: ReceiptAssistInput): ReceiptAssistSuggestion? {
        return try {
            val model = getOrCreateModel()
            val request = buildRequest(input)
            val response = model.generateContent(request)
            val text = response.candidates.firstOrNull()?.text ?: return null
            parseResponse(text)
        } catch (e: GenAiException) {
            Timber.w(e, "OnDeviceReceiptAssistService: GenAI error (code=%d)", e.errorCode)
            null
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceReceiptAssistService: unexpected error")
            null
        }
    }

    private fun buildRequest(input: ReceiptAssistInput): GenerateContentRequest {
        val prompt = buildPrompt(input)
        val builder = GenerateContentRequest.builder(TextPart(prompt))
        builder.temperature = AppConfig.Ai.ON_DEVICE_RECEIPT_TEMPERATURE
        builder.maxOutputTokens = AppConfig.Ai.ON_DEVICE_RECEIPT_MAX_TOKENS
        return builder.build()
    }

    internal fun buildPrompt(input: ReceiptAssistInput): String {
        return buildString {
            appendLine("Recover missing receipt fields from OCR text.")
            appendLine("Be conservative. Prefer null over guessing.")
            appendLine("Return ONLY one JSON object.")
            appendLine()
            appendLine("Currency: ${input.currency}")
            appendLine("Parsed merchant: ${input.parsedMerchant ?: "none"}")
            appendLine("Parsed total: ${input.parsedTotal?.toString() ?: "none"}")
            appendLine("Parsed date: ${input.parsedDate?.toString() ?: "none"}")
            appendLine("Parsed tax: ${input.parsedTaxAmount?.toString() ?: "none"}")
            appendLine("Line items: ${input.lineItemsJson ?: "none"}")
            appendLine("Current time ms: ${input.currentTimeMs}")
            appendLine()
            appendLine("OCR text:")
            appendLine(input.rawOcrText)
            appendLine()
            appendLine(
                "JSON schema: {" +
                    "\"merchant\":{\"value\":\"string\",\"confidence\":0.0,\"rationale\":\"string\"}|null," +
                    "\"total\":{\"value\":0.0,\"confidence\":0.0,\"rationale\":\"string\"}|null," +
                    "\"date\":{\"value\":0,\"confidence\":0.0,\"rationale\":\"string\"}|null," +
                    "\"taxAmount\":{\"value\":0.0,\"confidence\":0.0,\"rationale\":\"string\"}|null," +
                    "\"notes\":[\"short note\"]}"
            )
        }
    }

    internal fun parseResponse(text: String): ReceiptAssistSuggestion? {
        val jsonText = extractFirstJsonObject(text.trim()) ?: return null
        return try {
            val suggestion = JSONObject(jsonText)
            ReceiptAssistSuggestion(
                merchant = suggestion.optJSONObject("merchant")?.toSuggestedStringOrNull(),
                total = suggestion.optJSONObject("total")?.toSuggestedDoubleOrNull(),
                date = suggestion.optJSONObject("date")?.toSuggestedLongOrNull(),
                taxAmount = suggestion.optJSONObject("taxAmount")?.toSuggestedDoubleOrNull(),
                notes = suggestion.optJSONArray("notes").toStringList()
            )
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceReceiptAssistService: JSON parse failure")
            null
        }
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun JSONObject.toSuggestedStringOrNull(): SuggestedValue<String>? {
        val value = optString("value").trim()
        if (value.isBlank()) return null
        return SuggestedValue(
            value = value,
            confidence = optDoubleOrNull("confidence")?.toFloat(),
            rationale = optString("rationale").trim().ifBlank { null }
        )
    }

    private fun JSONObject.toSuggestedDoubleOrNull(): SuggestedValue<Double>? {
        if (!has("value") || isNull("value")) return null
        return SuggestedValue(
            value = optDouble("value"),
            confidence = optDoubleOrNull("confidence")?.toFloat(),
            rationale = optString("rationale").trim().ifBlank { null }
        )
    }

    private fun JSONObject.toSuggestedLongOrNull(): SuggestedValue<Long>? {
        if (!has("value") || isNull("value")) return null
        return SuggestedValue(
            value = optLong("value"),
            confidence = optDoubleOrNull("confidence")?.toFloat(),
            rationale = optString("rationale").trim().ifBlank { null }
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}
