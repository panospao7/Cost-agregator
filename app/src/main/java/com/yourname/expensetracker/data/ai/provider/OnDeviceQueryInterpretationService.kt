package com.yourname.expensetracker.data.ai.provider

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.yourname.expensetracker.domain.ai.model.AiChatMessage
import com.yourname.expensetracker.domain.ai.model.AnswerMode
import com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.QueryComparison
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.model.QueryOwnershipScope
import com.yourname.expensetracker.domain.ai.service.QueryInterpretationService
import com.yourname.expensetracker.domain.config.AppConfig
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceQueryInterpretationService @Inject constructor() : QueryInterpretationService {

    @Volatile
    private var cachedModel: GenerativeModel? = null

    private fun getOrCreateModel(): GenerativeModel {
        cachedModel?.let { return it }
        return synchronized(this) {
            cachedModel ?: Generation.getClient().also { cachedModel = it }
        }
    }

    override suspend fun interpret(
        input: FinancialQueryInterpretationInput
    ): FinancialQueryInterpretationResult {
        return try {
            val model = getOrCreateModel()
            val request = buildRequest(input)
            val response = withTimeoutOrNull(AppConfig.Ai.ON_DEVICE_QUERY_TIMEOUT_MS) {
                model.generateContent(request)
            } ?: run {
                Timber.w("OnDeviceQueryInterpretationService: timeout after ${AppConfig.Ai.ON_DEVICE_QUERY_TIMEOUT_MS}ms")
                return unsupported("Query interpretation timed out")
            }
            val text = response.candidates.firstOrNull()?.text ?: return unsupported()
            parseResponse(input, text) ?: unsupported()
        } catch (e: GenAiException) {
            Timber.w(e, "OnDeviceQueryInterpretationService: GenAI error (code=%d)", e.errorCode)
            unsupported()
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceQueryInterpretationService: unexpected error")
            unsupported()
        }
    }

    private fun buildRequest(input: FinancialQueryInterpretationInput): GenerateContentRequest {
        val prompt = buildPrompt(input)
        val builder = GenerateContentRequest.builder(TextPart(prompt))
        builder.temperature = AppConfig.Ai.ON_DEVICE_QUERY_TEMPERATURE
        builder.maxOutputTokens = AppConfig.Ai.ON_DEVICE_QUERY_MAX_TOKENS
        return builder.build()
    }

    internal fun buildPrompt(input: FinancialQueryInterpretationInput): String {
        val history = input.conversationHistory.takeLast(8).joinToString("\n") { message ->
            "- ${message.role.name}: ${message.text}"
        }.ifBlank { "none" }

        return buildString {
            appendLine("Interpret a finance assistant query into a bounded structured result.")
            appendLine("Use only supported enums and return ONLY one JSON object.")
            appendLine("If ambiguous, return a clarification. If unsupported, return unsupported.")
            appendLine()
            appendLine("Raw query: ${input.rawQuery}")
            appendLine("Locale: ${input.localeTag}")
            appendLine("Known categories: ${input.categoryNames.joinToString(", ")}")
            appendLine("Known merchants: ${input.merchantNames.take(20).joinToString(", ")}")
            appendLine("Conversation history:")
            appendLine(history)
            appendLine()
            appendLine("JSON schema:")
            appendLine("{\"kind\":\"structured|clarification|unsupported\",\"intent\":{\"metric\":\"LIST|TOTAL|COUNT|AVERAGE|MAX|MIN\",\"grouping\":\"NONE|CATEGORY|MERCHANT|DAY|WEEK|MONTH\",\"comparison\":\"NONE|PREVIOUS_EQUIVALENT_PERIOD\",\"answerMode\":\"INLINE_ANSWER|NAVIGATE|BOTH\",\"ownership\":\"ALL|MINE|NOT_MINE|SHARED|TRANSFER\",\"categoryNames\":[\"name\"],\"merchantNames\":[\"name\"],\"minAmount\":0.0,\"maxAmount\":0.0},\"clarification\":{\"prompt\":\"text\",\"options\":[\"a\",\"b\"]},\"unsupportedReason\":\"text\"}")
        }
    }

    internal fun parseResponse(
        input: FinancialQueryInterpretationInput,
        text: String
    ): FinancialQueryInterpretationResult? {
        val jsonText = extractFirstJsonObject(text.trim()) ?: return null
        return try {
            val root = JSONObject(jsonText)
            when (root.optString("kind").trim().lowercase(Locale.ROOT)) {
                "structured" -> parseStructured(input, root.optJSONObject("intent") ?: return null)
                "clarification" -> parseClarification(root.optJSONObject("clarification") ?: return null)
                "unsupported" -> FinancialQueryInterpretationResult.Unsupported(
                    root.optString("unsupportedReason").trim().ifBlank { "Query interpretation unavailable" }
                )
                else -> null
            }
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceQueryInterpretationService: JSON parse failure")
            null
        }
    }

    private fun parseStructured(
        input: FinancialQueryInterpretationInput,
        intent: JSONObject
    ): FinancialQueryInterpretationResult.Structured {
        val normalizedQuery = input.rawQuery.trim().lowercase(Locale.getDefault())
        return FinancialQueryInterpretationResult.Structured(
            intent = FinancialQueryIntent(
                rawQuery = input.rawQuery,
                normalizedQuery = normalizedQuery,
                filters = ExpenseQueryFilters(
                    merchants = intent.optJSONArray("merchantNames").toStringSet(),
                    ownership = intent.optString("ownership").toEnumOrDefault(QueryOwnershipScope.ALL),
                    minAmount = intent.optDoubleOrNull("minAmount"),
                    maxAmount = intent.optDoubleOrNull("maxAmount")
                ),
                metric = intent.optString("metric").toEnumOrDefault(QueryMetric.TOTAL),
                grouping = intent.optString("grouping").toEnumOrDefault(QueryGrouping.NONE),
                comparison = intent.optString("comparison").toEnumOrDefault(QueryComparison.NONE),
                answerMode = intent.optString("answerMode").toEnumOrDefault(AnswerMode.BOTH)
            )
        )
    }

    private fun parseClarification(clarification: JSONObject): FinancialQueryInterpretationResult.Clarification {
        return FinancialQueryInterpretationResult.Clarification(
            prompt = clarification.optString("prompt").trim().ifBlank { "Can you clarify what you want to see?" },
            options = clarification.optJSONArray("options").toStringList()
        )
    }

    private fun unsupported(reason: String = "Query interpretation provider unavailable") = 
        FinancialQueryInterpretationResult.Unsupported(reason)

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONArray?.toStringSet(): Set<String> = toStringList().toSet()

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private inline fun <reified T : Enum<T>> String.toEnumOrDefault(default: T): T {
        val value = trim()
        if (value.isBlank()) return default
        return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default
    }
}
