package com.yourname.expensetracker.domain.model

data class ExpenseSnapshot(
    val id: Long,
    val amount: Double,
    val effectiveAmount: Double,
    val currency: String,
    val merchant: String,
    val merchantKey: String?,
    val transactionType: DomainTransactionType,
    val date: Long,
    val categoryId: Long?,
    val isNotMine: Boolean,
    val transferDirection: DomainTransferDirection?,
    val notes: String?
)
