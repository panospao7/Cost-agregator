package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val icon: String, // Emoji or simple string
    val color: String, // Hex color code
    val isDefault: Boolean = false // If true, cannot be deleted (easily)
)
