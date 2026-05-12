package com.yourname.expensetracker.verification

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.lifestyle.LifestyleInflationDetector
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LifestyleAnalysisTest : AnalyticsEngineTestBase() {

    private lateinit var detector: LifestyleInflationDetector

    @Before
    override fun setUp() {
        super.setUp()
        detector = LifestyleInflationDetector(expenseDao, timeProvider = mockk(relaxed = true))
    }

    @Test
    fun `income elasticity calculates correctly`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "supermarket"),
                income("2026-02-05", 1100.0), spend("2026-02-10", 550.0, "supermarket")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 3)

        // elasticity = (%Δspending) / (%Δincome) = (10%) / (10%) = 1.0
        assertApproxEquals(1.0, report.incomeElasticity, 0.0001)
    }

    @Test
    fun `lifestyle inflation detects creep`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "Grocer"),
                income("2026-02-05", 1100.0), spend("2026-02-10", 650.0, "restaurant"),
                income("2026-03-05", 1200.0), spend("2026-03-10", 900.0, "restaurant")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 6)

        assertTrue(report.spendingGrowthRate > report.incomeGrowthRate)
        assertTrue(report.lifestyleCreepDetected)
        assertTrue(report.lifestyleCreepAlerts.isNotEmpty())
    }

    @Test
    fun `hedonic adaptation detects discretionary volatility`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 2000.0), spend("2026-01-10", 100.0, "restaurant"),
                income("2026-02-05", 2000.0), spend("2026-02-10", 500.0, "vacation"),
                income("2026-03-05", 2000.0), spend("2026-03-10", 200.0, "streaming")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 6)

        assertTrue(report.hedonicAdaptationScore > 0.0)
    }

    @Test
    fun `spending patterns analyze monthly distribution fields`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(
                income("2026-03-05", 1000.0),
                spend("2026-03-10", 300.0, "supermarket"),
                spend("2026-03-12", 200.0, "restaurant")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 2)
        val march = report.monthlyData.single { it.month == "2026-03" }

        assertApproxEquals(500.0, march.totalSpending, 0.0001)
        assertApproxEquals(200.0, march.discretionarySpending, 0.0001)
        assertApproxEquals(300.0, march.essentialSpending, 0.0001)
        assertApproxEquals(50.0, march.savingsRate, 0.0001)
    }

    @Test
    fun `empty dataset returns neutral analysis`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(emptyList())

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 12)

        assertApproxEquals(0.0, report.incomeElasticity, 0.0)
        assertApproxEquals(0.0, report.incomeGrowthRate, 0.0)
        assertApproxEquals(0.0, report.spendingGrowthRate, 0.0)
        assertApproxEquals(0.0, report.hedonicAdaptationScore, 0.0)
        assertFalse(report.lifestyleCreepDetected)
        assertTrue(report.monthlyData.isEmpty())
    }

    @Test
    fun `single income change event handled`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(
                income("2026-03-05", 3000.0),
                spend("2026-03-10", 900.0, "supermarket")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 2)

        assertApproxEquals(0.0, report.incomeElasticity, 0.0)
    }

    @Test
    fun `income_elasticity_zero_and_negative_income_change`() = runTest {
        // Case A: zero income change should avoid divide-by-zero and yield neutral elasticity
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "Grocer"),
                income("2026-02-05", 1000.0), spend("2026-02-10", 550.0, "Grocer")
            )
        )
        val zeroIncomeChange = detector.analyzeLifestyleInflation(monthsToAnalyze = 3)
        assertApproxEquals(0.0, zeroIncomeChange.incomeElasticity, 0.0001)

        // Case B: negative income change with spending decrease should produce finite, stable elasticity
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "Grocer"),
                income("2026-02-05", 900.0), spend("2026-02-10", 450.0, "Grocer")
            )
        )
        val negativeIncomeChange = detector.analyzeLifestyleInflation(monthsToAnalyze = 3)
        assertApproxEquals(1.0, negativeIncomeChange.incomeElasticity, 0.0001)
        assertTrue(negativeIncomeChange.incomeElasticity.isFinite())
    }

    private fun income(date: String, amount: Double): Expense = Expense(
        id = amount.toLong(),
        amount = amount,
        merchant = "Employer",
        transactionType = TransactionType.DEPOSIT,
        date = millis(date),
        createdAt = System.currentTimeMillis()
    )

    private fun spend(date: String, amount: Double, merchant: String): Expense = Expense(
        id = (amount * 10).toLong(),
        amount = amount,
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = millis(date),
        createdAt = System.currentTimeMillis()
    )

    private fun millis(isoDate: String): Long =
        LocalDate.parse(isoDate)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}