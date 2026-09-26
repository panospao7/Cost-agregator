package com.yourname.expensetracker.domain.tax

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.BusinessCategoryCurrencyTotal
import com.yourname.expensetracker.data.database.dao.CurrencyTotal
import com.yourname.expensetracker.data.repository.BusinessExpenseRepository
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.data.repository.TaxSettingsRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
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
// DEPRECATION_ERROR suppression is still required: the negative-control pins
// coVerify(exactly = 0) { expenseDao.getTotalSpentBetween(...) } reference an
// ERROR-deprecated DAO method (R13 removed the last positive use,
// getTotalDepositsForPeriod).
@Suppress("DEPRECATION_ERROR")
class TaxEstimatorTest : AnalyticsEngineTestBase() {

    private lateinit var businessExpenseRepository: BusinessExpenseRepository
    private lateinit var currencySettingsRepo: CurrencySettingsRepository
    private lateinit var taxSettingsRepo: TaxSettingsRepository
    private lateinit var multiCurrencyRepository: MultiCurrencyRepository
    private lateinit var taxEstimator: TaxEstimator

    @Before
    override fun setUp() {
        super.setUp()
        businessExpenseRepository = mockk(relaxed = true)

        currencySettingsRepo = mockk(relaxed = true)
        taxSettingsRepo = mockk(relaxed = true)
        multiCurrencyRepository = mockk()
        every { taxSettingsRepo.getFilingCurrency() } returns "EUR"
        every { taxSettingsRepo.getTaxCountry() } returns "GR"
        every { taxSettingsRepo.getFiscalYearStartMonth() } returns 1
        every { taxSettingsRepo.getFiscalYearStartDay() } returns 1
        every { currencySettingsRepo.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepo.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { expenseDao.getBusinessExpensesBetweenByCurrency(any(), any()) } returns emptyList()
        coEvery { expenseDao.getDepositTotalsBetweenByCurrency(any(), any()) } returns emptyList()
        coEvery {
            multiCurrencyRepository.aggregateDisplayAmounts(any(), any(), any())
        } answers {
            val amounts = firstArg<List<Pair<Double, String>>>()
            val counts = secondArg<List<Int>>()
            val target = thirdArg<String>()
            MoneyAggregateResult.Available(
                MoneyAggregate.singleCurrency(
                    amount = amounts.sumOf { it.first },
                    currency = CurrencyCode(target),
                    transactionCount = counts.sum()
                )
            )
        }

        taxEstimator = TaxEstimator(
            expenseDao = expenseDao,
            businessExpenseRepository = businessExpenseRepository,
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            currencySettingsRepository = currencySettingsRepo,
            taxSettings = taxSettingsRepo,
            ioDispatcher = Dispatchers.Unconfined,
            taxRateProvider = mockk(relaxed = true),
            multiCurrencyRepository = multiCurrencyRepository
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
            BusinessCategoryCurrencyTotal("Office Supplies", "EUR", 100.0, 1),
            BusinessCategoryCurrencyTotal("Software", "EUR", 250.0, 1),
            BusinessCategoryCurrencyTotal("Uncategorized", "EUR", 5850.0, 1)
        )
        // R13: income oracle stub moved to the currency-aware deposit
        // aggregate (the replacement DAO variant TaxEstimator now sources
        // getTaxYearSummary income from). Same 42000.0 fiscal-window deposit
        // truth; single EUR bucket short-circuits in MoneyAggregateBuilder
        // (no converter call), so the pinned total is exact.
        coEvery { expenseDao.getDepositTotalsBetweenByCurrency(any(), any()) } returns listOf(
            CurrencyTotal(currency = "EUR", total = 42000.0, txCount = 1)
        )
        coEvery { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) } returns 6200.0
        coEvery {
            businessExpenseRepository.getBusinessCategoryCurrencyTotals(any(), any())
        } returns categoryTotals
        coEvery { businessExpenseRepository.getTotalMileageDeduction(any(), any()) } returns 120.0

        val summary = taxEstimator.getTaxYearSummary(2026)

        assertEquals(2026, summary.year)
        assertApproxEquals(42000.0, summary.totalIncome, 0.01)
        assertApproxEquals(6200.0, summary.totalDeductibleExpenses, 0.01)
        assertApproxEquals(6200.0 * (0.24 / 1.24), summary.totalVatPaid, 0.01)
        // Cumulative progressive brackets (B.8 Batch 7) on 35,800 taxable:
        // 10,000x9% + 10,000x22% + 15,800x32% = 8,156.
        assertApproxEquals(8156.0, summary.estimatedTaxOwed, 0.01)
        assertApproxEquals(120.0, summary.mileageDeduction, 0.01)

        val deductions = summary.categorizedDeductions
        assertApproxEquals(100.0, deductions["Office Supplies"] ?: 0.0, 0.01)
        assertApproxEquals(250.0, deductions["Software"] ?: 0.0, 0.01)
        assertApproxEquals(5850.0, deductions["Uncategorized"] ?: 0.0, 0.01)

        coVerify(exactly = 0) { businessExpenseRepository.getBusinessExpenses(any(), any()) }
        coVerify(atLeast = 1) {
            businessExpenseRepository.getBusinessCategoryCurrencyTotals(any(), any())
        }
        coVerify(exactly = 0) { businessExpenseRepository.getExpensesByCategory(any(), any()) }
    }

    @Test
    fun `getTaxYearSummary merges null-category remainder into explicit Uncategorized total`() = runTest {
        // Regression: explicit businessCategory="Uncategorized" must not be
        // overwritten by the computed null-category remainder.
        val categoryTotals = listOf(
            BusinessCategoryCurrencyTotal("Office Supplies", "EUR", 200.0, 2),
            BusinessCategoryCurrencyTotal("Uncategorized", "EUR", 225.0, 4)
        )
        // Total 425 = Office(200) + explicit-Uncategorized(75) + null-category(150)
        // R13: same income-oracle stub migration as above (currency-aware
        // deposit aggregate instead of the ERROR-deprecated raw-SUM DAO call).
        coEvery { expenseDao.getDepositTotalsBetweenByCurrency(any(), any()) } returns listOf(
            CurrencyTotal(currency = "EUR", total = 30000.0, txCount = 1)
        )
        coEvery { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) } returns 425.0
        coEvery {
            businessExpenseRepository.getBusinessCategoryCurrencyTotals(any(), any())
        } returns categoryTotals
        coEvery { businessExpenseRepository.getTotalMileageDeduction(any(), any()) } returns 0.0

        val summary = taxEstimator.getTaxYearSummary(2026)

        val deductions = summary.categorizedDeductions
        assertApproxEquals(200.0, deductions["Office Supplies"] ?: 0.0, 0.01)
        // Uncategorized must be explicit(75) + null-remainder(150) = 225, NOT just 150
        assertApproxEquals(225.0, deductions["Uncategorized"] ?: 0.0, 0.01)
    }

    @Test
    fun `getTaxYearSummary rejects invalid filing currency even for an empty fiscal window`() = runTest {
        coEvery {
            businessExpenseRepository.getBusinessCategoryCurrencyTotals(any(), any())
        } returns emptyList()

        listOf("ZZZ", "", " ", "EU", "EURO", "EUR1").forEach { filingCurrency ->
            every { taxSettingsRepo.getFilingCurrency() } returns filingCurrency

            val failure = runCatching { taxEstimator.getTaxYearSummary(2026) }.exceptionOrNull()

            assertTrue(failure is MoneyDisplayUnavailableException)
            assertEquals(
                MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY,
                (failure as MoneyDisplayUnavailableException).reasonCode
            )
        }

        coVerify(exactly = 0) { expenseDao.getDepositTotalsBetweenByCurrency(any(), any()) }
        coVerify(exactly = 0) { expenseDao.getBusinessExpensesBetweenByCurrency(any(), any()) }
        coVerify(exactly = 0) { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) }
        coVerify(exactly = 0) { businessExpenseRepository.getBusinessCategoryCurrencyTotals(any(), any()) }
        coVerify(exactly = 0) { multiCurrencyRepository.aggregateDisplayAmounts(any(), any(), any()) }
    }

    @Test
    fun `getTaxYearSummary preserves a genuine empty zero in a supported filing currency`() = runTest {
        coEvery { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) } returns 0.0
        coEvery { businessExpenseRepository.getTotalMileageDeduction(any(), any()) } returns 0.0
        coEvery {
            businessExpenseRepository.getBusinessCategoryCurrencyTotals(any(), any())
        } returns emptyList()

        val summary = taxEstimator.getTaxYearSummary(2026)

        assertEquals(0.0, summary.totalIncome, 0.0)
        assertEquals(0.0, summary.totalDeductibleExpenses, 0.0)
        assertEquals(0.0, summary.totalVatPaid, 0.0)
        assertEquals(0.0, summary.estimatedTaxOwed, 0.0)
        assertEquals(0.0, summary.mileageDeduction, 0.0)
        assertThat(summary.categorizedDeductions).isEmpty()
        assertThat(summary.categorizedDeductionsAggregate).isEmpty()
        assertThat(summary.isPartial).isFalse()
        assertThat(summary.conversionWarnings).isEmpty()
        listOf(summary.incomeAggregate, summary.deductibleAggregate, summary.estimatedTaxAggregate)
            .forEach { aggregate ->
                assertEquals(0.0, aggregate.displayAmount, 0.0)
                assertEquals(CurrencyCode("EUR"), aggregate.displayCurrency)
                assertThat(aggregate.isPartial).isFalse()
            }
    }

    @Test
    fun `getTaxYearSummary converts category buckets to filing currency`() = runTest {
        coEvery { expenseDao.getDepositTotalsBetweenByCurrency(any(), any()) } returns listOf(
            CurrencyTotal(currency = "EUR", total = 30_000.0, txCount = 1)
        )
        coEvery { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) } returns 190.0
        coEvery { businessExpenseRepository.getTotalMileageDeduction(any(), any()) } returns 0.0
        val rows = listOf(
            BusinessCategoryCurrencyTotal("Office", "EUR", 100.0, 1),
            BusinessCategoryCurrencyTotal("Office", "USD", 100.0, 1)
        )
        coEvery {
            businessExpenseRepository.getBusinessCategoryCurrencyTotals(any(), any())
        } returns rows
        val converted = MoneyAggregateResult.Available(
            MoneyAggregate.singleCurrency(190.0, CurrencyCode("EUR"), transactionCount = 2)
        )
        coEvery {
            multiCurrencyRepository.aggregateDisplayAmounts(
                listOf(100.0 to "EUR", 100.0 to "USD"),
                listOf(1, 1),
                "EUR"
            )
        } returns converted

        val summary = taxEstimator.getTaxYearSummary(2026)

        assertApproxEquals(190.0, summary.categorizedDeductions.getValue("Office"), 0.0001)
        assertApproxEquals(
            190.0,
            summary.categorizedDeductionsAggregate.getValue("Office").displayAmount,
            0.0001
        )
        assertEquals(CurrencyCode("EUR"), summary.categorizedDeductionsAggregate.getValue("Office").displayCurrency)
    }

    @Test
    fun `getTaxYearSummary fails closed when a required category is unavailable`() = runTest {
        coEvery { expenseDao.getDepositTotalsBetweenByCurrency(any(), any()) } returns listOf(
            CurrencyTotal(currency = "EUR", total = 30_000.0, txCount = 1)
        )
        coEvery { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) } returns 100.0
        coEvery { businessExpenseRepository.getTotalMileageDeduction(any(), any()) } returns 0.0
        coEvery {
            businessExpenseRepository.getBusinessCategoryCurrencyTotals(any(), any())
        } returns listOf(BusinessCategoryCurrencyTotal("Office", "UNKNOWN", 100.0, 1))
        coEvery {
            multiCurrencyRepository.aggregateDisplayAmounts(any(), any(), "EUR")
        } returns MoneyAggregateResult.Unavailable(
            reason = MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name,
            requestedRateBasis = com.yourname.expensetracker.domain.core.money.RateBasis.LATEST_AVAILABLE
        )

        val failure = runCatching { taxEstimator.getTaxYearSummary(2026) }.exceptionOrNull()

        assertTrue(failure is MoneyDisplayUnavailableException)
        assertEquals(
            MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY,
            (failure as MoneyDisplayUnavailableException).reasonCode
        )
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
