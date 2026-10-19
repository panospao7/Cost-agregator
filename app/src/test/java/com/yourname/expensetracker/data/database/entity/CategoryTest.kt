package com.yourname.expensetracker.data.database.entity

import org.junit.Assert.*
import org.junit.Test

class CategoryTest {

    @Test
    fun `valid category creates successfully`() {
        val category = Category(
            name = "Food",
            icon = "🍔",
            color = "#FF5733"
        )
        assertEquals("Food", category.name)
    }

    @Test
    fun `invalid category inputs throw IllegalArgumentException with expected message`() {
        // row name to (name, icon, color, expected message fragment)
        data class Row(
            val label: String,
            val name: String,
            val icon: String,
            val color: String,
            val messageFragment: String?
        )
        val rows = listOf(
            Row("empty name", "", "📦", "#FF5733", null),
            Row("blank name", "   ", "📦", "#FF5733", null),
            Row("invalid color", "Food", "🍔", "invalid", null),
            Row("color without hash", "Food", "🍔", "FF5733", null),
            Row("icon too long", "Food", "too long icon", "#FF5733", null),
            Row("name too long", "a".repeat(51), "🍔", "#FF5733", "too long")
        )
        assertEquals("invalid category rows size", 6, rows.size)
        rows.forEach { row ->
            try {
                Category(name = row.name, icon = row.icon, color = row.color)
                fail("Row '${row.label}': expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                row.messageFragment?.let {
                    assertTrue(
                        "Row '${row.label}': message should contain '$it' but was '${e.message}'",
                        e.message?.contains(it) == true
                    )
                }
            }
        }
    }
}
