package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.TransactionType
import java.util.Locale
import javax.inject.Inject

class AccountingExportPolicy @Inject constructor() {

    companion object {
        /** Default max rows for validation — prevents OOM on large datasets. */
        const val DEFAULT_MAX_VALIDATION_ROWS: Int = 10_000
    }

    data class GlobalDatasetValidation(
        val rowCount: Int,
        val distinctCurrencies: Set<String>,
        val transactionTypes: Set<TransactionType>,
        val isSingleCurrency: Boolean,
        val isPurchaseOnly: Boolean,
        val errors: List<String>
    )

    /**
     * Validates the dataset for accounting export requirements (single currency,
     * purchase-only transactions).
     *
     * @param transactions The transactions to validate.
     * @param exportName Display name of the export format (for error messages).
     * @param maxValidationRows Maximum number of rows to examine (default [DEFAULT_MAX_VALIDATION_ROWS]).
     *                          Prevents OOM on very large datasets by only checking the first N rows.
     */
    fun validateAccountingDataset(
        transactions: List<ExportTransaction>,
        exportName: String,
        maxValidationRows: Int = DEFAULT_MAX_VALIDATION_ROWS
    ) {
        val subset = if (transactions.size <= maxValidationRows) transactions else transactions.take(maxValidationRows)
        requireSingleCurrency(subset, exportName)
        requirePurchaseTransactions(subset, exportName)
    }

    /**
     * Validates the full dataset for accounting export and returns a detailed report.
     *
     * @param transactions The transactions to validate.
     * @param exportName Display name of the export format (for error messages).
     * @param maxValidationRows Maximum number of rows to examine (default [DEFAULT_MAX_VALIDATION_ROWS]).
     */
    fun validateGlobalDataset(
        transactions: List<ExportTransaction>,
        exportName: String,
        maxValidationRows: Int = DEFAULT_MAX_VALIDATION_ROWS
    ): GlobalDatasetValidation {
        val subset = if (transactions.size <= maxValidationRows) transactions else transactions.take(maxValidationRows)
        val currencies = subset
            .map { it.currency.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toSet()

        val types = subset
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
            rowCount = subset.size,
            distinctCurrencies = currencies,
            transactionTypes = types,
            isSingleCurrency = isSingleCurrency,
            isPurchaseOnly = isPurchaseOnly,
            errors = errors
        )
    }

    /**
     * Requires all transactions in the dataset to share a single currency.
     *
     * @param maxValidationRows Maximum number of rows to examine (default [DEFAULT_MAX_VALIDATION_ROWS]).
     */
    fun requireSingleCurrency(
        transactions: List<ExportTransaction>,
        exportName: String,
        maxValidationRows: Int = DEFAULT_MAX_VALIDATION_ROWS
    ) {
        val subset = if (transactions.size <= maxValidationRows) transactions else transactions.take(maxValidationRows)
        val currencies = subset
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

    /**
     * Requires all transactions in the dataset to be of type PURCHASE.
     *
     * @param maxValidationRows Maximum number of rows to examine (default [DEFAULT_MAX_VALIDATION_ROWS]).
     */
    fun requirePurchaseTransactions(
        transactions: List<ExportTransaction>,
        exportName: String,
        maxValidationRows: Int = DEFAULT_MAX_VALIDATION_ROWS
    ) {
        val subset = if (transactions.size <= maxValidationRows) transactions else transactions.take(maxValidationRows)
        val unsupportedTypes = subset
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
