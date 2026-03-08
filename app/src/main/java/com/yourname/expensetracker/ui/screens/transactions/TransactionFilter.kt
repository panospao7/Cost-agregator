package com.yourname.expensetracker.ui.screens.transactions

import com.yourname.expensetracker.data.database.entity.TransactionType

data class TransactionFilter(
    val categoryId: Long? = null,
    val merchantName: String? = null,
    val transactionType: TransactionType? = null,
    val dateRange: Pair<Long, Long>? = null,
    val correlationId: Long = System.currentTimeMillis()
)
