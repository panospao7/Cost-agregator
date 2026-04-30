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
    /** ISO 4217 currency code. Mappers MUST explicitly set this from the source expense. */
    val currency: String,
    val transactionType: TransactionType = TransactionType.UNKNOWN,
    val sourceAccountName: String = "Unknown Funding Source",
    /** Original transaction currency before home-currency conversion. */
    val originalCurrency: String = currency,
    /** Home (reporting) currency for multi-currency support. */
    val homeCurrency: String = currency,
    /** Exchange rate used if originalCurrency differs from homeCurrency. */
    val conversionRateUsed: Double? = null,
    /** Original amount in originalCurrency before conversion. */
    val originalAmount: Double? = null
)
