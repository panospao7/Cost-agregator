package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.FinancialQueryResult
import com.yourname.expensetracker.domain.ai.model.QueryComparison
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.model.QueryOwnershipScope
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.PeriodRange
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import javax.inject.Inject

class ExecuteFinancialQueryUseCase @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository
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
        val preview = loadFilteredExpenses(intent, period)
        return FinancialQueryResult.TransactionList(
            title = buildListTitle(intent),
            previewCount = preview.size,
            drilldownIntent = intent
        )
    }

    private suspend fun executeCategoryBreakdown(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): FinancialQueryResult {
        val categoriesById = categoryRepository.getAll().associateBy { it.id }
        val rows = expenseRepository.getCategoryTotalsForPeriod(period.start, period.end)
            .filter { total ->
                intent.filters.categoryIds.isEmpty() || total.categoryId in intent.filters.categoryIds
            }
            .take(8)
            .map { total ->
                FinancialQueryResult.Breakdown.Row(
                    label = categoriesById[total.categoryId]?.name ?: "Unknown",
                    amount = total.total,
                    count = total.txCount
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
        val merchantStats = expenseRepository.getTopMerchantsForPeriod(period.start, period.end, limit = 8)
            .filter { stat ->
                intent.filters.merchants.isEmpty() || stat.displayName in intent.filters.merchants ||
                    stat.merchantName in intent.filters.merchants.map { MerchantKeyGenerator.generate(it) }
            }
            .map { stat ->
                FinancialQueryResult.Breakdown.Row(
                    label = stat.displayName,
                    amount = stat.totalAmount,
                    count = stat.transactionCount
                )
            }

        return FinancialQueryResult.Breakdown(
            title = UiText.fromKey("domain_ai_top_merchants"),
            rows = merchantStats,
            drilldownIntent = intent
        )
    }

    private suspend fun executeLargest(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): FinancialQueryResult {
        val largest = expenseRepository.getLargestExpenseForPeriod(period.start, period.end)
            ?: return FinancialQueryResult.Unsupported("No matching transactions found")

        return FinancialQueryResult.Summary(
            title = UiText.fromKey("domain_ai_largest_purchase"),
            primaryText = "${largest.merchant}: %.2f EUR".format(largest.effectiveAmount),
            supportingText = null,
            drilldownIntent = intent.copy(
                filters = intent.filters.copy(
                    merchants = setOf(largest.merchant),
                    transactionTypes = setOf(largest.transactionType.toDomain())
                )
            )
        )
    }

    private suspend fun executeTotal(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): FinancialQueryResult {
        val currentTotal = if (intent.filters.isSimplePurchaseTotal()) {
            expenseRepository.getTotalForPeriod(period.start, period.end)
        } else {
            loadFilteredExpenses(intent, period).sumOf { it.expense.effectiveAmount }
        }

        val supporting = if (intent.comparison == QueryComparison.PREVIOUS_EQUIVALENT_PERIOD) {
            val previous = previousEquivalentPeriod(period)
            val previousTotal = if (intent.filters.isSimplePurchaseTotal()) {
                expenseRepository.getTotalForPeriod(previous.start, previous.end)
            } else {
                loadFilteredExpenses(intent, previous).sumOf { it.expense.effectiveAmount }
            }
            "Previous period: %.2f EUR".format(previousTotal)
        } else {
            null
        }

        return FinancialQueryResult.Summary(
            title = UiText.fromKey("domain_ai_total_spending"),
            primaryText = "%.2f EUR".format(currentTotal),
            supportingText = supporting,
            drilldownIntent = intent
        )
    }

    private suspend fun executeCount(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ): FinancialQueryResult {
        val count = loadFilteredExpenses(intent, period).size
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
        val expenses = loadFilteredExpenses(intent, period)
        val average = if (expenses.isEmpty()) 0.0 else expenses.sumOf { it.expense.effectiveAmount } / expenses.size
        return FinancialQueryResult.Summary(
            title = UiText.fromKey("domain_ai_average_spending"),
            primaryText = "%.2f EUR".format(average),
            supportingText = if (expenses.isNotEmpty()) "Across ${expenses.size} transactions" else null,
            drilldownIntent = intent
        )
    }

    private suspend fun loadFilteredExpenses(
        intent: FinancialQueryIntent,
        period: PeriodRange
    ) = expenseRepository.getExpensesPagedDynamic(
        limit = 500,
        offset = 0,
        startDate = period.start,
        endDate = period.end,
        transactionType = intent.filters.transactionTypes.singleOrNull()?.toEntity(),
        categoryId = intent.filters.categoryIds.singleOrNull(),
        merchantName = intent.filters.merchants.singleOrNull(),
        ownershipFilter = intent.filters.ownership.toRepositoryOwnershipFilter(),
        minAmount = intent.filters.minAmount,
        maxAmount = intent.filters.maxAmount
    )

    private fun buildListTitle(intent: FinancialQueryIntent): UiText = when {
        intent.filters.categoryIds.isNotEmpty() -> UiText.from("Matching category transactions")
        intent.filters.merchants.isNotEmpty() -> UiText.from("Matching merchant transactions")
        else -> UiText.from("Matching transactions")
    }

    private fun previousEquivalentPeriod(period: PeriodRange): PeriodRange {
        val duration = period.end - period.start
        return PeriodRange(period.start - duration, period.start)
    }

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

    private fun com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters.isSimplePurchaseTotal(): Boolean {
        return merchants.isEmpty() &&
            categoryIds.isEmpty() &&
            transactionTypes.isEmpty() &&
            ownership == QueryOwnershipScope.ALL &&
            minAmount == null &&
            maxAmount == null
    }
}
