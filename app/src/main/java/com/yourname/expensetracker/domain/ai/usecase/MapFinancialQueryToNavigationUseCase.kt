package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.repository.OwnershipFilter
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.model.QueryOwnershipScope
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import javax.inject.Inject

class MapFinancialQueryToNavigationUseCase @Inject constructor() {

    operator fun invoke(intent: FinancialQueryIntent): TransactionFilter? {
        if (intent.metric != QueryMetric.LIST && intent.metric != QueryMetric.TOTAL && intent.metric != QueryMetric.COUNT && intent.metric != QueryMetric.AVERAGE && intent.metric != QueryMetric.MAX) {
            return null
        }

        val period = intent.filters.period

        return TransactionFilter(
            categoryId = intent.filters.categoryIds.singleOrNull(),
            merchantName = intent.filters.merchants.singleOrNull(),
            transactionType = intent.filters.transactionTypes.singleOrNull(),
            dateRange = period?.let { it.start to it.end },
            ownership = when (intent.filters.ownership) {
                QueryOwnershipScope.ALL -> null
                QueryOwnershipScope.MINE -> OwnershipFilter.MINE
                QueryOwnershipScope.NOT_MINE -> OwnershipFilter.NOT_MINE
                QueryOwnershipScope.SHARED -> OwnershipFilter.SHARED
                QueryOwnershipScope.TRANSFER -> OwnershipFilter.TRANSFER
            },
            minAmount = intent.filters.minAmount,
            maxAmount = intent.filters.maxAmount
        )
    }
}
