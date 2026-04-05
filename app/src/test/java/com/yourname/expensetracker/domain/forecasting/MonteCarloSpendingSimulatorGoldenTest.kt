package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MonteCarloSpendingSimulatorGoldenTest : AnalyticsEngineTestBase() {

    private lateinit var historicalDistribution: HistoricalSpendingDistribution
    private lateinit var dataQualityAssessor: DataQualityAssessor
    private lateinit var simulator: MonteCarloSpendingSimulator

    @Before
    override fun setUp() {
        super.setUp()
        historicalDistribution = mockk(relaxed = true)
        dataQualityAssessor = mockk(relaxed = true)
        simulator = MonteCarloSpendingSimulator(
            historicalDistribution = historicalDistribution,
            dataQualityAssessor = dataQualityAssessor,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `deterministic simulation with seed 42 and 1000 iterations matches exact p50 snapshot`() = runTest {
        every { timeProvider.now() } returns atTime("2026-03-15", 12, 0, 0)

        val fit = DistributionFit(
            mu = 5.8,
            sigma = 0.35,
            qualifyingWeekCount = 12,
            totalWeeksExamined = 18,
            trimmedWeeklyTotals = listOf(420.0, 500.0, 560.0, 610.0, 700.0, 760.0),
            allWeeklyTotals = listOf(400.0, 480.0, 520.0, 610.0, 730.0, 810.0, 920.0, 1010.0)
        )

        val confidence = SimulationConfidence(
            score = 0.82,
            level = ConfidenceLevel.HIGH,
            reason = "Based on 12 weeks"
        )

        coEvery { historicalDistribution.computeDistribution() } returns fit
        every { dataQualityAssessor.assess(fit, 8) } returns confidence

        val result = simulator.simulate(
            spentToDate = 991.79,
            knownUpcoming = 300.0,
            budgetAmount = 2500.0
        )

        result!!
        assertApproxEquals(2072.405515999798, result.percentile50, 0.000000001)
        assertApproxEquals(1781.1408711383133, result.percentile10, 0.000000001)
        assertApproxEquals(1894.4199370582614, result.percentile25, 0.000000001)
        assertApproxEquals(2273.0265472087963, result.percentile75, 0.000000001)
        assertApproxEquals(2484.391822211798, result.percentile90, 0.000000001)
        assertEquals(1000, result.metadata.iterations)
    }

    private fun atTime(date: String, hour: Int, minute: Int, second: Int): Long {
        val start = com.yourname.expensetracker.dateToMillis(date)
        return java.util.Calendar.getInstance().apply {
            timeInMillis = start
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, second)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
