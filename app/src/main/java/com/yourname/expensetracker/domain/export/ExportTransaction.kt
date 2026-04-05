package com.yourname.expensetracker.domain.export

/**
 * Domain DTO used by export formatters.
 */
data class ExportTransaction(
    val id: Long,
    val date: Long,
    val amount: Double,
    val merchant: String,
    val notes: String?,
    val categoryId: Long?
)
