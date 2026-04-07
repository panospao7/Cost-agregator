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
    val correlationId: Long = 0L
)
