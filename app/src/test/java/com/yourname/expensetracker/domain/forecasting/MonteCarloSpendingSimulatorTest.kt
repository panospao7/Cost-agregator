package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

        val spentToDate = 420.0
        val knownUpcoming = 80.0
        val expectedTotal = spentToDate + knownUpcoming

        val result = simulator.simulate(
            spentToDate = spentToDate,
            knownUpcoming = knownUpcoming,
            budgetAmount = 700.0,
            displayCurrency = "EUR"
        )

        assertTrue(result != null)
        result!!

        assertApproxEquals(expectedTotal, result.percentile10, 0.0001)
        assertApproxEquals(expectedTotal, result.percentile25, 0.0001)
        assertApproxEquals(expectedTotal, result.percentile50, 0.0001)
        assertApproxEquals(expectedTotal, result.percentile75, 0.0001)
        assertApproxEquals(expectedTotal, result.percentile90, 0.0001)

        assertEquals(0, result.metadata.iterations)
        assertEquals(ConfidenceLevel.LOW, result.confidence.level)
        assertTrue(result.confidence.reason.contains("No historical") || result.confidence.reason.contains("Rough estimate"))
    }

    @Test
    fun `leap year Feb 29 2024 reports 0 daysRemaining from simulate output`() = runTest {
        every { timeProvider.now() } returns ms(2024, 2, 29)
        coEvery { historicalDistribution.computeDistributionResult("EUR") } returns HistoricalDistributionResult.NoFit()

        val result = simulator.simulate(
            spentToDate = 100.0,
            knownUpcoming = 50.0,
            budgetAmount = 1000.0,
            displayCurrency = "EUR"
        )

        val nonNullResult = requireNotNull(result)

        assertEquals(0, nonNullResult.metadata.daysRemaining)
        assertEquals(ms(2024, 2, 29), nonNullResult.metadata.computedAt)
    }

    @Test
    fun `leap year Feb 28 2024 reports 1 dayRemaining from simulate output`() = runTest {
        every { timeProvider.now() } returns ms(2024, 2, 28)
        coEvery { historicalDistribution.computeDistributionResult("EUR") } returns
            HistoricalDistributionResult.Available(usableFit())
        every { dataQualityAssessor.assess(any(), any()) } returns highConfidence()

        val result = simulator.simulate(
            spentToDate = 100.0,
            knownUpcoming = 50.0,
            budgetAmount = 1000.0,
            displayCurrency = "EUR"
        )

        val nonNullResult = requireNotNull(result)

        assertEquals(1, nonNullResult.metadata.daysRemaining)
        assertEquals(ms(2024, 2, 28), nonNullResult.metadata.computedAt)
    }

    @Test
    fun `leap year Feb 15 2024 reports 14 daysRemaining from simulate output`() = runTest {
        every { timeProvider.now() } returns ms(2024, 2, 15)
        coEvery { historicalDistribution.computeDistributionResult("EUR") } returns
            HistoricalDistributionResult.Available(usableFit())
        every { dataQualityAssessor.assess(any(), any()) } returns highConfidence()

        val result = simulator.simulate(
            spentToDate = 100.0,
            knownUpcoming = 50.0,
            budgetAmount = 1000.0,
            displayCurrency = "EUR"
        )

        val nonNullResult = requireNotNull(result)

        assertEquals(14, nonNullResult.metadata.daysRemaining)
        assertEquals(ms(2024, 2, 15), nonNullResult.metadata.computedAt)
    }

    @Test
    fun `non leap year Feb 28 2023 reports 0 daysRemaining from simulate output`() = runTest {
        every { timeProvider.now() } returns ms(2023, 2, 28)
        coEvery { historicalDistribution.computeDistributionResult("EUR") } returns HistoricalDistributionResult.NoFit()

        val result = simulator.simulate(
            spentToDate = 100.0,
            knownUpcoming = 50.0,
            budgetAmount = 1000.0,
            displayCurrency = "EUR"
        )

        val nonNullResult = requireNotNull(result)

        assertEquals(0, nonNullResult.metadata.daysRemaining)
        assertEquals(ms(2023, 2, 28), nonNullResult.metadata.computedAt)
    }

    @Test
    fun `unavailable historical money is not downgraded to a deterministic forecast`() = runTest {
        every { timeProvider.now() } returns ms(2026, 4, 15)
        coEvery { historicalDistribution.computeDistributionResult("EUR") } returns
            HistoricalDistributionResult.Unavailable(
                reasonCode = MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE,
                totalInputCount = 4,
                includedInputCount = 3,
                excludedInputCount = 1
            )

        val error = runCatching {
            simulator.simulate(
                spentToDate = 100.0,
                knownUpcoming = 50.0,
                budgetAmount = 1000.0,
                displayCurrency = "EUR"
            )
        }.exceptionOrNull()

        assertTrue(error is MoneyDisplayUnavailableException)
        assertEquals(
            MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE,
            (error as MoneyDisplayUnavailableException).reasonCode
        )
    }

    @Test
    fun `end month unavailable history never becomes an under budget forecast`() = runTest {
        every { timeProvider.now() } returns ms(2026, 4, 30)
        for (reason in listOf(
            MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE,
            MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY
        )) {
            coEvery { historicalDistribution.computeDistributionResult("GBP") } returns
                HistoricalDistributionResult.Unavailable(reasonCode = reason)

            val error = runCatching {
                simulator.simulate(100.0, 50.0, 1000.0, displayCurrency = "GBP")
            }.exceptionOrNull()

            assertTrue(error is MoneyDisplayUnavailableException)
            assertEquals(reason, (error as MoneyDisplayUnavailableException).reasonCode)
        }
        verify(exactly = 0) { dataQualityAssessor.assess(any(), any()) }
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

        for ((day, sigma) in listOf(15 to fit.sigma, 30 to fit.sigma, 15 to 0.0)) {
            every { timeProvider.now() } returns ms(2026, 4, day)
            coEvery { historicalDistribution.computeDistributionResult("GBP") } returns
                HistoricalDistributionResult.Available(fit.copy(sigma = sigma))

            val result = requireNotNull(
                simulator.simulate(100.0, 50.0, 1000.0, displayCurrency = "GBP")
            )

            assertEquals("GBP", result.displayCurrency)
            assertSame(moneyQuality, result.metadata.historicalMoneyQuality)
            assertEquals(if (day == 30 || sigma == 0.0) 0 else 1000, result.metadata.iterations)
            if (result.metadata.iterations == 0) {
                assertApproxEquals(150.0, result.percentile50, 0.0001)
            }
        }
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
