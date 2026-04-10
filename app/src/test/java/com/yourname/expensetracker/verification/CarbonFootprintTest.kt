package com.yourname.expensetracker.verification

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.carbon.CarbonFootprintCalculator
import io.mockk.coEvery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CarbonFootprintTest : AnalyticsEngineTestBase() {

    private lateinit var calculator: CarbonFootprintCalculator

    @Before
    override fun setUp() {
        super.setUp()
        calculator = CarbonFootprintCalculator(expenseDao)
    }

    @Test
    fun `category footprint calculates correctly`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
            listOf(expense(merchant = "Local Bakery", amount = 100.0))

        val report = calculator.calculateCarbonFootprint(startDate = 0L, endDate = dayMs)

        assertApproxEquals(25.0, report.totalEmissionsKg, 0.0001)
    }

    @Test
    fun `merchant footprint uses specific factors`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
            listOf(expense(merchant = "ZARA", amount = 100.0))

        val report = calculator.calculateCarbonFootprint(startDate = 0L, endDate = dayMs)

        // Merchant-specific factor for ZARA = 0.55, category CLOTHING default = 0.50
        assertApproxEquals(55.0, report.totalEmissionsKg, 0.0001)
    }

    @Test
    fun `offset calculation returns deterministic cost`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
            listOf(expense(merchant = "Local Bakery", amount = 100.0))

        val report = calculator.calculateCarbonFootprint(startDate = 0L, endDate = dayMs)

        // total CO2 = 25kg, offset cost = (25/1000) * 22 = 0.55
        assertApproxEquals(0.55, report.offsetCost, 0.0001)
        assertTrue(report.recommendations.any { it.isOffset && it.offsetCost != null })
    }

    @Test
    fun `empty dataset returns zero footprint`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns emptyList()

        val report = calculator.calculateCarbonFootprint(startDate = 0L, endDate = dayMs)

        assertApproxEquals(0.0, report.totalEmissionsKg, 0.0)
        assertApproxEquals(0.0, report.dailyAverageKg, 0.0)
        assertEquals(emptyList<Any>(), report.categoryBreakdown)
    }

    @Test
    fun `shared expenses use effectiveAmount`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
            listOf(
                expense(
                    merchant = "Local Bakery",
                    amount = 120.0,
                    isSharedExpense = true,
                    myShareAmount = 40.0
                )
            )

        val report = calculator.calculateCarbonFootprint(startDate = 0L, endDate = dayMs)

        // Uses effectiveAmount(40) not raw amount(120): 40 * 0.25 = 10
        assertApproxEquals(10.0, report.totalEmissionsKg, 0.0001)
    }

    @Test
    fun `category breakdown sums to total`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
            listOf(
                expense(merchant = "SHELL", amount = 10.0),     // 23.0
                expense(merchant = "ZARA", amount = 20.0),      // 11.0
                expense(merchant = "Local Bakery", amount = 40.0) // 10.0
            )

        val report = calculator.calculateCarbonFootprint(startDate = 0L, endDate = dayMs)
        val sum = report.categoryBreakdown.sumOf { it.emissionsKg }

        assertApproxEquals(report.totalEmissionsKg, sum, 0.0001)
    }

    /**
     * A.9 Batch 7 regression: carbon reporting must use the uncapped
     * DAO query so datasets larger than the old 2000-row default are
     * not silently truncated.
     */
    @Test
    fun `A9 regression - all rows included beyond old 2000 limit`() = runTest {
        val bigList = (1..2500).map {
            expense(merchant = "SHELL", amount = 1.0)
        }
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns bigList

        val report = calculator.calculateCarbonFootprint(startDate = 0L, endDate = dayMs)

        // 2500 × €1 × 2.3 (SHELL) = 5750.0
        assertApproxEquals(5750.0, report.totalEmissionsKg, 0.1)
    }

    private fun expense(
        merchant: String,
        amount: Double,
        isSharedExpense: Boolean = false,
        myShareAmount: Double? = null
    ): Expense = Expense(
        id = 1L,
        amount = amount,
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = 1_700_000_000_000,
        isSharedExpense = isSharedExpense,
        myShareAmount = myShareAmount
    )

    private companion object {
        const val dayMs = 24L * 60L * 60L * 1000L
    }
}
