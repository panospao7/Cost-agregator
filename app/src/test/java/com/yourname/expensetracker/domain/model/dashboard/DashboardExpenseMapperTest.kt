package com.yourname.expensetracker.domain.model.dashboard

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardExpenseMapperTest {

    @Test
    fun `expense to dashboardExpense mapping correct all fields mapped`() {
        val expense = createExpense(
            date = "2026-03-05",
            amount = 42.75,
            type = TransactionType.TRANSFER,
            category = "Groceries",
            merchant = "Metro Market",
            id = 1001L
        ).copy(isManualEntry = true)

        val dashboardExpense = expense.toDashboardExpenseFixture()

        val mapped = dashboardExpense.toEntityExpense()

        assertEquals(expense.id, mapped.id)
        assertApproxEquals(expense.amount, mapped.amount, 0.0001)
        assertEquals(expense.merchant, mapped.merchant)
        assertEquals(expense.transactionType, mapped.transactionType)
        assertEquals(expense.date, mapped.date)
        assertEquals(expense.categoryId, mapped.categoryId)
        assertEquals(expense.isNotMine, mapped.isNotMine)
        assertEquals(expense.isManualEntry, mapped.isManualEntry)
        assertEquals(MerchantKeyGenerator.generate(expense.merchant), mapped.merchantKey)
    }

    @Test
    fun `null category handled gracefully shows Uncategorized`() {
        val expense = createExpense(
            date = "2026-03-06",
            amount = 18.40,
            category = null,
            merchant = "No Category Merchant",
            id = 1002L
        )

        val mapped = expense
            .toDashboardExpenseFixture()
            .toEntityExpense()

        assertNull(mapped.categoryId)
        val categoryLabel = mapped.categoryId?.toString() ?: "Uncategorized"
        assertEquals("Uncategorized", categoryLabel)
        assertApproxEquals(expense.amount, mapped.amount, 0.0001)
    }

    @Test
    fun `shared expense effectiveAmount used instead of raw amount`() {
        val sharedExpense = createExpense(
            date = "2026-03-07",
            amount = 120.0,
            effectiveAmount = 30.0,
            isSharedExpense = true,
            myShareAmount = 30.0,
            category = "Food & Dining",
            merchant = "Shared Dinner",
            id = 1003L
        )

        val dashboardExpense = sharedExpense.toDashboardExpenseFixture(
            amountOverride = sharedExpense.effectiveAmount
        )

        val mapped = dashboardExpense.toEntityExpense()

        assertApproxEquals(120.0, sharedExpense.amount, 0.0001)
        assertApproxEquals(30.0, sharedExpense.effectiveAmount, 0.0001)
        assertApproxEquals(30.0, mapped.amount, 0.0001)
    }

    @Test
    fun `list mapping preserves order and count`() {
        val expenses = listOf(
            createExpense(date = "2026-03-08", amount = 10.0, merchant = "A", id = 2001L),
            createExpense(date = "2026-03-09", amount = 20.0, merchant = "B", id = 2002L),
            createExpense(date = "2026-03-10", amount = 30.0, merchant = "C", id = 2003L)
        )

        val mapped = expenses
            .map { it.toDashboardExpenseFixture() }
            .map { it.toEntityExpense() }

        assertEquals(expenses.size, mapped.size)
        assertEquals(expenses.map { it.id }, mapped.map { it.id })
        assertEquals(expenses.map { it.merchant }, mapped.map { it.merchant })
        expenses.zip(mapped).forEach { (expected, actual) ->
            assertApproxEquals(expected.amount, actual.amount, 0.0001)
        }
    }

    private fun Expense.toDashboardExpenseFixture(amountOverride: Double = amount): DashboardExpense {
        return DashboardExpense(
            id = id,
            amount = amountOverride,
            effectiveAmount = effectiveAmount,
            merchant = merchant,
            transactionType = transactionType.toDashboardTransactionType(),
            date = date,
            categoryId = categoryId,
            isNotMine = isNotMine,
            isManualEntry = isManualEntry
        )
    }

    private fun TransactionType.toDashboardTransactionType(): DashboardTransactionType {
        return when (this) {
            TransactionType.PURCHASE -> DashboardTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> DashboardTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> DashboardTransactionType.TRANSFER
            TransactionType.DEPOSIT -> DashboardTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> DashboardTransactionType.UNKNOWN
        }
    }
}
