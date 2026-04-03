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

    private fun ms(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
