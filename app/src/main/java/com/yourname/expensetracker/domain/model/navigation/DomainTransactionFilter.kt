package com.yourname.expensetracker.domain.model.navigation

import com.yourname.expensetracker.domain.model.DomainTransactionType

data class DomainTransactionFilter(
    val categoryId: Long? = null,
    val merchantName: String? = null,
    val transactionType: DomainTransactionType? = null,
    val dateRange: Pair<Long, Long>? = null,
    val ownership: DomainOwnershipFilter? = null,
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    /**
     * S11-017: Currency basis for minAmount/maxAmount filters.
     * null = raw effectiveAmount (legacy/no-filter).
     * non-null = home-currency-normalized basis (from assistant query execution).
     * Transactions screen uses effectiveAmount which is already home-currency normalized,
     * so both paths agree when this is set to the home currency.
     */
    val amountCurrency: String? = null,
    val correlationId: Long = 0L
)
