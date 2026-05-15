package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.model.QueryOwnershipScope
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.navigation.DomainOwnershipFilter
import com.yourname.expensetracker.domain.model.navigation.DomainTransactionFilter
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class MapFinancialQueryToNavigationUseCase @Inject constructor(
    private val currencySettingsRepository: CurrencySettingsRepository
) {

    suspend operator fun invoke(intent: FinancialQueryIntent): DomainTransactionFilter? {
        if (intent.metric != QueryMetric.LIST && intent.metric != QueryMetric.TOTAL && intent.metric != QueryMetric.COUNT && intent.metric != QueryMetric.AVERAGE && intent.metric != QueryMetric.MAX) {
            return null
        }

        val period = intent.filters.period
        // S11-017: Include home currency basis so Transactions knows the amount filter basis
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }.getOrNull()

        return DomainTransactionFilter(
            categoryId = intent.filters.categoryIds.singleOrNull(),
            merchantName = intent.filters.merchants.singleOrNull(),
            transactionType = intent.filters.transactionTypes.singleOrNull(),
            dateRange = period?.let { it.start to it.end },
            ownership = when (intent.filters.ownership) {
                QueryOwnershipScope.ALL -> null
                QueryOwnershipScope.MINE -> DomainOwnershipFilter.MINE
                QueryOwnershipScope.NOT_MINE -> DomainOwnershipFilter.NOT_MINE
                QueryOwnershipScope.SHARED -> DomainOwnershipFilter.SHARED
                QueryOwnershipScope.TRANSFER -> DomainOwnershipFilter.TRANSFER
            },
            minAmount = intent.filters.minAmount,
            maxAmount = intent.filters.maxAmount,
            amountCurrency = if (intent.filters.minAmount != null || intent.filters.maxAmount != null) homeCurrency else null
        )
    }
}
