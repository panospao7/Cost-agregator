package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.assertMoneyEquals
import com.yourname.expensetracker.assertMoneyExact
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SpendingPaceBoundaryTest {

    companion object {
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()
    }

    private lateinit var timeProvider: TimeProvider
    private lateinit var calculator: SpendingPaceCalculator

    @Before
    fun setUp() {
        timeProvider = mockk(relaxed = true)
        calculator = SpendingPaceCalculator(timeProvider)
        every { timeProvider.now() } returns ms(2026, 4, 15)
    }

    @Test
    fun `pace_status_boundaries_exactly_90_and_110_are_on_pace`() {
        val previousMonthStart = ms(2026, 3, 1)
        val currentMonthStart = ms(2026, 4, 1)
        val previousMonthEnd = currentMonthStart

        // March has 31 days, so 1550 => baseline daily rate 50.0
        val previous = expense(id = 1L, date = previousMonthStart, amount = 1550.0)

        val at90 = calculator.calculate(
            currentMonthStart = currentMonthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = listOf(previous, expense(id = 2L, date = ms(2026, 4, 2), amount = 675.0)),
            displayCurrency = "EUR"
        )

        val at110 = calculator.calculate(
            currentMonthStart = currentMonthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = listOf(previous, expense(id = 3L, date = ms(2026, 4, 3), amount = 825.0)),
            displayCurrency = "EUR"
        )

        val below90 = calculator.calculate(
            currentMonthStart = currentMonthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = listOf(previous, expense(id = 4L, date = ms(2026, 4, 4), amount = 674.925)),
            displayCurrency = "EUR"
        )

        val above110 = calculator.calculate(
            currentMonthStart = currentMonthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = listOf(previous, expense(id = 5L, date = ms(2026, 4, 5), amount = 825.075)),
            displayCurrency = "EUR"
        )

        assertApproxEquals(90f, at90.pacePercentage, 0.001f)
        assertApproxEquals(110f, at110.pacePercentage, 0.001f)
        assertApproxEquals(89.99f, below90.pacePercentage, 0.01f)
        assertApproxEquals(110.01f, above110.pacePercentage, 0.01f)

        assertEquals(PaceStatus.ON_PACE, at90.paceStatus)
        assertEquals(PaceStatus.ON_PACE, at110.paceStatus)
        assertEquals(PaceStatus.UNDER_PACE, below90.paceStatus)
        assertEquals(PaceStatus.OVER_PACE, above110.paceStatus)
    }

    @Test
    fun `day 1 projection blends baseline with linear projection`() {
        val previousMonthStart = ms(2026, 2, 1)
        val currentMonthStart = ms(2026, 3, 1)
        val previousMonthEnd = currentMonthStart
        every { timeProvider.now() } returns msAt(2026, 3, 1, 23, 59, 59)

        val result = calculator.calculate(
            currentMonthStart = currentMonthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = listOf(
                expense(id = 10L, date = ms(2026, 2, 10), amount = 100.0),
                expense(id = 11L, date = ms(2026, 3, 1), amount = 800.0)
            ),
            displayCurrency = "EUR"
        )

        assertMoneyEquals(800.0, result.currentMonthSpent, "current month spent")
        assertEquals(1, result.daysElapsed)
        // February baseline = 100 / 28 per day; March baseline projection (31 days) is
        // 110.714..., blended at 80% with the day-one linear projection (24800) at 20%:
        // 110.714... * 0.8 + 24800 * 0.2 = 5048.5714... -> 5048.57 at cent precision.
        assertMoneyEquals(5048.57, result.projectedTotal, "day 1 blended projection")
    }

    @Test
    fun `float boundary ratio at 90 percent remains on pace`() {
        val previousMonthStart = ms(2026, 2, 1)
        val currentMonthStart = ms(2026, 3, 1)
        val previousMonthEnd = currentMonthStart
        every { timeProvider.now() } returns msAt(2026, 3, 7, 23, 59, 59)

        val result = calculator.calculate(
            currentMonthStart = currentMonthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = listOf(
                expense(id = 30L, date = ms(2026, 2, 10), amount = 100.0),
                expense(id = 31L, date = ms(2026, 3, 3), amount = 22.5)
            ),
            displayCurrency = "EUR"
        )

        assertApproxEquals(90.0f, result.pacePercentage, 0.01f)
        assertEquals(PaceStatus.ON_PACE, result.paceStatus)
    }

    // The zero-previous-month baseline case that used to live here is now a row of
    // SpendingPaceCalculatorDeepTest."no baseline table" (row "boundary: two current
    // purchases, empty February").

    // ------------------------------------------------------------------------
    // B3-4 (NEW-P5-002): production projection guard. SpendingPaceCalculator and
    // ForecastInputAssembler floor daysElapsed at 1, so daysElapsed == 0 can only
    // reach the guard by calling SpendingPaceProjection directly. These replace the
    // formula-copy cases of the deleted DashboardProjectionSafetyTest.
    // ------------------------------------------------------------------------

    private data class GuardRow(
        val name: String,
        val monthSpent: Double,
        val daysElapsed: Int,
        val baselineDailyRate: Double,
        val expected: Double
    )

    @Test
    fun `projection with non positive days elapsed returns month spent without dividing by zero`() {
        val rows = listOf(
            GuardRow("zero days elapsed returns month spent", 100.0, 0, 5.0, 100.0),
            GuardRow("negative days elapsed returns month spent unchanged", 100.0, -1, 0.0, 100.0),
            GuardRow("zero days elapsed and zero spend is zero not NaN", 0.0, 0, 0.0, 0.0)
        )
        assertEquals("guard table size", 3, rows.size)

        for (row in rows) {
            val projected = SpendingPaceProjection.calculateProjectedTotal(
                monthSpent = row.monthSpent,
                daysElapsed = row.daysElapsed,
                daysInMonth = 30,
                baselineDailyRate = row.baselineDailyRate
            )
            // The guard returns monthSpent unchanged, so the result must be bit-exact.
            assertMoneyExact(row.expected, projected, row.name)
        }
    }

    @Test
    fun `projection at five or more days elapsed is the linear projection ignoring baseline`() {
        // 300 over 10 of 30 days -> 900; baseline must not influence a stable projection.
        assertMoneyEquals(
            900.0,
            SpendingPaceProjection.calculateProjectedTotal(300.0, 10, 30, baselineDailyRate = 99.0),
            "ten days elapsed"
        )
        // Exactly 5 days is already stable: 150 / 5 * 30 = 900.
        assertMoneyEquals(
            900.0,
            SpendingPaceProjection.calculateProjectedTotal(150.0, 5, 30, baselineDailyRate = 10.0),
            "exactly five days elapsed"
        )
    }

    @Test
    fun `projection before five days without baseline stabilizes elapsed days at five`() {
        // 90 over 3 days with no baseline: 90 * 30 / 5 = 540 (not the volatile 900).
        assertMoneyEquals(
            540.0,
            SpendingPaceProjection.calculateProjectedTotal(90.0, 3, 30, baselineDailyRate = 0.0),
            "three days, no baseline"
        )
    }

    @Test
    fun `projection at four days blends baseline and linear projections at exact weights`() {
        // linear = 120 * 30 / 4 = 900; baseline monthly = 10 * 30 = 300; weight = 4 / 5.
        // blended = 300 * 0.2 + 900 * 0.8 = 780.00
        val projected = SpendingPaceProjection.calculateProjectedTotal(120.0, 4, 30, baselineDailyRate = 10.0)

        assertMoneyEquals(780.0, projected, "four days blended")
    }

    private fun expense(id: Long, date: Long, amount: Double): ExpenseSnapshot = ExpenseSnapshot(
        id = id,
        amount = amount,
        effectiveAmount = amount,
        currency = "EUR",
        merchant = "Test",
        merchantKey = null,
        transactionType = DomainTransactionType.PURCHASE,
        date = date,
        categoryId = null,
        isNotMine = false,
        transferDirection = null,
        notes = null
    )

    // Zone is pinned to UTC by [utcZone]; systemDefault() therefore resolves to UTC.
    private fun ms(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun msAt(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute, second)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
