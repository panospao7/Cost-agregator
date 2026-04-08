package com.yourname.expensetracker.ui.screens.transactions

import com.yourname.expensetracker.data.database.entity.TransactionType

data class TransactionFilter(
    val categoryId: Long? = null,
    val merchantName: String? = null,
    val transactionType: TransactionType? = null,
    val dateRange: Pair<Long, Long>? = null,
    val ownership: com.yourname.expensetracker.data.repository.OwnershipFilter? = null,
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    val correlationId: Long = 0L
)
