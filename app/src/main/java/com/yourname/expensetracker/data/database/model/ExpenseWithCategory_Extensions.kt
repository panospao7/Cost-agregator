package com.yourname.expensetracker.data.database.model

import com.yourname.expensetracker.data.database.entity.TransactionType
import java.text.SimpleDateFormat
import java.util.*

/**
 * Extension properties for ExpenseWithCategory to provide formatted display values.
 */

// Date formatter with caching for performance
private val dateFormatCache = ThreadLocal<SimpleDateFormat>()

val ExpenseWithCategory.formattedDate: String
    get() {
        val formatter = dateFormatCache.get() ?: SimpleDateFormat(
            "HH:mm",
            Locale.getDefault()
        ).also { dateFormatCache.set(it) }
        
        return try {
            formatter.format(Date(expense.date))
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
        return "$prefix${expense.currency}${String.format(Locale.getDefault(), "%.2f", expense.amount)}"
    }
