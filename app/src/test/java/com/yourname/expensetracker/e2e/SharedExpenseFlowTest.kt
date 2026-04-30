package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.dateToMillis
import com.yourname.expensetracker.toAnalyticsCategoryRefs
import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.util.ViewModelTestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedExpenseFlowTest : ViewModelTestUtils() {

    @Test
    fun `shared expense uses effectiveAmount and excludes notMine across layers`() = runTest(testDispatcher) {
        val now = dateToMillis("2026-03-31")
        val includedShared = createExpense(
            date = "2026-03-10",
            amount = 100.0,
            effectiveAmount = 50.0,
            isSharedExpense = true,
            myShareAmount = 50.0,
            isNotMine = false,
            id = 1L
        )
        val excludedNotMine = createExpense(
            date = "2026-03-12",
            amount = 200.0,
            effectiveAmount = 0.0,
            isNotMine = true,
            id = 2L
        )

        val pipeline = buildPipeline(expenses = listOf(includedShared, excludedNotMine), nowMs = now)
        val (startMs, endMs) = TimePeriodUtils.getMonthRange(now)

        val daoExpenses = pipeline.expenseDao.getExpensesBetween(startMs, endMs)
        assertEquals(1, daoExpenses.size)
        assertEquals(includedShared.id, daoExpenses.first().id)

        val daoTotal = pipeline.expenseDao.getTotalForPeriod(startMs, endMs)
        val repoTotal = pipeline.expenseRepository.getTotalForPeriod(startMs, endMs)
        val engineTotal = pipeline.insightsEngine
            .generateInsights(
                categories = com.yourname.expensetracker.TEST_CATEGORIES.toAnalyticsCategoryRefs(),
                allExpenses = listOf(includedShared, excludedNotMine).toExpenseSnapshots(),
                displayCurrency = "EUR"
            )
            .monthlyComparison.currentTotal

        val vmTotal = pipeline.awaitViewModelState(testDispatcher).currentTotal

        assertApproxEquals(50.0, daoTotal)
        assertApproxEquals(daoTotal, repoTotal)
        assertApproxEquals(repoTotal, engineTotal)
        assertApproxEquals(engineTotal, vmTotal)
    }
}
