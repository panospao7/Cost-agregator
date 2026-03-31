package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a group for sharing expenses (e.g., "Trip to Paris", "Roommates", "Family").
 */
@Entity(
    tableName = "expense_groups",
    indices = [
        Index(value = ["isActive"]),
        Index(value = ["createdAt"])
    ]
)
data class ExpenseGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,              // Group name (e.g., "Weekend Trip")
    val description: String? = null, // Optional description
    val defaultCurrency: String = "EUR",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val createdBy: String = "me"   // User who created the group
)
