package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.NormalizedExpenseSnapshot
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.toExpenseSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MonteCarloSpendingSimulatorTest {

    private lateinit var historicalDistribution: HistoricalSpendingDistribution
    private lateinit var dataQualityAssessor: DataQualityAssessor
    private lateinit var timeProvider: TimeProvider
    private lateinit var simulator: MonteCarloSpendingSimulator

    @Before
    fun setUp() {
        historicalDistribution = mockk(relaxed = true)
        dataQualityAssessor = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)

        simulator = MonteCarloSpendingSimulator(
            historicalDistribution = historicalDistribution,
            dataQualityAssessor = dataQualityAssessor,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `monte_carlo_zero_history_returns_deterministic_degraded_result`() = runTest {
        every { timeProvider.now() } returns ms(2026, 4, 15)
        coEvery { historicalDistribution.computeDistributionResult("EUR") } returns HistoricalDistributionResult.NoFit()

        val degradedConfidence = SimulationConfidence(
            score = 0.0,
            level = ConfidenceLevel.LOW,
            reason = "No historical spending data available"
        )
        every { dataQualityAssessor.assess(null, 0) } returns degradedConfidence

        // Hard-coded: 420.0 spent + 80.0 upcoming. Budget 700.0 >= 500.0, so no probability is offered.
        val result = requireNotNull(
            simulator.simulate(
                spentToDate = 420.0,
                knownUpcoming = 80.0,
                budgetAmount = 700.0,
                displayCurrency = "EUR"
            )
        )

        assertApproxEquals(500.0, result.percentile10, 0.0001)
        assertApproxEquals(500.0, result.percentile25, 0.0001)
        assertApproxEquals(500.0, result.percentile50, 0.0001)
        assertApproxEquals(500.0, result.percentile75, 0.0001)
        assertApproxEquals(500.0, result.percentile90, 0.0001)

        assertNull(result.probabilityUnderBudget)
        assertEquals(0, result.metadata.iterations)
        assertEquals(15, result.metadata.daysRemaining)
        assertEquals(ConfidenceLevel.LOW, result.confidence.level)
        assertEquals(0.0, result.confidence.score, 0.0)
        // The simulator passes the assessor result through unchanged; equality with the stubbed reason.
        assertEquals("No historical spending data available", result.confidence.reason)
    }

    private data class DaysRemainingRow(
        val label: String,
        val year: Int,
        val month: Int,
        val day: Int,
        val usableFitAvailable: Boolean,
        val expectedDaysRemaining: Int
    )

    @Test
    fun `daysRemaining table reports calendar days left and computedAt from simulate output`() = runTest {
        val rows = listOf(
            DaysRemainingRow("leap Feb 29 2024 end of month NoFit", 2024, 2, 29, false, 0),
            DaysRemainingRow("leap Feb 28 2024 usable fit", 2024, 2, 28, true, 1),
            DaysRemainingRow("leap Feb 15 2024 usable fit", 2024, 2, 15, true, 14),
            DaysRemainingRow("non leap Feb 28 2023 end of month NoFit", 2023, 2, 28, false, 0)
        )
        assertEquals(4, rows.size)

        for (row in rows) {
            setUp() // fresh mocks and simulator per row so stubs never leak between rows
            every { timeProvider.now() } returns ms(row.year, row.month, row.day)
            if (row.usableFitAvailable) {
                coEvery { historicalDistribution.computeDistributionResult("EUR") } returns
                    HistoricalDistributionResult.Available(usableFit())
                every { dataQualityAssessor.assess(any(), any()) } returns highConfidence()
            } else {
                coEvery { historicalDistribution.computeDistributionResult("EUR") } returns
                    HistoricalDistributionResult.NoFit()
            }

            val result = requireNotNull(
                simulator.simulate(
                    spentToDate = 100.0,
                    knownUpcoming = 50.0,
                    budgetAmount = 1000.0,
                    displayCurrency = "EUR"
                )
            ) { "[${row.label}] result was null" }

            assertEquals("[${row.label}] daysRemaining", row.expectedDaysRemaining, result.metadata.daysRemaining)
            assertEquals("[${row.label}] computedAt", ms(row.year, row.month, row.day), result.metadata.computedAt)
        }
    }

    private data class UnavailableRow(
        val label: String,
        val day: Int,
        val currency: String,
        val reason: MoneyDisplayUnavailableReasonCode,
        val totalInputCount: Int,
        val includedInputCount: Int,
        val excludedInputCount: Int
    )

    @Test
    fun `unavailable historical money never becomes a deterministic or under budget forecast`() = runTest {
        val rows = listOf(
            UnavailableRow(
                "mid month EUR conversion unavailable with counts", 15, "EUR",
                MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE, 4, 3, 1
            ),
            UnavailableRow(
                "end month GBP conversion unavailable", 30, "GBP",
                MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE, 0, 0, 0
            ),
            UnavailableRow(
                "end month GBP invalid target currency", 30, "GBP",
                MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY, 0, 0, 0
            )
        )
        assertEquals(3, rows.size)

        for (row in rows) {
            setUp() // fresh mocks per row so the exactly = 0 verification is per row
            every { timeProvider.now() } returns ms(2026, 4, row.day)
            coEvery { historicalDistribution.computeDistributionResult(row.currency) } returns
                HistoricalDistributionResult.Unavailable(
                    reasonCode = row.reason,
                    totalInputCount = row.totalInputCount,
                    includedInputCount = row.includedInputCount,
                    excludedInputCount = row.excludedInputCount
                )

            val error = runCatching {
                simulator.simulate(100.0, 50.0, 1000.0, displayCurrency = row.currency)
            }.exceptionOrNull()

            assertTrue("[${row.label}] expected MoneyDisplayUnavailableException but was $error", error is MoneyDisplayUnavailableException)
            assertEquals("[${row.label}] reasonCode", row.reason, (error as MoneyDisplayUnavailableException).reasonCode)
            verify(exactly = 0) { dataQualityAssessor.assess(any(), any()) }
        }
    }

    @Test
    fun `end month distribution cancellation retains exception identity`() = runTest {
        every { timeProvider.now() } returns ms(2026, 4, 30)
        val cancellation = CancellationException("TEST_CANCELLED")
        coEvery { historicalDistribution.computeDistributionResult("GBP") } throws cancellation

        val error = runCatching {
            simulator.simulate(100.0, 50.0, 1000.0, displayCurrency = "GBP")
        }.exceptionOrNull()

        assertSame(cancellation, error)
    }

    @Test
    fun invalidDisplayCurrencyFailsBeforeEndMonthFallback() = runTest {
        every { timeProvider.now() } returns ms(2026, 4, 30)

        val error = runCatching {
            simulator.simulate(
                spentToDate = 100.0,
                knownUpcoming = 50.0,
                budgetAmount = 1_000.0,
                displayCurrency = "XYZ"
            )
        }.exceptionOrNull()

        assertTrue(error is MoneyDisplayUnavailableException)
        assertEquals(
            MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY,
            (error as MoneyDisplayUnavailableException).reasonCode
        )
        coVerify(exactly = 0) { historicalDistribution.computeDistributionResult(any()) }
        verify(exactly = 0) { dataQualityAssessor.assess(any(), any()) }
    }

    @Test
    fun `end month genuine no fit retains deterministic total and probability`() = runTest {
        every { timeProvider.now() } returns ms(2026, 4, 30)
        coEvery { historicalDistribution.computeDistributionResult("GBP") } returns HistoricalDistributionResult.NoFit()
        every { dataQualityAssessor.assess(null, 0) } returns
            SimulationConfidence(0.0, ConfidenceLevel.LOW, "No historical spending data available")

        val result = requireNotNull(simulator.simulate(100.0, 50.0, 1000.0, displayCurrency = "GBP"))

        assertEquals("GBP", result.displayCurrency)
        assertApproxEquals(150.0, result.percentile10, 0.0001)
        assertApproxEquals(150.0, result.percentile90, 0.0001)
        assertEquals(1.0, requireNotNull(result.probabilityUnderBudget), 0.0)
        assertEquals(0, result.metadata.iterations)
    }

    @Test
    fun `every available simulation branch preserves historical money quality`() = runTest {
        val moneyQuality = HistoricalMoneyQuality(
            quality = ConversionQuality.COMPLETE,
            requestedRateBasis = RateBasis.TRANSACTION_DATE,
            actualRateBasis = RateBasis.TRANSACTION_DATE,
            totalInputCount = 48,
            includedInputCount = 48,
            latestRateTimestamp = 123456L
        )
        val fit = usableFit().copy(displayCurrency = "GBP", moneyQuality = moneyQuality)
        every { dataQualityAssessor.assess(any(), any()) } returns highConfidence()

        // (label, day, sigma, expected iterations): simulated, end-of-month, and unusable (sigma 0) branches.
        val rows = listOf(
            Triple("mid month simulated", 15 to 0.35, 1000),
            Triple("end of month deterministic", 30 to 0.35, 0),
            Triple("mid month zero sigma degraded", 15 to 0.0, 0)
        )
        assertEquals(3, rows.size)

        for ((label, dayAndSigma, expectedIterations) in rows) {
            val (day, sigma) = dayAndSigma
            every { timeProvider.now() } returns ms(2026, 4, day)
            coEvery { historicalDistribution.computeDistributionResult("GBP") } returns
                HistoricalDistributionResult.Available(fit.copy(sigma = sigma))

            val result = requireNotNull(
                simulator.simulate(100.0, 50.0, 1000.0, displayCurrency = "GBP")
            ) { "[$label] result was null" }

            assertEquals("[$label] displayCurrency", "GBP", result.displayCurrency)
            assertSame("[$label] historicalMoneyQuality", moneyQuality, result.metadata.historicalMoneyQuality)
            assertEquals("[$label] iterations", expectedIterations, result.metadata.iterations)
            if (expectedIterations == 0) {
                assertApproxEquals(150.0, result.percentile50, 0.0001, "[$label] percentile50")
            }
        }
    }

    @Test
    fun `bl01 regression 6 weeks at 100 and 2 quiet weeks produces sane 14 day p50 and p90`() = runTest {
        // April 16, 2026 in a 30-day month -> 14 days remaining.
        val now = ms(2026, 4, 16)
        every { timeProvider.now() } returns now

        val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
        val normalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)
        val currencySettings = mockk<CurrencySettingsRepository>()
        every { currencySettings.homeCurrency() } returns flowOf("EUR")
        coEvery { normalizer.normalizeExpenses(any(), any()) } answers {
            val rows = firstArg<List<Expense>>()
            AnalyticsNormalizationResult(
                homeCurrency = "EUR",
                normalizedExpenses = rows.map {
                    NormalizedExpenseSnapshot(it.toExpenseSnapshot(), it.currency, it.effectiveAmount, it.effectiveAmount)
                },
                includedExpenses = rows.map { it.toExpenseSnapshot() },
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = rows.size
            )
        }

        val currentWeekStart = TimePeriodUtils.getStartOfWeek(now)
        // 6 positive weeks around 100.00 (small natural spread so sigma > 0) + 2 quiet weeks (weeks 3 and 6 = 0.00).
        val activeWeeks = mapOf(
            1 to 96.0,
            2 to 98.0,
            4 to 100.0,
            5 to 100.0,
            7 to 102.0,
            8 to 104.0
        )
        val expenses = mutableListOf<Expense>()
        var id = 1L
        for ((weekOffset, total) in activeWeeks) {
            val weekStart = TimePeriodUtils.addDays(currentWeekStart, -(weekOffset * 7))
            for (dayOffset in 0..3) {
                val date = TimePeriodUtils.addDays(weekStart, dayOffset) + 36_000_000L
                expenses.add(
                    Expense(
                        id = id++,
                        amount = total / 4.0,
                        merchant = "TestMerchant",
                        transactionType = TransactionType.PURCHASE,
                        date = date
                    )
                )
            }
        }
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns expenses

        val realDistribution = HistoricalSpendingDistribution(
            expenseRepository = expenseRepository,
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = normalizer,
            currencySettingsRepository = currencySettings
        )
        every { dataQualityAssessor.assess(any(), any()) } returns highConfidence()
        val realSimulator = MonteCarloSpendingSimulator(
            historicalDistribution = realDistribution,
            dataQualityAssessor = dataQualityAssessor,
            timeProvider = timeProvider
        )

        val result = requireNotNull(
            realSimulator.simulate(
                spentToDate = 0.0,
                knownUpcoming = 0.0,
                budgetAmount = 500.0,
                displayCurrency = "EUR"
            )
        )

        assertEquals(14, result.metadata.daysRemaining)
        // Hand-computed old bug: mu = 2.3026 (exp=10), sigma = 4.2636 -> 14d P50 ~ 20, P90 ~ 4,720.
        // True 14d spend (2 weeks at ~100/wk, optionally scaled by 6/8 active rate):
        // P50 must be in a sane band [100, 260] (never ~20) and P90 in [130, 450] (never ~4,720).
        assertTrue(
            "14-day P50 should be in sane band [100.0, 260.0], but was ${result.percentile50}",
            result.percentile50 in 100.0..260.0
        )
        assertTrue(
            "14-day P90 should be plausible [130.0, 450.0] (old bug produced ~4720), but was ${result.percentile90}",
            result.percentile90 in 130.0..450.0
        )
        assertTrue("P90 must be >= P50", result.percentile90 >= result.percentile50)
    }

    @Test
    fun `implausible high sigma fit above ceiling degrades to deterministic instead of absurd p90`() = runTest {
        every { timeProvider.now() } returns ms(2026, 4, 16) // 14 days remaining
        // Direct fit with implausible sigma = 4.2636 (the BL-01 pathology).
        val pathologicalFit = usableFit().copy(
            mu = 2.302585,
            sigma = 4.2636,
            qualifyingWeekCount = 8,
            totalWeeksExamined = 8
        )
        coEvery { historicalDistribution.computeDistributionResult("EUR") } returns
            HistoricalDistributionResult.Available(pathologicalFit)
        every { dataQualityAssessor.assess(any(), any()) } returns
            SimulationConfidence(0.2, ConfidenceLevel.LOW, "Implausible variance")

        val result = requireNotNull(
            simulator.simulate(
                spentToDate = 200.0,
                knownUpcoming = 50.0,
                budgetAmount = 600.0,
                displayCurrency = "EUR"
            )
        )

        // With sigma ceiling enforced, an implausible fit (sigma = 4.2636) must degrade to
        // deterministic (spentToDate + knownUpcoming = 250.0, 0 iterations) rather than exploding to >4,000.
        assertEquals("Implausible sigma must take degraded 0-iteration path", 0, result.metadata.iterations)
        assertApproxEquals(250.0, result.percentile50, 0.01)
        assertApproxEquals(250.0, result.percentile90, 0.01)
    }

    private fun usableFit(): DistributionFit = DistributionFit(
        mu = 5.8,
        sigma = 0.35,
        qualifyingWeekCount = 12,
        totalWeeksExamined = 18,
        trimmedWeeklyTotals = listOf(420.0, 500.0, 560.0, 610.0, 700.0, 760.0),
        allWeeklyTotals = listOf(400.0, 480.0, 520.0, 610.0, 730.0, 810.0, 920.0, 1010.0),
        displayCurrency = "EUR"
    )

    private fun highConfidence(): SimulationConfidence = SimulationConfidence(
        score = 0.82,
        level = ConfidenceLevel.HIGH,
        reason = "Based on 12 weeks"
    )

    private fun ms(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
