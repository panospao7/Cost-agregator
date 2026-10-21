package com.yourname.expensetracker.e2e

import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.TEST_CATEGORIES
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.assertMoneyEquals
import com.yourname.expensetracker.assertMoneyExact
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.dateToMillis
import com.yourname.expensetracker.toAnalyticsCategoryRefs
import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.analytics.AnalyticsPeriod
import com.yourname.expensetracker.domain.analytics.AnalyticsPeriodRange
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.util.ViewModelTestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * B6-25: wiring-parity matrix for the analytics pipeline (DAO -> repository -> engine -> ViewModel),
 * replacing the six single-scenario Flow tests (MonthlyTotal, SharedExpense, DateBoundary,
 * CategoryBreakdown, DailyAverage, EmptyData).
 *
 * Every row compares each layer with a hard-coded oracle and with its neighbouring layer. The
 * class pins the JVM default zone to UTC because the production time utilities read it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION_ERROR")
class PipelineWiringParityTest : ViewModelTestUtils() {

    companion object {
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()
    }

    private class TotalsRow(
        val name: String,
        val expenses: List<Expense>,
        val expectedTotal: Double,
        /** When set, the DAO's owned-row read for the range must return exactly these ids. */
        val expectedDaoRowIds: Set<Long>?,
        /** Explicit half-open range; null means the calendar month of the clock. */
        val explicitRange: Pair<Long, Long>?,
        /** True when the ViewModel total must equal the oracle (month range); false when the ViewModel picks its own range. */
        val viewModelUsesMonthRange: Boolean,
        val checkSpendingSummary: Boolean,
        /** When set, the DAO and repository transaction counts for the range must equal this hard-coded count. */
        val expectedTransactionCount: Int? = null
    )

    @Test
    fun `totals pipeline parity matrix - dao repository engine viewModel and summary agree with the oracle`() = runTest(testDispatcher) {
        val now = dateToMillis("2026-03-31")
        val includedShared = createExpense(
            date = "2026-03-10", amount = 100.0, effectiveAmount = 50.0,
            isSharedExpense = true, myShareAmount = 50.0, isNotMine = false, id = 1L
        )
        val excludedNotMine = createExpense(date = "2026-03-12", amount = 200.0, effectiveAmount = 0.0, isNotMine = true, id = 2L)

        val rows = listOf(
            TotalsRow(
                name = "monthly total 10.00 + 20.00 + 30.00",
                expenses = listOf(
                    createExpense("2026-03-05", 10.0, id = 1L),
                    createExpense("2026-03-15", 20.0, id = 2L),
                    createExpense("2026-03-25", 30.0, id = 3L)
                ),
                expectedTotal = 60.0,
                expectedDaoRowIds = null,
                explicitRange = null,
                viewModelUsesMonthRange = true,
                checkSpendingSummary = true
            ),
            TotalsRow(
                name = "shared share counted (50.00 of 100.00), notMine excluded",
                expenses = listOf(includedShared, excludedNotMine),
                expectedTotal = 50.0,
                expectedDaoRowIds = setOf(1L),
                explicitRange = null,
                viewModelUsesMonthRange = true,
                checkSpendingSummary = false,
                // Only the shared expense is counted: the notMine row must not inflate the count.
                expectedTransactionCount = 1
            ),
            TotalsRow(
                name = "half-open boundary: Mar 1 and Mar 31 in, Apr 1 out",
                expenses = listOf(
                    createExpense("2026-03-01", 10.0, id = 1L),
                    createExpense("2026-03-31", 20.0, id = 2L),
                    createExpense("2026-04-01", 30.0, id = 3L)
                ),
                expectedTotal = 30.0,
                expectedDaoRowIds = setOf(1L, 2L),
                explicitRange = dateToMillis("2026-03-01") to dateToMillis("2026-04-01"),
                viewModelUsesMonthRange = false,
                checkSpendingSummary = false
            )
        )
        assertEquals("totals matrix must keep all 3 rows", 3, rows.size)

        for (row in rows) {
            val pipeline = buildPipeline(expenses = row.expenses, nowMs = now)
            val (startMs, endMs) = row.explicitRange ?: TimePeriodUtils.getMonthRange(now)

            val daoRows = pipeline.expenseDao.getExpensesBetween(startMs, endMs)
            row.expectedDaoRowIds?.let { ids ->
                assertEquals("${row.name}: dao row count", ids.size, daoRows.size)
                assertEquals("${row.name}: dao row ids", ids, daoRows.map { it.id }.toSet())
            }

            row.expectedTransactionCount?.let { count ->
                assertEquals("${row.name}: dao transaction count", count, pipeline.expenseDao.getCountForPeriod(startMs, endMs))
                assertEquals(
                    "${row.name}: repository transaction count",
                    count,
                    pipeline.expenseRepository.getTransactionCountForPeriod(startMs, endMs)
                )
            }

            val daoTotal = pipeline.expenseDao.getTotalForPeriod(startMs, endMs)
            val repoTotal = pipeline.expenseRepository.getTotalForPeriod(startMs, endMs)
            val engineTotal = pipeline.insightsEngine
                .generateInsights(
                    categories = TEST_CATEGORIES.toAnalyticsCategoryRefs(),
                    allExpenses = row.expenses.toExpenseSnapshots(),
                    displayCurrency = "EUR"
                )
                .monthlyComparison.currentTotal
            val vmState = pipeline.awaitViewModelState(testDispatcher)
            val vmTotal = vmState.currentTotal

            assertMoneyExact(row.expectedTotal, daoTotal, "${row.name} [dao pass-through]")
            assertMoneyEquals(row.expectedTotal, repoTotal, "${row.name} [repository]")
            assertMoneyEquals(row.expectedTotal, engineTotal, "${row.name} [insights engine]")
            assertApproxEquals(daoTotal, repoTotal, 0.01, "${row.name}: dao vs repository: ")
            assertApproxEquals(repoTotal, engineTotal, 0.01, "${row.name}: repository vs engine: ")

            if (row.viewModelUsesMonthRange) {
                // The ViewModel total is compared with the hard-coded oracle and the engine, not with an alias of itself.
                assertMoneyEquals(row.expectedTotal, vmTotal, "${row.name} [viewModel]")
                assertApproxEquals(engineTotal, vmTotal, 0.01, "${row.name}: engine vs viewModel: ")
            } else {
                // The ViewModel owns its date range: its total must equal the DAO total over that same range.
                val vmRange = requireNotNull(vmState.currentDateRange) { "${row.name}: loaded analytics must expose its date range" }
                val vmDaoTotal = pipeline.expenseDao.getTotalForPeriod(vmRange.first, vmRange.second)
                assertApproxEquals(vmDaoTotal, vmTotal, 0.01, "${row.name}: dao over viewModel range vs viewModel: ")
            }

            if (row.checkSpendingSummary) {
                val summaryTotal = pipeline.analyticsRepository.getSpendingSummary(startMs, endMs).first().totalSpent
                assertMoneyEquals(row.expectedTotal, summaryTotal, "${row.name} [spending summary]")
                assertApproxEquals(summaryTotal, vmTotal, 0.01, "${row.name}: summary vs viewModel: ")
            }
        }
    }

    @Test
    fun `breakdown statistics and empty pipeline parity matrix`() = runTest(testDispatcher) {
        val now = dateToMillis("2026-03-31")

        // Row 1: category totals and percentages flow through every layer without loss.
        run {
            val expenses = listOf(
                createExpense("2026-03-05", 30.0, category = "food", id = 1L),
                createExpense("2026-03-10", 50.0, category = "food", id = 2L),
                createExpense("2026-03-20", 20.0, category = "groceries", id = 3L)
            )
            val pipeline = buildPipeline(expenses = expenses, nowMs = now)
            val (startMs, endMs) = TimePeriodUtils.getMonthRange(now)

            val daoTotals = pipeline.expenseDao.getCategoryTotalsForPeriod(startMs, endMs).associate { it.categoryId to it.total }
            val repoTotals = pipeline.expenseRepository.getCategoryTotalsForPeriod(startMs, endMs).associate { it.categoryId to it.total }
            val engineCategoryInsights = pipeline.insightsEngine
                .generateInsights(
                    TEST_CATEGORIES.toAnalyticsCategoryRefs(), expenses.toExpenseSnapshots(), "EUR"
                )
                .categoryInsights
            val engineTotals = engineCategoryInsights.associate { it.category.id to it.currentTotal }
            val enginePercentages = engineCategoryInsights.associate { it.category.id to it.percentageOfTotal }
            val loaded = pipeline.awaitViewModelState(testDispatcher)
            val vmTotals = loaded.categoryBreakdown.associate { it.category.id to it.moneyTotal.amount }
            val vmPercentages = loaded.categoryBreakdown.associate { it.category.id to it.percentage }

            val expectedTotals = mapOf(1L to 80.0, 2L to 20.0)
            val expectedPercentages = mapOf(1L to 80f, 2L to 20f)
            assertEquals("category breakdown: dao category keys", expectedTotals.keys, daoTotals.keys)
            assertEquals("category breakdown: dao totals must equal repository totals", daoTotals, repoTotals)
            expectedTotals.forEach { (categoryId, amount) ->
                assertMoneyExact(amount, daoTotals[categoryId], "category=$categoryId [dao pass-through]")
                assertMoneyEquals(amount, engineTotals[categoryId], "category=$categoryId [insights engine]")
                assertMoneyEquals(amount, vmTotals[categoryId], "category=$categoryId [viewModel]")
                assertApproxEquals(requireNotNull(daoTotals[categoryId]), engineTotals[categoryId], 0.01, "category=$categoryId dao vs engine: ")
                assertApproxEquals(requireNotNull(engineTotals[categoryId]), vmTotals[categoryId], 0.01, "category=$categoryId engine vs viewModel: ")
            }
            expectedPercentages.forEach { (categoryId, percentage) ->
                assertApproxEquals(percentage, enginePercentages[categoryId], 0.1f, "category=$categoryId engine percentage: ")
                assertApproxEquals(percentage, vmPercentages[categoryId], 0.1f, "category=$categoryId viewModel percentage: ")
            }
            val vmPercentageSum = vmPercentages.values.sum()
            assertTrue("Percentages should sum to ~100, was $vmPercentageSum", kotlin.math.abs(vmPercentageSum - 100f) < 0.2f)
        }

        // Row 2: the daily average divides by the period's calendar days, not by days with spending.
        run {
            val expenses = (1..30).map { day ->
                createExpense("2026-03-${day.toString().padStart(2, '0')}", 30.0, id = day.toLong())
            }
            val pipeline = buildPipeline(expenses = expenses, nowMs = now)
            val period = pipeline.advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.MONTH, now)
            assertEquals(LocalDate.of(2026, 3, 1), Instant.ofEpochMilli(period.startMs).atZone(ZoneOffset.UTC).toLocalDate())
            assertEquals(LocalDate.of(2026, 4, 1), Instant.ofEpochMilli(period.endMs).atZone(ZoneOffset.UTC).toLocalDate())

            val daoExpenses = pipeline.expenseDao.getExpensesBetween(period.startMs, period.endMs)
            assertEquals("daily average: dao rows in March", 30, daoExpenses.size)

            val stats = pipeline.advancedAnalyticsEngine.getStatisticalInsights(period, displayCurrency = "EUR").first
            assertEquals("daily average: days with spending", 30, stats.daysWithSpending)
            assertEquals("daily average: days without spending (Mar 31)", 1, stats.daysWithoutSpending)

            val vmState = pipeline.awaitViewModelState(testDispatcher)
            val vmRange = requireNotNull(vmState.currentDateRange) { "Loaded analytics must expose its date range" }
            val engineAverageOverVmRange = pipeline.advancedAnalyticsEngine
                .getStatisticalInsights(
                    AnalyticsPeriodRange(
                        period = AnalyticsPeriod.CUSTOM,
                        startMs = vmRange.first,
                        endMs = vmRange.second,
                        label = "VM_RANGE",
                        comparisonRange = null
                    ),
                    displayCurrency = "EUR"
                )
                .first.averageDailySpend
            val viewModelAverage = requireNotNull(vmState.statisticalInsights) {
                "Loaded analytics must expose statistical insights"
            }.averageDailySpend

            // March has 31 calendar days, including the zero-spend March 31: 900.00 / 31 = 29.03.
            // Dividing by the 30 spending days would incorrectly produce 30.00.
            assertMoneyEquals(29.03, stats.averageDailySpend, "daily average [engine, 900.00 / 31 days]")
            assertApproxEquals(engineAverageOverVmRange, viewModelAverage, 0.01, "daily average: engine over viewModel range vs viewModel: ")
        }

        // Row 3: an empty dataset flows through every layer as an available zero with empty breakdowns.
        run {
            val pipeline = buildPipeline(expenses = emptyList(), nowMs = now)
            val (startMs, endMs) = TimePeriodUtils.getMonthRange(now)

            val daoTotal = pipeline.expenseDao.getTotalForPeriod(startMs, endMs)
            val repoTotal = pipeline.expenseRepository.getTotalForPeriod(startMs, endMs)
            val summary = pipeline.analyticsRepository.getSpendingSummary(startMs, endMs).first()
            val stats = pipeline.advancedAnalyticsEngine
                .getStatisticalInsights(pipeline.advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.MONTH, now), displayCurrency = "EUR").first

            var loaded = pipeline.viewModel.state.value
            val collector = launch { pipeline.viewModel.state.collect { loaded = it } }
            testDispatcher.scheduler.advanceTimeBy(1500)
            testDispatcher.scheduler.advanceUntilIdle()
            collector.cancel()
            pipeline.viewModel.viewModelScope.cancel()

            assertMoneyExact(0.0, daoTotal, "empty [dao]")
            assertMoneyExact(0.0, repoTotal, "empty [repository]")
            // Replaces assertNotNull(summary), which was vacuous for a non-null return type.
            assertMoneyExact(0.0, summary.totalSpent, "empty [spending summary total]")
            assertEquals("empty [spending summary transaction count]", 0, summary.transactionCount)
            assertMoneyExact(0.0, stats.averageDailySpend, "empty [advanced average daily spend]")
            // The initial state also reads 0 / empty, so the zero checks below only mean something once loading finished.
            assertFalse("empty: ViewModel state must have finished loading", loaded.isLoading)
            assertMoneyExact(0.0, loaded.currentTotal, "empty [viewModel total]")
            assertEquals("empty [viewModel transaction count]", 0, loaded.transactionCount)
            assertEquals(
                "empty [viewModel category breakdown]",
                emptyList<Double>(),
                loaded.categoryBreakdown.map { it.moneyTotal.amount }
            )
        }
    }
}
