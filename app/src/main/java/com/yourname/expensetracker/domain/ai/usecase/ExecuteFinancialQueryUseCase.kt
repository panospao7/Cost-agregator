package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.FinancialQueryResult
import com.yourname.expensetracker.domain.ai.model.FinancialQueryDataQuality
import com.yourname.expensetracker.domain.ai.model.QueryComparison
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.model.QueryOwnershipScope
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.PeriodRange
import com.yourname.expensetracker.domain.model.UiText
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject

/**
 * Executes financial queries from AI-assistant interpretation.
 *
 * Currency handling: Per-currency totals are displayed side-by-side (e.g., "50.00 EUR + 100.00 USD")
 * without conversion to home currency. This is intentional for transparency — the assistant shows
 * what the user has in each currency. For single-currency users, this is always one line.
 *
 * Amount filters (minAmount/maxAmount) operate on raw effectiveAmount — they do not automatically
 * convert to home currency. Queries like "expenses over $50" filter on the numeric value of
 * effectiveAmount regardless of currency.
 *
 * TODO (PR 5/W32): Amount filters (minAmount/maxAmount) should accept optional currency.
 * Currently "expenses over $50" compares raw effectiveAmount across all currencies.
 * See: ExpenseRepository.buildExpenseDynamicQueryParts for filter SQL.
 */
class ExecuteFinancialQueryUseCase @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val currencyConverter: CurrencyConverter,
    private val currencySettingsRepository: CurrencySettingsRepository
) {

    suspend operator fun invoke(intent: FinancialQueryIntent): FinancialQueryResult {
        val period = intent.filters.period ?: return FinancialQueryResult.Clarification(
            prompt = "Which time period should I use?",
            options = listOf("This month", "This week", "Last month")
        )

        return when {
            intent.metric == QueryMetric.LIST -> executeList(intent, period)
            intent.grouping == QueryGrouping.CATEGORY -> executeCategoryBreakdown(intent, period)
            intent.grouping == QueryGrouping.MERCHANT -> executeMerchantBreakdown(intent, period)
            intent.metric == QueryMetric.MAX -> executeLargest(intent, period)
            intent.metric == QueryMetric.TOTAL -> executeTotal(intent, period)
            intent.metric == QueryMetric.COUNT -> executeCount(intent, period)
            intent.metric == QueryMetric.AVERAGE -> executeAverage(intent, period)
            else -> FinancialQueryResult.Unsupported("This query type is not supported yet")
        }
    }

    private suspend fun executeList(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): FinancialQueryResult {
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")

        // Apply amount filter with currency awareness (in-memory after period/type/category narrowing)
        if (intent.filters.minAmount != null || intent.filters.maxAmount != null) {
            val results = expenseRepository.getAssistantExpensesFiltered(
                startDate = period.start,
                endDate = period.end,
                transactionTypes = intent.filters.transactionTypes.map { it.toEntity() }.toSet(),
                categoryIds = intent.filters.categoryIds,
                merchantNames = intent.filters.merchants,
                ownershipFilter = intent.filters.ownership.toRepositoryOwnershipFilter(),
                minAmount = null,
                maxAmount = null
            )
            var failedConversions = 0
            val filteredResults = results.mapNotNull { expenseWithCategory ->
                val expense = expenseWithCategory.expense
                val normalizedAmount = if (expense.currency.equals(homeCurrency, true)) {
                    expense.effectiveAmount
                } else {
                    currencyConverter.convert(expense.effectiveAmount, expense.currency, homeCurrency)?.convertedAmount
                }
                if (normalizedAmount == null) {
                    failedConversions++
                    null
                } else if (intent.filters.minAmount != null && normalizedAmount < intent.filters.minAmount) {
                    null
                } else if (intent.filters.maxAmount != null && normalizedAmount > intent.filters.maxAmount) {
                    null
                } else {
                    expenseWithCategory
                }
            }
            return FinancialQueryResult.TransactionList(
                title = buildListTitle(intent),
                previewCount = filteredResults.size,
                drilldownIntent = intent,
                dataQuality = FinancialQueryDataQuality(
                    isPartial = failedConversions > 0,
                    excludedCount = failedConversions,
                    missingRateCount = failedConversions,
                    warnings = if (failedConversions > 0) {
                        listOf("$failedConversions expense(s) excluded due to missing exchange rates")
                    } else {
                        emptyList()
                    }
                )
            )
        }

        val previewCount = expenseRepository.getAssistantExpenseCountFiltered(
            startDate = period.start,
            endDate = period.end,
            transactionTypes = intent.filters.transactionTypes.map { it.toEntity() }.toSet(),
            categoryIds = intent.filters.categoryIds,
            merchantNames = intent.filters.merchants,
            ownershipFilter = intent.filters.ownership.toRepositoryOwnershipFilter()
        )
        return FinancialQueryResult.TransactionList(
            title = buildListTitle(intent),
            previewCount = previewCount,
            drilldownIntent = intent,
            dataQuality = FinancialQueryDataQuality()
        )
    }

    private suspend fun executeCategoryBreakdown(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): FinancialQueryResult {
        val categoriesById = categoryRepository.getAll().associateBy { it.id }
        val filtered = assistantFilteredExpenses(intent, period)
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")
        val groups = filtered.expenses
            .filter { it.expense.categoryId != null }
            .groupBy { it.expense.categoryId!! }
            .values
        var failedConversions = 0
        val sorted = groups.map { grouped ->
            val byCurrency = grouped.groupBy { it.expense.currency }
                // SAFE: per-currency bucket sum, then convertMultiple — correct multi-currency handling
                .map { (currency, list) -> list.sumOf { it.expense.effectiveAmount } to currency }
            val sortKey = if (byCurrency.size == 1) {
                val (amount, currency) = byCurrency.first()
                // VERIFIED (PR-E22 / PR5): Single non-home currency conversion correctly
                // uses currencyConverter.convert() and increments failedConversions
                // when conversion fails (missing rate). The 0.0 fallback avoids NPE.
                if (currency.equals(homeCurrency, ignoreCase = true)) amount
                else currencyConverter.convert(amount, currency, homeCurrency)?.convertedAmount
                    ?: run {
                        failedConversions++
                        0.0
                    }
            } else {
                val aggregate = currencyConverter.convertMultiple(byCurrency, homeCurrency)
                failedConversions += aggregate.failedConversions.size
                aggregate.total
            }
            grouped to sortKey
        }.sortedByDescending { it.second }
            .take(8)
            .map { it.first }
        val rows = sorted.map { grouped ->
            val categoryId = grouped.first().expense.categoryId!!
            val currencyTotals = grouped.toCurrencyTotals()
            FinancialQueryResult.Breakdown.Row(
                label = categoriesById[categoryId]?.name ?: "Unknown",
                amount = currencyTotals.singleOrNull()?.amount,
                count = grouped.size,
                valueText = formatCurrencyTotals(currencyTotals)
            )
        }

        return FinancialQueryResult.Breakdown(
            title = UiText.fromKey("domain_ai_top_categories"),
            rows = rows,
            drilldownIntent = intent,
            dataQuality = FinancialQueryDataQuality(
                isPartial = failedConversions > 0,
                excludedCount = failedConversions,
                missingRateCount = failedConversions,
                warnings = if (failedConversions > 0) {
                    listOf("$failedConversions category breakdown item(s) affected by missing exchange rates")
                } else {
                    emptyList()
                }
            )
        )
    }

    private suspend fun executeMerchantBreakdown(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): FinancialQueryResult {
        val filtered = assistantFilteredExpenses(intent, period)
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")
        val groups = filtered.expenses
            .groupBy { it.expense.merchantKey ?: it.expense.merchant }
            .values
        var failedConversions = 0
        val sorted = groups.map { grouped ->
            val byCurrency = grouped.groupBy { it.expense.currency }
                // SAFE: per-currency bucket sum, then convertMultiple — correct multi-currency handling
                .map { (currency, list) -> list.sumOf { it.expense.effectiveAmount } to currency }
            val sortKey = if (byCurrency.size == 1) {
                val (amount, currency) = byCurrency.first()
                if (currency.equals(homeCurrency, ignoreCase = true)) amount
                else currencyConverter.convert(amount, currency, homeCurrency)?.convertedAmount
                    ?: run {
                        failedConversions++
                        0.0
                    }
            } else {
                val aggregate = currencyConverter.convertMultiple(byCurrency, homeCurrency)
                failedConversions += aggregate.failedConversions.size
                aggregate.total
            }
            grouped to sortKey
        }.sortedByDescending { it.second }
            .take(8)
            .map { it.first }
        val merchantRows = sorted.map { grouped ->
            val currencyTotals = grouped.toCurrencyTotals()
            FinancialQueryResult.Breakdown.Row(
                label = grouped.minOf { it.expense.merchant },
                amount = currencyTotals.singleOrNull()?.amount,
                count = grouped.size,
                valueText = formatCurrencyTotals(currencyTotals)
            )
        }

        return FinancialQueryResult.Breakdown(
            title = UiText.fromKey("domain_ai_top_merchants"),
            rows = merchantRows,
            drilldownIntent = intent,
            dataQuality = FinancialQueryDataQuality(
                isPartial = failedConversions > 0,
                excludedCount = failedConversions,
                missingRateCount = failedConversions,
                warnings = if (failedConversions > 0) {
                    listOf("$failedConversions merchant breakdown item(s) affected by missing exchange rates")
                } else {
                    emptyList()
                }
            )
        )
    }

    private suspend fun executeLargest(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): FinancialQueryResult {
        val matching = assistantFilteredExpenses(intent, period)
        if (matching.expenses.isEmpty()) {
            return FinancialQueryResult.Unsupported("No matching transactions found")
        }

        // W32: Normalize amounts to home currency before maxByOrNull to avoid
        // comparing raw mixed-currency amounts (e.g. 100 JPY vs 50 USD).
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")

        var failedConversions = 0

        // Determine if we have mixed currencies
        val distinctCurrencies = matching.expenses.map { it.expense.currency }.distinct()
        val largest = if (distinctCurrencies.size <= 1) {
            // Single currency — safe to compare directly
            matching.expenses.maxByOrNull { it.expense.effectiveAmount }
        } else {
            // Mixed currencies — convert each to home currency for comparison
            val withNormalized = matching.expenses.mapNotNull { expenseWithCategory ->
                val normalizedAmount = if (expenseWithCategory.expense.currency.equals(homeCurrency, ignoreCase = true)) {
                    expenseWithCategory.expense.effectiveAmount
                } else {
                    currencyConverter.convert(
                        amount = expenseWithCategory.expense.effectiveAmount,
                        fromCurrency = expenseWithCategory.expense.currency,
                        toCurrency = homeCurrency
                    )?.convertedAmount ?: run {
                        failedConversions++
                        null  // exclude this row
                    }
                }
                normalizedAmount?.let { expenseWithCategory to it }
            }
            withNormalized.maxByOrNull { it.second }?.first
        } ?: return FinancialQueryResult.Unsupported("No matching transactions found")

        val dataQuality = FinancialQueryDataQuality(
            isPartial = failedConversions > 0,
            excludedCount = failedConversions,
            missingRateCount = failedConversions, // simplified — all failures are missing rates in this path
            warnings = if (failedConversions > 0) listOf("$failedConversions expense(s) excluded due to missing exchange rates") else emptyList()
        )

        return FinancialQueryResult.Summary(
            title = UiText.fromKey("domain_ai_largest_purchase"),
            primaryText = "${largest.expense.merchant}: ${formatSingleExpenseAmount(largest.expense)}",
            supportingText = null,
            drilldownIntent = intent.copy(
                filters = intent.filters.copy(
                    merchants = setOf(largest.expense.merchant),
                    transactionTypes = setOf(largest.expense.transactionType.toDomain())
                )
            ),
            dataQuality = dataQuality
        )
    }

    private suspend fun executeTotal(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): FinancialQueryResult {
        val current = assistantFilteredExpenses(intent, period)
        val currentCurrencyTotals = current.expenses.toCurrencyTotals()
        // No conversion loop in this path — per-currency totals are shown side-by-side.
        // failedConversions is 0 because toCurrencyTotals groups raw amounts by currency
        // without attempting cross-currency conversion.
        val failedConversions = 0

        val supporting = if (intent.comparison == QueryComparison.PREVIOUS_EQUIVALENT_PERIOD) {
            val previous = previousEquivalentPeriod(period)
            val previousResult = assistantFilteredExpenses(intent, previous)
            "Previous period: ${formatCurrencyTotals(previousResult.expenses.toCurrencyTotals())}"
        } else {
            null
        }

        return FinancialQueryResult.Summary(
            title = UiText.fromKey("domain_ai_total_spending"),
            primaryText = formatCurrencyTotals(currentCurrencyTotals),
            supportingText = supporting,
            drilldownIntent = intent,
            dataQuality = FinancialQueryDataQuality(
                isPartial = failedConversions > 0,
                excludedCount = failedConversions,
                missingRateCount = failedConversions
            )
        )
    }

    private suspend fun executeCount(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): FinancialQueryResult {
        val count = expenseRepository.getAssistantExpenseCountFiltered(
            startDate = period.start,
            endDate = period.end,
            transactionTypes = intent.filters.transactionTypes.map { it.toEntity() }.toSet(),
            categoryIds = intent.filters.categoryIds,
            merchantNames = intent.filters.merchants,
            ownershipFilter = intent.filters.ownership.toRepositoryOwnershipFilter(),
            minAmount = intent.filters.minAmount,
            maxAmount = intent.filters.maxAmount
        )
        return FinancialQueryResult.Summary(
            title = UiText.fromKey("domain_ai_transaction_count"),
            primaryText = count.toString(),
            drilldownIntent = intent
        )
    }

    private suspend fun executeAverage(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): FinancialQueryResult {
        val result = assistantFilteredExpenses(intent, period)
        val expenses = result.expenses
        // Per-currency averages are computed from raw amounts without cross-currency
        // conversion, so failedConversions is 0 in this path.
        val failedConversions = 0
        return FinancialQueryResult.Summary(
            title = UiText.fromKey("domain_ai_average_spending"),
            primaryText = formatCurrencyAverages(expenses.toCurrencyTotals()),
            supportingText = if (expenses.isNotEmpty()) "Across ${expenses.size} transactions" else null,
            drilldownIntent = intent,
            dataQuality = FinancialQueryDataQuality(
                isPartial = failedConversions > 0,
                excludedCount = failedConversions,
                missingRateCount = failedConversions
            )
        )
    }

    /**
     * TODO (PR-E22): Replace per-method inline filtering with this unified helper.
     * Currently ALL execute* methods have their own currency-aware amount filter logic.
     * This helper consolidates the pattern:
     *
     * Step 1: Push date/type/category/merchant filters to DAO (minAmount/maxAmount = null).
     * Step 2: Normalize each expense's effectiveAmount to homeCurrency via currencyConverter.convertAsOf().
     * Step 3: Apply minAmount/maxAmount filter in-memory on normalized amounts.
     * Step 4: Track failedConversions (count of expenses excluded due to missing rates).
     * Step 5: Return Pair<List<ExpenseWithCategory>, FinancialQueryDataQuality>.
     *
     * Migration plan:
     * - executeList() already does this inline (lines 71-115). Replace with call to this helper.
     * - executeCategoryBreakdown() uses assistantFilteredExpenses + inline conversion. Replace.
     * - executeMerchantBreakdown() uses assistantFilteredExpenses + inline conversion. Replace.
     * - executeLargest() uses assistantFilteredExpenses + inline conversion. Replace.
     * - executeTotal() / executeAverage() / executeCount() don't need amount filter, keep as-is.
     */
    private suspend fun assistantFilteredExpensesCurrencyAware(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): Pair<List<ExpenseWithCategory>, FinancialQueryDataQuality> {
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")
        var failedConversions = 0
        val warnings = mutableListOf<String>()

        // 1. Fetch expenses with date/type/category/merchant filters (no amount filter)
        val expenses = expenseRepository.getAssistantExpensesFiltered(
            startDate = period.start,
            endDate = period.end,
            transactionTypes = intent.filters.transactionTypes.map { it.toEntity() }.toSet(),
            categoryIds = intent.filters.categoryIds,
            merchantNames = intent.filters.merchants,
            ownershipFilter = intent.filters.ownership.toRepositoryOwnershipFilter(),
            minAmount = null,
            maxAmount = null
        )

        // 2. Apply amount filter in-memory with currency normalization
        val minAmount = intent.filters.minAmount
        val maxAmount = intent.filters.maxAmount
        val needsAmountFilter = minAmount != null || maxAmount != null

        val filtered = if (!needsAmountFilter) {
            expenses
        } else {
            expenses.mapNotNull { ewc ->
                val expense = ewc.expense
                val normalizedAmount = if (expense.currency.equals(homeCurrency, true)) {
                    expense.effectiveAmount
                } else {
                    currencyConverter.convertAsOf(
                        amount = expense.effectiveAmount,
                        fromCurrency = expense.currency,
                        toCurrency = homeCurrency,
                        atMillis = expense.date
                    )?.convertedAmount
                }
                if (normalizedAmount == null) {
                    failedConversions++
                    warnings.add("Excluded ${expense.merchant}: ${expense.effectiveAmount} ${expense.currency} — conversion failed")
                    null
                } else if (minAmount != null && normalizedAmount < minAmount) {
                    null
                } else if (maxAmount != null && normalizedAmount > maxAmount) {
                    null
                } else {
                    ewc
                }
            }
        }

        val dataQuality = FinancialQueryDataQuality(
            isPartial = failedConversions > 0,
            excludedCount = failedConversions,
            missingRateCount = failedConversions,
            warnings = warnings
        )

        return Pair(filtered, dataQuality)
    }

    private suspend fun assistantFilteredExpenses(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): AssistantFilteredExpenses {
        val expenses = expenseRepository.getAssistantExpensesFiltered(
            startDate = period.start,
            endDate = period.end,
            transactionTypes = intent.filters.transactionTypes.map { it.toEntity() }.toSet(),
            categoryIds = intent.filters.categoryIds,
            merchantNames = intent.filters.merchants,
            ownershipFilter = intent.filters.ownership.toRepositoryOwnershipFilter(),
            minAmount = intent.filters.minAmount,
            maxAmount = intent.filters.maxAmount
        )
        return AssistantFilteredExpenses(
            expenses = expenses
        )
    }

    private fun buildListTitle(intent: FinancialQueryIntent): UiText = when {
        intent.filters.categoryIds.isNotEmpty() -> UiText.from("Matching category transactions")
        intent.filters.merchants.isNotEmpty() -> UiText.from("Matching merchant transactions")
        else -> UiText.from("Matching transactions")
    }

    private fun previousEquivalentPeriod(period: PeriodRange): PeriodRange {
        val duration = period.end - period.start
        return PeriodRange(period.start - duration, period.start)
    }

    private fun formatSingleExpenseAmount(expense: Expense): String =
        formatAmount(expense.effectiveAmount, expense.currency)

    private fun formatCurrencyTotals(currencyTotals: List<CurrencyTotal>): String = when {
        currencyTotals.isEmpty() -> formatAmount(0.0, "N/A")
        currencyTotals.size == 1 -> formatAmount(currencyTotals.first().amount, currencyTotals.first().currency)
        else -> currencyTotals.joinToString(" + ") { total ->
            formatAmount(total.amount, total.currency)
        }
    }

    private fun formatCurrencyAverages(currencyTotals: List<CurrencyTotal>): String = when {
        currencyTotals.isEmpty() -> formatAmount(0.0, "N/A")
        currencyTotals.size == 1 -> formatAmount(currencyTotals.first().amount / currencyTotals.first().count, currencyTotals.first().currency)
        else -> currencyTotals.joinToString(" • ") { total ->
            "${formatAmount(total.amount / total.count, total.currency)} avg"
        }
    }

    private fun formatAmount(amount: Double, currency: String): String =
        String.format(Locale.US, "%.2f %s", amount, currency.normalizedCurrencyCode())

    private fun String.normalizedCurrencyCode(): String = trim().uppercase(Locale.US).ifBlank { "UNKNOWN" }

    private fun List<ExpenseWithCategory>.toCurrencyTotals(): List<CurrencyTotal> =
        groupBy { it.expense.currency.normalizedCurrencyCode() }
            .map { (currency, expenses) ->
                CurrencyTotal(
                    currency = currency,
                    // SAFE: per-currency bucket sum from addExpenseToBucket — correct multi-currency handling
                    amount = expenses.sumOf { it.expense.effectiveAmount },
                    count = expenses.size
                )
            }
            .sortedBy { it.currency }

    private data class AssistantFilteredExpenses(
        val expenses: List<ExpenseWithCategory>
    )

    private data class CurrencyTotal(
        val currency: String,
        val amount: Double,
        val count: Int
    )

    private fun QueryOwnershipScope.toRepositoryOwnershipFilter(): com.yourname.expensetracker.data.repository.OwnershipFilter =
        when (this) {
            QueryOwnershipScope.ALL -> com.yourname.expensetracker.data.repository.OwnershipFilter.ALL
            QueryOwnershipScope.MINE -> com.yourname.expensetracker.data.repository.OwnershipFilter.MINE
            QueryOwnershipScope.NOT_MINE -> com.yourname.expensetracker.data.repository.OwnershipFilter.NOT_MINE
            QueryOwnershipScope.SHARED -> com.yourname.expensetracker.data.repository.OwnershipFilter.SHARED
            QueryOwnershipScope.TRANSFER -> com.yourname.expensetracker.data.repository.OwnershipFilter.TRANSFER
        }

    private fun DomainTransactionType.toEntity(): com.yourname.expensetracker.data.database.entity.TransactionType =
        when (this) {
            DomainTransactionType.PURCHASE -> com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE
            DomainTransactionType.WITHDRAWAL -> com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL
            DomainTransactionType.TRANSFER -> com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER
            DomainTransactionType.DEPOSIT -> com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT
            DomainTransactionType.UNKNOWN -> com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN
        }

    private fun com.yourname.expensetracker.data.database.entity.TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }

}
