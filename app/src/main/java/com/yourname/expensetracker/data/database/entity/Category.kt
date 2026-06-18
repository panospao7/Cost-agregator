package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing an expense category.
 *
 * Categories are used to classify expenses (e.g. "Groceries", "Transport", "Utilities").
 * Each category has a name, an emoji icon, and a hex color for UI display.
 * Default categories (isDefault = true) cannot be deleted.
 *
 * ## Invariants
 * - Name must be non-blank and at most 50 characters.
 * - Icon must be at most 10 characters (typically a single emoji).
 * - Color must be a valid 6-digit hex code prefixed with '#'.
 */
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val icon: String, // Emoji or simple string
    val color: String, // Hex color code
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false // If true, cannot be deleted (easily)
) {
    /**
     * Normalized version of the name: trimmed and lowercased.
     * Used for case-insensitive uniqueness checks.
     */
    val normalizedName: String get() = name.trim().lowercase()

    init {
        require(name.isNotBlank()) { "Category name cannot be blank" }
        require(name.length <= 50) { "Category name too long (max 50 chars)" }
        require(icon.length <= 10) { "Icon too long (max 10 chars)" }
        require(color.matches(Regex("^#[0-9A-Fa-f]{6}$"))) { 
            "Color must be valid hex code (e.g., #FF5733)" 
        }
    }
}
