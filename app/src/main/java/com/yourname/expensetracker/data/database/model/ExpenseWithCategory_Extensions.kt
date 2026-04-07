package com.yourname.expensetracker.data.database.model

import com.yourname.expensetracker.data.database.entity.TransactionType
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Extension properties for ExpenseWithCategory to provide formatted display values.
 *
 * Note on [formattedAmount]: this extension uses [Expense.effectiveAmount] (ownership-adjusted)
 * to match the behaviour of the [ExpenseWithCategory.formattedAmount] member property. Both
 * surfaces must agree so callers cannot accidentally render raw posted amounts for shared rows.
 */

val ExpenseWithCategory.formattedDate: String
    get() {
        return try {
            Instant.ofEpochMilli(expense.date)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            "Unknown"
        }
    }

val ExpenseWithCategory.formattedAmount: String
    get() {
        val prefix = when (expense.transactionType) {
            TransactionType.PURCHASE, TransactionType.WITHDRAWAL -> "-"
            TransactionType.DEPOSIT -> "+"
            else -> ""
        }
        return "$prefix${expense.currency}${String.format(Locale.getDefault(), "%.2f", expense.effectiveAmount)}"
    }
