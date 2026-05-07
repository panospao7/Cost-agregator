package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.FinancialQueryResult
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
        val previewCount = expenseRepository.getAssistantExpenseCountFiltered(
            startDate = period.start,
            endDate = period.end,
            transactionTypes = intent.filters.transactionTypes.map { it.toEntity() }.toSet(),
            categoryIds = intent.filters.categoryIds,
            merchantNames = intent.filters.merchants,
            ownershipFilter = intent.filters.ownership.toRepositoryOwnershipFilter(),
            minAmount = intent.filters.minAmount,
            maxAmount = intent.filters.maxAmount
        )
        return FinancialQueryResult.TransactionList(
            title = buildListTitle(intent),
            previewCount = previewCount,
            drilldownIntent = intent
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
        val sorted = groups.map { grouped ->
            val byCurrency = grouped.groupBy { it.expense.currency }
                // SAFE: per-currency bucket sum, then convertMultiple — correct multi-currency handling
                .map { (currency, list) -> list.sumOf { it.expense.effectiveAmount } to currency }
            val sortKey = if (byCurrency.size == 1) byCurrency.first().first
            else currencyConverter.convertMultiple(byCurrency, homeCurrency).total
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
            drilldownIntent = intent
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
        val sorted = groups.map { grouped ->
            val byCurrency = grouped.groupBy { it.expense.currency }
                // SAFE: per-currency bucket sum, then convertMultiple — correct multi-currency handling
                .map { (currency, list) -> list.sumOf { it.expense.effectiveAmount } to currency }
            val sortKey = if (byCurrency.size == 1) byCurrency.first().first
            else currencyConverter.convertMultiple(byCurrency, homeCurrency).total
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
            drilldownIntent = intent
        )
    }

    private suspend fun executeLargest(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): FinancialQueryResult {
        val matching = assistantFilteredExpenses(intent, period)
        // TODO (W32): Normalize amounts to home currency before maxByOrNull.
        // Currently compares raw mixed-currency amounts.
        val largest = matching.expenses.maxByOrNull { it.expense.effectiveAmount }
            ?: return FinancialQueryResult.Unsupported("No matching transactions found")

        return FinancialQueryResult.Summary(
            title = UiText.fromKey("domain_ai_largest_purchase"),
            primaryText = "${largest.expense.merchant}: ${formatSingleExpenseAmount(largest.expense)}",
            supportingText = null,
            drilldownIntent = intent.copy(
                filters = intent.filters.copy(
                    merchants = setOf(largest.expense.merchant),
                    transactionTypes = setOf(largest.expense.transactionType.toDomain())
                )
            )
        )
    }

    private suspend fun executeTotal(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): FinancialQueryResult {
        val current = assistantFilteredExpenses(intent, period)
        val currentCurrencyTotals = current.expenses.toCurrencyTotals()

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
            drilldownIntent = intent
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
        return FinancialQueryResult.Summary(
            title = UiText.fromKey("domain_ai_average_spending"),
            primaryText = formatCurrencyAverages(expenses.toCurrencyTotals()),
            supportingText = if (expenses.isNotEmpty()) "Across ${expenses.size} transactions" else null,
            drilldownIntent = intent
        )
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
