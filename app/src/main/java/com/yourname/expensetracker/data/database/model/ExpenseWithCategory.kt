package com.yourname.expensetracker.data.database.model

import androidx.room.Embedded
import androidx.room.Relation
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import java.text.SimpleDateFormat
import java.util.*

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
    // Pre-computed formatting for UI efficiency
    val formattedDate: String by lazy {
        SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(expense.date))
    }

    val formattedAmount: String by lazy {
        String.format("%.2f %s", expense.amount, expense.currency)
    }
}
