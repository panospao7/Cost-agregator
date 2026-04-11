package com.yourname.expensetracker.domain.income

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RecurringIncomeTrackerTest : AnalyticsEngineTestBase() {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var tracker: RecurringIncomeTracker

    @Before
    override fun setUp() {
        super.setUp()
        expenseRepository = mockk(relaxed = true)

        tracker = RecurringIncomeTracker(
            expenseDao = expenseDao,
            expenseRepository = expenseRepository,
            timeProvider = timeProvider
        )
    }

    // ============================================================================
    // A.10 Batch 5 – spending-side ratio uses canonical isSpending semantics
    // ============================================================================

    /**
     * A.10 Batch 5: getIncomeExpenseRatio must count only canonical spending
     * (PURCHASE) on the expense side.  Withdrawals are account movements,
     * not user spending, and must be excluded from the ratio.
     */
    @Test
    fun `A10 Batch5 - income expense ratio excludes withdrawal from spending`() = runTest {
        val d1 = ms("2026-04-01")

        val transactions = listOf(
            expense(d1, 1000.0, TransactionType.DEPOSIT),
            expense(d1, 200.0, TransactionType.PURCHASE),
            expense(d1, 500.0, TransactionType.WITHDRAWAL)   // must NOT count as spending
        )

        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns transactions

        val ratio = tracker.getIncomeExpenseRatio()

        assertApproxEquals(1000.0, ratio.totalIncome)
        // Only PURCHASE counts as spending
        assertApproxEquals(200.0, ratio.totalExpenses)
        assertApproxEquals(800.0, ratio.savings)
        assertTrue(ratio.isPositive)
        // Savings rate: (1000 - 200) / 1000 * 100 = 80%
        assertApproxEquals(80.0, ratio.savingsRate)
    }

    /**
     * A.10 Batch 5: TRANSFER and UNKNOWN must also be excluded from
     * the spending side of the income-vs-expense ratio.
     */
    @Test
    fun `A10 Batch5 - income expense ratio excludes transfer and unknown from spending`() = runTest {
        val d1 = ms("2026-04-01")

        val transactions = listOf(
            expense(d1, 500.0, TransactionType.DEPOSIT),
            expense(d1, 100.0, TransactionType.PURCHASE),
            expense(d1, 300.0, TransactionType.TRANSFER),
            expense(d1, 50.0, TransactionType.UNKNOWN)
        )

        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns transactions

        val ratio = tracker.getIncomeExpenseRatio()

        assertApproxEquals(500.0, ratio.totalIncome)
        // Only PURCHASE (100) counts as spending
        assertApproxEquals(100.0, ratio.totalExpenses)
        assertApproxEquals(400.0, ratio.savings)
        assertTrue(ratio.isPositive)
    }

    /**
     * A.10 Batch 5: When there are only deposits, spending must be zero.
     */
    @Test
    fun `A10 Batch5 - deposits only yields zero spending`() = runTest {
        val d1 = ms("2026-04-01")

        val transactions = listOf(
            expense(d1, 2000.0, TransactionType.DEPOSIT)
        )

        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns transactions

        val ratio = tracker.getIncomeExpenseRatio()

        assertApproxEquals(2000.0, ratio.totalIncome)
        assertApproxEquals(0.0, ratio.totalExpenses)
        assertApproxEquals(2000.0, ratio.savings)
        assertApproxEquals(100.0, ratio.savingsRate)
        assertTrue(ratio.isPositive)
    }

    /**
     * A.10 Batch 5: recurring income detection must query DEPOSIT type only
     * (deposit-only semantics preserved).
     */
    @Test
    fun `A10 Batch5 - recurring income detection queries deposits only`() = runTest {
        // Two deposits 30 days apart from same source
        val d1 = ms("2026-02-01")
        val d2 = ms("2026-03-01")

        val deposits = listOf(
            expense(d1, 3000.0, TransactionType.DEPOSIT, merchant = "Employer"),
            expense(d2, 3000.0, TransactionType.DEPOSIT, merchant = "Employer")
        )

        coEvery {
            expenseDao.getExpensesByTypeBetween(any(), any(), eq("DEPOSIT"))
        } returns deposits

        val result = tracker.detectRecurringIncome()

        assertEquals(1, result.size)
        assertEquals("Employer", result[0].source)
        assertApproxEquals(3000.0, result[0].amount)
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    private fun expense(
        date: Long,
        amount: Double,
        type: TransactionType,
        merchant: String = "T"
    ) = Expense(
        amount = amount,
        merchant = merchant,
        transactionType = type,
        date = date
    )

    private fun ms(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
