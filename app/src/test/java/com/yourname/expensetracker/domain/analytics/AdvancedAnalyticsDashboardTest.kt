package com.yourname.expensetracker.domain.analytics

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

class AdvancedAnalyticsDashboardTest : AnalyticsEngineTestBase() {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var dashboard: AdvancedAnalyticsDashboard

    @Before
    override fun setUp() {
        super.setUp()
        expenseRepository = mockk(relaxed = true)
        dashboard = AdvancedAnalyticsDashboard(
            expenseDao = expenseDao,
            expenseRepository = expenseRepository,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `totals net cashflow top categories top merchants and trends calculate correctly`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-05-01")

        val all = listOf(
            // March
            exp("2026-03-03", 100.0, TransactionType.PURCHASE, categoryId = 1L, merchant = "A"),
            exp("2026-03-05", 50.0, TransactionType.WITHDRAWAL, merchant = "ATM"),
            exp("2026-03-08", 300.0, TransactionType.DEPOSIT, merchant = "Salary"),
            // April
            exp("2026-04-02", 200.0, TransactionType.PURCHASE, categoryId = 2L, merchant = "B"),
            exp("2026-04-04", 100.0, TransactionType.PURCHASE, categoryId = 1L, merchant = "A"),
            exp("2026-04-10", 500.0, TransactionType.DEPOSIT, merchant = "Salary")
        )

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } answers {
            val rangeStart = firstArg<Long>()
            val rangeEnd = secondArg<Long>()
            when (rangeStart) {
                start -> if (rangeEnd == end) {
                    all
                } else {
                    all.filter { it.date in rangeStart..rangeEnd }
                }
                ms("2026-03-01") -> all.filter { it.date in ms("2026-03-01") until ms("2026-04-01") }
                ms("2026-04-01") -> all.filter { it.date in ms("2026-04-01") until ms("2026-05-01") }
                else -> {
                    all.filter { it.date in rangeStart..rangeEnd }
                }
            }
        }

        val result = dashboard.generateDashboardData(start, end)

        assertApproxEquals(450.0, result.totalSpent) // purchases + withdrawals
        assertApproxEquals(800.0, result.totalIncome)
        assertApproxEquals(350.0, result.netCashflow)

        assertEquals(2, result.topCategories.size)
        val categoryAmounts = result.topCategories.associate { it.categoryId to it.amount }
        assertApproxEquals(200.0, categoryAmounts[1L] ?: 0.0)
        assertApproxEquals(200.0, categoryAmounts[2L] ?: 0.0)

        assertEquals("A", result.topMerchants.first().merchant)
        assertApproxEquals(200.0, result.topMerchants.first().amount)

        assertEquals(3, result.monthlyTrend.size)
        assertEquals("2026-03", result.monthlyTrend[0].month)
        assertApproxEquals(150.0, result.monthlyTrend[0].spending)
        assertApproxEquals(300.0, result.monthlyTrend[0].income)

        assertEquals(3, result.weeklyPattern.sumOf { it.transactionCount })
    }

    @Test
    fun `no income edge case keeps totals and avoids divide errors in insights`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val expenses = listOf(
            exp("2026-03-01", 100.0, TransactionType.PURCHASE),
            exp("2026-03-02", 50.0, TransactionType.PURCHASE)
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns expenses

        val result = dashboard.generateDashboardData(start, end)
        assertApproxEquals(150.0, result.totalSpent)
        assertApproxEquals(0.0, result.totalIncome)
        assertApproxEquals(-150.0, result.netCashflow)
    }

    @Test
    fun `no expenses and empty dataset return zeros and stable shapes`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        val result = dashboard.generateDashboardData(ms("2026-03-01"), ms("2026-04-01"))

        assertEquals(0.0, result.totalSpent, 0.0)
        assertEquals(0.0, result.totalIncome, 0.0)
        assertEquals(0.0, result.netCashflow, 0.0)
        assertTrue(result.topCategories.isEmpty())
        assertTrue(result.topMerchants.isEmpty())
        assertEquals(7, result.weeklyPattern.size)
    }

    @Test
    fun `equal income and expenses yields zero net and no savings insight`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val data = listOf(
            exp("2026-03-01", 100.0, TransactionType.PURCHASE),
            exp("2026-03-02", 100.0, TransactionType.DEPOSIT)
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns data

        val result = dashboard.generateDashboardData(start, end)
        assertApproxEquals(0.0, result.netCashflow)
        assertTrue(result.insights.none { it.type == DashboardInsightType.SAVINGS_OPPORTUNITY })
    }

    private fun exp(
        date: String,
        amount: Double,
        type: TransactionType,
        categoryId: Long? = null,
        merchant: String = "M"
    ): Expense = Expense(
        amount = amount,
        merchant = merchant,
        transactionType = type,
        categoryId = categoryId,
        date = ms(date)
    )

    private fun ms(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
