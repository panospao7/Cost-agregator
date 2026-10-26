package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.ai.model.AiChatMessage
import com.yourname.expensetracker.domain.ai.model.AssistantUnsupportedReason
import com.yourname.expensetracker.domain.ai.model.ClarificationChoice
import com.yourname.expensetracker.domain.ai.model.ClarificationCompletion
import com.yourname.expensetracker.domain.ai.model.ClarificationSlot
import com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.PresentationChoice
import com.yourname.expensetracker.domain.ai.model.QueryComparison
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.model.QueryOwnershipScope
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.QueryInterpretationService
import com.yourname.expensetracker.domain.model.PeriodRange
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class InterpretFinancialQueryUseCase @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val queryInterpretationService: QueryInterpretationService,
    private val inputBuilder: FinancialQueryInterpretationInputBuilder,
    private val categoryRepository: CategoryRepository
) {

    suspend operator fun invoke(
        rawQuery: String,
        priorMessages: List<AiChatMessage> = emptyList()
    ): FinancialQueryInterpretationResult {
        val settings = aiSettingsRepository.settings().first()
        if (!settings.aiEnabled || !settings.assistantEnabled || !settings.queryInterpretationEnabled) {
            return FinancialQueryInterpretationResult.Unsupported(
                AssistantUnsupportedReason.INTERPRETATION_DISABLED
            )
        }

        val input = inputBuilder.build(rawQuery, settings, priorMessages)

        val providerResult = try {
            queryInterpretationService.interpret(input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Bounded log: code + exception class only (never the throwable or its message).
            Timber.w(
                "InterpretFinancialQueryUseCase: %s class=%s",
                AssistantUnsupportedReason.INTERPRETATION_FAILED.name,
                e::class.java.simpleName
            )
            FinancialQueryInterpretationResult.Unsupported(
                AssistantUnsupportedReason.INTERPRETATION_FAILED
            )
        }

        return when (providerResult) {
            is FinancialQueryInterpretationResult.Structured -> {
                enrichStructuredResult(providerResult, rawQuery, input.currentTimeMs)
            }
            is FinancialQueryInterpretationResult.Clarification -> {
                // If provider returns another clarification, try local fallback as safety net
                val fallbackResult = localFallbackInterpret(rawQuery, input.currentTimeMs)
                // Only use fallback if it produces a structured result
                if (fallbackResult is FinancialQueryInterpretationResult.Structured) {
                    fallbackResult
                } else {
                    providerResult
                }
            }
            is FinancialQueryInterpretationResult.Unsupported ->
                localFallbackInterpret(rawQuery, input.currentTimeMs)
        }
    }

    /**
     * Merges locally derivable period/category/type/ownership dimensions into a
     * provider-structured result when the provider omitted them.
     *
     * Provider-supplied non-default values are always preserved; local heuristics
     * only fill gaps so that richer queries (e.g. "largest groceries this week")
     * retain their grouping/metric richness from the provider while gaining the
     * period and category filters the provider may not have resolved.
     */
    private suspend fun enrichStructuredResult(
        result: FinancialQueryInterpretationResult.Structured,
        rawQuery: String,
        nowMs: Long
    ): FinancialQueryInterpretationResult.Structured {
        val intent = result.intent
        val normalized = rawQuery.trim().lowercase(Locale.getDefault())
        val filters = intent.filters

        // --- Fill period (and its granularity) if provider left it null ---
        val resolvedPeriod = if (filters.period == null) {
            AssistantPeriodResolver.resolveText(normalized, nowMs).period
        } else {
            null
        }
        val enrichedPeriod = filters.period ?: resolvedPeriod?.toPeriodRange()
        val enrichedGranularity = if (resolvedPeriod != null) resolvedPeriod.granularity else filters.periodGranularity

        // --- Fill categoryIds if provider left them empty ---
        val enrichedCategoryIds = if (filters.categoryIds.isEmpty()) {
            val categories = categoryRepository.getAll()
            val matchedCategory = categories.firstOrNull { category ->
                normalized.contains(category.name.lowercase(Locale.getDefault()))
            }
            matchedCategory?.id?.let(::setOf) ?: emptySet()
        } else {
            filters.categoryIds
        }

        // --- Fill transactionTypes if provider left them empty ---
        val enrichedTypes = if (filters.transactionTypes.isEmpty()) {
            val matchedType = when {
                normalized.contains("transfer") -> DomainTransactionType.TRANSFER
                normalized.contains("withdraw") || normalized.contains("atm") -> DomainTransactionType.WITHDRAWAL
                normalized.contains("deposit") || normalized.contains("income") || normalized.contains("salary") -> DomainTransactionType.DEPOSIT
                normalized.contains("purchase") || normalized.contains("spend") || normalized.contains("spent") -> DomainTransactionType.PURCHASE
                else -> null
            }
            matchedType?.let(::setOf) ?: emptySet()
        } else {
            filters.transactionTypes
        }

        // --- Fill ownership if provider returned ALL and local cues are stronger ---
        val enrichedOwnership = if (filters.ownership == QueryOwnershipScope.ALL) {
            resolveOwnership(normalized)
        } else {
            filters.ownership
        }

        // --- Fill comparison if provider returned NONE and local cues suggest comparison ---
        val enrichedComparison = if (intent.comparison == QueryComparison.NONE) {
            if (AssistantPeriodResolver.isComparisonRequested(normalized)) {
                QueryComparison.PREVIOUS_EQUIVALENT_PERIOD
            } else {
                QueryComparison.NONE
            }
        } else {
            intent.comparison
        }

        // --- Validate and clamp min/max amounts for AI robustness ---
        val validatedMinAmount = validateAmountBound(filters.minAmount, "minAmount")
        val validatedMaxAmount = validateAmountBound(filters.maxAmount, "maxAmount")

        val enrichedFilters = filters.copy(
            period = enrichedPeriod,
            periodGranularity = enrichedGranularity,
            categoryIds = enrichedCategoryIds,
            transactionTypes = enrichedTypes,
            ownership = enrichedOwnership,
            minAmount = validatedMinAmount,
            maxAmount = validatedMaxAmount
        )

        return result.copy(
            intent = intent.copy(
                filters = enrichedFilters,
                comparison = enrichedComparison
            )
        )
    }

    private suspend fun localFallbackInterpret(
        rawQuery: String,
        now: Long
    ): FinancialQueryInterpretationResult {
        val normalized = rawQuery.trim().lowercase(Locale.getDefault())
        if (normalized.isBlank()) {
            return FinancialQueryInterpretationResult.Clarification(
                prompt = "What would you like to know about your expenses?",
                options = listOf(
                    "This month total",
                    "Top merchants this month",
                    "Largest purchase this month",
                    "Show groceries this month"
                )
            )
        }

        val isBarePeriod = isBarePeriodOnlyQuery(normalized, "this month", "current month") ||
            isBarePeriodOnlyQuery(normalized, "last month", "previous month") ||
            isBarePeriodOnlyQuery(normalized, "this week", "current week")
        if (isBarePeriod) {
            // Bare period-only queries resolve through the single assistant period lexicon.
            val barePeriod = AssistantPeriodResolver.resolveText(normalized, now).period
            return FinancialQueryInterpretationResult.Structured(
                FinancialQueryIntent(
                    rawQuery = rawQuery,
                    normalizedQuery = normalized,
                    filters = ExpenseQueryFilters(
                        period = barePeriod.toPeriodRange(),
                        periodGranularity = barePeriod.granularity
                    ),
                    metric = QueryMetric.TOTAL,
                    grouping = QueryGrouping.NONE,
                    comparison = QueryComparison.NONE
                )
            )
        }

        val categories = categoryRepository.getAll()
        val matchedCategory = categories.firstOrNull { category ->
            normalized.contains(category.name.lowercase(Locale.getDefault()))
        }

        val matchedType = when {
            normalized.contains("transfer") -> DomainTransactionType.TRANSFER
            normalized.contains("withdraw") || normalized.contains("atm") -> DomainTransactionType.WITHDRAWAL
            normalized.contains("deposit") || normalized.contains("income") || normalized.contains("salary") -> DomainTransactionType.DEPOSIT
            normalized.contains("purchase") || normalized.contains("spend") || normalized.contains("spent") -> DomainTransactionType.PURCHASE
            else -> null
        }

        val textPeriod = AssistantPeriodResolver.resolveText(normalized, now)
        val ownership = resolveOwnership(normalized)
        val metric = resolveMetric(normalized)
        val grouping = resolveGrouping(normalized, metric)
        val comparison = if (AssistantPeriodResolver.isComparisonRequested(normalized)) {
            QueryComparison.PREVIOUS_EQUIVALENT_PERIOD
        } else {
            QueryComparison.NONE
        }

        val intent = FinancialQueryIntent(
            rawQuery = rawQuery,
            normalizedQuery = normalized,
            filters = ExpenseQueryFilters(
                period = textPeriod.period.toPeriodRange(),
                periodGranularity = textPeriod.period.granularity,
                categoryIds = matchedCategory?.id?.let(::setOf) ?: emptySet(),
                transactionTypes = matchedType?.let(::setOf) ?: emptySet(),
                ownership = ownership
            ),
            metric = metric,
            grouping = grouping,
            comparison = if (metric == QueryMetric.TOTAL) comparison else QueryComparison.NONE
        )

        val supported = when {
            metric == QueryMetric.LIST -> true
            metric == QueryMetric.TOTAL -> true
            metric == QueryMetric.COUNT -> true
            metric == QueryMetric.AVERAGE -> true
            metric == QueryMetric.MAX -> true
            metric == QueryMetric.MIN -> true
            grouping == QueryGrouping.MERCHANT -> true
            grouping == QueryGrouping.CATEGORY -> true
            else -> false
        }

        if (!supported) {
            return FinancialQueryInterpretationResult.Unsupported(
                AssistantUnsupportedReason.METRIC_NOT_SUPPORTED
            )
        }

        if (grouping != QueryGrouping.NONE && metric !in setOf(QueryMetric.TOTAL, QueryMetric.LIST)) {
            return FinancialQueryInterpretationResult.Clarification(
                prompt = "Do you want a total breakdown or a transaction list?",
                options = listOf("Breakdown", "Transaction list"),
                // C4: typed completion so a tap fills the presentation slot without re-interpretation.
                completion = ClarificationCompletion(
                    baseIntent = intent,
                    slot = ClarificationSlot.PRESENTATION,
                    choices = listOf(
                        ClarificationChoice.Presentation(PresentationChoice.BREAKDOWN),
                        ClarificationChoice.Presentation(PresentationChoice.TRANSACTION_LIST)
                    )
                )
            )
        }

        return FinancialQueryInterpretationResult.Structured(intent)
    }

    private fun resolveMetric(query: String): QueryMetric = when {
        query.contains("largest") || query.contains("biggest") || query.contains("highest") -> QueryMetric.MAX
        // C5 (INV-C17): MIN is answered by the executor, never re-mapped to TOTAL.
        query.contains("smallest") || query.contains("cheapest") || query.contains("lowest") || query.contains("minimum") -> QueryMetric.MIN
        query.contains("average") || query.contains("avg") || query.contains("mean") -> QueryMetric.AVERAGE
        query.contains("count") || query.contains("how many") || query.contains("number of") -> QueryMetric.COUNT
        query.contains("show me") || query.contains("list") || query.contains("show ") -> QueryMetric.LIST
        else -> QueryMetric.TOTAL
    }

    private fun resolveGrouping(query: String, metric: QueryMetric): QueryGrouping = when {
        query.contains("merchant") || query.contains("merchants") || query.contains("where did i spend") -> QueryGrouping.MERCHANT
        query.contains("category") || query.contains("categories") -> QueryGrouping.CATEGORY
        metric == QueryMetric.LIST -> QueryGrouping.NONE
        else -> QueryGrouping.NONE
    }

    private fun resolveOwnership(query: String): QueryOwnershipScope = when {
        query.contains("not mine") -> QueryOwnershipScope.NOT_MINE
        query.contains("shared") -> QueryOwnershipScope.SHARED
        query.contains("transfer") || query.contains("transfers") -> QueryOwnershipScope.TRANSFER
        query.contains("my ") || query.contains("mine") -> QueryOwnershipScope.MINE
        else -> QueryOwnershipScope.ALL
    }

    /** Wraps the resolver's plain values into the (allowlisted-here) [PeriodRange]. */
    private fun AssistantPeriod.toPeriodRange(): PeriodRange = PeriodRange(start = startMs, end = endMs)

    /**
     * Validates and clamps an amount bound returned by the AI provider.
     * Rejects negative values, absurdly large values (> 1 billion), and
     * NaN/Infinity to prevent downstream errors.
     */
    private fun validateAmountBound(value: Double?, fieldName: String): Double? {
        if (value == null) return null
        if (value.isNaN() || value.isInfinite()) {
            Timber.w("InterpretFinancialQueryUseCase: AI returned invalid %s — rejecting", fieldName)
            return null
        }
        if (value < 0.0) {
            Timber.w("InterpretFinancialQueryUseCase: AI returned negative %s — clamping to 0", fieldName)
            return 0.0
        }
        val MAX_AMOUNT = 1_000_000_000.0
        if (value > MAX_AMOUNT) {
            Timber.w("InterpretFinancialQueryUseCase: AI returned excessive %s — clamping to maximum", fieldName)
            return MAX_AMOUNT
        }
        return value
    }

    private fun isBarePeriodOnlyQuery(query: String, vararg periodPhrases: String): Boolean {
        val normalized = query.trim()
        if (normalized.isBlank()) return false
        if (periodPhrases.none { phrase -> normalized == phrase || normalized == "$phrase only" || normalized == "for $phrase" }) {
            return false
        }

        return !containsRichIntentCue(normalized)
    }

    private fun containsRichIntentCue(query: String): Boolean {
        val keywords = listOf(
            "top",
            "largest",
            "biggest",
            "highest",
            "smallest",
            "cheapest",
            "lowest",
            "minimum",
            "average",
            "avg",
            "count",
            "how many",
            "number of",
            "show",
            "list",
            "merchant",
            "merchants",
            "category",
            "categories",
            "grocer",
            "transport",
            "transfer",
            "withdraw",
            "deposit",
            "purchase",
            "spend",
            "spent",
            "shared",
            "mine",
            "not mine",
            "compare",
            "vs"
        )
        return keywords.any(query::contains)
    }
}
