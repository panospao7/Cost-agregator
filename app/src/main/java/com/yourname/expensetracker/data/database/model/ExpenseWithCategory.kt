package com.yourname.expensetracker.data.database.model

import androidx.room.Embedded
import androidx.room.Relation
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM dd, HH:mm")
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

    val formattedAmount: String by lazy {
        String.format(java.util.Locale.US, "%.2f %s", expense.amount, expense.currency)
    }

    val categoryColor: Long by lazy {
        try {
            category?.color?.let { android.graphics.Color.parseColor(it).toLong() } ?: android.graphics.Color.GRAY.toLong()
        } catch (e: Exception) {
            android.util.Log.e("ExpenseWithCategory", "Error parsing category color: ${category?.color}", e)
            android.graphics.Color.GRAY.toLong()
        }
    }
}
