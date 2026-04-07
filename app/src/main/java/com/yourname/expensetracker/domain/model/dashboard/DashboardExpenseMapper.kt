package com.yourname.expensetracker.domain.model.dashboard

import com.yourname.expensetracker.domain.model.TransactionSummary

/**
 * Maps a [DashboardExpense] to a domain-safe [TransactionSummary] DTO.
 *
 * The returned [TransactionSummary] carries all fields needed for block-party previews,
 * spending-pace calculations, and downstream domain analytics without crossing the
 * data-layer boundary.
 */
fun DashboardExpense.toTransactionSummary(): TransactionSummary {
    return TransactionSummary(
        id = id,
        amount = amount,
        effectiveAmount = effectiveAmount,
        merchant = merchant,
        date = date,
        categoryId = categoryId
    )
}

