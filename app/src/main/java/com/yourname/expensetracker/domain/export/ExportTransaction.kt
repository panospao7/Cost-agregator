package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.TransactionType

/**
 * Domain DTO used by export formatters.
 */
data class ExportTransaction(
    val id: Long,
    val date: Long,
    val amount: Double,
    val merchant: String,
    val notes: String?,
    val categoryId: Long?,
    val currency: String = "EUR",
    val transactionType: TransactionType = TransactionType.UNKNOWN,
    val sourceAccountName: String = "Unknown Funding Source"
)
