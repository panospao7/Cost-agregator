package com.yourname.expensetracker.domain.model.navigation

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.OwnershipFilter

data class DomainTransactionFilter(
    val categoryId: Long? = null,
    val merchantName: String? = null,
    val transactionType: TransactionType? = null,
    val dateRange: Pair<Long, Long>? = null,
    val ownership: OwnershipFilter? = null,
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    val correlationId: Long = System.currentTimeMillis()
)
