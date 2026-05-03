package com.yourname.expensetracker.data.database.model

import androidx.room.Embedded
import androidx.room.Relation
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.yourname.expensetracker.data.database.entity.TransactionType
import timber.log.Timber

/**
 * Optimized Room model for displaying transactions. 
 * Formatted strings are computed once when the object is instantiated from the DB,
 * preventing expensive re-calculation during LazyColumn scrolling.
 */
data class ExpenseWithCategory(
    @Embedded
    val expense: Expense,

    @Relation(
        parentColumn = "categoryId",
        entityColumn = "id"
    )
    val category: Category?
) {
    private companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM dd, HH:mm", Locale.getDefault())
    }

    val formattedDate: String by lazy {
        try {
            Instant.ofEpochMilli(expense.date)
                .atZone(ZoneId.systemDefault())
                .format(DATE_FORMATTER)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Human-readable amount string representing the user's effective (ownership-adjusted) spend.
     * Uses [Expense.effectiveAmount] so that shared and "not-mine" rows are reflected correctly.
     * Includes a polarity prefix (+/−) based on [Expense.transactionType] and places the
     * currency code before the numeric value for consistent presentation across all surfaces.
     * Raw posted amount is available via [expense.amount] when explicitly needed for reference.
     */
    val formattedAmount: String by lazy {
        val prefix = when (expense.transactionType) {
            TransactionType.PURCHASE, TransactionType.WITHDRAWAL -> "-"
            TransactionType.DEPOSIT -> "+"
            else -> ""
        }
        "$prefix${expense.currency}${String.format(java.util.Locale.getDefault(), "%.2f", expense.effectiveAmount)}"
    }

    val categoryColor: Long by lazy {
        try {
            category?.color?.let { android.graphics.Color.parseColor(it).toLong() } ?: android.graphics.Color.GRAY.toLong()
        } catch (e: Exception) {
            Timber.e(e, "Error parsing category color: ${category?.color}")
            android.graphics.Color.GRAY.toLong()
        }
    }
}
