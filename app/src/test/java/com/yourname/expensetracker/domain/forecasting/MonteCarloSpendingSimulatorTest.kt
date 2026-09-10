package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        coEvery { historicalDistribution.computeDistribution() } returns null

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
            budgetAmount = 700.0
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
        coEvery { historicalDistribution.computeDistribution() } returns null

        val result = simulator.simulate(
            spentToDate = 100.0,
            knownUpcoming = 50.0,
            budgetAmount = 1000.0
        )

        val nonNullResult = requireNotNull(result)

        assertEquals(0, nonNullResult.metadata.daysRemaining)
        assertEquals(ms(2024, 2, 29), nonNullResult.metadata.computedAt)
    }

    @Test
    fun `leap year Feb 28 2024 reports 1 dayRemaining from simulate output`() = runTest {
        every { timeProvider.now() } returns ms(2024, 2, 28)
        coEvery { historicalDistribution.computeDistribution() } returns usableFit()
        every { dataQualityAssessor.assess(any(), any()) } returns highConfidence()

        val result = simulator.simulate(
            spentToDate = 100.0,
            knownUpcoming = 50.0,
            budgetAmount = 1000.0
        )

        val nonNullResult = requireNotNull(result)

        assertEquals(1, nonNullResult.metadata.daysRemaining)
        assertEquals(ms(2024, 2, 28), nonNullResult.metadata.computedAt)
    }

    @Test
    fun `leap year Feb 15 2024 reports 14 daysRemaining from simulate output`() = runTest {
        every { timeProvider.now() } returns ms(2024, 2, 15)
        coEvery { historicalDistribution.computeDistribution() } returns usableFit()
        every { dataQualityAssessor.assess(any(), any()) } returns highConfidence()

        val result = simulator.simulate(
            spentToDate = 100.0,
            knownUpcoming = 50.0,
            budgetAmount = 1000.0
        )

        val nonNullResult = requireNotNull(result)

        assertEquals(14, nonNullResult.metadata.daysRemaining)
        assertEquals(ms(2024, 2, 15), nonNullResult.metadata.computedAt)
    }

    @Test
    fun `non leap year Feb 28 2023 reports 0 daysRemaining from simulate output`() = runTest {
        every { timeProvider.now() } returns ms(2023, 2, 28)
        coEvery { historicalDistribution.computeDistribution() } returns null

        val result = simulator.simulate(
            spentToDate = 100.0,
            knownUpcoming = 50.0,
            budgetAmount = 1000.0
        )

        val nonNullResult = requireNotNull(result)

        assertEquals(0, nonNullResult.metadata.daysRemaining)
        assertEquals(ms(2023, 2, 28), nonNullResult.metadata.computedAt)
    }

    private fun usableFit(): DistributionFit = DistributionFit(
        mu = 5.8,
        sigma = 0.35,
        qualifyingWeekCount = 12,
        totalWeeksExamined = 18,
        trimmedWeeklyTotals = listOf(420.0, 500.0, 560.0, 610.0, 700.0, 760.0),
        allWeeklyTotals = listOf(400.0, 480.0, 520.0, 610.0, 730.0, 810.0, 920.0, 1010.0)
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
