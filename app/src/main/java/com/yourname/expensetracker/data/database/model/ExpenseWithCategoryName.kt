package com.yourname.expensetracker.data.database.model

import androidx.room.ColumnInfo
import com.yourname.expensetracker.data.database.entity.Expense

data class ExpenseWithCategoryName(
    @androidx.room.Embedded
    val expense: Expense,
    
    @ColumnInfo(name = "categoryName")
    val categoryName: String?
)
