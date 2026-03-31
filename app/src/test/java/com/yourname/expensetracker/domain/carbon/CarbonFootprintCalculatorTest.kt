package com.yourname.expensetracker.domain.carbon

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * PHASE 5 TEST: CarbonFootprintCalculator
 * 
 * Tests carbon footprint calculations based on spending categories and merchants.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarbonFootprintCalculatorTest {

    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private lateinit var calculator: CarbonFootprintCalculator

    @Before
    fun setup() {
        calculator = CarbonFootprintCalculator(expenseDao)
    }

    @Test
    fun `calculateCarbonFootprint returns report for expenses`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            createMockExpenses()
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        assertThat(report).isNotNull()
        assertThat(report.totalEmissionsKg).isGreaterThan(0.0)
        assertThat(report.dailyAverageKg).isGreaterThan(0.0)
        assertThat(report.periodDays).isGreaterThan(0)
    }

    @Test
    fun `fuel purchases have high emission factors`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense("SHELL", 50.0, TransactionType.PURCHASE))
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        // Fuel has factor of 2.3 kg CO2 per euro
        val expectedEmissions = 50.0 * 2.3
        assertThat(report.totalEmissionsKg).isWithin(0.1).of(expectedEmissions)
    }

    @Test
    fun `electronics purchases have moderate emission factors`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense("PLAISIO", 100.0, TransactionType.PURCHASE))
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        // Electronics has factor of 0.8 kg CO2 per euro
        val expectedEmissions = 100.0 * 0.8
        assertThat(report.totalEmissionsKg).isWithin(0.1).of(expectedEmissions)
    }

    @Test
    fun `restaurant purchases have lower emission factors`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense("EVEREST", 30.0, TransactionType.PURCHASE))
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        // Restaurants have factor of 0.35 kg CO2 per euro
        val expectedEmissions = 30.0 * 0.35
        assertThat(report.totalEmissionsKg).isWithin(0.1).of(expectedEmissions)
    }

    @Test
    fun `grocery purchases have low emission factors`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense("SKLAVENITIS", 80.0, TransactionType.PURCHASE))
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        // Grocery has factor of 0.25 kg CO2 per euro
        val expectedEmissions = 80.0 * 0.25
        assertThat(report.totalEmissionsKg).isWithin(0.1).of(expectedEmissions)
    }

    @Test
    fun `flights have very high emission factors`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense("AEGEAN", 200.0, TransactionType.PURCHASE))
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        // Flights have factor of 0.50 kg CO2 per euro
        val expectedEmissions = 200.0 * 0.50
        assertThat(report.totalEmissionsKg).isWithin(0.1).of(expectedEmissions)
    }

    @Test
    fun `category breakdown sums to total emissions`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            createMockExpenses()
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        val categoryTotal = report.categoryBreakdown.sumOf { it.emissionsKg }
        assertThat(categoryTotal).isWithin(0.01).of(report.totalEmissionsKg)
    }

    @Test
    fun `category percentages sum to approximately 100`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            createMockExpenses()
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        val totalPercentage = report.categoryBreakdown.sumOf { it.percentage }
        assertThat(totalPercentage).isAtMost(100)
        assertThat(totalPercentage).isAtLeast(99) // Allow for rounding
    }

    @Test
    fun `daily average is calculated correctly`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense("Test", 300.0, TransactionType.PURCHASE))
        )
        
        val startDate = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        val endDate = System.currentTimeMillis()
        
        val report = calculator.calculateCarbonFootprint(startDate, endDate)
        
        val expectedDailyAvg = report.totalEmissionsKg / 30.0
        assertThat(report.dailyAverageKg).isWithin(0.1).of(expectedDailyAvg)
    }

    @Test
    fun `sustainability score is between 0 and 100`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            createMockExpenses()
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        assertThat(report.sustainabilityScore).isAtLeast(0)
        assertThat(report.sustainabilityScore).isAtMost(100)
    }

    @Test
    fun `offset cost is calculated for total emissions`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense("Test", 100.0, TransactionType.PURCHASE))
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        assertThat(report.offsetCost).isGreaterThan(0.0)
    }

    @Test
    fun `recommendations are generated based on high emission categories`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense("SHELL", 100.0, TransactionType.PURCHASE)) // High fuel emissions
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        assertThat(report.recommendations).isNotEmpty()
    }

    @Test
    fun `alternatives suggested for high impact purchases`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            createMockExpenses()
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        // Should suggest alternatives for transport, food, etc.
        assertThat(report.alternatives).isNotNull()
    }

    @Test
    fun `monthly trend calculated from expense history`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            createMockExpenses()
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        assertThat(report.monthlyTrend).isNotNull()
    }

    @Test
    fun `paris agreement gap shows percentage above target`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense("Test", 100.0, TransactionType.PURCHASE))
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        // Paris target is 4.0 kg/day
        assertThat(report.parisAgreementGap).isNotNull()
    }

    @Test
    fun `comparison to national average is calculated`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense("Test", 100.0, TransactionType.PURCHASE))
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        // Greek average is 10.0 kg/day
        assertThat(report.comparisonToNationalAverage).isNotNull()
    }

    @Test
    fun `merchant patterns used for known merchants`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense("SHELL", 50.0, TransactionType.PURCHASE))
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        // SHELL should use merchant pattern factor (2.3) not category default
        val expectedEmissions = 50.0 * 2.3
        assertThat(report.totalEmissionsKg).isWithin(0.1).of(expectedEmissions)
    }

    @Test
    fun `unknown merchants fall back to category detection`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense("UNKNOWN STORE", 100.0, TransactionType.PURCHASE))
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        // Unknown merchants use DEFAULT factor
        assertThat(report.totalEmissionsKg).isGreaterThan(0.0)
    }

    @Test
    fun `non purchase transactions are filtered out`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(
                createExpense("Deposit", 100.0, TransactionType.DEPOSIT),
                createExpense("Purchase", 50.0, TransactionType.PURCHASE)
            )
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        // Only purchases should count
        assertThat(report.totalEmissionsKg).isEqualTo(50.0 * 0.25) // DEFAULT factor
    }

    @Test
    fun `empty expense list returns zero emissions`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(emptyList())
        
        val report = calculator.calculateCarbonFootprint()
        
        assertThat(report.totalEmissionsKg).isEqualTo(0.0)
        assertThat(report.dailyAverageKg).isEqualTo(0.0)
    }

    @Test
    fun `merchants detected from Greek names`() = runTest {
        every { expenseDao.getExpensesBetweenFlow(any(), any()) } returns flowOf(
            listOf(createExpense("ΣΚΛΑΒΕΝΙΤΗΣ", 50.0, TransactionType.PURCHASE))
        )
        
        val report = calculator.calculateCarbonFootprint()
        
        // Greek supermarket name should be detected
        val groceryCategory = report.categoryBreakdown.find { it.category.contains("GROCERY") }
        assertThat(groceryCategory).isNotNull()
    }

    // Helper methods
    
    private fun createMockExpenses(): List<Expense> {
        return listOf(
            createExpense("SHELL", 50.0, TransactionType.PURCHASE),
            createExpense("SKLAVENITIS", 80.0, TransactionType.PURCHASE),
            createExpense("EVEREST", 30.0, TransactionType.PURCHASE),
            createExpense("PLAISIO", 100.0, TransactionType.PURCHASE),
            createExpense("AEGEAN", 200.0, TransactionType.PURCHASE)
        )
    }
    
    private fun createExpense(
        merchant: String,
        amount: Double,
        type: TransactionType
    ): Expense {
        return Expense(
            id = 1L,
            merchant = merchant,
            amount = amount,
            date = System.currentTimeMillis(),
            categoryId = 1,
            notes = null,
            transactionType = type,
            currency = "EUR"
        )
    }
}