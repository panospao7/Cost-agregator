package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.TransactionType
import java.util.Locale
import javax.inject.Inject

class AccountingExportPolicy @Inject constructor() {

    data class GlobalDatasetValidation(
        val rowCount: Int,
        val distinctCurrencies: Set<String>,
        val transactionTypes: Set<TransactionType>,
        val isSingleCurrency: Boolean,
        val isPurchaseOnly: Boolean,
        val errors: List<String>
    )

    fun validateAccountingDataset(transactions: List<ExportTransaction>, exportName: String) {
        requireSingleCurrency(transactions, exportName)
        requirePurchaseTransactions(transactions, exportName)
    }

    fun validateGlobalDataset(transactions: List<ExportTransaction>, exportName: String): GlobalDatasetValidation {
        val currencies = transactions
            .map { it.currency.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toSet()

        val types = transactions
            .map { it.transactionType }
            .distinct()
            .toSet()

        val isSingleCurrency = currencies.size <= 1
        val isPurchaseOnly = types.all { it == TransactionType.PURCHASE }
        val errors = mutableListOf<String>()

        if (!isSingleCurrency) {
            errors.add(
                "$exportName export requires a single-currency dataset. " +
                    "Found: ${currencies.joinToString(", ")}, rows=${transactions.size}"
            )
        }
        if (!isPurchaseOnly) {
            errors.add(
                "$exportName export supports PURCHASE transactions only. " +
                    "Found: ${types.joinToString(", ")}, rows=${transactions.size}"
            )
        }

        return GlobalDatasetValidation(
            rowCount = transactions.size,
            distinctCurrencies = currencies,
            transactionTypes = types,
            isSingleCurrency = isSingleCurrency,
            isPurchaseOnly = isPurchaseOnly,
            errors = errors
        )
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
