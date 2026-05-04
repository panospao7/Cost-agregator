package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.dateToMillis
import com.yourname.expensetracker.domain.analytics.AnalyticsPeriod
import com.yourname.expensetracker.domain.analytics.AnalyticsPeriodRange
import com.yourname.expensetracker.util.ViewModelTestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DailyAverageFlowTest : ViewModelTestUtils() {

    @Test
    fun `daily average uses periodDays not only daysWithSpending`() = runTest(testDispatcher) {
        val now = dateToMillis("2026-03-31")
        val expenses = (1..30).map { day ->
            createExpense("2026-03-${day.toString().padStart(2, '0')}", 30.0, id = day.toLong())
        }

        val pipeline = buildPipeline(expenses = expenses, nowMs = now)
        val period = pipeline.advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.MONTH, now)

        val daoExpenses = pipeline.expenseDao.getExpensesBetween(period.startMs, period.endMs)
        assertEquals(30, daoExpenses.size)

        val stats = pipeline.advancedAnalyticsEngine.getStatisticalInsights(period, displayCurrency = "EUR")
        val engineAverage = stats.averageDailySpend

        val vmState = pipeline.awaitViewModelState(testDispatcher)
        val vmRange = vmState.currentDateRange
        val vmAverage = if (vmRange != null) {
            val vmPeriod = AnalyticsPeriodRange(
                period = AnalyticsPeriod.CUSTOM,
                startMs = vmRange.first,
                endMs = vmRange.second,
                label = "VM_RANGE",
                comparisonRange = null
            )
            pipeline.advancedAnalyticsEngine
                .getStatisticalInsights(vmPeriod, displayCurrency = "EUR")
                .averageDailySpend
        } else {
            -1.0
        }
        val viewModelAverage = vmState.statisticalInsights?.averageDailySpend ?: -1.0

        assertApproxEquals(30.0, engineAverage)
        assertApproxEquals(vmAverage, viewModelAverage)
    }
}