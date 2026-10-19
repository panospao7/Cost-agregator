package com.yourname.expensetracker.domain.tax

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.repository.BusinessExpenseRepository
import com.yourname.expensetracker.data.repository.TaxSettingsRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * CRITICAL TEST (HIGH-6): Tax Calculation Accuracy
 *
 * Tests the tax configuration tables and proves the progressive-bracket and VAT
 * arithmetic through the production [TaxEstimator.estimateTaxes].
 *
 * B3-4: the former version recomputed progressive tax and VAT with test-local
 * helper functions and asserted against its own copy (vacuous), and carried an
 * @Ignore'd test of a local VAT-extraction helper. Bracket arithmetic is now
 * exercised through the real estimator over a full tax year (period year
 * fraction exactly 1.0), and results are compared at exact cent precision.
 * The estimator is the only production owner of progressive-tax arithmetic;
 * [TaxConfiguration] itself is a pure data table.
 */
class TaxCalculationTest {

    private lateinit var businessExpenseRepository: BusinessExpenseRepository
    private lateinit var taxEstimator: TaxEstimator
    private lateinit var taxRateProvider: TaxRateProvider

    private val fullYearStart: Long = LocalDate.of(2026, 1, 1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val fullYearEnd: Long = LocalDate.of(2027, 1, 1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val expenseDao = mockk<ExpenseDao>(relaxed = true)
        coEvery { expenseDao.getBusinessExpensesBetweenByCurrency(any(), any()) } returns emptyList()
        coEvery { expenseDao.getDepositTotalsBetweenByCurrency(any(), any()) } returns emptyList()

        businessExpenseRepository = mockk(relaxed = true)
        coEvery { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) } returns 0.0

        val taxSettings = mockk<TaxSettingsRepository>(relaxed = true)
        every { taxSettings.getFilingCurrency() } returns "EUR"
        every { taxSettings.getTaxCountry() } returns "GR"
        every { taxSettings.getFiscalYearStartMonth() } returns 1
        every { taxSettings.getFiscalYearStartDay() } returns 1

        val currencySettings = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencySettings.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettings.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        // Explicit provider stub: by default the provider reports no usable rate (0.0),
        // so the estimator uses the configured country VAT rate. Individual tests
        // override this stub to prove provider precedence.
        taxRateProvider = mockk(relaxed = true)
        stubProviderRate(0.0)

        taxEstimator = TaxEstimator(
            expenseDao = expenseDao,
            businessExpenseRepository = businessExpenseRepository,
            timeProvider = mockk<TimeProvider>(relaxed = true),
            currencyConverter = mockk(relaxed = true),
            currencySettingsRepository = currencySettings,
            taxSettings = taxSettings,
            ioDispatcher = Dispatchers.Unconfined,
            taxRateProvider = taxRateProvider,
            multiCurrencyRepository = null
        )
    }

    // ==================== CONFIGURATION TABLES ====================

    @Test
    fun `greece configuration table has 24 percent VAT and three brackets`() {
        val config = GreeceTaxConfiguration()

        assertThat(config.getVatRate()).isEqualTo(0.24)

        val brackets = config.getTaxBrackets()
        assertThat(brackets.map { Triple(it.minIncome, it.maxIncome, it.rate) }).containsExactly(
            Triple(0.0, 10000.0, 0.09),
            Triple(10000.0, 20000.0, 0.22),
            Triple(20000.0, null, 0.32)
        ).inOrder()
    }

    @Test
    fun `US VAT rate is 0 percent`() {
        assertThat(UsTaxConfiguration().getVatRate()).isEqualTo(0.0)
    }

    @Test
    fun `US configuration table has three contiguous brackets`() {
        val brackets = UsTaxConfiguration().getTaxBrackets()

        assertThat(brackets.map { Triple(it.minIncome, it.maxIncome, it.rate) }).containsExactly(
            Triple(0.0, 11000.0, 0.10),
            Triple(11000.0, 44725.0, 0.12),
            Triple(44725.0, 95375.0, 0.22)
        ).inOrder()
    }

    @Test
    fun `tax bracket names are not empty`() {
        GreeceTaxConfiguration().getTaxBrackets().forEach { bracket ->
            assertThat(bracket.name).isNotEmpty()
        }
    }

    @Test
    fun `tax bracket rates are between 0 and 1`() {
        GreeceTaxConfiguration().getTaxBrackets().forEach { bracket ->
            assertThat(bracket.rate).isAtLeast(0.0)
            assertThat(bracket.rate).isAtMost(1.0)
        }
    }

    @Test
    fun `tax brackets have valid min and max`() {
        GreeceTaxConfiguration().getTaxBrackets().forEach { bracket ->
            assertThat(bracket.minIncome).isAtLeast(0.0)
            if (bracket.maxIncome != null) {
                assertThat(bracket.maxIncome!!).isGreaterThan(bracket.minIncome)
            }
        }
    }

    // ==================== GREECE PROGRESSIVE TAX (production estimator) ====================

    @Test
    fun `tax on 5000 euros income in greece is 450 euros at the 9 percent bracket`() = runTest {
        assertEquals(cents("450.00"), twoDp(estimateTax(GreeceTaxConfiguration(), 5000.0)))
    }

    @Test
    fun `tax on 10000 euros income in greece is 900 euros entirely in first bracket`() = runTest {
        assertEquals(cents("900.00"), twoDp(estimateTax(GreeceTaxConfiguration(), 10000.0)))
    }

    @Test
    fun `tax on income just above the 10000 boundary uses the higher rate for the excess`() = runTest {
        // 10000 * 9% = 900.00; the extra 1 euro is taxed at 22% = 0.22.
        assertEquals(cents("900.22"), twoDp(estimateTax(GreeceTaxConfiguration(), 10001.0)))
    }

    @Test
    fun `tax on 15000 euros income in greece spans two brackets`() = runTest {
        // 10000 * 9% + 5000 * 22% = 900 + 1100
        assertEquals(cents("2000.00"), twoDp(estimateTax(GreeceTaxConfiguration(), 15000.0)))
    }

    @Test
    fun `tax on fractional income 15000_55 in greece is 2000_12 to the cent`() = runTest {
        // 900 + 5000.55 * 22% = 900 + 1100.121
        assertEquals(cents("2000.12"), twoDp(estimateTax(GreeceTaxConfiguration(), 15000.55)))
    }

    @Test
    fun `tax on very high income in greece applies top rate to the unbounded bracket`() = runTest {
        // 900 + 2200 + (1_000_000 - 20_000) * 32%
        assertEquals(cents("316700.00"), twoDp(estimateTax(GreeceTaxConfiguration(), 1_000_000.0)))
    }

    @Test
    fun `tax on zero income returns zero`() = runTest {
        val estimate = estimate(GreeceTaxConfiguration(), 0.0)

        assertEquals(cents("0.00"), twoDp(estimate.estimatedIncomeTax))
        assertEquals(cents("0.00"), twoDp(estimate.effectiveTaxRate))
    }

    @Test
    fun `effective tax rate for 30000 euros in greece is exactly 21 percent`() = runTest {
        // tax 6300 / income 30000 = 21%
        val estimate = estimate(GreeceTaxConfiguration(), 30000.0)

        assertEquals(cents("6300.00"), twoDp(estimate.estimatedIncomeTax))
        assertEquals(cents("21.00"), twoDp(estimate.effectiveTaxRate))
    }

    @Test
    fun `deductible expenses reduce taxable income before brackets are applied`() = runTest {
        coEvery { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) } returns 10000.0

        val estimate = estimate(GreeceTaxConfiguration(), 30000.0)

        // taxable 20000 -> 900 + 2200
        assertEquals(cents("20000.00"), twoDp(estimate.taxableIncome))
        assertEquals(cents("3100.00"), twoDp(estimate.estimatedIncomeTax))
    }

    @Test
    fun `deductions larger than income floor taxable income and tax at zero`() = runTest {
        coEvery { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) } returns 50000.0

        val estimate = estimate(GreeceTaxConfiguration(), 30000.0)

        assertEquals(cents("0.00"), twoDp(estimate.taxableIncome))
        assertEquals(cents("0.00"), twoDp(estimate.estimatedIncomeTax))
    }

    // ==================== US PROGRESSIVE TAX (production estimator) ====================

    @Test
    fun `US 10 percent bracket applies to the first 11000`() = runTest {
        assertEquals(cents("500.00"), twoDp(estimateTax(UsTaxConfiguration(), 5000.0)))
    }

    @Test
    fun `US 12 percent bracket applies to income between 11000 and 44725`() = runTest {
        // 11000 * 10% + 9000 * 12% = 1100 + 1080
        assertEquals(cents("2180.00"), twoDp(estimateTax(UsTaxConfiguration(), 20000.0)))
    }

    @Test
    fun `US tax at the 44725 boundary stops before the 22 percent bracket`() = runTest {
        // 1100 + 33725 * 12% = 1100 + 4047
        assertEquals(cents("5147.00"), twoDp(estimateTax(UsTaxConfiguration(), 44725.0)))
    }

    // ==================== VAT: configured-rate fallback (provider returns no rate) ====================
    // The provider stub in setUp returns 0.0, which the estimator treats as "no provider
    // rate", so these cases prove the TaxConfiguration VAT rate fallback only.

    @Test
    fun `fallback VAT portion of 1240 euros VAT-inclusive deductible spend at configured 24 percent is 240`() = runTest {
        coEvery { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) } returns 1240.0

        val estimate = estimate(GreeceTaxConfiguration(), 30000.0)

        assertEquals(cents("240.00"), twoDp(estimate.estimatedVatPortion))
    }

    @Test
    fun `fallback VAT portion on 99_99 spend at configured 24 percent is 19_35 to the cent`() = runTest {
        // 99.99 * 0.24 / 1.24 = 19.3529...
        coEvery { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) } returns 99.99

        val estimate = estimate(GreeceTaxConfiguration(), 30000.0)

        assertEquals(cents("19.35"), twoDp(estimate.estimatedVatPortion))
    }

    @Test
    fun `fallback VAT portion is zero when there is no deductible spend`() = runTest {
        val estimate = estimate(GreeceTaxConfiguration(), 30000.0)

        assertEquals(cents("0.00"), twoDp(estimate.estimatedVatPortion))
    }

    @Test
    fun `fallback VAT portion is zero under the US configured 0 percent rate with deductible spend`() = runTest {
        coEvery { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) } returns 1240.0

        val estimate = estimate(UsTaxConfiguration(), 30000.0)

        assertEquals(cents("0.00"), twoDp(estimate.estimatedVatPortion))
    }

    // ==================== VAT: provider rate precedence ====================

    @Test
    fun `provider VAT rate takes precedence over the US configured 0 percent rate`() = runTest {
        // Known production issue (recorded for the release work, NOT asserted as correct
        // here): DemoTaxRateProvider returns percent units (24.0) while TaxEstimator
        // expects a fraction. This test stubs the unit the estimator expects (0.24).
        stubProviderRate(0.24)
        coEvery { businessExpenseRepository.getTotalBusinessExpenses(any(), any()) } returns 1240.0

        val estimate = estimate(UsTaxConfiguration(), 30000.0)

        // 1240 * 0.24 / 1.24 = 240.00 from the PROVIDER rate; US config alone would give 0.
        assertEquals(cents("240.00"), twoDp(estimate.estimatedVatPortion))
        coVerify { taxRateProvider.getRate("GR", null) }
    }

    // ==================== FACTORY TESTS ====================

    @Test
    fun `factory returns greece configuration for GR code`() {
        val config = TaxConfigurationFactory.getConfiguration("GR")

        assertThat(config.getCountryCode()).isEqualTo("GR")
        assertThat(config.getVatRate()).isEqualTo(0.24)
    }

    @Test
    fun `factory returns US configuration for US code`() {
        val config = TaxConfigurationFactory.getConfiguration("US")

        assertThat(config.getCountryCode()).isEqualTo("US")
        assertThat(config.getVatRate()).isEqualTo(0.0)
    }

    @Test
    fun `factory defaults to greece for unknown country code`() {
        val config = TaxConfigurationFactory.getConfiguration("XX")

        assertThat(config.getCountryCode()).isEqualTo("GR")
    }

    @Test
    fun `factory defaults to greece when no code provided`() {
        val config = TaxConfigurationFactory.getConfiguration()

        assertThat(config.getCountryCode()).isEqualTo("GR")
    }

    @Test
    fun `current configuration returns greece by default`() {
        val config = TaxConfigurationFactory.getCurrentConfiguration()

        assertThat(config.getCountryCode()).isEqualTo("GR")
    }

    // ==================== CURRENCY TESTS ====================

    @Test
    fun `greece configuration uses EUR currency`() {
        val config = GreeceTaxConfiguration()

        assertThat(config.getCurrency()).isEqualTo("EUR")
    }

    @Test
    fun `US configuration uses USD currency`() {
        val config = UsTaxConfiguration()

        assertThat(config.getCurrency()).isEqualTo("USD")
    }

    // ==================== HELPERS (harness only, no tax logic) ====================

    /** Runs the production estimator over exactly one tax year (year fraction 1.0). */
    private suspend fun estimate(config: TaxConfiguration, annualIncome: Double): TaxEstimate =
        taxEstimator.estimateTaxes(
            startDate = fullYearStart,
            endDate = fullYearEnd,
            estimatedAnnualIncome = annualIncome,
            taxConfig = config
        )

    private suspend fun estimateTax(config: TaxConfiguration, annualIncome: Double): Double =
        estimate(config, annualIncome).estimatedIncomeTax

    private fun stubProviderRate(standardVatRate: Double) {
        coEvery { taxRateProvider.getRate(any(), any()) } returns TaxRateResult(
            standardVatRate = standardVatRate,
            currency = "EUR",
            country = "GR"
        )
    }

    private fun twoDp(value: Double): BigDecimal =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)

    private fun cents(value: String): BigDecimal = BigDecimal(value)
}
