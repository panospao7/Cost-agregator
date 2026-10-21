package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertMoneyEquals
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MonthMoneyAggregate
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.data.repository.PeriodMoneyAggregate
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Exact-value pins for [TotalsAggregationEngine] averages and category breakdown.
 *
 * Two tables, every expected value hard-coded. The category `totalAmount` (a repository
 * pass-through) is asserted bit-exact (`assertEquals(..., 0.0)`); the computed means and the
 * percentages use [assertMoneyEquals], which rounds only the actual HALF_UP to cents and
 * compares it with the cent-precision literal (not bit-exact).
 * The engine does not round: a mean is the raw
 * `displayAmount / divisor` (TotalsAggregationEngine.averageMoney), so for example
 * the 12-month mean 1600 / 12 is 133.33333333333334 and is pinned as 133.33.
 * Each row runs against freshly cleared and re-stubbed mocks.
 */
class TotalsAggregationEngineDeepTest {

    private lateinit var engine: TotalsAggregationEngine
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var multiCurrencyRepo: MultiCurrencyRepository
    private lateinit var categoryRepository: CategoryRepository

    private val eur = CurrencyCode("EUR")

    @Before
    fun setup() {
        expenseRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        multiCurrencyRepo = mockk()
        categoryRepository = mockk(relaxed = true)
        stubDefaults()
        engine = TotalsAggregationEngine(expenseRepository, timeProvider, multiCurrencyRepo, categoryRepository, Dispatchers.Unconfined)
        every { timeProvider.now() } returns dateMs(2026, 4, 15)
    }

    private fun stubDefaults() {
        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } returns
            MoneyAggregateResult.Available(MoneyAggregate.empty(eur))
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns emptyList()
        coEvery { multiCurrencyRepo.getCategoryAggregatesHistorical(any(), any()) } returns emptyMap()
        coEvery { multiCurrencyRepo.getWeeklyAggregatesHistorical(any(), any()) } returns emptyList()
        coEvery { multiCurrencyRepo.getDailyAggregatesHistorical(any(), any()) } returns emptyList()
        coEvery { categoryRepository.getAll() } returns emptyList()
        // Reactive flow trigger: must emit at least once so flatMapLatest executes
        every { expenseRepository.getTotalSpent() } returns flowOf(0.0)
    }

    /** Fresh mocks for a table row: clear every recorded call/answer, re-stub, pin the clock. */
    private fun resetMocks(now: Long) {
        clearMocks(expenseRepository, timeProvider, multiCurrencyRepo, categoryRepository)
        stubDefaults()
        every { timeProvider.now() } returns now
    }

    // ========== TABLE 1: getAverageForPeriodType ==========

    private class AverageRow(
        val name: String,
        val type: PeriodType,
        val excludeCurrent: Boolean,
        val now: Long,
        /** Cent-precision expected mean in EUR, or null when the baseline must be unavailable. */
        val expected: Double?,
        val stub: () -> Unit
    )

    /** YEAR windows (about 365 days) yield [byYear] totals (missing years 0.0); other windows yield [other]. */
    private fun stubPurchaseTotals(byYear: Map<Int, Double> = emptyMap(), other: Double? = null) {
        val thirtyOneDaysMs = 31L * 24 * 60 * 60 * 1000
        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            val amount = if (other != null && end - start <= thirtyOneDaysMs) {
                other
            } else {
                byYear[Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).year] ?: 0.0
            }
            MoneyAggregateResult.Available(MoneyAggregate.singleCurrency(amount, eur, 0))
        }
    }

    private fun stubMonths(vararg months: Pair<String, Double>) {
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns
            months.map { (key, amount) -> MonthMoneyAggregate(key, MoneyAggregate.singleCurrency(amount, eur, 1)) }
    }

    private fun stubWeeks(vararg weeks: Pair<String, Double>) {
        coEvery { multiCurrencyRepo.getWeeklyAggregatesHistorical(any(), any()) } returns
            weeks.map { (key, amount) -> PeriodMoneyAggregate(key, MoneyAggregate.singleCurrency(amount, eur, 1)) }
    }

    private fun averageRows(): List<AverageRow> {
        val apr15of2026 = dateMs(2026, 4, 15)
        val apr15of2024Noon = LocalDateTime.of(2024, 4, 15, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val years2023to2025 = mapOf(2023 to 10000.0, 2024 to 14000.0, 2025 to 12000.0)
        return listOf(
            // --- include-current (excludeCurrent = false) ---
            AverageRow("MONTH include (1200+900+1500)/3", PeriodType.MONTH, false, apr15of2026, 1200.0) {
                stubMonths("2025-05" to 1200.0, "2025-06" to 900.0, "2025-07" to 1500.0)
            },
            AverageRow("MONTH include (1000+1200+1100)/3", PeriodType.MONTH, false, apr15of2024Noon, 1100.0) {
                stubMonths("2023-05" to 1000.0, "2023-06" to 1200.0, "2023-07" to 1100.0)
            },
            AverageRow("MONTH include 12 months 1600/12 is 133.33", PeriodType.MONTH, false, apr15of2026, 133.33) {
                stubMonths(
                    "2025-05" to 100.0, "2025-06" to 100.0, "2025-07" to 100.0, "2025-08" to 100.0,
                    "2025-09" to 100.0, "2025-10" to 100.0, "2025-11" to 100.0, "2025-12" to 100.0,
                    "2026-01" to 300.0, "2026-02" to 200.0, "2026-03" to 300.0, "2026-04" to 0.0
                )
            },
            AverageRow("WEEK include (250+350+300)/3", PeriodType.WEEK, false, apr15of2026, 300.0) {
                stubWeeks("2026-W10" to 250.0, "2026-W11" to 350.0, "2026-W12" to 300.0)
            },
            AverageRow("WEEK include (70+140)/2", PeriodType.WEEK, false, apr15of2026, 105.0) {
                stubWeeks("w1" to 70.0, "w2" to 140.0)
            },
            AverageRow("DAY total 1275 over 30 days", PeriodType.DAY, false, apr15of2026, 42.50) {
                stubPurchaseTotals(other = 1275.0)
            },
            AverageRow("DAY total 4500 over 30 days", PeriodType.DAY, false, apr15of2024Noon, 150.0) {
                stubPurchaseTotals(other = 4500.0)
            },
            AverageRow("DAY total 345 over 30 days", PeriodType.DAY, false, apr15of2026, 11.50) {
                stubPurchaseTotals(other = 345.0)
            },
            AverageRow("DAY zero total gives zero mean not unavailable", PeriodType.DAY, false, apr15of2026, 0.0) { },
            AverageRow("YEAR include zero years are dropped (10000+14000+12000)/3", PeriodType.YEAR, false, apr15of2026, 12000.0) {
                stubPurchaseTotals(byYear = years2023to2025)
            },
            AverageRow("YEAR include five identical years", PeriodType.YEAR, false, apr15of2026, 200.0) {
                stubPurchaseTotals(byYear = mapOf(2022 to 200.0, 2023 to 200.0, 2024 to 200.0, 2025 to 200.0, 2026 to 200.0))
            },
            AverageRow("YEAR include current year (10000+14000+12000+5000)/4", PeriodType.YEAR, false, apr15of2026, 10250.0) {
                stubPurchaseTotals(byYear = years2023to2025 + (2026 to 5000.0))
            },
            AverageRow("YEAR include current year (8000+12000+3000)/3 is 7666.67", PeriodType.YEAR, false, apr15of2026, 7666.67) {
                stubPurchaseTotals(byYear = mapOf(2024 to 8000.0, 2025 to 12000.0, 2026 to 3000.0))
            },
            AverageRow("YEAR include with no purchase data is unavailable", PeriodType.YEAR, false, apr15of2026, null) { },
            // --- exclude-current (excludeCurrent = true) ---
            AverageRow("MONTH exclude current (800+1000)/2", PeriodType.MONTH, true, apr15of2026, 900.0) {
                stubMonths("2025-06" to 800.0, "2025-07" to 1000.0, "2026-04" to 400.0)
            },
            AverageRow("MONTH exclude current (1000+1200)/2", PeriodType.MONTH, true, apr15of2024Noon, 1100.0) {
                stubMonths("2023-05" to 1000.0, "2023-06" to 1200.0, "2024-04" to 800.0)
            },
            AverageRow("WEEK exclude in-progress W15 (200+400)/2", PeriodType.WEEK, true, dateMs(2026, 4, 8), 300.0) {
                stubWeeks("2026-W13" to 200.0, "2026-W14" to 400.0, "2026-W15" to 150.0)
            },
            AverageRow("YEAR exclude current (10000+14000+12000)/3", PeriodType.YEAR, true, apr15of2026, 12000.0) {
                stubPurchaseTotals(byYear = years2023to2025 + (2026 to 5000.0))
            },
            AverageRow("YEAR exclude current (8000+12000)/2", PeriodType.YEAR, true, apr15of2026, 10000.0) {
                stubPurchaseTotals(byYear = mapOf(2024 to 8000.0, 2025 to 12000.0, 2026 to 3000.0))
            }
        )
    }

    @Test
    fun `average table - exact money per period type and exclude-current policy`() = runTest {
        val rows = averageRows()
        assertEquals("average table size", 19, rows.size)
        rows.forEach { row ->
            resetMocks(row.now)
            row.stub()

            val average = engine.getAverageForPeriodType(row.type, row.excludeCurrent)

            if (row.expected == null) {
                assertNull("${row.name}: baseline must be unavailable, not zero", average)
            } else {
                assertMoneyEquals(row.expected, requireNotNull(average) { "${row.name}: average was null" }.amount, row.name)
                assertEquals("${row.name}: currency", eur, average.currency)
            }
        }
    }

    // ========== TABLE 2: getCategoryBreakdown ==========

    private class CategoryIn(
        val id: Long,
        val name: String,
        val amount: Double,
        val count: Int,
        val icon: String = "?",
        val color: String = "#808080"
    )

    private class CategoryOut(
        val name: String,
        val amount: Double,
        val count: Int,
        /** Percent of the grand total at 2-decimal precision. */
        val percent: Double,
        val icon: String = "?",
        val color: String = "#808080"
    )

    private class CategoryRow(
        val name: String,
        val label: String,
        val input: List<CategoryIn>,
        /** Expected output in sorted (descending amount) order. */
        val expected: List<CategoryOut>,
        val expectedPercentSum: Double? = null
    )

    private fun categoryRows(): List<CategoryRow> = listOf(
        CategoryRow(
            "250/150/100 percentages 50/30/20", "Jan",
            listOf(
                CategoryIn(1L, "Groceries", 250.0, 10),
                CategoryIn(2L, "Entertainment", 150.0, 5),
                CategoryIn(3L, "Transport", 100.0, 3)
            ),
            listOf(
                CategoryOut("Groceries", 250.0, 10, 50.0),
                CategoryOut("Entertainment", 150.0, 5, 30.0),
                CategoryOut("Transport", 100.0, 3, 20.0)
            ),
            100.0
        ),
        CategoryRow("empty repository result", "Apr", emptyList(), emptyList()),
        CategoryRow(
            "sorted by amount descending", "Jan",
            listOf(
                CategoryIn(1L, "Small", 50.0, 2),
                CategoryIn(2L, "Large", 500.0, 10),
                CategoryIn(3L, "Medium", 150.0, 5)
            ),
            listOf(
                CategoryOut("Large", 500.0, 10, 71.43),
                CategoryOut("Medium", 150.0, 5, 21.43),
                CategoryOut("Small", 50.0, 2, 7.14)
            ),
            100.0
        ),
        CategoryRow(
            "sorted by purchase amount descending", "Mar",
            listOf(
                CategoryIn(1L, "Small Category", 50.0, 2),
                CategoryIn(2L, "Large Category", 500.0, 10),
                CategoryIn(3L, "Medium Category", 200.0, 5)
            ),
            listOf(
                CategoryOut("Large Category", 500.0, 10, 66.67),
                CategoryOut("Medium Category", 200.0, 5, 26.67),
                CategoryOut("Small Category", 50.0, 2, 6.67)
            ),
            100.0
        ),
        CategoryRow(
            "single category keeps its metadata and is 100 percent", "Jan",
            listOf(CategoryIn(1L, "Unknown", 100.0, 5)),
            listOf(CategoryOut("Unknown", 100.0, 5, 100.0))
        ),
        CategoryRow(
            "zero grand total gives zero percent not NaN", "Jan",
            listOf(CategoryIn(1L, "Empty", 0.0, 0)),
            listOf(CategoryOut("Empty", 0.0, 0, 0.0))
        ),
        CategoryRow(
            "purchase-only 200/100 percentages sum to 100", "Mar",
            listOf(
                CategoryIn(1L, "Groceries", 200.0, 5),
                CategoryIn(2L, "Dining", 100.0, 3)
            ),
            listOf(
                CategoryOut("Groceries", 200.0, 5, 66.67),
                CategoryOut("Dining", 100.0, 3, 33.33)
            ),
            100.0
        ),
        CategoryRow(
            "300/100 is 75/25", "Apr",
            listOf(
                CategoryIn(1L, "Cat1", 300.0, 3),
                CategoryIn(2L, "Cat2", 100.0, 1)
            ),
            listOf(
                CategoryOut("Cat1", 300.0, 3, 75.0),
                CategoryOut("Cat2", 100.0, 1, 25.0)
            ),
            100.0
        ),
        CategoryRow(
            "500/300/200 percentages sum to 100", "April 2024",
            listOf(
                CategoryIn(1L, "Food", 500.0, 5, "food", "#FF0000"),
                CategoryIn(2L, "Transport", 300.0, 3, "vehicle", "#00FF00"),
                CategoryIn(3L, "Entertainment", 200.0, 2, "entertain", "#0000FF")
            ),
            listOf(
                CategoryOut("Food", 500.0, 5, 50.0, "food", "#FF0000"),
                CategoryOut("Transport", 300.0, 3, 30.0, "vehicle", "#00FF00"),
                CategoryOut("Entertainment", 200.0, 2, 20.0, "entertain", "#0000FF")
            ),
            100.0
        ),
        CategoryRow(
            "single category with 1000 is 100 percent", "April 2024",
            listOf(CategoryIn(1L, "Food", 1000.0, 10, "food", "#FF0000")),
            listOf(CategoryOut("Food", 1000.0, 10, 100.0, "food", "#FF0000"))
        )
    )

    @Test
    fun `category breakdown table - exact totals percentages order and metadata`() = runTest {
        val rows = categoryRows()
        assertEquals("category table size", 10, rows.size)
        rows.forEach { row ->
            resetMocks(dateMs(2026, 4, 15))
            coEvery { multiCurrencyRepo.getCategoryAggregatesHistorical(any(), any()) } returns
                row.input.associate { it.id to MoneyAggregate.singleCurrency(it.amount, eur, it.count) }
            coEvery { categoryRepository.getAll() } returns
                row.input.map { Category(id = it.id, name = it.name, icon = it.icon, color = it.color) }

            val result = engine.getCategoryBreakdown(dateMs(2026, 4, 1), dateMs(2026, 5, 1), row.label).first()

            assertEquals("${row.name}: row count", row.expected.size, result.size)
            row.expected.forEachIndexed { index, expected ->
                val actual = result[index]
                val tag = "${row.name} [#$index ${expected.name}]"
                assertEquals("$tag: name at sorted position", expected.name, actual.category.name)
                assertEquals("$tag: icon", expected.icon, actual.category.icon)
                assertEquals("$tag: color", expected.color, actual.category.color)
                assertEquals("$tag: periodLabel", row.label, actual.periodLabel)
                assertEquals("$tag: transactionCount", expected.count, actual.transactionCount)
                // Pass-through of the repository aggregate's displayAmount: must be exact.
                assertEquals("$tag: totalAmount", expected.amount, actual.totalAmount, 0.0)
                assertMoneyEquals(expected.percent, actual.percentageOfTotal, "$tag: percentageOfTotal")
            }
            row.expectedPercentSum?.let {
                assertMoneyEquals(it, result.sumOf { entry -> entry.percentageOfTotal }, "${row.name}: percent sum")
            }
        }
    }

    private fun dateMs(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
