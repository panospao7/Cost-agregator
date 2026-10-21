package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.assertMoneyEquals
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MonthMoneyAggregate
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.data.repository.PeriodMoneyAggregate
import com.yourname.expensetracker.domain.core.money.ConversionFailure
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.FailureReason
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.model.PeriodStatus
import com.yourname.expensetracker.domain.model.PeriodTotal
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

/**
 * Validation tests for TotalsAggregationEngine period totals.
 *
 * Four tables (monthly, weekly, daily, yearly) pin repository aggregates to
 * [PeriodTotal] rows with exact hard-coded money (pass-through amounts compared
 * exactly; sums via [assertMoneyEquals]), period order, exact transaction counts,
 * period type, and where relevant
 * status, partial-state propagation, and the historical-API read. Every row runs
 * against freshly cleared and re-stubbed mocks. The percentage rounding invariant
 * stays a standalone test.
 */
class TotalsAggregationEngineValidationTest {

    private lateinit var engine: TotalsAggregationEngine
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var multiCurrencyRepo: MultiCurrencyRepository
    private lateinit var categoryRepository: CategoryRepository

    private val eur = CurrencyCode("EUR")

    // Helper to create timestamps for specific dates
    private fun createDate(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Before
    fun setup() {
        expenseRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        multiCurrencyRepo = mockk()
        categoryRepository = mockk(relaxed = true)
        stubDefaults()
        engine = TotalsAggregationEngine(expenseRepository, timeProvider, multiCurrencyRepo, categoryRepository, Dispatchers.Unconfined)
    }

    private fun stubDefaults() {
        // Must emit at least one value for reactiveFlow / reactiveCategoryBreakdownFlow to work
        every { expenseRepository.getTotalSpent() } returns flowOf(null)
        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } returns
            MoneyAggregateResult.Available(MoneyAggregate.empty(eur))
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns emptyList()
        coEvery { multiCurrencyRepo.getCategoryAggregatesHistorical(any(), any()) } returns emptyMap()
        coEvery { multiCurrencyRepo.getWeeklyAggregatesHistorical(any(), any()) } returns emptyList()
        coEvery { multiCurrencyRepo.getDailyAggregatesHistorical(any(), any()) } returns emptyList()
        coEvery { categoryRepository.getAll() } returns emptyList()
    }

    /** Fresh mocks for a table row: clear every recorded call/answer, re-stub, pin the clock. */
    private fun resetMocks(now: Long) {
        clearMocks(expenseRepository, timeProvider, multiCurrencyRepo, categoryRepository)
        stubDefaults()
        every { timeProvider.now() } returns now
    }

    // ========== Table machinery ==========

    private class Expect(
        val key: String,
        val amount: Double,
        val count: Int,
        val type: PeriodType,
        val status: PeriodStatus? = null,
        val isPartial: Boolean = false,
        val warning: String? = null,
        val label: String? = null,
        val startMs: Long? = null
    )

    private class PeriodRow(
        val name: String,
        val now: Long,
        val stub: () -> Unit,
        val call: () -> Flow<List<PeriodTotal>>,
        val expected: List<Expect>,
        val expectedSum: Double? = null,
        val expectedCountSum: Int? = null,
        val verifyApi: (() -> Unit)? = null
    )

    private suspend fun checkPeriodTable(table: String, expectedRowCount: Int, rows: List<PeriodRow>) {
        assertEquals("$table size", expectedRowCount, rows.size)
        rows.forEach { row ->
            resetMocks(row.now)
            row.stub()

            val result = row.call().first()

            val rowTag = "$table / ${row.name}"
            assertEquals("$rowTag: number of periods", row.expected.size, result.size)
            assertEquals("$rowTag: period order", row.expected.map { it.key }, result.map { it.periodKey })
            row.expected.forEach { expected ->
                val tag = "$rowTag [${expected.key}]"
                val matches = result.filter { it.periodKey == expected.key }
                assertEquals("$tag: exactly one period with this key", 1, matches.size)
                val actual = matches.single()
                // Pass-through of the repository amount (or zero-fill): must be exact.
                assertEquals("$tag: totalAmount", expected.amount, actual.totalAmount, 0.0)
                assertEquals("$tag: transactionCount", expected.count, actual.transactionCount)
                assertEquals("$tag: periodType", expected.type, actual.periodType)
                assertEquals("$tag: isPartial", expected.isPartial, actual.isPartial)
                assertEquals("$tag: warningMessage", expected.warning, actual.warningMessage)
                expected.status?.let { assertEquals("$tag: status", it, actual.status) }
                expected.label?.let { assertEquals("$tag: periodLabel", it, actual.periodLabel) }
                expected.startMs?.let { assertEquals("$tag: startDateMs", it, actual.startDateMs) }
            }
            row.expectedSum?.let {
                assertMoneyEquals(it, result.sumOf { period -> period.totalAmount }, "$rowTag: sum of totals")
            }
            row.expectedCountSum?.let {
                assertEquals("$rowTag: sum of counts", it, result.sumOf { period -> period.transactionCount })
            }
            row.verifyApi?.invoke()
        }
    }

    private fun partialAggregate(amount: Double, count: Int): MoneyAggregate =
        MoneyAggregate.partial(
            displayAmount = amount,
            displayCurrency = eur,
            sourceBuckets = listOf(MoneyBucket(eur, amount, count)),
            failures = listOf(
                ConversionFailure(
                    originalAmount = MoneyAmount(50.0, CurrencyCode("USD")),
                    targetCurrency = eur,
                    reason = FailureReason.MISSING_RATE,
                    transactionCount = 1
                )
            )
        )

    /** Warning produced by [MoneyAggregate.partial] for a single failure covering one transaction. */
    private val partialWarning = "Partial: 1 transaction(s) from 1 currency/currencies could not be converted"

    private val apr15of2026 = createDate(2026, 4, 15)
    private val apr15of2024Noon = createDate(2024, 4, 15, 12, 0)

    // ========== TABLE 1: monthly totals ==========

    private fun month(year: Int, m: Int, amount: Double = 0.0, count: Int = 0, isPartial: Boolean = false, warning: String? = null, status: PeriodStatus? = null) =
        Expect("%04d-%02d".format(year, m), amount, count, PeriodType.MONTH, isPartial = isPartial, warning = warning, status = status)

    private fun twelveMonths(year: Int, vararg nonZero: Expect): List<Expect> =
        (1..12).map { m -> nonZero.firstOrNull { it.key == "%04d-%02d".format(year, m) } ?: month(year, m) }

    private fun stubMonths(vararg months: Triple<String, Double, Int>) {
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns
            months.map { (key, amount, count) -> MonthMoneyAggregate(key, MoneyAggregate.singleCurrency(amount, eur, count)) }
    }

    @Test
    fun `monthly totals table - exact amounts counts and zero fill`() = runTest {
        val rows = listOf(
            PeriodRow("empty repository zero-fills 2026", apr15of2026, {}, { engine.getMonthlyTotals(2026) }, twelveMonths(2026)),
            PeriodRow("empty repository zero-fills 2024", apr15of2024Noon, {}, { engine.getMonthlyTotals(2024) }, twelveMonths(2024)),
            PeriodRow(
                "January 150 with 2 transactions", apr15of2026,
                { stubMonths(Triple("2026-01", 150.0, 2)) },
                { engine.getMonthlyTotals(2026) },
                twelveMonths(2026, month(2026, 1, 150.0, 2))
            ),
            PeriodRow(
                "purchase-only January 450 with 8 transactions", apr15of2026,
                { stubMonths(Triple("2026-01", 450.0, 8)) },
                { engine.getMonthlyTotals(2026) },
                twelveMonths(2026, month(2026, 1, 450.0, 8))
            ),
            PeriodRow(
                "three months 2024 sum to 5300", apr15of2024Noon,
                { stubMonths(Triple("2024-01", 1500.0, 15), Triple("2024-02", 2000.0, 20), Triple("2024-03", 1800.0, 18)) },
                { engine.getMonthlyTotals(2024) },
                twelveMonths(2024, month(2024, 1, 1500.0, 15), month(2024, 2, 2000.0, 20), month(2024, 3, 1800.0, 18)),
                expectedSum = 5300.0,
                expectedCountSum = 53
            ),
            PeriodRow(
                "January 100 and February 200 sum to 300", apr15of2026,
                { stubMonths(Triple("2026-01", 100.0, 2), Triple("2026-02", 200.0, 4)) },
                { engine.getMonthlyTotals(2026) },
                twelveMonths(2026, month(2026, 1, 100.0, 2), month(2026, 2, 200.0, 4)),
                expectedSum = 300.0,
                expectedCountSum = 6
            ),
            PeriodRow(
                "partial January keeps isPartial and warning and has no comparison status", apr15of2026,
                {
                    coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns
                        listOf(MonthMoneyAggregate("2026-01", partialAggregate(150.0, 2)))
                },
                { engine.getMonthlyTotals(2026) },
                twelveMonths(2026, month(2026, 1, 150.0, 2, isPartial = true, warning = partialWarning, status = PeriodStatus.NO_DATA))
            )
        )
        checkPeriodTable("monthly table", 7, rows)
    }

    // ========== TABLE 2: weekly totals ==========

    private fun week(key: String, amount: Double, count: Int, label: String? = null) =
        Expect(key, amount, count, PeriodType.WEEK, label = label)

    private fun stubWeeks(vararg weeks: Triple<String, Double, Int>) {
        coEvery { multiCurrencyRepo.getWeeklyAggregatesHistorical(any(), any()) } returns
            weeks.map { (key, amount, count) -> PeriodMoneyAggregate(key, MoneyAggregate.singleCurrency(amount, eur, count)) }
    }

    @Test
    fun `weekly totals table - exact amounts counts labels and historical API read`() = runTest {
        val rows = listOf(
            PeriodRow("no repository rows returns no weeks", apr15of2026, {}, { engine.getWeeklyTotals(2026, 1) }, emptyList()),
            PeriodRow(
                "empty aggregate week stays a zero row", apr15of2026,
                {
                    coEvery { multiCurrencyRepo.getWeeklyAggregatesHistorical(any(), any()) } returns
                        listOf(PeriodMoneyAggregate("2026-W1", MoneyAggregate.empty(eur)))
                },
                { engine.getWeeklyTotals(2026, 1) },
                listOf(week("2026-W1", 0.0, 0))
            ),
            PeriodRow(
                "week 300 with 5 transactions", apr15of2026,
                { stubWeeks(Triple("2026-W3", 300.0, 5)) },
                { engine.getWeeklyTotals(2026, 1) },
                listOf(week("2026-W3", 300.0, 5))
            ),
            PeriodRow(
                "purchase-only week 275 with 6 transactions", apr15of2026,
                { stubWeeks(Triple("2026-W2", 275.0, 6)) },
                { engine.getWeeklyTotals(2026, 1) },
                listOf(week("2026-W2", 275.0, 6))
            ),
            PeriodRow(
                "two weeks 70 and 130 sum to 200", apr15of2026,
                { stubWeeks(Triple("2026-W05", 70.0, 2), Triple("2026-W06", 130.0, 3)) },
                { engine.getWeeklyTotals(2026, 2) },
                listOf(week("2026-W05", 70.0, 2), week("2026-W06", 130.0, 3)),
                expectedSum = 200.0,
                expectedCountSum = 5
            ),
            PeriodRow(
                "labels are W1 W2 W3 and weeks are not zero-filled", apr15of2026,
                { stubWeeks(Triple("2026-W2", 100.0, 2), Triple("2026-W3", 200.0, 4), Triple("2026-W4", 150.0, 3)) },
                { engine.getWeeklyTotals(2026, 1) },
                listOf(week("2026-W2", 100.0, 2, "W1"), week("2026-W3", 200.0, 4, "W2"), week("2026-W4", 150.0, 3, "W3"))
            ),
            PeriodRow(
                "reads the PURCHASE-only historical weekly API", apr15of2026,
                { stubWeeks(Triple("2026-W03", 120.0, 4)) },
                { engine.getWeeklyTotals(2026, 1) },
                listOf(week("2026-W03", 120.0, 4)),
                verifyApi = { coVerify { multiCurrencyRepo.getWeeklyAggregatesHistorical(any(), any()) } }
            )
        )
        checkPeriodTable("weekly table", 7, rows)
    }

    // ========== TABLE 3: daily totals ==========

    private fun day(key: String, amount: Double = 0.0, count: Int = 0, startMs: Long? = null) =
        Expect(key, amount, count, PeriodType.DAY, startMs = startMs)

    private fun stubDays(vararg days: Triple<String, Double, Int>) {
        coEvery { multiCurrencyRepo.getDailyAggregatesHistorical(any(), any()) } returns
            days.map { (key, amount, count) -> PeriodMoneyAggregate(key, MoneyAggregate.singleCurrency(amount, eur, count)) }
    }

    private fun stubJanuaryWeekWith(vararg nonZero: Triple<String, Double, Int>) {
        coEvery { multiCurrencyRepo.getDailyAggregatesHistorical(any(), any()) } returns (12..18).map { dayOfMonth ->
            val key = "2026-01-%02d".format(dayOfMonth)
            val hit = nonZero.firstOrNull { it.first == key }
            PeriodMoneyAggregate(key, if (hit == null) MoneyAggregate.empty(eur) else MoneyAggregate.singleCurrency(hit.second, eur, hit.third))
        }
    }

    private fun januaryWeek(vararg nonZero: Expect): List<Expect> =
        (12..18).map { dayOfMonth ->
            val key = "2026-01-%02d".format(dayOfMonth)
            nonZero.firstOrNull { it.key == key } ?: day(key)
        }

    @Test
    fun `daily totals table - exact amounts counts boundaries and historical API read`() = runTest {
        val rows = listOf(
            PeriodRow("no repository rows returns no days", apr15of2026, {}, { engine.getDailyTotals(2026, 1) }, emptyList()),
            PeriodRow("seven empty days stay zero rows", apr15of2026, { stubJanuaryWeekWith() }, { engine.getDailyTotals(2026, 3) }, januaryWeek()),
            PeriodRow(
                "week day 50 with 3 transactions", apr15of2026,
                { stubJanuaryWeekWith(Triple("2026-01-12", 50.0, 3)) },
                { engine.getDailyTotals(2026, 3) },
                januaryWeek(day("2026-01-12", 50.0, 3))
            ),
            PeriodRow(
                "purchase-only week day 88.50 with 4 transactions", apr15of2026,
                { stubJanuaryWeekWith(Triple("2026-01-12", 88.50, 4)) },
                { engine.getDailyTotals(2026, 3) },
                januaryWeek(day("2026-01-12", 88.50, 4))
            ),
            PeriodRow(
                "range keeps repository-supplied empty days as zero rows", apr15of2026,
                {
                    coEvery { multiCurrencyRepo.getDailyAggregatesHistorical(any(), any()) } returns listOf(
                        PeriodMoneyAggregate("2026-01-12", MoneyAggregate.singleCurrency(50.0, eur, 2)),
                        PeriodMoneyAggregate("2026-01-13", MoneyAggregate.empty(eur)),
                        PeriodMoneyAggregate("2026-01-14", MoneyAggregate.empty(eur))
                    )
                },
                { engine.getDailyTotalsForRange(createDate(2026, 1, 12), createDate(2026, 1, 14, 23, 59)) },
                listOf(day("2026-01-12", 50.0, 2), day("2026-01-13"), day("2026-01-14"))
            ),
            PeriodRow(
                "range purchase-only day 75 with 3 transactions", apr15of2026,
                { stubDays(Triple("2026-03-10", 75.0, 3)) },
                { engine.getDailyTotalsForRange(createDate(2026, 3, 10), createDate(2026, 3, 10, 23, 59)) },
                listOf(day("2026-03-10", 75.0, 3))
            ),
            PeriodRow(
                "range two days 45 and 110 sum to 155 with 7 transactions", apr15of2026,
                { stubDays(Triple("2026-02-05", 45.0, 2), Triple("2026-02-06", 110.0, 5)) },
                { engine.getDailyTotalsForRange(createDate(2026, 2, 5), createDate(2026, 2, 6, 23, 59)) },
                listOf(day("2026-02-05", 45.0, 2), day("2026-02-06", 110.0, 5)),
                expectedSum = 155.0,
                expectedCountSum = 7
            ),
            PeriodRow(
                "range reads the PURCHASE-only historical daily API", apr15of2026,
                { stubDays(Triple("2026-01-12", 40.0, 2)) },
                { engine.getDailyTotalsForRange(createDate(2026, 1, 12), createDate(2026, 1, 12, 23, 59)) },
                listOf(day("2026-01-12", 40.0, 2)),
                verifyApi = { coVerify { multiCurrencyRepo.getDailyAggregatesHistorical(any(), any()) } }
            ),
            PeriodRow(
                "compact day keys 20 and 30 sum to 50", apr15of2026,
                { stubDays(Triple("20260202", 20.0, 1), Triple("20260203", 30.0, 1)) },
                { engine.getDailyTotalsForRange(createDate(2026, 2, 2), createDate(2026, 2, 9)) },
                listOf(day("20260202", 20.0, 1), day("20260203", 30.0, 1)),
                expectedSum = 50.0,
                expectedCountSum = 2
            ),
            PeriodRow(
                "full week 2024 days 100 150 200 then four zero days sum to 450", apr15of2024Noon,
                {
                    stubDays(
                        Triple("2024-04-15", 100.0, 2), Triple("2024-04-16", 150.0, 3), Triple("2024-04-17", 200.0, 4),
                        Triple("2024-04-18", 0.0, 0), Triple("2024-04-19", 0.0, 0),
                        Triple("2024-04-20", 0.0, 0), Triple("2024-04-21", 0.0, 0)
                    )
                },
                { engine.getDailyTotalsForRange(createDate(2024, 4, 15), createDate(2024, 4, 22)) },
                listOf(
                    day("2024-04-15", 100.0, 2, createDate(2024, 4, 15)),
                    day("2024-04-16", 150.0, 3, createDate(2024, 4, 16)),
                    day("2024-04-17", 200.0, 4, createDate(2024, 4, 17)),
                    day("2024-04-18"), day("2024-04-19"), day("2024-04-20"), day("2024-04-21")
                ),
                expectedSum = 450.0,
                expectedCountSum = 9
            ),
            PeriodRow(
                "midnight transaction lands in its own day", apr15of2026,
                { stubDays(Triple("2024-04-15", 100.0, 1)) },
                { engine.getDailyTotalsForRange(createDate(2024, 4, 15), createDate(2024, 4, 16)) },
                listOf(day("2024-04-15", 100.0, 1, createDate(2024, 4, 15)))
            )
        )
        checkPeriodTable("daily table", 11, rows)
    }

    // ========== TABLE 4: yearly totals ==========

    private fun year(y: Int, amount: Double, count: Int, status: PeriodStatus? = null, isPartial: Boolean = false, warning: String? = null) =
        Expect(y.toString(), amount, count, PeriodType.YEAR, status = status, isPartial = isPartial, warning = warning)

    /** Per-year purchase totals; any year not listed returns an empty aggregate. */
    private fun stubYears(vararg years: Triple<Int, Double, Int>) {
        val byYear = years.associateBy { it.first }
        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } answers {
            val calendarYear = Instant.ofEpochMilli(firstArg<Long>()).atZone(ZoneId.systemDefault()).year
            val hit = byYear[calendarYear]
            MoneyAggregateResult.Available(
                if (hit == null) MoneyAggregate.empty(eur) else MoneyAggregate.singleCurrency(hit.second, eur, hit.third)
            )
        }
    }

    @Test
    fun `yearly totals table - exact amounts counts filtering status and partial state`() = runTest {
        val rows = listOf(
            PeriodRow(
                "purchase-only 2025 and 2026 only", apr15of2026,
                { stubYears(Triple(2025, 12000.0, 120), Triple(2026, 3500.0, 35)) },
                { engine.getYearlyTotals() },
                listOf(year(2025, 12000.0, 120), year(2026, 3500.0, 35))
            ),
            PeriodRow(
                "status uses completed years only (2024 and 2025 average 12000)", apr15of2026,
                { stubYears(Triple(2024, 10000.0, 100), Triple(2025, 14000.0, 140), Triple(2026, 2000.0, 20)) },
                { engine.getYearlyTotals() },
                listOf(
                    year(2024, 10000.0, 100, PeriodStatus.UNDER_AVERAGE),
                    year(2025, 14000.0, 140, PeriodStatus.OVER_AVERAGE),
                    year(2026, 2000.0, 20, PeriodStatus.UNDER_AVERAGE)
                )
            ),
            PeriodRow(
                "years with no data are dropped; 2024 is kept because it is the current year", apr15of2024Noon,
                {
                    stubYears(
                        Triple(2021, 5000.0, 50), Triple(2022, 6000.0, 60),
                        Triple(2023, 7000.0, 70), Triple(2024, 3000.0, 30)
                    )
                },
                { engine.getYearlyTotals() },
                listOf(year(2021, 5000.0, 50), year(2022, 6000.0, 60), year(2023, 7000.0, 70), year(2024, 3000.0, 30))
            ),
            PeriodRow(
                "status average of completed years 5000 6000 7000 is 6000", apr15of2024Noon,
                {
                    stubYears(
                        Triple(2021, 5000.0, 50), Triple(2022, 6000.0, 60),
                        Triple(2023, 7000.0, 70), Triple(2024, 1500.0, 15)
                    )
                },
                { engine.getYearlyTotals() },
                listOf(
                    year(2021, 5000.0, 50, PeriodStatus.UNDER_AVERAGE),
                    year(2022, 6000.0, 60, PeriodStatus.OVER_AVERAGE),
                    year(2023, 7000.0, 70, PeriodStatus.OVER_AVERAGE),
                    year(2024, 1500.0, 15, PeriodStatus.UNDER_AVERAGE)
                )
            ),
            PeriodRow(
                "transaction counts come from the purchase-only aggregate", apr15of2024Noon,
                { stubYears(Triple(2023, 8500.0, 95), Triple(2024, 2100.0, 22)) },
                { engine.getYearlyTotals() },
                listOf(year(2023, 8500.0, 95), year(2024, 2100.0, 22))
            ),
            PeriodRow(
                "five consecutive years 1000 to 1800 map exactly", apr15of2026,
                {
                    stubYears(
                        Triple(2022, 1000.0, 6), Triple(2023, 1200.0, 6), Triple(2024, 1400.0, 6),
                        Triple(2025, 1600.0, 6), Triple(2026, 1800.0, 6)
                    )
                },
                { engine.getYearlyTotals() },
                listOf(
                    year(2022, 1000.0, 6, PeriodStatus.UNDER_AVERAGE),
                    year(2023, 1200.0, 6, PeriodStatus.UNDER_AVERAGE),
                    year(2024, 1400.0, 6, PeriodStatus.OVER_AVERAGE),
                    year(2025, 1600.0, 6, PeriodStatus.OVER_AVERAGE),
                    year(2026, 1800.0, 6, PeriodStatus.OVER_AVERAGE)
                )
            ),
            PeriodRow(
                "partial conversion propagates to every year and blocks status", apr15of2026,
                {
                    coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } returns
                        MoneyAggregateResult.Available(partialAggregate(1000.0, 5))
                },
                { engine.getYearlyTotals() },
                (2022..2026).map { year(it, 1000.0, 5, PeriodStatus.NO_DATA, isPartial = true, warning = partialWarning) }
            ),
            PeriodRow(
                "unavailable home currency degrades to a zero current year without crashing", apr15of2026,
                {
                    coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } returns
                        MoneyAggregateResult.Unavailable(
                            reason = "Home currency unavailable",
                            requestedRateBasis = RateBasis.TRANSACTION_DATE
                        )
                },
                { engine.getYearlyTotals() },
                listOf(year(2026, 0.0, 0, PeriodStatus.NO_DATA))
            )
        )
        checkPeriodTable("yearly table", 8, rows)
    }

    // ========== Standalone: percentage rounding invariant ==========

    @Test
    fun `category_percentage_rounding_sum_invariant`() = runTest {
        resetMocks(apr15of2026)
        val categoryAggregates = mapOf<Long?, MoneyAggregate>(
            1L to MoneyAggregate.singleCurrency(33.33, eur, 1),
            2L to MoneyAggregate.singleCurrency(33.33, eur, 1),
            3L to MoneyAggregate.singleCurrency(33.33, eur, 1)
        )
        val categories = listOf(
            Category(id = 1, name = "A", icon = "a", color = "#111111"),
            Category(id = 2, name = "B", icon = "b", color = "#222222"),
            Category(id = 3, name = "C", icon = "c", color = "#333333")
        )
        // These icons are all 1 char, well within the 10-char limit
        coEvery { multiCurrencyRepo.getCategoryAggregatesHistorical(any(), any()) } returns categoryAggregates
        coEvery { categoryRepository.getAll() } returns categories

        val result = engine.getCategoryBreakdown(createDate(2024, 4, 1), createDate(2024, 5, 1), "April 2024").first()
        val roundedPercentSum = result.sumOf { kotlin.math.round(it.percentageOfTotal * 100) / 100.0 }

        // Rounded percentages should preserve the 100% invariant within small float tolerance.
        assertApproxEquals(100.0, roundedPercentSum, 0.05)
    }
}
