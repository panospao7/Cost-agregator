package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val icon: String, // Emoji or simple string
    val color: String, // Hex color code
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false // If true, cannot be deleted (easily)
) {
    init {
        require(name.isNotBlank()) { "Category name cannot be blank" }
        require(name.length <= 50) { "Category name too long (max 50 chars)" }
        require(icon.length <= 10) { "Icon too long (max 10 chars)" }
        require(color.matches(Regex("^#[0-9A-Fa-f]{6}$"))) { 
            "Color must be valid hex code (e.g., #FF5733)" 
        }
    }
}
