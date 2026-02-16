package com.yourname.expensetracker.data.database.model

import com.yourname.expensetracker.data.database.entity.TransactionType
import java.text.SimpleDateFormat
import java.util.*

/**
 * Extension properties for ExpenseWithCategory to provide formatted display values.
 * Add this to your existing ExpenseWithCategory.kt file or as a separate extension file.
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
            TransactionType.DEPOSIT, TransactionType.REFUND -> "+"
            else -> ""
        }
        return "$prefix${expense.currency}${String.format(Locale.getDefault(), "%.2f", expense.amount)}"
    }

/**
 * Also add this helper function to NotificationRepository if it doesn't exist:
 * 
 * suspend fun getExpenseCountForPeriod(startMs: Long, endMs: Long): Int {
 *     return expenseDao.getCountForPeriod(startMs, endMs)
 * }
 */
