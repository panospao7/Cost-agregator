package com.yourname.expensetracker.domain.model

import org.junit.Assert.*
import org.junit.Test

class CategoryBreakdownTest {

    @Test
    fun `CategoryBreakdown stores values correctly`() {
        val categoryInfo = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722",
            isIncome = false
        )

        val breakdown = CategoryBreakdown(
            category = categoryInfo,
            totalAmount = 250.0,
            transactionCount = 10,
            percentageOfTotal = 25.0,
            periodLabel = "Jan"
        )

        assertEquals(250.0, breakdown.totalAmount, 0.01)
        assertEquals(10, breakdown.transactionCount)
        assertEquals(25.0, breakdown.percentageOfTotal, 0.01)
        assertEquals("Jan", breakdown.periodLabel)
        assertEquals(categoryInfo, breakdown.category)
    }

    @Test
    fun `CategoryBreakdown is immutable`() {
        val categoryInfo = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722"
        )

        val breakdown = CategoryBreakdown(
            category = categoryInfo,
            totalAmount = 250.0,
            transactionCount = 10,
            percentageOfTotal = 25.0,
            periodLabel = "Jan"
        )

        assertTrue(breakdown is CategoryBreakdown)
    }

    @Test
    fun `CategoryBreakdown copy preserves values`() {
        val categoryInfo = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722"
        )

        val original = CategoryBreakdown(
            category = categoryInfo,
            totalAmount = 250.0,
            transactionCount = 10,
            percentageOfTotal = 25.0,
            periodLabel = "Jan"
        )

        val copy = original.copy()

        assertEquals(original.category, copy.category)
        assertEquals(original.totalAmount, copy.totalAmount, 0.01)
        assertEquals(original.transactionCount, copy.transactionCount)
        assertEquals(original.percentageOfTotal, copy.percentageOfTotal, 0.01)
        assertEquals(original.periodLabel, copy.periodLabel)
    }

    @Test
    fun `CategoryBreakdown copy with modified values`() {
        val categoryInfo = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722"
        )

        val original = CategoryBreakdown(
            category = categoryInfo,
            totalAmount = 250.0,
            transactionCount = 10,
            percentageOfTotal = 25.0,
            periodLabel = "Jan"
        )

        val modified = original.copy(
            totalAmount = 300.0,
            percentageOfTotal = 30.0
        )

        assertEquals(300.0, modified.totalAmount, 0.01)
        assertEquals(30.0, modified.percentageOfTotal, 0.01)
        assertEquals("Jan", modified.periodLabel)
    }

    @Test
    fun `CategoryBreakdown equals and hashCode`() {
        val categoryInfo1 = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722"
        )

        val categoryInfo2 = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722"
        )

        val breakdown1 = CategoryBreakdown(
            category = categoryInfo1,
            totalAmount = 250.0,
            transactionCount = 10,
            percentageOfTotal = 25.0,
            periodLabel = "Jan"
        )

        val breakdown2 = CategoryBreakdown(
            category = categoryInfo2,
            totalAmount = 250.0,
            transactionCount = 10,
            percentageOfTotal = 25.0,
            periodLabel = "Jan"
        )

        assertEquals(breakdown1, breakdown2)
        assertEquals(breakdown1.hashCode(), breakdown2.hashCode())
    }

    @Test
    fun `CategoryBreakdown toString contains key fields`() {
        val categoryInfo = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722"
        )

        val breakdown = CategoryBreakdown(
            category = categoryInfo,
            totalAmount = 250.0,
            transactionCount = 10,
            percentageOfTotal = 25.0,
            periodLabel = "Jan"
        )

        val str = breakdown.toString()
        assertTrue(str.contains("Groceries"))
        assertTrue(str.contains("250.0"))
        assertTrue(str.contains("Jan"))
    }

    @Test
    fun `CategoryBreakdown with zero values`() {
        val categoryInfo = CategoryInfo(
            id = 1L,
            name = "Empty",
            icon = "?",
            color = "#808080"
        )

        val breakdown = CategoryBreakdown(
            category = categoryInfo,
            totalAmount = 0.0,
            transactionCount = 0,
            percentageOfTotal = 0.0,
            periodLabel = "Feb"
        )

        assertEquals(0.0, breakdown.totalAmount, 0.01)
        assertEquals(0, breakdown.transactionCount)
        assertEquals(0.0, breakdown.percentageOfTotal, 0.01)
    }

    @Test
    fun `CategoryBreakdown with different categories`() {
        val groceriesInfo = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722"
        )

        val entertainmentInfo = CategoryInfo(
            id = 2L,
            name = "Entertainment",
            icon = "\uD83C\uDFAC",
            color = "#2196F3"
        )

        val groceriesBreakdown = CategoryBreakdown(
            category = groceriesInfo,
            totalAmount = 500.0,
            transactionCount = 20,
            percentageOfTotal = 50.0,
            periodLabel = "Mar"
        )

        val entertainmentBreakdown = CategoryBreakdown(
            category = entertainmentInfo,
            totalAmount = 300.0,
            transactionCount = 5,
            percentageOfTotal = 30.0,
            periodLabel = "Mar"
        )

        assertEquals("Groceries", groceriesBreakdown.category.name)
        assertEquals("Entertainment", entertainmentBreakdown.category.name)
        assertNotEquals(groceriesBreakdown, entertainmentBreakdown)
    }

    @Test
    fun `CategoryBreakdown with different period labels`() {
        val categoryInfo = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722"
        )

        val janBreakdown = CategoryBreakdown(
            category = categoryInfo,
            totalAmount = 250.0,
            transactionCount = 10,
            percentageOfTotal = 25.0,
            periodLabel = "Jan"
        )

        val febBreakdown = CategoryBreakdown(
            category = categoryInfo,
            totalAmount = 300.0,
            transactionCount = 12,
            percentageOfTotal = 30.0,
            periodLabel = "Feb"
        )

        assertEquals("Jan", janBreakdown.periodLabel)
        assertEquals("Feb", febBreakdown.periodLabel)
        assertNotEquals(janBreakdown, febBreakdown)
    }

    @Test
    fun `CategoryBreakdown with income category`() {
        val incomeCategoryInfo = CategoryInfo(
            id = 100L,
            name = "Salary",
            icon = "\uD83D\uDCB0",
            color = "#4CAF50",
            isIncome = true
        )

        val incomeBreakdown = CategoryBreakdown(
            category = incomeCategoryInfo,
            totalAmount = 5000.0,
            transactionCount = 1,
            percentageOfTotal = 100.0,
            periodLabel = "Jan"
        )

        assertTrue(incomeBreakdown.category.isIncome)
        assertEquals(5000.0, incomeBreakdown.totalAmount, 0.01)
    }

    @Test
    fun `CategoryInfo stores all values correctly`() {
        val categoryInfo = CategoryInfo(
            id = 42L,
            name = "Transportation",
            icon = "\uD83D\uDE8C",
            color = "#9C27B0",
            isIncome = false
        )

        assertEquals(42L, categoryInfo.id)
        assertEquals("Transportation", categoryInfo.name)
        assertEquals("\uD83D\uDE8C", categoryInfo.icon)
        assertEquals("#9C27B0", categoryInfo.color)
        assertFalse(categoryInfo.isIncome)
    }

    @Test
    fun `CategoryInfo default isIncome is false`() {
        val categoryInfo = CategoryInfo(
            id = 1L,
            name = "Test",
            icon = "?",
            color = "#000000"
        )

        assertFalse(categoryInfo.isIncome)
    }

    @Test
    fun `CategoryInfo equals and hashCode`() {
        val info1 = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722"
        )

        val info2 = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722"
        )

        assertEquals(info1, info2)
        assertEquals(info1.hashCode(), info2.hashCode())
    }

    @Test
    fun `CategoryInfo with different ids are not equal`() {
        val info1 = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722"
        )

        val info2 = CategoryInfo(
            id = 2L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722"
        )

        assertNotEquals(info1, info2)
    }

    @Test
    fun `CategoryInfo toString contains key fields`() {
        val categoryInfo = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722"
        )

        val str = categoryInfo.toString()
        assertTrue(str.contains("Groceries"))
        assertTrue(str.contains("1"))
    }

    @Test
    fun `CategoryInfo copy preserves values`() {
        val original = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722",
            isIncome = false
        )

        val copy = original.copy()

        assertEquals(original.id, copy.id)
        assertEquals(original.name, copy.name)
        assertEquals(original.icon, copy.icon)
        assertEquals(original.color, copy.color)
        assertEquals(original.isIncome, copy.isIncome)
    }

    @Test
    fun `CategoryInfo copy with modified values`() {
        val original = CategoryInfo(
            id = 1L,
            name = "Groceries",
            icon = "\uD83D\uDED2",
            color = "#FF5722"
        )

        val modified = original.copy(
            name = "Food",
            color = "#00BCD4"
        )

        assertEquals("Food", modified.name)
        assertEquals("#00BCD4", modified.color)
        assertEquals(1L, modified.id)
    }

    @Test
    fun `CategoryBreakdown percentage can be 100`() {
        val categoryInfo = CategoryInfo(
            id = 1L,
            name = "Only",
            icon = "?",
            color = "#000"
        )

        val breakdown = CategoryBreakdown(
            category = categoryInfo,
            totalAmount = 100.0,
            transactionCount = 1,
            percentageOfTotal = 100.0,
            periodLabel = "Jan"
        )

        assertEquals(100.0, breakdown.percentageOfTotal, 0.01)
    }

    @Test
    fun `CategoryBreakdown percentage can be fractional`() {
        val categoryInfo = CategoryInfo(
            id = 1L,
            name = "Small",
            icon = "?",
            color = "#000"
        )

        val breakdown = CategoryBreakdown(
            category = categoryInfo,
            totalAmount = 33.33,
            transactionCount = 2,
            percentageOfTotal = 33.33,
            periodLabel = "Jan"
        )

        assertEquals(33.33, breakdown.percentageOfTotal, 0.01)
    }
}
