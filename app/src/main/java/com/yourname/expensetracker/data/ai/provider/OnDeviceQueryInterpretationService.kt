package com.yourname.expensetracker.data.ai.provider

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.yourname.expensetracker.domain.ai.model.AnswerMode
import com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.QueryComparison
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.model.QueryOwnershipScope
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.PeriodRange
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
        val visibleCategoryLookupKeys = visibleCategoryLookupKeys(input)
        val visibleMerchantLookupKeys = visibleMerchantLookupKeys(input)

        return buildString {
            appendLine("Interpret a finance assistant query into a bounded structured result.")
            appendLine("Use only supported enums and return ONLY one JSON object.")
            appendLine("If ambiguous, return a clarification. If unsupported, return unsupported.")
            appendLine()
            appendLine("Raw query: ${input.rawQuery}")
            appendLine("Locale: ${input.localeTag}")
            appendLine("Known categories: ${input.categoryNames.joinToString(", ")}")
            appendLine("Known merchants: ${input.merchantNames.take(20).joinToString(", ")}")
            appendLine("Known category lookup keys: ${visibleCategoryLookupKeys.joinToString(", ")}")
            appendLine("Known merchant lookup keys: ${visibleMerchantLookupKeys.joinToString(", ")}")
            appendLine("Conversation history:")
            appendLine(history)
            appendLine()
            appendLine("JSON schema:")
            appendLine("""{"kind":"structured|clarification|unsupported","intent":{"metric":"LIST|TOTAL|COUNT|AVERAGE|MAX|MIN","grouping":"NONE|CATEGORY|MERCHANT|DAY|WEEK|MONTH","comparison":"NONE|PREVIOUS_EQUIVALENT_PERIOD","answerMode":"INLINE_ANSWER|NAVIGATE|BOTH","ownership":"ALL|MINE|NOT_MINE|SHARED|TRANSFER","categoryNames":["name-or-alias"],"merchantNames":["name-or-alias"],"transactionTypes":["PURCHASE|WITHDRAWAL|TRANSFER|DEPOSIT"],"periodKeyword":"TODAY|THIS_WEEK|THIS_MONTH|LAST_MONTH|THIS_QUARTER|THIS_YEAR|null","period":{"startMs":0,"endMs":0}},"clarification":{"prompt":"text","options":["a","b"]},"unsupportedReason":"text"}""")
            appendLine()
            appendLine("Rules:")
            appendLine("- categoryNames and merchantNames MUST be arrays, even for a single value.")
            appendLine("- transactionTypes MUST be an array of valid types.")
            appendLine("- Resolve categories and merchants only from the known lookup keys above.")
            appendLine("- periodKeyword should be one of the listed keywords or null if not specified.")
            appendLine("- If a concrete date range is explicit, include period.startMs and period.endMs.")
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

        // --- Category resolution: names/aliases → IDs ---
        val rawCategoryNames = intent.optJSONArray("categoryNames").toStringSet()
        val resolvedCategoryIds = mutableSetOf<Long>()
        val resolvedMerchants = mutableSetOf<String>()

        for (catName in rawCategoryNames) {
            val directId = resolveCategoryId(catName, input)
            if (directId != null) {
                resolvedCategoryIds.add(directId)
            }
        }

        // --- Merchant resolution: aliases → real names ---
        val rawMerchantNames = intent.optJSONArray("merchantNames").toStringSet()
        for (merchant in rawMerchantNames) {
            resolveMerchantName(merchant, input)?.let(resolvedMerchants::add)
        }

        // --- Transaction type parsing ---
        val rawTransactionTypes = intent.optJSONArray("transactionTypes").toStringSet()
        val resolvedTypes = rawTransactionTypes.mapNotNull { typeName ->
            enumValues<DomainTransactionType>().firstOrNull {
                it.name.equals(typeName, ignoreCase = true)
            }
        }.toSet()

        // --- Period resolution ---
        val resolvedPeriod = resolvePeriod(intent, input.currentTimeMs)

        // Validate and clamp AI-returned amount bounds
        val rawMinAmount = intent.optDoubleOrNull("minAmount")
        val rawMaxAmount = intent.optDoubleOrNull("maxAmount")
        val validatedMinAmount = validateAmountBound(rawMinAmount, "minAmount")
        val validatedMaxAmount = validateAmountBound(rawMaxAmount, "maxAmount")

        return FinancialQueryInterpretationResult.Structured(
            intent = FinancialQueryIntent(
                rawQuery = input.rawQuery,
                normalizedQuery = normalizedQuery,
                filters = ExpenseQueryFilters(
                    period = resolvedPeriod,
                    merchants = resolvedMerchants,
                    categoryIds = resolvedCategoryIds,
                    transactionTypes = resolvedTypes,
                    ownership = intent.optString("ownership").toEnumOrDefault(QueryOwnershipScope.ALL),
                    minAmount = validatedMinAmount,
                    maxAmount = validatedMaxAmount
                ),
                metric = intent.optString("metric").toEnumOrDefault(QueryMetric.TOTAL),
                grouping = intent.optString("grouping").toEnumOrDefault(QueryGrouping.NONE),
                comparison = intent.optString("comparison").toEnumOrDefault(QueryComparison.NONE),
                answerMode = intent.optString("answerMode").toEnumOrDefault(AnswerMode.BOTH)
            )
        )
    }

    private fun resolveCategoryId(
        rawValue: String,
        input: FinancialQueryInterpretationInput
    ): Long? {
        val trimmed = rawValue.trim()
        if (trimmed.isBlank()) return null

        input.categoryLookupMap[trimmed]?.let { return it }
        input.categoryNameToIdMap[trimmed]?.let { return it }
        input.categoryAliasMap[trimmed]?.let { canonical ->
            input.categoryLookupMap[canonical]?.let { return it }
            input.categoryNameToIdMap[canonical]?.let { return it }
        }

        val lookupMatch = input.categoryLookupMap.entries.firstOrNull { (key, _) ->
            key.equals(trimmed, ignoreCase = true)
        }
        if (lookupMatch != null) return lookupMatch.value

        val aliasMatch = input.categoryAliasMap.entries.firstOrNull { (alias, canonical) ->
            alias.equals(trimmed, ignoreCase = true) || canonical.equals(trimmed, ignoreCase = true)
        }
        return aliasMatch?.value?.let { canonical ->
            input.categoryLookupMap[canonical] ?: input.categoryNameToIdMap[canonical]
        }
    }

    private fun resolveMerchantName(
        rawValue: String,
        input: FinancialQueryInterpretationInput
    ): String? {
        val trimmed = rawValue.trim()
        if (trimmed.isBlank()) return null

        input.merchantLookupMap[trimmed]?.let { return it }
        input.merchantAliasMap[trimmed]?.let { return it }

        input.merchantLookupMap.entries.firstOrNull { (key, _) ->
            key.equals(trimmed, ignoreCase = true)
        }?.let { return it.value }

        input.merchantAliasMap.entries.firstOrNull { (key, _) ->
            key.equals(trimmed, ignoreCase = true)
        }?.let { return it.value }

        return trimmed
    }

    private fun visibleCategoryLookupKeys(input: FinancialQueryInterpretationInput): List<String> {
        return if (input.categoryAliasMap.isNotEmpty()) {
            input.categoryAliasMap.keys.sorted()
        } else {
            input.categoryLookupMap.keys.sorted()
        }
    }

    private fun visibleMerchantLookupKeys(input: FinancialQueryInterpretationInput): List<String> {
        return if (input.merchantAliasMap.isNotEmpty()) {
            input.merchantAliasMap.keys.sorted().take(20)
        } else {
            input.merchantLookupMap.keys.sorted().take(20)
        }
    }

    private fun resolvePeriod(intent: JSONObject, nowMs: Long): PeriodRange? {
        intent.optJSONObject("period")?.let { periodObject ->
            val startMs = periodObject.optLongOrNull("startMs")
            val endMs = periodObject.optLongOrNull("endMs")
            if (startMs != null && endMs != null && endMs > startMs) {
                return PeriodRange(startMs, endMs)
            }
        }

        val periodKeyword = intent.optString("periodKeyword")
            .trim()
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: intent.optString("period")
                .trim()
                .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

        return periodKeyword?.let { resolvePeriodFromKeyword(it, nowMs) }
    }

    /**
     * Resolves a period keyword emitted by the on-device model into a concrete [PeriodRange].
     */
    private fun resolvePeriodFromKeyword(keyword: String, nowMs: Long): PeriodRange? {
        return when (keyword.uppercase(Locale.ROOT)) {
            "TODAY" -> {
                val dayStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(nowMs)
                PeriodRange(dayStart, nowMs)
            }
            "THIS_WEEK" -> {
                val (start, end) = com.yourname.expensetracker.domain.util.TimePeriodUtils.getWeekRange(nowMs, 0)
                PeriodRange(start, end)
            }
            "THIS_MONTH" -> {
                val (start, end) = com.yourname.expensetracker.domain.util.TimePeriodUtils.getMonthRange(nowMs, 0)
                PeriodRange(start, end)
            }
            "LAST_MONTH" -> {
                val (start, end) = com.yourname.expensetracker.domain.util.TimePeriodUtils.getMonthRange(nowMs, -1)
                PeriodRange(start, end)
            }
            "THIS_QUARTER" -> {
                val start = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfQuarter(nowMs)
                PeriodRange(start, nowMs)
            }
            "THIS_YEAR" -> {
                val start = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfYear(nowMs)
                PeriodRange(start, nowMs)
            }
            else -> null
        }
    }

    /**
     * Validates and clamps an amount bound returned by the AI provider.
     * Rejects negative values, absurdly large values (> 1 billion), and
     * NaN/Infinity to prevent downstream errors.
     */
    private fun validateAmountBound(value: Double?, fieldName: String): Double? {
        if (value == null) return null
        if (value.isNaN() || value.isInfinite()) {
            Timber.w("OnDeviceQueryInterpretationService: AI returned invalid %s=%s — rejecting", fieldName, value)
            return null
        }
        if (value < 0.0) {
            Timber.w("OnDeviceQueryInterpretationService: AI returned negative %s=%.2f — clamping to 0", fieldName, value)
            return 0.0
        }
        val MAX_AMOUNT = 1_000_000_000.0
        if (value > MAX_AMOUNT) {
            Timber.w("OnDeviceQueryInterpretationService: AI returned excessive %s=%.2f — clamping to %.0f", fieldName, value, MAX_AMOUNT)
            return MAX_AMOUNT
        }
        return value
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

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private inline fun <reified T : Enum<T>> String.toEnumOrDefault(default: T): T {
        val value = trim()
        if (value.isBlank()) return default
        return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default
    }
}
