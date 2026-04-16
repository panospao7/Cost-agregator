package com.yourname.expensetracker.ui.screens.lifestyle

import app.cash.turbine.test
import com.yourname.expensetracker.domain.lifestyle.LifestyleInflationDetector
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LifestyleInflationViewModelTest : ViewModelTestUtils() {

    private val lifestyleDetector = mockk<LifestyleInflationDetector>(relaxed = true)

    private lateinit var viewModel: LifestyleInflationViewModel

    @Before
    override fun setup() {
        super.setup()
        coEvery { lifestyleDetector.analyzeLifestyleInflation(any()) } returns lifestyleReport()
        viewModel = LifestyleInflationViewModel(lifestyleDetector)
    }

    @Test
    fun `initial state shows inflation analysis`() = runTest(testDispatcher) {
        val report = lifestyleReport(lifestyleCreepDetected = true, lifestyleInflationRate = 0.18)
        coEvery { lifestyleDetector.analyzeLifestyleInflation(12) } returns report

        viewModel.report.test {
            assertNull(awaitItem())

            viewModel.analyze(12)
            advanceUntilIdle()

            val loaded = awaitItem()
            assertNotNull(loaded)
            assertTrue(loaded!!.lifestyleCreepDetected)
            assertEquals(0.18, loaded.lifestyleInflationRate, 0.0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `detects lifestyle creep`() = runTest(testDispatcher) {
        val creepReport = lifestyleReport(
            lifestyleCreepDetected = true,
            alerts = listOf(
                LifestyleInflationDetector.LifestyleCreepAlert(
                    month = "2026-03",
                    incomeGrowthPercent = 3.0,
                    spendingGrowthPercent = 8.5,
                    discretionaryGrowthPercent = 10.0,
                    severity = LifestyleInflationDetector.CreepSeverity.HIGH,
                    description = "Spending grew faster than income"
                )
            )
        )
        coEvery { lifestyleDetector.analyzeLifestyleInflation(6) } returns creepReport

        viewModel.report.test {
            awaitItem() // initial null

            viewModel.analyze(6)
            advanceUntilIdle()

            val loaded = awaitItem()!!
            assertTrue(loaded.lifestyleCreepDetected)
            assertEquals(1, loaded.lifestyleCreepAlerts.size)
            assertEquals(LifestyleInflationDetector.CreepSeverity.HIGH, loaded.lifestyleCreepAlerts.first().severity)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no income change no inflation`() = runTest(testDispatcher) {
        val stableReport = lifestyleReport(
            lifestyleCreepDetected = false,
            incomeGrowthRate = 0.0,
            spendingGrowthRate = 0.0,
            lifestyleInflationRate = 0.0,
            alerts = emptyList()
        )
        coEvery { lifestyleDetector.analyzeLifestyleInflation(12) } returns stableReport

        viewModel.report.test {
            awaitItem() // initial null

            viewModel.analyze(12)
            advanceUntilIdle()

            val loaded = awaitItem()!!
            assertFalse(loaded.lifestyleCreepDetected)
            assertEquals(0.0, loaded.incomeGrowthRate, 0.0)
            assertEquals(0.0, loaded.spendingGrowthRate, 0.0)
            assertEquals(0.0, loaded.lifestyleInflationRate, 0.0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `error state on detector failure`() = runTest(testDispatcher) {
        coEvery { lifestyleDetector.analyzeLifestyleInflation(3) } throws IllegalStateException("detector down")

        viewModel.report.test {
            assertNull(awaitItem())

            viewModel.analyze(3)
            advanceUntilIdle()

            assertNull(viewModel.report.value)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.isLoading.test {
            assertFalse(awaitItem())

            viewModel.analyze(3)
            advanceUntilIdle()

            assertTrue(awaitItem())
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `latest analyze request wins when stale result completes last`() = runTest(testDispatcher) {
        val staleResult = CompletableDeferred<LifestyleInflationDetector.LifestyleInflationReport>()
        val staleReport = lifestyleReport(lifestyleInflationRate = 0.21)
        val latestReport = lifestyleReport(lifestyleInflationRate = 0.04)

        coEvery { lifestyleDetector.analyzeLifestyleInflation(12) } coAnswers {
            withContext(NonCancellable) { staleResult.await() }
        }
        coEvery { lifestyleDetector.analyzeLifestyleInflation(6) } returns latestReport

        viewModel.analyze(12)
        runCurrent()

        viewModel.analyze(6)
        advanceUntilIdle()

        assertEquals(latestReport, viewModel.report.value)
        assertFalse(viewModel.isLoading.value)

        staleResult.complete(staleReport)
        advanceUntilIdle()

        assertEquals(latestReport, viewModel.report.value)
        assertFalse(viewModel.isLoading.value)
    }

    private fun lifestyleReport(
        lifestyleCreepDetected: Boolean = false,
        incomeGrowthRate: Double = 0.05,
        spendingGrowthRate: Double = 0.08,
        lifestyleInflationRate: Double = 0.03,
        alerts: List<LifestyleInflationDetector.LifestyleCreepAlert> = emptyList()
    ) = LifestyleInflationDetector.LifestyleInflationReport(
        analysisPeriodMonths = 12,
        incomeSpendingCorrelation = 0.72,
        incomeElasticity = 1.1,
        lifestyleCreepDetected = lifestyleCreepDetected,
        lifestyleCreepAlerts = alerts,
        incomeGrowthRate = incomeGrowthRate,
        spendingGrowthRate = spendingGrowthRate,
        lifestyleInflationRate = lifestyleInflationRate,
        hedonicAdaptationScore = 22.0,
        monthlyData = emptyList(),
        recommendations = listOf(
            LifestyleInflationDetector.LifestyleRecommendation(
                type = LifestyleInflationDetector.RecommendationType.SPENDING_REVIEW,
                title = UiText.DynamicString("Review spending"),
                description = "Track spending weekly",
                priority = LifestyleInflationDetector.RecommendationPriority.MEDIUM,
                actionItems = listOf("Audit subscriptions")
            )
        )
    )
}
