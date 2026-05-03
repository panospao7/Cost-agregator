package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod

/**
 * Shared accounting export mapper.
 *
 * BAK-14: All Double values are guarded with [isFinite] before serialization
 * to prevent NaN or Infinite values from corrupting the export output.
 */
object ExpenseExportMapper {
    fun map(expense: Expense): ExportTransaction {
        return ExportTransaction(
            id = expense.id,
            date = expense.date,
            // BAK-14: Guard against NaN/Infinite values that would produce
            // corrupt export rows (e.g. "NaN" or "Infinity" strings in CSV/IIF).
            amount = expense.effectiveAmount.takeIf { it.isFinite() } ?: 0.0,
            merchant = expense.merchant,
            notes = expense.notes,
            categoryId = expense.categoryId,
            currency = expense.currency,
            transactionType = expense.transactionType,
            sourceAccountName = expense.paymentMethod.toExportSourceAccountName(),
            originalCurrency = expense.currency,
            // BAK-14: Guard original amount as well — corruption could come
            // from division by zero or failed currency conversions.
            originalAmount = expense.amount.takeIf { it.isFinite() }
        )
    }
}

fun Expense.toExportTransaction(): ExportTransaction = ExpenseExportMapper.map(this)

internal fun PaymentMethod.toExportSourceAccountName(): String = when (this) {
    PaymentMethod.CARD -> "Credit Card"
    PaymentMethod.CASH -> "Cash"
    PaymentMethod.BANK_TRANSFER -> "Bank Account"
    PaymentMethod.UNKNOWN -> "Unknown Funding Source"
}
