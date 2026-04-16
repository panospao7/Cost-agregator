package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.TransactionType
import java.util.Locale
import javax.inject.Inject

class AccountingExportPolicy @Inject constructor() {

    fun validateAccountingDataset(transactions: List<ExportTransaction>, exportName: String) {
        requireSingleCurrency(transactions, exportName)
        requirePurchaseTransactions(transactions, exportName)
    }

    fun requireSingleCurrency(transactions: List<ExportTransaction>, exportName: String) {
        val currencies = transactions
            .map { it.currency.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()

        if (currencies.size > 1) {
            throw IllegalArgumentException(
                "$exportName export requires a single-currency dataset. " +
                    "Filter the export to one currency before exporting. Found: ${currencies.joinToString(", ")}."
            )
        }
    }

    fun requirePurchaseTransactions(transactions: List<ExportTransaction>, exportName: String) {
        val unsupportedTypes = transactions
            .map { it.transactionType }
            .filter { it != TransactionType.PURCHASE }
            .distinct()

        if (unsupportedTypes.isNotEmpty()) {
            throw IllegalArgumentException(
                "$exportName export supports PURCHASE transactions only. " +
                    "Remove unsupported transaction types before exporting: ${unsupportedTypes.joinToString(", ")}."
            )
        }
    }
}
