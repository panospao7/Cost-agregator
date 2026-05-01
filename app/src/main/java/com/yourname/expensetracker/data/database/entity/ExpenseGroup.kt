package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
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
        Index(value = ["createdAt"]),
        Index(value = ["isActive", "createdAt"])
    ]
)
data class ExpenseGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,              // Group name (e.g., "Weekend Trip")
    val description: String? = null, // Optional description
    @ColumnInfo(defaultValue = "EUR") val defaultCurrency: String = "EUR",
    @ColumnInfo(defaultValue = "1") val isActive: Boolean = true,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "me") val createdBy: String = "me"   // User who created the group
)
