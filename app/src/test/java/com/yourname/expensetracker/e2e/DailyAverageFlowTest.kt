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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
        val zone = ZoneId.systemDefault()
        assertEquals(LocalDate.of(2026, 3, 1), Instant.ofEpochMilli(period.startMs).atZone(zone).toLocalDate())
        assertEquals(LocalDate.of(2026, 4, 1), Instant.ofEpochMilli(period.endMs).atZone(zone).toLocalDate())

        val daoExpenses = pipeline.expenseDao.getExpensesBetween(period.startMs, period.endMs)
        assertEquals(30, daoExpenses.size)

        val stats = pipeline.advancedAnalyticsEngine.getStatisticalInsights(period, displayCurrency = "EUR").first
        val engineAverage = stats.averageDailySpend
        assertEquals(30, stats.daysWithSpending)
        assertEquals(1, stats.daysWithoutSpending)

        val vmState = pipeline.awaitViewModelState(testDispatcher)
        val vmRange = requireNotNull(vmState.currentDateRange) { "Loaded analytics must expose its date range" }
        val vmAverage = run {
            val vmPeriod = AnalyticsPeriodRange(
                period = AnalyticsPeriod.CUSTOM,
                startMs = vmRange.first,
                endMs = vmRange.second,
                label = "VM_RANGE",
                comparisonRange = null
            )
            pipeline.advancedAnalyticsEngine
                .getStatisticalInsights(vmPeriod, displayCurrency = "EUR")
                .first.averageDailySpend
        }
        val viewModelAverage = requireNotNull(vmState.statisticalInsights) {
            "Loaded analytics must expose statistical insights"
        }.averageDailySpend

        // March has 31 calendar days, including the zero-spend March 31.
        // Dividing by the 30 spending days would incorrectly produce 30.0.
        assertApproxEquals(900.0 / 31.0, engineAverage)
        assertApproxEquals(vmAverage, viewModelAverage)
    }
}
