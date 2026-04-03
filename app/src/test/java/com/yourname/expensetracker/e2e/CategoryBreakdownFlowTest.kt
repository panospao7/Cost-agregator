package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.dateToMillis
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.util.ViewModelTestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryBreakdownFlowTest : ViewModelTestUtils() {

    @Test
    fun `category totals and percentages flow through all layers without loss`() = runTest(testDispatcher) {
        val now = dateToMillis("2026-03-31")
        val expenses = listOf(
            createExpense("2026-03-05", 30.0, category = "food", id = 1L),
            createExpense("2026-03-10", 50.0, category = "food", id = 2L),
            createExpense("2026-03-20", 20.0, category = "groceries", id = 3L)
        )

        val pipeline = buildPipeline(expenses = expenses, nowMs = now)
        val (startMs, endMs) = TimePeriodUtils.getMonthRange(now)

        val daoTotals = pipeline.expenseDao.getCategoryTotalsForPeriod(startMs, endMs)
            .associate { it.categoryId to it.total }

        val repoTotals = pipeline.expenseRepository.getCategoryTotalsForPeriod(startMs, endMs)
            .associate { it.categoryId to it.total }

        val engineCategoryInsights = pipeline.insightsEngine
            .generateInsights(com.yourname.expensetracker.TEST_CATEGORIES, expenses)
            .categoryInsights
        val engineTotals = engineCategoryInsights.associate { it.category.id to it.currentTotal }
        val enginePercentages = engineCategoryInsights.associate { it.category.id to it.percentageOfTotal }

        val loaded = pipeline.awaitViewModelState(testDispatcher)

        val vmTotals = loaded.categoryBreakdown.associate { it.category.id to it.total }
        val vmPercentages = loaded.categoryBreakdown.associate { it.category.id to it.percentage }

        assertApproxEquals(80.0, daoTotals[1L] ?: 0.0)
        assertApproxEquals(20.0, daoTotals[2L] ?: 0.0)
        assertEquals(daoTotals, repoTotals)
        assertApproxEquals(daoTotals[1L] ?: 0.0, engineTotals[1L] ?: 0.0)
        assertApproxEquals(daoTotals[2L] ?: 0.0, engineTotals[2L] ?: 0.0)
        assertApproxEquals(engineTotals[1L] ?: 0.0, vmTotals[1L] ?: 0.0)
        assertApproxEquals(engineTotals[2L] ?: 0.0, vmTotals[2L] ?: 0.0)

        assertApproxEquals(80f, enginePercentages[1L] ?: 0f, tolerance = 0.1f)
        assertApproxEquals(20f, enginePercentages[2L] ?: 0f, tolerance = 0.1f)
        assertApproxEquals(80f, vmPercentages[1L] ?: 0f, tolerance = 0.1f)
        assertApproxEquals(20f, vmPercentages[2L] ?: 0f, tolerance = 0.1f)

        val vmPercentageSum = vmPercentages.values.sum()
        assertTrue("Percentages should sum to ~100, was $vmPercentageSum", kotlin.math.abs(vmPercentageSum - 100f) < 0.2f)
    }
}
