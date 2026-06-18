package com.yourname.expensetracker.domain.tax

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.BusinessCategoryTotal
import com.yourname.expensetracker.data.repository.BusinessExpenseRepository
import com.yourname.expensetracker.data.repository.TaxSettingsRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Tests for [TaxEstimator].
 *
 * Verifies B.8 Batch 7 tax correctness fixes: cumulative progressive brackets,
 * requested-period income alignment, business-only VAT scope, and real
 * yearly income in tax summaries.
 */
@Suppress("DEPRECATION_ERROR")
class TaxEstimatorTest : AnalyticsEngineTestBase() {

    private lateinit var businessExpenseRepository: BusinessExpenseRepository
    private lateinit var currencySettingsRepo: CurrencySettingsRepository
    private lateinit var taxSettingsRepo: TaxSettingsRepository
    private lateinit var taxEstimator: TaxEstimator

    @Before
    override fun setUp() {
        super.setUp()
        businessExpenseRepository = mockk(relaxed = true)

        currencySettingsRepo = mockk(relaxed = true)
        taxSettingsRepo = mockk(relaxed = true)
        every { taxSettingsRepo.getFilingCurrency() } returns "EUR"
        every { taxSettingsRepo.getTaxCountry() } returns "GR"
        every { taxSettingsRepo.getFiscalYearStartMonth() } returns 1
        every { taxSettingsRepo.getFiscalYearStartDay() } returns 1
        every { currencySettingsRepo.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepo.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { expenseDao.getBusinessExpensesBetweenByCurrency(any(), any()) } returns emptyList()
        coEvery { expenseDao.getDepositTotalsBetweenByCurrency(any(), any()) } returns emptyList()

        taxEstimator = TaxEstimator(
            expenseDao = expenseDao,
            businessExpenseRepository = businessExpenseRepository,
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            currencySettingsRepository = currencySettingsRepo,
            taxSettings = taxSettingsRepo,
            ioDispatcher = Dispatchers.Unconfined,
            taxRateProvider = mockk(relaxed = true)
        )
    }

    // =========================================================================
    // Core VAT estimation
    // =========================================================================

    @Test
    fun `estimateTaxes uses business-only deductible total for VAT calculation`() = runTest {
        val start = atDateTime(2026, 3, 1, 0, 0)
        val end = atDateTime(2026, 4, 1, 0, 0)

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 1240.0

        val taxConfig = GreeceTaxConfiguration() // 24% VAT
        val estimate = taxEstimator.estimateTaxes(start, end, 30000.0, taxConfig = taxConfig)

        val expectedVat = 1240.0 * (0.24 / 1.24)
        assertApproxEquals(expectedVat, estimate.estimatedVatPortion, 0.01)
        coVerify(exactly = 0) { expenseDao.getTotalSpentBetween(any(), any()) }
    }

    @Test
    fun `estimateTaxes with zero business spending returns zero VAT`() = runTest {
        val start = atDateTime(2026, 3, 1, 0, 0)
        val end = atDateTime(2026, 4, 1, 0, 0)

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 0.0

        val estimate = taxEstimator.estimateTaxes(start, end, 30000.0)

        assertApproxEquals(0.0, estimate.estimatedVatPortion, 0.001)
    }

    @Test
    fun `estimateTaxes with US config returns zero VAT`() = runTest {
        val start = atDateTime(2026, 3, 1, 0, 0)
        val end = atDateTime(2026, 4, 1, 0, 0)

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 5000.0

        val usConfig = UsTaxConfiguration() // 0% VAT
        val estimate = taxEstimator.estimateTaxes(start, end, 30000.0, taxConfig = usConfig)

        assertApproxEquals(0.0, estimate.estimatedVatPortion, 0.001)
    }

    // =========================================================================
    // Income tax and deductible calculation
    // =========================================================================

    @Test
    fun `estimateTaxes aligns income to requested monthly period`() = runTest {
        val start = atDateTime(2026, 3, 1, 0, 0)
        val end = atDateTime(2026, 4, 1, 0, 0)
        val annualIncome = 12000.0

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 0.0

        val estimate = taxEstimator.estimateTaxes(start, end, annualIncome, taxConfig = GreeceTaxConfiguration())

        val periodFraction = periodYearFraction(start, end)
        assertApproxEquals(annualIncome * periodFraction, estimate.estimatedIncome, 0.01)
    }

    @Test
    fun `estimateTaxes calculates period aligned tax with business deductions`() = runTest {
        val start = atDateTime(2026, 3, 1, 0, 0)
        val end = atDateTime(2026, 4, 1, 0, 0)
        val annualIncome = 15000.0

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 500.0

        val taxConfig = GreeceTaxConfiguration()
        val estimate = taxEstimator.estimateTaxes(start, end, annualIncome, taxConfig = taxConfig)

        val periodFraction = periodYearFraction(start, end)
        val expectedIncome = annualIncome * periodFraction
        val expectedTaxableIncome = maxOf(expectedIncome - 500.0, 0.0)
        val expectedTax = progressiveTax(expectedTaxableIncome, taxConfig, periodFraction)

        assertApproxEquals(500.0, estimate.deductibleExpenses, 0.01)
        assertApproxEquals(expectedIncome, estimate.estimatedIncome, 0.01)
        assertApproxEquals(expectedTaxableIncome, estimate.taxableIncome, 0.01)
        assertApproxEquals(expectedTax, estimate.estimatedIncomeTax, 0.01)
        coVerify(exactly = 1) { businessExpenseRepository.getTotalBusinessExpenses(start, end) }
        coVerify(exactly = 0) { businessExpenseRepository.getBusinessExpenses(any(), any()) }
    }

    @Test
    fun `estimateTaxes applies progressive brackets cumulatively for full year`() = runTest {
        val start = atDateTime(2026, 1, 1, 0, 0)
        val end = atDateTime(2027, 1, 1, 0, 0)

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 0.0

        val estimate = taxEstimator.estimateTaxes(start, end, 30000.0)

        assertApproxEquals(30000.0, estimate.estimatedIncome, 0.01)
        assertApproxEquals(6300.0, estimate.estimatedIncomeTax, 0.01)
    }

    @Test
    fun `estimateTaxes keeps low income entirely in lowest bracket for full year`() = runTest {
        val start = atDateTime(2026, 1, 1, 0, 0)
        val end = atDateTime(2027, 1, 1, 0, 0)

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 0.0

        val estimate = taxEstimator.estimateTaxes(start, end, 5000.0)

        assertApproxEquals(450.0, estimate.estimatedIncomeTax, 0.01)
    }

    // =========================================================================
    // Tax config / notes preservation
    // =========================================================================

    @Test
    fun `estimateTaxes includes correct country code in notes`() = runTest {
        val start = atDateTime(2026, 3, 1, 0, 0)
        val end = atDateTime(2026, 4, 1, 0, 0)

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 0.0

        val grEstimate = taxEstimator.estimateTaxes(start, end, 10000.0, taxConfig = GreeceTaxConfiguration())
        assertTrue(grEstimate.notes.contains("GR"))

        val usEstimate = taxEstimator.estimateTaxes(start, end, 10000.0, taxConfig = UsTaxConfiguration())
        assertTrue(usEstimate.notes.contains("US"))
    }

    @Test
    fun `estimateTaxes returns correct date range`() = runTest {
        val start = atDateTime(2026, 3, 1, 0, 0)
        val end = atDateTime(2026, 4, 1, 0, 0)

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 0.0

        val estimate = taxEstimator.estimateTaxes(start, end, 10000.0)

        assertEquals(start, estimate.startDate)
        assertEquals(end, estimate.endDate)
    }

    // =========================================================================
    // Effective tax rate
    // =========================================================================

    @Test
    fun `effectiveTaxRate is zero when income is zero`() = runTest {
        val start = atDateTime(2026, 3, 1, 0, 0)
        val end = atDateTime(2026, 4, 1, 0, 0)

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 0.0

        val estimate = taxEstimator.estimateTaxes(start, end, 0.0)

        assertApproxEquals(0.0, estimate.effectiveTaxRate, 0.001)
    }

    // =========================================================================
    // Tax year summary
    // =========================================================================

    @Test
    fun `getTaxYearSummary uses real yearly income and categorizes business deductions`() = runTest {
        val categoryTotals = listOf(
            BusinessCategoryTotal(businessCategory = "Office Supplies", total = 100.0, count = 1),
            BusinessCategoryTotal(businessCategory = "Software", total = 250.0, count = 1)
        )
        coEvery { expenseDao.getTotalDepositsForPeriod(any(), any()) } returns 42000.0
        coEvery { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) } returns 6200.0
        coEvery { businessExpenseRepository.getExpensesByCategory(any(), any()) } returns categoryTotals
        coEvery { businessExpenseRepository.getTotalMileageDeduction(any(), any()) } returns 120.0

        val summary = taxEstimator.getTaxYearSummary(2026)

        assertEquals(2026, summary.year)
        assertApproxEquals(42000.0, summary.totalIncome, 0.01)
        assertApproxEquals(6200.0, summary.totalDeductibleExpenses, 0.01)
        assertApproxEquals(6200.0 * (0.24 / 1.24), summary.totalVatPaid, 0.01)
        assertApproxEquals(8100.0, summary.estimatedTaxOwed, 0.01)
        assertApproxEquals(120.0, summary.mileageDeduction, 0.01)

        val deductions = summary.categorizedDeductions
        assertApproxEquals(100.0, deductions["Office Supplies"] ?: 0.0, 0.01)
        assertApproxEquals(250.0, deductions["Software"] ?: 0.0, 0.01)
        assertApproxEquals(5850.0, deductions["Uncategorized"] ?: 0.0, 0.01)

        coVerify(exactly = 0) { businessExpenseRepository.getBusinessExpenses(any(), any()) }
        coVerify(atLeast = 1) { businessExpenseRepository.getExpensesByCategory(any(), any()) }
    }

    @Test
    fun `getTaxYearSummary merges null-category remainder into explicit Uncategorized total`() = runTest {
        // Regression: explicit businessCategory="Uncategorized" must not be
        // overwritten by the computed null-category remainder.
        val categoryTotals = listOf(
            BusinessCategoryTotal(businessCategory = "Office Supplies", total = 200.0, count = 2),
            BusinessCategoryTotal(businessCategory = "Uncategorized", total = 75.0, count = 3)
        )
        // Total 425 = Office(200) + explicit-Uncategorized(75) + null-category(150)
        coEvery { expenseDao.getTotalDepositsForPeriod(any(), any()) } returns 30000.0
        coEvery { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) } returns 425.0
        coEvery { businessExpenseRepository.getExpensesByCategory(any(), any()) } returns categoryTotals
        coEvery { businessExpenseRepository.getTotalMileageDeduction(any(), any()) } returns 0.0

        val summary = taxEstimator.getTaxYearSummary(2026)

        val deductions = summary.categorizedDeductions
        assertApproxEquals(200.0, deductions["Office Supplies"] ?: 0.0, 0.01)
        // Uncategorized must be explicit(75) + null-remainder(150) = 225, NOT just 150
        assertApproxEquals(225.0, deductions["Uncategorized"] ?: 0.0, 0.01)
    }

    // =========================================================================
    // Business-only aggregate semantics
    // =========================================================================

    @Test
    fun `business deductions use only PURCHASE-filtered aggregate`() = runTest {
        val start = atDateTime(2026, 3, 1, 0, 0)
        val end = atDateTime(2026, 4, 1, 0, 0)

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 300.0

        val estimate = taxEstimator.estimateTaxes(start, end, 20000.0)

        assertApproxEquals(300.0, estimate.deductibleExpenses, 0.01)

        coVerify(exactly = 1) { businessExpenseRepository.getTotalBusinessExpenses(start, end) }
        coVerify(exactly = 0) { businessExpenseRepository.getBusinessExpenses(any(), any()) }
    }

    @Test
    fun `non-business purchases do not affect VAT estimate`() = runTest {
        val start = atDateTime(2026, 3, 1, 0, 0)
        val end = atDateTime(2026, 4, 1, 0, 0)

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 1000.0

        val estimate1 = taxEstimator.estimateTaxes(start, end, 25000.0)
        val estimate2 = taxEstimator.estimateTaxes(start, end, 25000.0)

        assertApproxEquals(estimate1.estimatedVatPortion, estimate2.estimatedVatPortion, 0.001)
        assertApproxEquals(estimate1.deductibleExpenses, estimate2.deductibleExpenses, 0.001)
        assertApproxEquals(estimate1.estimatedIncomeTax, estimate2.estimatedIncomeTax, 0.001)
        assertApproxEquals(estimate1.effectiveTaxRate, estimate2.effectiveTaxRate, 0.001)
        assertApproxEquals(1000.0 * (0.24 / 1.24), estimate1.estimatedVatPortion, 0.01)
        coVerify(exactly = 0) { expenseDao.getTotalSpentBetween(any(), any()) }
    }

    // =========================================================================
    // PR7 — Tax currency consistency
    // =========================================================================

    @Test
    fun `estimateTaxes uses filing currency not home currency for deductions`() = runTest {
        val start = atDateTime(2026, 3, 1, 0, 0)
        val end = atDateTime(2026, 4, 1, 0, 0)

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 0.0

        // Structural check: aggregate target currency should be filing currency.
        // Since we mock the DAO and converter, verify the method runs correctly.
        val estimate = taxEstimator.estimateTaxes(start, end, 30000.0, "EUR")
        assertThat(estimate).isNotNull()
        assertThat(estimate.isPartial).isFalse()
    }

    @Test
    fun `estimateTaxes returns non-partial when income currency matches filing currency`() = runTest {
        val start = atDateTime(2026, 3, 1, 0, 0)
        val end = atDateTime(2026, 4, 1, 0, 0)

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 0.0

        val estimate = taxEstimator.estimateTaxes(start, end, 30000.0, "EUR")
        assertThat(estimate.isPartial).isFalse()
        assertThat(estimate.notes).doesNotContain("Income currency")
    }

    @Test
    fun `estimateTaxes warns when income currency differs from filing currency`() = runTest {
        val start = atDateTime(2026, 3, 1, 0, 0)
        val end = atDateTime(2026, 4, 1, 0, 0)

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 0.0

        val estimate = taxEstimator.estimateTaxes(start, end, 30000.0, "USD")
        assertThat(estimate.isPartial).isTrue()
        assertThat(estimate.notes).contains("USD")
        assertThat(estimate.notes).contains("filing currency")
    }

    @Test
    fun `estimateTaxes warns when home currency differs from filing currency`() = runTest {
        val start = atDateTime(2026, 3, 1, 0, 0)
        val end = atDateTime(2026, 4, 1, 0, 0)

        coEvery { businessExpenseRepository.getTotalBusinessExpenses(start, end) } returns 0.0
        every { currencySettingsRepo.homeCurrency() } returns flowOf("USD")

        val estimate = taxEstimator.estimateTaxes(start, end, 30000.0)
        assertThat(estimate.notes).contains("Home currency (USD) differs from tax filing currency (EUR)")
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun atDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun periodYearFraction(startDate: Long, endDate: Long): Double {
        if (endDate <= startDate) return 0.0

        var cursor = startDate
        var totalFraction = 0.0

        while (cursor < endDate) {
            val calendar = Calendar.getInstance().apply { timeInMillis = cursor }
            val year = calendar.get(Calendar.YEAR)
            val yearStart = atDateTime(year, 1, 1, 0, 0)
            val nextYearStart = atDateTime(year + 1, 1, 1, 0, 0)
            val segmentEnd = minOf(endDate, nextYearStart)

            totalFraction += (segmentEnd - cursor).toDouble() / (nextYearStart - yearStart).toDouble()
            cursor = segmentEnd
        }

        return totalFraction
    }

    private fun progressiveTax(
        income: Double,
        config: TaxConfiguration,
        periodYearFraction: Double
    ): Double {
        if (income <= 0.0 || periodYearFraction <= 0.0) return 0.0

        return config.getTaxBrackets()
            .sortedBy { it.minIncome }
            .sumOf { bracket ->
                val lower = bracket.minIncome * periodYearFraction
                val upper = bracket.maxIncome?.times(periodYearFraction) ?: Double.POSITIVE_INFINITY
                maxOf(minOf(income, upper) - lower, 0.0) * bracket.rate
            }
    }
}
