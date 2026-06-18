package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.TEST_CATEGORIES
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.startOfMonth
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CategoryInsightEngineTest {

    private val engine = CategoryInsightEngine()

    @Test
    fun `calculate returns expected golden march category totals and percentages`() {
        val currentMonth = MonthPeriod(
            year = 2026,
            month = 2,
            startMs = startOfMonth(2026, 3),
            endMs = startOfMonth(2026, 4)
        )
        val previousMonth = MonthPeriod(
            year = 2026,
            month = 1,
            startMs = startOfMonth(2026, 2),
            endMs = startOfMonth(2026, 3)
        )
        val categoryMap = (TEST_CATEGORIES + Category(
            id = 99L,
            name = "Rent",
            icon = "🏠",
            color = "#607D8B",
            isDefault = true
        )).toAnalyticsCategoryMap()

        val insights = engine.calculate(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            categoryMap = categoryMap,
            allExpenses = goldenMarchAndFebruaryExpensesWithRentCategory().map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        val groceries = insights.find { it.category.id == 2L }
        val dining = insights.find { it.category.id == 1L }
        val rent = insights.find { it.category.id == 99L }

        assertNotNull(groceries)
        assertNotNull(dining)
        assertNotNull(rent)

        assertApproxEquals(136.10, groceries!!.currentTotal, 0.01)
        assertApproxEquals(10.60f, groceries.percentageOfTotal, 0.01f)
        assertApproxEquals(147.45f, groceries.changeFromPrevious!!, 0.01f)
        assertEquals(3, groceries.currentCount)

        assertApproxEquals(46.80, dining!!.currentTotal, 0.01)
        assertApproxEquals(3.65f, dining.percentageOfTotal, 0.01f)
        assertApproxEquals(56.0f, dining.changeFromPrevious!!, 0.01f)
        assertEquals(3, dining.currentCount)

        assertApproxEquals(800.00, rent!!.currentTotal, 0.01)
        assertApproxEquals(62.33f, rent.percentageOfTotal, 0.01f)
        assertApproxEquals(0.0f, rent.changeFromPrevious!!, 0.01f)
        assertEquals(1, rent.currentCount)

        val percentageSum = insights.sumOf { it.percentageOfTotal.toDouble() }
        assertApproxEquals(100.0, percentageSum, 0.01)
    }

    @Test
    fun `calculate returns uncategorized fallback for missing category mapping`() {
        val currentMonth = MonthPeriod(
            year = 2026,
            month = 2,
            startMs = startOfMonth(2026, 3),
            endMs = startOfMonth(2026, 4)
        )
        val expenses = listOf(
            createExpense(
                date = "2026-03-06",
                amount = 120.0,
                merchant = "Unknown Category Merchant",
                id = 501L
            ).copy(categoryId = 777L)
        )

        val insights = engine.calculate(
            currentMonth = currentMonth,
            previousMonth = null,
            categoryMap = TEST_CATEGORIES.toAnalyticsCategoryMap(),
            allExpenses = expenses.map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        assertEquals(1, insights.size)
        assertEquals("Uncategorized", insights.first().category.name)
        assertApproxEquals(120.0, insights.first().currentTotal, 0.01)
        assertApproxEquals(100.0f, insights.first().percentageOfTotal, 0.01f)
    }

    @Test
    fun `calculate with empty expenses returns empty insights`() {
        val currentMonth = MonthPeriod(
            year = 2026,
            month = 2,
            startMs = startOfMonth(2026, 3),
            endMs = startOfMonth(2026, 4)
        )

        val insights = engine.calculate(
            currentMonth = currentMonth,
            previousMonth = null,
            categoryMap = TEST_CATEGORIES.toAnalyticsCategoryMap(),
            allExpenses = emptyList(),
            displayCurrency = "EUR"
        )

        assertTrue(insights.isEmpty())
    }

    // ========== A.10 Batch 7 — Purchase-only lock-in tests ==========

    @Test
    fun `calculate excludes deposits from category totals`() {
        val currentMonth = MonthPeriod(
            year = 2026,
            month = 2,
            startMs = startOfMonth(2026, 3),
            endMs = startOfMonth(2026, 4)
        )

        val expenses = listOf(
            createExpense("2026-03-05", 50.0, merchant = "Grocery Store", category = "groceries", id = 201L),
            createExpense("2026-03-10", 2500.0, type = TransactionType.DEPOSIT, merchant = "Salary", id = 202L),
            createExpense("2026-03-15", 30.0, merchant = "Restaurant", category = "dining", id = 203L)
        )

        val insights = engine.calculate(
            currentMonth = currentMonth,
            previousMonth = null,
            categoryMap = TEST_CATEGORIES.toAnalyticsCategoryMap(),
            allExpenses = expenses.map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        // Only PURCHASE transactions should be included
        val totalAcrossInsights = insights.sumOf { it.currentTotal }
        assertApproxEquals(80.0, totalAcrossInsights, 0.01)

        // Deposit should not appear in any category
        assertTrue(insights.none { it.currentTotal >= 2500.0 })
    }

    @Test
    fun `calculate excludes transfers from category totals`() {
        val currentMonth = MonthPeriod(
            year = 2026,
            month = 2,
            startMs = startOfMonth(2026, 3),
            endMs = startOfMonth(2026, 4)
        )

        val expenses = listOf(
            createExpense("2026-03-05", 50.0, merchant = "Grocery Store", category = "groceries", id = 301L),
            createExpense("2026-03-12", 500.0, type = TransactionType.TRANSFER, merchant = "Bank Transfer", id = 302L),
            createExpense("2026-03-20", 25.0, merchant = "Coffee Shop", category = "dining", id = 303L)
        )

        val insights = engine.calculate(
            currentMonth = currentMonth,
            previousMonth = null,
            categoryMap = TEST_CATEGORIES.toAnalyticsCategoryMap(),
            allExpenses = expenses.map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        val totalAcrossInsights = insights.sumOf { it.currentTotal }
        assertApproxEquals(75.0, totalAcrossInsights, 0.01)

        assertTrue(insights.none { it.currentTotal >= 500.0 })
    }

    @Test
    fun `calculate excludes withdrawals from category totals`() {
        val currentMonth = MonthPeriod(
            year = 2026,
            month = 2,
            startMs = startOfMonth(2026, 3),
            endMs = startOfMonth(2026, 4)
        )

        val expenses = listOf(
            createExpense("2026-03-05", 100.0, merchant = "Supermarket", category = "groceries", id = 401L),
            createExpense("2026-03-10", 200.0, type = TransactionType.WITHDRAWAL, merchant = "ATM", id = 402L)
        )

        val insights = engine.calculate(
            currentMonth = currentMonth,
            previousMonth = null,
            categoryMap = TEST_CATEGORIES.toAnalyticsCategoryMap(),
            allExpenses = expenses.map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        assertEquals(1, insights.size)
        assertApproxEquals(100.0, insights.first().currentTotal, 0.01)
        assertApproxEquals(100.0f, insights.first().percentageOfTotal, 0.01f)
    }

    @Test
    fun `calculate mixed types upstream yields purchase-only percentages summing to 100`() {
        val currentMonth = MonthPeriod(
            year = 2026,
            month = 2,
            startMs = startOfMonth(2026, 3),
            endMs = startOfMonth(2026, 4)
        )

        val expenses = listOf(
            // Purchases — should be counted
            createExpense("2026-03-01", 200.0, merchant = "Rent", id = 501L).copy(categoryId = 5L),
            createExpense("2026-03-05", 60.0, merchant = "Lidl", category = "groceries", id = 502L),
            createExpense("2026-03-10", 40.0, merchant = "Restaurant", category = "dining", id = 503L),
            // Non-purchases — should be excluded
            createExpense("2026-03-15", 3000.0, type = TransactionType.DEPOSIT, merchant = "Salary", id = 504L),
            createExpense("2026-03-17", 500.0, type = TransactionType.TRANSFER, merchant = "Savings", id = 505L),
            createExpense("2026-03-20", 100.0, type = TransactionType.WITHDRAWAL, merchant = "ATM", id = 506L),
            createExpense("2026-03-25", 75.0, type = TransactionType.UNKNOWN, merchant = "Unknown Txn", id = 507L)
        )

        val insights = engine.calculate(
            currentMonth = currentMonth,
            previousMonth = null,
            categoryMap = TEST_CATEGORIES.toAnalyticsCategoryMap(),
            allExpenses = expenses.map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        // Total from purchases: 200 + 60 + 40 = 300
        val totalAcrossInsights = insights.sumOf { it.currentTotal }
        assertApproxEquals(300.0, totalAcrossInsights, 0.01)

        // Transaction count from purchases only
        val totalCount = insights.sumOf { it.currentCount }
        assertEquals(3, totalCount)

        // Percentages must sum to 100
        val percentageSum = insights.sumOf { it.percentageOfTotal.toDouble() }
        assertApproxEquals(100.0, percentageSum, 0.01)
    }

    @Test
    fun `calculate excludes non-purchase types from previous month comparison`() {
        val currentMonth = MonthPeriod(
            year = 2026,
            month = 2,
            startMs = startOfMonth(2026, 3),
            endMs = startOfMonth(2026, 4)
        )
        val previousMonth = MonthPeriod(
            year = 2026,
            month = 1,
            startMs = startOfMonth(2026, 2),
            endMs = startOfMonth(2026, 3)
        )

        val expenses = listOf(
            // Current month purchases
            createExpense("2026-03-05", 100.0, merchant = "Grocery Store", category = "groceries", id = 601L),
            // Previous month: mix of purchases and deposits
            createExpense("2026-02-05", 80.0, merchant = "Grocery Store", category = "groceries", id = 602L),
            createExpense("2026-02-15", 5000.0, type = TransactionType.DEPOSIT, merchant = "Salary", category = "groceries", id = 603L)
        )

        val insights = engine.calculate(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            categoryMap = TEST_CATEGORIES.toAnalyticsCategoryMap(),
            allExpenses = expenses.map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        val groceries = insights.find { it.category.id == 2L }
        assertNotNull(groceries)

        // Previous total should only include the 80.0 purchase, not the 5000.0 deposit
        assertApproxEquals(80.0, groceries!!.previousTotal!!, 0.01)

        // Change from previous: (100 - 80) / 80 * 100 = 25%
        assertApproxEquals(25.0f, groceries.changeFromPrevious!!, 0.01f)
    }

    @Test
    fun `calculate with only non-purchase transactions returns empty insights`() {
        val currentMonth = MonthPeriod(
            year = 2026,
            month = 2,
            startMs = startOfMonth(2026, 3),
            endMs = startOfMonth(2026, 4)
        )

        val expenses = listOf(
            createExpense("2026-03-10", 3000.0, type = TransactionType.DEPOSIT, merchant = "Salary", id = 701L),
            createExpense("2026-03-15", 500.0, type = TransactionType.TRANSFER, merchant = "Savings", id = 702L),
            createExpense("2026-03-20", 200.0, type = TransactionType.WITHDRAWAL, merchant = "ATM", id = 703L)
        )

        val insights = engine.calculate(
            currentMonth = currentMonth,
            previousMonth = null,
            categoryMap = TEST_CATEGORIES.toAnalyticsCategoryMap(),
            allExpenses = expenses.map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        assertTrue(insights.isEmpty())
    }

    @Test
    fun `calculate preserves ranking order after non-purchase exclusion`() {
        val currentMonth = MonthPeriod(
            year = 2026,
            month = 2,
            startMs = startOfMonth(2026, 3),
            endMs = startOfMonth(2026, 4)
        )

        val expenses = listOf(
            createExpense("2026-03-05", 300.0, merchant = "Electric Co", category = "utilities", id = 801L),
            createExpense("2026-03-10", 100.0, merchant = "Lidl", category = "groceries", id = 802L),
            createExpense("2026-03-15", 50.0, merchant = "Cinema", category = "entertainment", id = 803L),
            // Large deposit should NOT influence ranking
            createExpense("2026-03-20", 10000.0, type = TransactionType.DEPOSIT, merchant = "Bonus", id = 804L)
        )

        val insights = engine.calculate(
            currentMonth = currentMonth,
            previousMonth = null,
            categoryMap = TEST_CATEGORIES.toAnalyticsCategoryMap(),
            allExpenses = expenses.map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        assertEquals(3, insights.size)
        // Ranking: Utilities(300) > Groceries(100) > Entertainment(50)
        assertEquals(5L, insights[0].category.id) // Utilities
        assertEquals(2L, insights[1].category.id) // Groceries
        assertEquals(3L, insights[2].category.id) // Entertainment
    }

    @Test
    fun `calculate previous totals remain correct with large previous period`() {
        val currentMonth = MonthPeriod(
            year = 2026,
            month = 2,
            startMs = startOfMonth(2026, 3),
            endMs = startOfMonth(2026, 4)
        )
        val previousMonth = MonthPeriod(
            year = 2026,
            month = 1,
            startMs = startOfMonth(2026, 2),
            endMs = startOfMonth(2026, 3)
        )

        val previousStart = previousMonth.startMs
        val previousExpenses = (0 until 1_000).map { idx ->
            createExpense(
                date = "2026-02-01",
                amount = 1.0,
                merchant = "G-$idx",
                category = if (idx % 2 == 0) "groceries" else "dining",
                id = 10_000L + idx
            ).copy(date = previousStart + TimeUnit.HOURS.toMillis((idx % 24).toLong()))
        }
        val currentExpenses = listOf(
            createExpense("2026-03-03", 100.0, merchant = "Lidl", category = "groceries", id = 20_001L),
            createExpense("2026-03-04", 100.0, merchant = "Lidl", category = "groceries", id = 20_002L)
        )

        val insights = engine.calculate(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            categoryMap = TEST_CATEGORIES.toAnalyticsCategoryMap(),
            allExpenses = (currentExpenses + previousExpenses).map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        val groceries = insights.first { it.category.id == 2L }
        assertApproxEquals(200.0, groceries.currentTotal, 0.01)
        assertApproxEquals(500.0, groceries.previousTotal ?: 0.0, 0.01)
        assertEquals(500, groceries.previousCount)
    }

    private fun goldenMarchAndFebruaryExpensesWithRentCategory() = listOf(
        createExpense("2026-03-01", 800.00, merchant = "Rent Co", id = 1L).copy(categoryId = 99L),
        createExpense("2026-03-02", 45.30, merchant = "Lidl", category = "groceries", id = 2L),
        createExpense("2026-03-05", 62.50, merchant = "Shell Gas", id = 3L),
        createExpense("2026-03-07", 15.99, merchant = "Netflix", category = "entertainment", id = 4L),
        createExpense("2026-03-10", 38.70, merchant = "Lidl", category = "groceries", id = 5L),
        createExpense("2026-03-12", 24.50, merchant = "Restaurant A", category = "dining", id = 6L),
        createExpense("2026-03-15", 2500.00, type = TransactionType.DEPOSIT, merchant = "Salary", id = 7L),
        createExpense("2026-03-15", 4.80, merchant = "Coffee Shop", category = "dining", id = 8L),
        createExpense("2026-03-18", 52.10, merchant = "Lidl", category = "groceries", id = 9L),
        createExpense("2026-03-20", 89.90, merchant = "Zara", id = 10L),
        createExpense("2026-03-22", 12.30, merchant = "Pharmacy", id = 11L),
        createExpense(
            date = "2026-03-25",
            amount = 35.00,
            effectiveAmount = 17.50,
            merchant = "Friend Lunch",
            category = "dining",
            id = 12L,
            isSharedExpense = true,
            mySharePercentage = 50
        ),
        createExpense("2026-03-28", 120.00, merchant = "Utilities", category = "utilities", id = 13L),
        createExpense("2026-03-30", 500.00, type = TransactionType.DEPOSIT, merchant = "Bonus", id = 14L),

        createExpense("2026-02-01", 800.00, merchant = "Rent Co", id = 101L).copy(categoryId = 99L),
        createExpense("2026-02-05", 55.00, merchant = "Lidl", category = "groceries", id = 102L),
        createExpense("2026-02-10", 58.00, merchant = "Shell Gas", id = 103L),
        createExpense("2026-02-15", 2500.00, type = TransactionType.DEPOSIT, merchant = "Salary", id = 104L),
        createExpense("2026-02-18", 30.00, merchant = "Restaurant B", category = "dining", id = 105L),
        createExpense("2026-02-25", 115.00, merchant = "Utilities", category = "utilities", id = 106L)
    )

    private fun List<Category>.toAnalyticsCategoryMap(): Map<Long, AnalyticsCategoryRef> {
        return associate { category ->
            category.id to AnalyticsCategoryRef(
                id = category.id,
                name = category.name,
                icon = category.icon,
                color = category.color
            )
        }
    }

    private fun com.yourname.expensetracker.data.database.entity.Expense.toSnapshot(): ExpenseSnapshot {
        return ExpenseSnapshot(
            id = id,
            amount = amount,
            effectiveAmount = effectiveAmount,
            currency = currency,
            merchant = merchant,
            merchantKey = merchantKey,
            transactionType = when (transactionType) {
                TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
                TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
                TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
                TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
                TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
            },
            date = date,
            categoryId = categoryId,
            isNotMine = isNotMine,
            transferDirection = when (transferDirection) {
                com.yourname.expensetracker.data.database.entity.TransferDirection.INCOMING -> DomainTransferDirection.INCOMING
                com.yourname.expensetracker.data.database.entity.TransferDirection.OUTGOING -> DomainTransferDirection.OUTGOING
                null -> null
            },
            notes = notes
        )
    }
}
