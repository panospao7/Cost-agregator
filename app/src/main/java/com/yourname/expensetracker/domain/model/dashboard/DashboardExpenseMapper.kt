package com.yourname.expensetracker.domain.model.dashboard

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator

fun DashboardExpense.toEntityExpense(): Expense {
    val txType = when (transactionType) {
        DashboardTransactionType.PURCHASE -> TransactionType.PURCHASE
        DashboardTransactionType.WITHDRAWAL -> TransactionType.WITHDRAWAL
        DashboardTransactionType.TRANSFER -> TransactionType.TRANSFER
        DashboardTransactionType.DEPOSIT -> TransactionType.DEPOSIT
        DashboardTransactionType.UNKNOWN -> TransactionType.UNKNOWN
    }

    return Expense(
        id = id,
        amount = amount,
        merchant = merchant,
        transactionType = txType,
        date = date,
        categoryId = categoryId,
        isNotMine = isNotMine,
        isManualEntry = isManualEntry,
        merchantKey = MerchantKeyGenerator.generate(merchant)
    )
}
