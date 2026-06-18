package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.startOfMonth
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonthlyComparisonCalculatorTest {

    private val calculator = MonthlyComparisonCalculator()

    @Test
    fun `calculate returns expected month over month percentage for golden march versus february`() {
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

        val comparison = calculator.calculate(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            allExpenses = goldenMarchAndFebruaryExpenses().map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        assertApproxEquals(1283.59, comparison.currentTotal, 0.01)
        assertApproxEquals(1058.00, comparison.previousTotal!!, 0.01)
        assertApproxEquals(225.59, comparison.changeAmount!!, 0.01)
        assertApproxEquals(21.32f, comparison.changePercentage!!, 0.01f)
        assertEquals(12, comparison.currentCount)
        assertEquals(5, comparison.previousCount)
    }

    @Test
    fun `calculate with zero previous month keeps change amount and percentage null`() {
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

        val comparison = calculator.calculate(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            allExpenses = listOf(
                createExpense("2026-03-04", 100.0, merchant = "A", id = 1L),
                createExpense("2026-03-09", 200.0, merchant = "B", id = 2L),
                createExpense("2026-02-15", 999.0, type = TransactionType.DEPOSIT, merchant = "Salary", id = 3L)
            ).map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        assertApproxEquals(300.0, comparison.currentTotal, 0.01)
        assertApproxEquals(0.0, comparison.previousTotal!!, 0.01)
        assertNull(comparison.changeAmount)
        assertNull(comparison.changePercentage)
        assertEquals(2, comparison.currentCount)
        assertEquals(0, comparison.previousCount)
    }

    @Test
    fun `calculate tracks count changes while filtering deposits and not-mine transactions`() {
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

        val comparison = calculator.calculate(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            allExpenses = listOf(
                createExpense("2026-03-01", 30.0, merchant = "M1", id = 1L),
                createExpense("2026-03-02", 40.0, merchant = "M2", id = 2L),
                createExpense("2026-03-03", 50.0, merchant = "M3", id = 3L),
                createExpense("2026-03-03", 500.0, type = TransactionType.DEPOSIT, merchant = "Salary", id = 4L),
                createExpense("2026-03-04", 60.0, effectiveAmount = 0.0, merchant = "M4", isNotMine = true, id = 5L),

                createExpense("2026-02-01", 10.0, merchant = "P1", id = 6L),
                createExpense("2026-02-02", 20.0, merchant = "P2", id = 7L),
                createExpense("2026-02-02", 30.0, effectiveAmount = 0.0, merchant = "P3", isNotMine = true, id = 8L),
                createExpense("2026-02-03", 400.0, type = TransactionType.DEPOSIT, merchant = "Bonus", id = 9L)
            ).map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        assertEquals(3, comparison.currentCount)
        assertEquals(2, comparison.previousCount)
        assertApproxEquals(120.0, comparison.currentTotal, 0.01)
        assertApproxEquals(30.0, comparison.previousTotal!!, 0.01)
        assertApproxEquals(300.0f, comparison.changePercentage!!, 0.01f)
    }

    private fun goldenMarchAndFebruaryExpenses() = listOf(
        createExpense("2026-03-01", 800.00, merchant = "Rent Co", id = 1L),
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

        createExpense("2026-02-01", 800.00, merchant = "Rent Co", id = 101L),
        createExpense("2026-02-05", 55.00, merchant = "Lidl", category = "groceries", id = 102L),
        createExpense("2026-02-10", 58.00, merchant = "Shell Gas", id = 103L),
        createExpense("2026-02-15", 2500.00, type = TransactionType.DEPOSIT, merchant = "Salary", id = 104L),
        createExpense("2026-02-18", 30.00, merchant = "Restaurant B", category = "dining", id = 105L),
        createExpense("2026-02-25", 115.00, merchant = "Utilities", category = "utilities", id = 106L)
    )

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
