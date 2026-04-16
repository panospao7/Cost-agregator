package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod

/**
 * Shared accounting export mapper.
 */
object ExpenseExportMapper {
    fun map(expense: Expense): ExportTransaction {
        return ExportTransaction(
            id = expense.id,
            date = expense.date,
            amount = expense.effectiveAmount,
            merchant = expense.merchant,
            notes = expense.notes,
            categoryId = expense.categoryId,
            currency = expense.currency,
            transactionType = expense.transactionType,
            sourceAccountName = expense.paymentMethod.toExportSourceAccountName()
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
