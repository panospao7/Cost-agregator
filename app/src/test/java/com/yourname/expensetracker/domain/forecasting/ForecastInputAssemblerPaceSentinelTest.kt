package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * P5-002/NEW-P6-013 (RP-06 batch 6a): the legacy [ForecastInputAssembler.buildSpendingPace]
 * path must agree with the canonical [SpendingPaceCalculator] for the same
 * normalized snapshots, and its no-baseline percentage must be the canonical
 * -1f sentinel (was 0f, indistinguishable from a true 0% pace).
 *
 * The two paths are kept until their callers are migrated; this parity test is
 * the precondition for ever deleting either one.
 */
class ForecastInputAssemblerPaceSentinelTest {

    private var fixedNowMs: Long = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = fixedNowMs
    }
    private lateinit var assembler: ForecastInputAssembler
    private lateinit var calculator: SpendingPaceCalculator

    @Before
    fun setup() {
        // buildSpendingPace only touches timeProvider and its expense input; the
        // remaining collaborators are unused on this path.
        assembler = ForecastInputAssembler(
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = mockk(relaxed = true),
            currencySettingsRepository = mockk(relaxed = true),
            currencyConverter = mockk(relaxed = true),
            recurringLifecycleCoordinator = mockk(relaxed = true),
            recurringOccurrenceDao = mockk(relaxed = true),
            databaseReadBarrier = mockk(relaxed = true)
        )
        calculator = SpendingPaceCalculator(timeProvider)
    }

    @Test
    fun `no baseline yields the canonical -1f sentinel`() {
        fixedNowMs = toEpochMs(2024, 7, 15, 12, 0)
        val monthStart = TimePeriodUtils.getStartOfMonth(fixedNowMs)

        val expenses = listOf(
            purchase(1, 100.0, monthStart + DAY_MS),
            purchase(2, 150.0, monthStart + 2 * DAY_MS)
        )

        val pace = assembler.buildSpendingPace(expenses, displayCurrency = "EUR")

        assertEquals(PaceStatus.NO_BASELINE, pace.paceStatus)
        assertEquals("no-baseline percentage must be the canonical -1f sentinel", -1f, pace.pacePercentage)
    }

    @Test
    fun `completed baseline yields a real percentage on the legacy path`() {
        fixedNowMs = toEpochMs(2024, 7, 15, 12, 0)
        val monthStart = TimePeriodUtils.getStartOfMonth(fixedNowMs)

        val expenses = listOf(
            purchase(1, 1550.0, monthStart - 5 * DAY_MS),
            purchase(2, 1550.0, monthStart - 12 * DAY_MS),
            purchase(3, 100.0, monthStart + DAY_MS)
        )

        val pace = assembler.buildSpendingPace(expenses, displayCurrency = "EUR")

        assertEquals(PaceStatus.UNDER_PACE, pace.paceStatus)
        assertNotEquals(-1f, pace.pacePercentage)
        assertEquals(3100.0, pace.previousMonthTotal!!, 1e-6)
    }

    @Test
    fun `legacy assembler and canonical calculator agree for the same snapshots`() {
        fixedNowMs = toEpochMs(2024, 7, 15, 12, 0)
        val monthStart = TimePeriodUtils.getStartOfMonth(fixedNowMs)
        val previousMonthStart = TimePeriodUtils.getStartOfMonth(TimePeriodUtils.addMonths(fixedNowMs, -1))

        val expenses = listOf(
            purchase(1, 1550.0, monthStart - 5 * DAY_MS),
            purchase(2, 1550.0, monthStart - 12 * DAY_MS),
            purchase(3, 100.0, monthStart + DAY_MS),
            purchase(4, 100.0, monthStart + 2 * DAY_MS),
            // Non-purchase rows are excluded by both paths' ownership rules.
            purchase(5, 900.0, monthStart - 3 * DAY_MS, type = DomainTransactionType.DEPOSIT),
            purchase(6, 700.0, monthStart + DAY_MS, notMine = true)
        )

        val legacy = assembler.buildSpendingPace(expenses, displayCurrency = "EUR")
        val canonical = calculator.calculate(
            currentMonthStart = monthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = monthStart,
            allExpenses = expenses,
            displayCurrency = "EUR",
            referenceNowMs = fixedNowMs
        )

        assertEquals(canonical.paceStatus, legacy.paceStatus)
        assertEquals(canonical.pacePercentage, legacy.pacePercentage, 1e-3f)
        assertEquals(canonical.currentMonthSpent, legacy.currentMonthSpent, 1e-6)
        assertEquals(canonical.previousMonthTotal!!, legacy.previousMonthTotal!!, 1e-6)
        assertEquals(canonical.projectedTotal, legacy.projectedTotal, 1e-6)
        assertEquals(canonical.daysElapsed, legacy.daysElapsed)
        assertEquals(canonical.daysInMonth, legacy.daysInMonth)
        // averageMonthlyTotal is intentionally different: the dashboard overlays
        // the RP-05 completed-history mean, the calculator leaves it null.
    }

    private fun purchase(
        id: Long,
        amount: Double,
        date: Long,
        type: DomainTransactionType = DomainTransactionType.PURCHASE,
        notMine: Boolean = false
    ): ExpenseSnapshot = ExpenseSnapshot(
        id = id,
        amount = amount,
        effectiveAmount = amount,
        currency = "EUR",
        merchant = "M$id",
        merchantKey = null,
        transactionType = type,
        date = date,
        categoryId = 7L,
        isNotMine = notMine,
        transferDirection = null,
        notes = null
    )

    private fun toEpochMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
