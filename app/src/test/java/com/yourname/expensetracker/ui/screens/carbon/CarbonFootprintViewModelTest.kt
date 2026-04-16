package com.yourname.expensetracker.ui.screens.carbon

import app.cash.turbine.test
import com.yourname.expensetracker.domain.carbon.CarbonFootprintCalculator
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
class CarbonFootprintViewModelTest : ViewModelTestUtils() {

    private val calculator = mockk<CarbonFootprintCalculator>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    private lateinit var viewModel: CarbonFootprintViewModel

    private val fixedNow = 1_700_000_000_000L

    @Before
    override fun setup() {
        super.setup()
        every { timeProvider.now() } returns fixedNow
        coEvery { calculator.calculateCarbonFootprint(any(), any()) } returns carbonReport()
        viewModel = CarbonFootprintViewModel(calculator, timeProvider)
    }

    @Test
    fun `initial state shows carbon footprint`() = runTest(testDispatcher) {
        val report = carbonReport(totalEmissionsKg = 64.0, dailyAverageKg = 2.1)
        coEvery { calculator.calculateCarbonFootprint(any(), any()) } returns report

        viewModel.report.test {
            assertNull(awaitItem())

            viewModel.loadReport(30)
            advanceUntilIdle()

            val loaded = awaitItem()
            assertNotNull(loaded)
            assertEquals(64.0, loaded!!.totalEmissionsKg, 0.0)
            assertEquals(2.1, loaded.dailyAverageKg, 0.0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `period change recalculates`() = runTest(testDispatcher) {
        val report30 = carbonReport(totalEmissionsKg = 90.0, dailyAverageKg = 3.0, periodDays = 30)
        val report7 = carbonReport(totalEmissionsKg = 14.0, dailyAverageKg = 2.0, periodDays = 7)

        val start30 = fixedNow - (30 * TimePeriodUtils.DAY_IN_MILLIS)
        val start7 = fixedNow - (7 * TimePeriodUtils.DAY_IN_MILLIS)

        coEvery { calculator.calculateCarbonFootprint(start30, fixedNow) } returns report30
        coEvery { calculator.calculateCarbonFootprint(start7, fixedNow) } returns report7

        viewModel.report.test {
            assertNull(awaitItem())

            viewModel.loadReport(30)
            advanceUntilIdle()
            assertEquals(90.0, awaitItem()!!.totalEmissionsKg, 0.0)

            viewModel.loadReport(7)
            advanceUntilIdle()
            assertEquals(14.0, awaitItem()!!.totalEmissionsKg, 0.0)

            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { calculator.calculateCarbonFootprint(start30, fixedNow) }
        coVerify(exactly = 1) { calculator.calculateCarbonFootprint(start7, fixedNow) }
    }

    @Test
    fun `high footprint suggestions shown`() = runTest(testDispatcher) {
        val high = carbonReport(
            totalEmissionsKg = 420.0,
            dailyAverageKg = 14.0,
            recommendations = listOf(
                CarbonFootprintCalculator.SustainabilityRecommendation(
                    category = "Transportation",
                    title = UiText.DynamicString("Reduce fuel usage"),
                    description = "Use public transit more",
                    potentialImpact = "40% reduction",
                    difficulty = CarbonFootprintCalculator.Difficulty.MEDIUM,
                    savings = 120.0
                )
            )
        )
        coEvery { calculator.calculateCarbonFootprint(any(), any()) } returns high

        viewModel.report.test {
            assertNull(awaitItem())

            viewModel.loadReport()
            advanceUntilIdle()

            val loaded = awaitItem()!!
            assertTrue(loaded.totalEmissionsKg > 100)
            assertTrue(loaded.recommendations.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty data zero footprint`() = runTest(testDispatcher) {
        val zero = carbonReport(totalEmissionsKg = 0.0, dailyAverageKg = 0.0, periodDays = 30)
        coEvery { calculator.calculateCarbonFootprint(any(), any()) } returns zero

        viewModel.report.test {
            assertNull(awaitItem())

            viewModel.loadReport(30)
            advanceUntilIdle()

            val loaded = awaitItem()!!
            assertEquals(0.0, loaded.totalEmissionsKg, 0.0)
            assertEquals(0.0, loaded.dailyAverageKg, 0.0)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.isLoading.test {
            assertFalse(awaitItem())
            viewModel.loadReport(30)
            advanceUntilIdle()
            assertTrue(awaitItem())
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `latest load request wins when stale result completes last`() = runTest(testDispatcher) {
        val staleResult = CompletableDeferred<CarbonFootprintCalculator.CarbonFootprintReport>()
        val staleReport = carbonReport(totalEmissionsKg = 90.0, dailyAverageKg = 3.0, periodDays = 30)
        val latestReport = carbonReport(totalEmissionsKg = 14.0, dailyAverageKg = 2.0, periodDays = 7)
        val start30 = fixedNow - (30 * TimePeriodUtils.DAY_IN_MILLIS)
        val start7 = fixedNow - (7 * TimePeriodUtils.DAY_IN_MILLIS)

        coEvery { calculator.calculateCarbonFootprint(start30, fixedNow) } coAnswers {
            withContext(NonCancellable) { staleResult.await() }
        }
        coEvery { calculator.calculateCarbonFootprint(start7, fixedNow) } returns latestReport

        viewModel.loadReport(30)
        runCurrent()

        viewModel.loadReport(7)
        advanceUntilIdle()

        assertEquals(latestReport, viewModel.report.value)
        assertFalse(viewModel.isLoading.value)

        staleResult.complete(staleReport)
        advanceUntilIdle()

        assertEquals(latestReport, viewModel.report.value)
        assertFalse(viewModel.isLoading.value)
    }

    private fun carbonReport(
        totalEmissionsKg: Double = 10.0,
        dailyAverageKg: Double = 0.5,
        periodDays: Int = 30,
        recommendations: List<CarbonFootprintCalculator.SustainabilityRecommendation> = emptyList()
    ) = CarbonFootprintCalculator.CarbonFootprintReport(
        totalEmissionsKg = totalEmissionsKg,
        dailyAverageKg = dailyAverageKg,
        periodDays = periodDays,
        categoryBreakdown = emptyList(),
        merchantBreakdown = emptyList(),
        comparisonToNationalAverage = -80,
        comparisonToGlobalAverage = -85,
        parisAgreementGap = -90,
        sustainabilityScore = 92,
        offsetCost = 0.0,
        recommendations = recommendations,
        alternatives = emptyList(),
        monthlyTrend = emptyList()
    )
}
