package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.dateToMillis
import com.yourname.expensetracker.domain.analytics.AnalyticsPeriod
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.util.ViewModelTestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmptyDataFlowTest : ViewModelTestUtils() {

    @Test
    fun `empty dataset flows safely with sensible defaults`() = runTest(testDispatcher) {
        val now = dateToMillis("2026-03-31")
        val pipeline = buildPipeline(expenses = emptyList(), nowMs = now)
        val (startMs, endMs) = TimePeriodUtils.getMonthRange(now)

        val daoTotal = pipeline.expenseDao.getTotalForPeriod(startMs, endMs)
        val repoTotal = pipeline.expenseRepository.getTotalForPeriod(startMs, endMs)
        val summary = pipeline.analyticsRepository.getSpendingSummary(startMs, endMs).first()

        val stats = pipeline.advancedAnalyticsEngine
            .getStatisticalInsights(pipeline.advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.MONTH, now))

        var loaded = pipeline.viewModel.state.value
        val collector = launch { pipeline.viewModel.state.collect { loaded = it } }
        testDispatcher.scheduler.advanceTimeBy(1500)
        testDispatcher.scheduler.advanceUntilIdle()
        collector.cancel()

        assertEquals(0.0, daoTotal, 0.0)
        assertEquals(0.0, repoTotal, 0.0)
        assertNotNull(summary)
        assertEquals(0.0, stats.averageDailySpend, 0.0)
        assertEquals(0.0, loaded.currentTotal, 0.0)
        assertEquals(0, loaded.transactionCount)
        assertEquals(emptyList<Double>(), loaded.categoryBreakdown.map { it.total })
    }
}
