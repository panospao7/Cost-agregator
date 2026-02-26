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
    
    @Test(expected = IllegalArgumentException::class)
    fun `empty category name throws exception`() {
        Category(name = "", icon = "📦", color = "#FF5733")
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `blank category name throws exception`() {
        Category(name = "   ", icon = "📦", color = "#FF5733")
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `invalid color throws exception`() {
        Category(name = "Food", icon = "🍔", color = "invalid")
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `color without hash throws exception`() {
        Category(name = "Food", icon = "🍔", color = "FF5733")
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `icon too long throws exception`() {
        Category(name = "Food", icon = "too long icon", color = "#FF5733")
    }
    
    @Test
    fun `category name too long throws exception`() {
        try {
            Category(name = "a".repeat(51), icon = "🍔", color = "#FF5733")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("too long") == true)
        }
    }
}
