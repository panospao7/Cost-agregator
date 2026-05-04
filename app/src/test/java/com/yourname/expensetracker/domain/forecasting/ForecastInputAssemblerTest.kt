package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class ForecastInputAssemblerTest {

    private lateinit var timeProvider: TimeProvider
    private lateinit var assembler: ForecastInputAssembler

    @Before
    fun setup() {
        timeProvider = mockk()
        assembler = ForecastInputAssembler(timeProvider, analyticsCurrencyNormalizer = mockk(), currencySettingsRepository = mockk(), recurringLifecycleCoordinator = mockk(), recurringOccurrenceDao = mockk())
        every { timeProvider.now() } returns ms(2026, Calendar.JANUARY, 15, 12)
    }

    @Test
    fun `merge recurring keeps manual and high confidence detected with manual precedence`() {
        val now = ms(2026, Calendar.JANUARY, 15, 12)
        val manual = listOf(
            ManualRecurringExpense(
                id = 1,
                merchant = "Netflix",
                amount = 15.0,
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = now + DAY_MS
            )
        )
        val detected = listOf(
            recurring("Netflix", 99.0, now + DAY_MS, 0.95f),
            recurring("Gym", 30.0, now + 2 * DAY_MS, 0.80f)
        )

        val merged = assembler.mergeRecurringPatterns(manual, detected)
        val byMerchant = merged.associateBy { it.merchantName }

        assertEquals(2, merged.size)
        assertEquals(15.0, byMerchant.getValue("Netflix").averageAmount, 0.0001)
        assertEquals(1.0f, byMerchant.getValue("Netflix").confidence)
        assertTrue(byMerchant.containsKey("Gym"))
    }

    @Test
    fun `merge recurring deduplicates stale manual duplicates by merchant frequency and amount signature`() {
        val now = ms(2026, Calendar.JANUARY, 15, 12)
        val manual = listOf(
            ManualRecurringExpense(
                id = 1,
                merchant = "NETFLIX",
                amount = 20.0,
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = now + DAY_MS
            ),
            ManualRecurringExpense(
                id = 2,
                merchant = "Netflix",
                amount = 20.0,
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = now + 3 * DAY_MS
            ),
            ManualRecurringExpense(
                id = 3,
                merchant = "Netflix!",
                amount = 20.0,
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = now + 2 * DAY_MS
            )
        )

        val merged = assembler.mergeRecurringPatterns(manual, emptyList())

        assertEquals(1, merged.size)
        assertEquals(2L, merged.single().id)
        assertEquals(20.0, merged.single().averageAmount, 0.0001)
    }

    @Test
    fun `merge recurring keeps legitimate same merchant manual rules when signature differs`() {
        val now = ms(2026, Calendar.JANUARY, 15, 12)
        val manual = listOf(
            ManualRecurringExpense(
                id = 1,
                merchant = "Netflix",
                amount = 15.0,
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = now + DAY_MS
            ),
            ManualRecurringExpense(
                id = 2,
                merchant = "NETFLIX!!",
                amount = 25.0,
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = now + 2 * DAY_MS
            ),
            ManualRecurringExpense(
                id = 3,
                merchant = "Netflix",
                amount = 15.0,
                frequency = RecurrenceFrequency.WEEKLY,
                nextDate = now + 3 * DAY_MS
            )
        )

        val merged = assembler.mergeRecurringPatterns(manual, emptyList())

        assertEquals(3, merged.size)
        assertTrue(merged.any { it.id == 1L && it.averageAmount == 15.0 && it.frequency == RecurrenceFrequency.MONTHLY })
        assertTrue(merged.any { it.id == 2L && it.averageAmount == 25.0 && it.frequency == RecurrenceFrequency.MONTHLY })
        assertTrue(merged.any { it.id == 3L && it.averageAmount == 15.0 && it.frequency == RecurrenceFrequency.WEEKLY })
    }

    @Test
    fun `merge recurring excludes detected only on manual stale duplicate signature collision`() {
        val now = ms(2026, Calendar.JANUARY, 15, 12)
        val manual = listOf(
            ManualRecurringExpense(
                id = 10,
                merchant = "Netflix",
                amount = 14.0,
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = now + DAY_MS
            )
        )
        val detected = listOf(
            recurring("NETFLIX!!", 14.0, now + 2 * DAY_MS, 0.92f),
            recurring("Spotify", 9.0, now + DAY_MS, 0.91f)
        )

        val merged = assembler.mergeRecurringPatterns(manual, detected)
        val byMerchant = merged.associateBy { it.merchantName }

        assertEquals(2, merged.size)
        assertEquals(14.0, byMerchant.getValue("Netflix").averageAmount, 0.0001)
        assertFalse(byMerchant.containsKey("NETFLIX!!"))
        assertTrue(byMerchant.containsKey("Spotify"))
    }

    @Test
    fun `merge recurring keeps same merchant detected rules when amount or frequency differs`() {
        val now = ms(2026, Calendar.JANUARY, 15, 12)
        val manual = listOf(
            ManualRecurringExpense(
                id = 10,
                merchant = "Netflix",
                amount = 14.0,
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = now + DAY_MS
            )
        )
        val detected = listOf(
            recurring("NETFLIX!!", 70.0, now + 2 * DAY_MS, 0.92f),
            recurring(
                merchant = "Netflix",
                amount = 14.0,
                nextDate = now + 3 * DAY_MS,
                confidence = 0.93f,
                frequency = RecurrenceFrequency.WEEKLY
            )
        )

        val merged = assembler.mergeRecurringPatterns(manual, detected)

        assertEquals(3, merged.size)
        assertTrue(merged.any { it.id == 10L && it.averageAmount == 14.0 && it.frequency == RecurrenceFrequency.MONTHLY })
        assertTrue(merged.any { it.id == null && it.averageAmount == 70.0 && it.frequency == RecurrenceFrequency.MONTHLY })
        assertTrue(merged.any { it.id == null && it.averageAmount == 14.0 && it.frequency == RecurrenceFrequency.WEEKLY })
    }

    @Test
    fun `merge recurring excludes detected below confidence threshold`() {
        val now = ms(2026, Calendar.JANUARY, 15, 12)
        val detected = listOf(
            recurring("High", 10.0, now + DAY_MS, 0.70f),
            recurring("Low", 20.0, now + DAY_MS, 0.69f)
        )

        val merged = assembler.mergeRecurringPatterns(emptyList(), detected)
        val merchants = merged.map { it.merchantName }

        assertEquals(listOf("High"), merchants)
    }

    @Test
    fun `merge recurring keeps manual item due today visible`() {
        val now = ms(2026, Calendar.APRIL, 15, 12)
        every { timeProvider.now() } returns now
        val dueToday = ms(2026, Calendar.APRIL, 15, 0)

        val merged = assembler.mergeRecurringPatterns(
            manualEntities = listOf(
                ManualRecurringExpense(
                    id = 1,
                    merchant = "Rent",
                    amount = 800.0,
                    frequency = RecurrenceFrequency.MONTHLY,
                    nextDate = dueToday
                )
            ),
            detectedPatterns = emptyList()
        )

        assertEquals(1, merged.size)
        assertEquals(dueToday, merged.single().nextExpectedDate)
    }

    @Test
    fun `merge recurring rolls manual item forward only when before today`() {
        val now = ms(2026, Calendar.APRIL, 15, 12)
        every { timeProvider.now() } returns now
        val yesterday = ms(2026, Calendar.APRIL, 14, 23)

        val merged = assembler.mergeRecurringPatterns(
            manualEntities = listOf(
                ManualRecurringExpense(
                    id = 1,
                    merchant = "Rent",
                    amount = 800.0,
                    frequency = RecurrenceFrequency.MONTHLY,
                    nextDate = yesterday
                )
            ),
            detectedPatterns = emptyList()
        )

        assertEquals(1, merged.size)
        assertEquals(ms(2026, Calendar.MAY, 14, 23), merged.single().nextExpectedDate)
    }

    @Test
    fun `buildPastSumDaily computes cumulative month-to-date owned purchases`() {
        val now = ms(2026, Calendar.JANUARY, 3, 12)
        val monthStart = ms(2026, Calendar.JANUARY, 1, 0)
        every { timeProvider.now() } returns now

        val expenses = listOf(
            snapshot(amount = 10.0, date = monthStart, type = DomainTransactionType.PURCHASE),
            snapshot(amount = 5.0, date = monthStart + DAY_MS, type = DomainTransactionType.DEPOSIT),
            snapshot(amount = 20.0, date = monthStart + DAY_MS, type = DomainTransactionType.PURCHASE),
            snapshot(amount = 100.0, date = monthStart + DAY_MS, type = DomainTransactionType.PURCHASE, isNotMine = true),
            snapshot(amount = 999.0, date = monthStart - DAY_MS, type = DomainTransactionType.PURCHASE),
            snapshot(amount = 7.0, date = now, type = DomainTransactionType.PURCHASE),
            snapshot(amount = 111.0, date = now + DAY_MS, type = DomainTransactionType.PURCHASE)
        )

        val result = assembler.buildPastSumDaily(expenses)

        assertEquals(listOf(10.0, 30.0, 37.0), result)
    }

    @Test
    fun `buildSpendingPace assembles expected pace values`() {
        val now = ms(2026, Calendar.JANUARY, 3, 12)
        val janStart = ms(2026, Calendar.JANUARY, 1, 0)
        val decStart = ms(2025, Calendar.DECEMBER, 1, 0)
        every { timeProvider.now() } returns now

        val expenses = listOf(
            snapshot(amount = 10.0, date = janStart, type = DomainTransactionType.PURCHASE),
            snapshot(amount = 20.0, date = janStart + DAY_MS, type = DomainTransactionType.PURCHASE),
            snapshot(amount = 30.0, date = janStart + 2 * DAY_MS, type = DomainTransactionType.PURCHASE),
            snapshot(amount = 310.0, date = decStart + DAY_MS, type = DomainTransactionType.PURCHASE)
        )

        val pace = assembler.buildSpendingPace(expenses)

        assertEquals(60.0, pace.currentMonthSpent, 0.0001)
        assertEquals(3, pace.daysElapsed)
        assertEquals(31, pace.daysInMonth)
        assertEquals(372.0, pace.projectedTotal, 0.0001)
        assertEquals(310.0, pace.previousMonthTotal ?: 0.0, 0.0001)
        assertEquals(310.0, pace.averageMonthlyTotal ?: 0.0, 0.0001)
        assertEquals(200f, pace.pacePercentage)
        assertEquals(PaceStatus.OVER_PACE, pace.paceStatus)
    }

    private fun recurring(
        merchant: String,
        amount: Double,
        nextDate: Long,
        confidence: Float,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY
    ): RecurringPattern = RecurringPattern(
        merchantName = merchant,
        averageAmount = amount,
        currency = "EUR",
        frequency = frequency,
        periodVarianceDays = 0,
        amountVariancePercent = 0.0,
        nextExpectedDate = nextDate,
        confidence = confidence,
        previousDates = emptyList()
    )

    private fun snapshot(
        amount: Double,
        date: Long,
        type: DomainTransactionType,
        isNotMine: Boolean = false
    ): ExpenseSnapshot = ExpenseSnapshot(
        id = 0L,
        amount = amount,
        effectiveAmount = amount,
        currency = "EUR",
        merchant = "Merchant",
        merchantKey = null,
        transactionType = type,
        date = date,
        categoryId = null,
        isNotMine = isNotMine,
        transferDirection = null,
        notes = null
    )

    private fun ms(year: Int, month: Int, day: Int, hour: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}