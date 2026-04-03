package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.dateToMillis
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.util.ViewModelTestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DateBoundaryFlowTest : ViewModelTestUtils() {

    @Test
    fun `half open date interval includes start excludes end across flow`() = runTest(testDispatcher) {
        val startMs = dateToMillis("2026-03-01")
        val endMs = dateToMillis("2026-04-01")
        val now = dateToMillis("2026-03-31")

        val atStart = createExpense("2026-03-01", 10.0, id = 1L)
        val justBeforeEnd = createExpense("2026-03-31", 20.0, id = 2L)
        val atEnd = createExpense("2026-04-01", 30.0, id = 3L)

        val pipeline = buildPipeline(expenses = listOf(atStart, justBeforeEnd, atEnd), nowMs = now)

        val daoExpenses = pipeline.expenseDao.getExpensesBetween(startMs, endMs)
        assertEquals(2, daoExpenses.size)
        assertEquals(setOf(1L, 2L), daoExpenses.map { it.id }.toSet())

        val daoTotal = pipeline.expenseDao.getTotalForPeriod(startMs, endMs)
        val repoTotal = pipeline.expenseRepository.getTotalForPeriod(startMs, endMs)

        val vmState = pipeline.awaitViewModelState(testDispatcher)
        val vmRange = vmState.currentDateRange
        val vmTotal = vmState.currentTotal
        val vmDaoTotal = if (vmRange != null) {
            pipeline.expenseDao.getTotalForPeriod(vmRange.first, vmRange.second)
        } else {
            0.0
        }

        assertApproxEquals(30.0, daoTotal)
        assertApproxEquals(daoTotal, repoTotal)
        assertApproxEquals(vmDaoTotal, vmTotal)
    }
}
