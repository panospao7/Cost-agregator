package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.dateToMillis
import com.yourname.expensetracker.toAnalyticsCategoryRefs
import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.util.ViewModelTestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonthlyTotalFlowTest : ViewModelTestUtils() {

    @Test
    fun `monthly total flows unchanged through dao repository engine viewModel and ui`() = runTest(testDispatcher) {
        val now = dateToMillis("2026-03-31")
        val expenses = listOf(
            createExpense("2026-03-05", 10.0, id = 1L),
            createExpense("2026-03-15", 20.0, id = 2L),
            createExpense("2026-03-25", 30.0, id = 3L)
        )

        val pipeline = buildPipeline(expenses = expenses, nowMs = now)
        val (startMs, endMs) = TimePeriodUtils.getMonthRange(now)

        val daoTotal = pipeline.expenseDao.getTotalForPeriod(startMs, endMs)
        val repoTotal = pipeline.expenseRepository.getTotalForPeriod(startMs, endMs)
        val engineTotal = pipeline.insightsEngine
            .generateInsights(com.yourname.expensetracker.TEST_CATEGORIES.toAnalyticsCategoryRefs(), expenses.toExpenseSnapshots(), "EUR")
            .monthlyComparison
            .currentTotal

        val viewModelTotal = pipeline.awaitViewModelState(testDispatcher).currentTotal

        val uiTotal = viewModelTotal

        assertApproxEquals(60.0, daoTotal)
        assertApproxEquals(daoTotal, repoTotal)
        assertApproxEquals(repoTotal, engineTotal)
        assertApproxEquals(engineTotal, viewModelTotal)
        assertApproxEquals(60.0, uiTotal)

        val summaryTotal = pipeline.analyticsRepository.getSpendingSummary(startMs, endMs).first().totalSpent
        assertApproxEquals(60.0, summaryTotal)
        assertEquals(summaryTotal, viewModelTotal, 0.01)
    }
}
