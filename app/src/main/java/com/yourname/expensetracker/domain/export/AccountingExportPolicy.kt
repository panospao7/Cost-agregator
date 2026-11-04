package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.TransactionType
import java.util.Locale
import javax.inject.Inject

enum class AccountingExportViolation {
    SINGLE_CURRENCY,
    PURCHASE_ONLY
}

class AccountingExportPolicyViolationException(
    val exportName: String,
    val violation: AccountingExportViolation,
    val detailValues: List<String>
) : IllegalArgumentException(
    when (violation) {
        AccountingExportViolation.SINGLE_CURRENCY ->
            "$exportName export requires a single-currency dataset. " +
                "Filter the export to one currency before exporting. Found: ${detailValues.joinToString(", ")}."
        AccountingExportViolation.PURCHASE_ONLY ->
            "$exportName export supports PURCHASE transactions only. " +
                "Remove unsupported transaction types before exporting: ${detailValues.joinToString(", ")}."
    }
)

class AccountingExportEmptyDatasetException(
    val exportName: String
) : IllegalArgumentException("ACCOUNTING_EXPORT_EMPTY_DATASET")

class AccountingExportPolicy @Inject constructor() {

    /**
     * DR-08: every row that will be written is validated; there is no row cap. Rows the user
     * bears nothing of ([bearsNoAmount], e.g. not-mine) are skipped by the exporters, so they
     * are not validated either (a USD not-mine row cannot make an EUR export fail).
     */
    private fun exportedRows(transactions: List<ExportTransaction>): List<ExportTransaction> =
        transactions.filterNot { it.bearsNoAmount() }

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
     */
    fun validateAccountingDataset(
        transactions: List<ExportTransaction>,
        exportName: String
    ) {
        requireSingleCurrency(transactions, exportName)
        requirePurchaseTransactions(transactions, exportName)
    }

    /**
     * Validates the full dataset for accounting export and returns a detailed report.
     *
     * @param transactions The transactions to validate.
     * @param exportName Display name of the export format (for error messages).
     */
    fun validateGlobalDataset(
        transactions: List<ExportTransaction>,
        exportName: String
    ): GlobalDatasetValidation {
        val subset = exportedRows(transactions)
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
     * Requires all exported transactions in the dataset to share a single currency (every row).
     */
    fun requireSingleCurrency(
        transactions: List<ExportTransaction>,
        exportName: String
    ) {
        val subset = exportedRows(transactions)
        val currencies = subset
            .map { it.currency.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()

        if (currencies.size > 1) {
            throw AccountingExportPolicyViolationException(
                exportName = exportName,
                violation = AccountingExportViolation.SINGLE_CURRENCY,
                detailValues = currencies
            )
        }
    }

    /**
     * Requires all exported transactions in the dataset to be of type PURCHASE (every row).
     */
    fun requirePurchaseTransactions(
        transactions: List<ExportTransaction>,
        exportName: String
    ) {
        val subset = exportedRows(transactions)
        val unsupportedTypes = subset
            .map { it.transactionType }
            .filter { it != TransactionType.PURCHASE }
            .distinct()

        if (unsupportedTypes.isNotEmpty()) {
            throw AccountingExportPolicyViolationException(
                exportName = exportName,
                violation = AccountingExportViolation.PURCHASE_ONLY,
                detailValues = unsupportedTypes.map { it.name }
            )
        }
    }
}
