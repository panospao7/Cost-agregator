package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
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
    private val currencySettingsRepository = TestCurrencySettingsRepository()
    private val analyticsCurrencyNormalizer = testAnalyticsCurrencyNormalizer()

    @Before
    override fun setUp() {
        super.setUp()
        expenseRepository = mockk(relaxed = true)
        dashboard = AdvancedAnalyticsDashboard(
            expenseDao = expenseDao,
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            currencySettingsRepository = currencySettingsRepository,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
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

        // Stubs use half-open [rangeStart, rangeEnd) semantics: date >= rangeStart && date < rangeEnd
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } answers {
            val rangeStart = firstArg<Long>()
            val rangeEnd = secondArg<Long>()
            all.filter { it.date >= rangeStart && it.date < rangeEnd }.toExpenseSnapshots()
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

        // Half-open [2026-03-01, 2026-05-01) covers only March and April — May bucket must NOT appear
        assertEquals(2, result.monthlyTrend.size)
        assertEquals("2026-03", result.monthlyTrend[0].month)
        assertApproxEquals(150.0, result.monthlyTrend[0].spending)
        assertApproxEquals(300.0, result.monthlyTrend[0].income)
        assertEquals("2026-04", result.monthlyTrend[1].month)

        assertEquals(3, result.weeklyPattern.sumOf { it.transactionCount })
    }

    @Test
    fun `transactions at or after endDate are excluded from the final monthly bucket`() = runTest {
        val start = ms("2026-04-01")
        val end = ms("2026-05-01")

        val all = listOf(
            // Inside range [2026-04-01, 2026-05-01)
            exp("2026-04-15", 80.0, TransactionType.PURCHASE, merchant = "InRange"),
            // Exactly at endDate — must be excluded
            exp("2026-05-01", 999.0, TransactionType.PURCHASE, merchant = "AtEnd"),
            // After endDate — must also be excluded
            exp("2026-05-10", 200.0, TransactionType.PURCHASE, merchant = "AfterEnd")
        )

        // Half-open stub: [rangeStart, rangeEnd)
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } answers {
            val rangeStart = firstArg<Long>()
            val rangeEnd = secondArg<Long>()
            all.filter { it.date >= rangeStart && it.date < rangeEnd }.toExpenseSnapshots()
        }

        val result = dashboard.generateDashboardData(start, end)

        // Only one bucket: April — May must not appear
        assertEquals(1, result.monthlyTrend.size)
        assertEquals("2026-04", result.monthlyTrend[0].month)

        // Only the in-range April transaction contributes spending
        assertApproxEquals(80.0, result.monthlyTrend[0].spending)

        // Totals also exclude out-of-range transactions
        assertApproxEquals(80.0, result.totalSpent)
    }

    @Test
    fun `no income edge case keeps totals and avoids divide errors in insights`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val expenses = listOf(
            exp("2026-03-01", 100.0, TransactionType.PURCHASE),
            exp("2026-03-02", 50.0, TransactionType.PURCHASE)
        )
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns expenses.toExpenseSnapshots()

        val result = dashboard.generateDashboardData(start, end)
        assertApproxEquals(150.0, result.totalSpent)
        assertApproxEquals(0.0, result.totalIncome)
        assertApproxEquals(-150.0, result.netCashflow)
    }

    @Test
    fun `no expenses and empty dataset return zeros and stable shapes`() = runTest {
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
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
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns data.toExpenseSnapshots()

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
