package com.yourname.expensetracker.ui.screens.transactions

data class TransactionFilter(
    val categoryId: Long? = null,
    val merchantName: String? = null,
    val dateRange: Pair<Long, Long>? = null,
    val correlationId: Long = System.currentTimeMillis()
)
