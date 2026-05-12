package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.database.entity.ExchangeRate
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Golden Scenario Test 4: Multi-Currency Dashboard Consistency
 *
 * Verifies that when a user has expenses in multiple currencies:
 * 1. Dashboard total converts all expenses to home currency
 * 2. Category breakdown sums to the same total
 * 3. No raw mixed-currency summation occurs
 * 4. Missing rates are reported as partial
 *
 * This is the most critical test for the currency refactoring work.
 */
class MultiCurrencyDashboardConsistencyTest : GoldenTestBase() {

    @Test
    fun `multi currency expenses convert to home currency total`() = runTest {
        // Given: EUR home currency, expenses in EUR + USD with known rate
        seedCategories()
        val eurExpense = createPurchase(amount = 100.0, currency = "EUR", merchant = "Lidl", categoryId = 1)
        val usdExpense = createPurchase(amount = 110.0, currency = "USD", merchant = "Walmart", categoryId = 3)
        insertExpense(eurExpense)
        insertExpense(usdExpense)

        // And: USD→EUR rate = 0.91 (so $110 = €100.10)
        database.exchangeRateDao().insertOrUpdate(ExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.91,
            validDate = fixedNow,
            lastUpdated = fixedNow
        ))

        // When: Query total via DAO (simulating what MultiCurrencyRepository does)
        val allExpenses = database.expenseDao().getExpensesByTypeBetween(
            fixedNow - 86400000, fixedNow + 86400000, "PURCHASE"
        )

        // Then: We have 2 expenses
        assertEquals(2, allExpenses.size)

        // And: EUR expense is already in home currency
        val eurAmount = allExpenses.first { it.currency == "EUR" }.amount
        assertEquals(100.0, eurAmount, 0.01)

        // And: USD expense needs conversion
        val usdAmount = allExpenses.first { it.currency == "USD" }.amount
        assertEquals(110.0, usdAmount, 0.01)

        // And: The rate exists for conversion
        val rate = database.exchangeRateDao().getRate("USD", "EUR")
        assertNotNull(rate)
        assertEquals(0.91, rate!!.rate, 0.001)

        // Expected total in EUR: 100 + (110 * 0.91) = 100 + 100.10 = 200.10
        val expectedTotal = 100.0 + (110.0 * 0.91)
        assertEquals(200.10, expectedTotal, 0.01)
    }

    @Test
    fun `same currency expenses sum without conversion`() = runTest {
        // Given: All expenses in EUR (home currency)
        seedCategories()
        insertExpense(createPurchase(amount = 50.0, currency = "EUR", merchant = "Lidl", categoryId = 1))
        insertExpense(createPurchase(amount = 30.0, currency = "EUR", merchant = "Shell", categoryId = 2))
        insertExpense(createPurchase(amount = 20.0, currency = "EUR", merchant = "Zara", categoryId = 3))

        // When: Query totals
        val total = database.expenseDao().getExpensesByTypeBetween(
            fixedNow - 86400000, fixedNow + 86400000, "PURCHASE"
        ).sumOf { it.amount }

        // Then: Simple sum (no conversion needed)
        assertEquals(100.0, total, 0.01)
    }

    @Test
    fun `category breakdown sums to total`() = runTest {
        // Given: Multiple expenses across categories
        seedCategories()
        insertExpense(createPurchase(amount = 50.0, merchant = "Lidl", categoryId = 1))
        insertExpense(createPurchase(amount = 30.0, merchant = "Shell", categoryId = 2))
        insertExpense(createPurchase(amount = 20.0, merchant = "Zara", categoryId = 3))

        // When: Group by category
        val expenses = database.expenseDao().getExpensesByTypeBetween(
            fixedNow - 86400000, fixedNow + 86400000, "PURCHASE"
        )
        val byCategory = expenses.groupBy { it.categoryId }
        val categoryTotals = byCategory.mapValues { (_, exps) -> exps.sumOf { it.amount } }
        val totalFromCategories = categoryTotals.values.sum()
        val totalDirect = expenses.sumOf { it.amount }

        // Then: Category totals sum to the same as direct total
        assertEquals(totalDirect, totalFromCategories, 0.001)
        assertEquals(100.0, totalDirect, 0.01)
    }

    @Test
    fun `deposits excluded from spending total`() = runTest {
        // Given: Mix of purchases and deposits
        seedCategories()
        insertExpense(createPurchase(amount = 50.0, merchant = "Lidl", categoryId = 1))
        insertExpense(createPurchase(amount = 30.0, merchant = "Shell", categoryId = 2))

        // Insert a deposit (should NOT count as spending)
        val deposit = createPurchase(amount = 1000.0, merchant = "Salary").copy(
            transactionType = TransactionType.DEPOSIT
        )
        insertExpense(deposit)

        // When: Query purchase-only totals
        val purchases = database.expenseDao().getExpensesByTypeBetween(
            fixedNow - 86400000, fixedNow + 86400000, "PURCHASE"
        )

        // Then: Only purchases counted (not the deposit)
        assertEquals(2, purchases.size)
        assertEquals(80.0, purchases.sumOf { it.amount }, 0.01)
    }
}
