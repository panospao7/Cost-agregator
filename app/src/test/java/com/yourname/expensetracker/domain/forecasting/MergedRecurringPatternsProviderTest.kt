package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class MergedRecurringPatternsProviderTest {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var recurringExpenseRepository: RecurringExpenseRepository
    private lateinit var recurringExpenseEngine: RecurringExpenseEngine
    private lateinit var forecastInputAssembler: ForecastInputAssembler
    private lateinit var timeProvider: TimeProvider
    private lateinit var provider: MergedRecurringPatternsProvider

    @Before
    fun setup() {
        expenseRepository = mockk(relaxed = true)
        recurringExpenseRepository = mockk(relaxed = true)
        recurringExpenseEngine = mockk(relaxed = true)
        timeProvider = mockk()
        forecastInputAssembler = ForecastInputAssembler(timeProvider, analyticsCurrencyNormalizer = mockk(), currencySettingsRepository = mockk(), currencyConverter = mockk(relaxed = true), recurringLifecycleCoordinator = mockk(), recurringOccurrenceDao = mockk(), databaseReadBarrier = mockk(relaxed = true))
        provider = MergedRecurringPatternsProvider(
            expenseRepository = expenseRepository,
            recurringExpenseRepository = recurringExpenseRepository,
            recurringExpenseEngine = recurringExpenseEngine,
            forecastInputAssembler = forecastInputAssembler,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `getPatternsFromSnapshots dedupes stale manual duplicates and keeps deterministic merchant label`() = runTest {
        val now = ms(2026, Calendar.JANUARY, 15)
        every { timeProvider.now() } returns now
        every { recurringExpenseEngine.detectPatternsFromSnapshots(any(), any()) } returns emptyList()

        val patterns = provider.getPatternsFromSnapshots(
            expenseSnapshots = emptyList(),
            manualRecurring = listOf(
                manual(id = 1, merchant = "NETFLIX", amount = 15.0, nextDate = ms(2025, Calendar.DECEMBER, 1)),
                manual(id = 2, merchant = "Netflix", amount = 15.0, nextDate = ms(2025, Calendar.DECEMBER, 20))
            )
        )

        assertEquals(1, patterns.size)
        assertEquals("Netflix", patterns.single().merchantName)
        assertEquals(2L, patterns.single().id)
        assertEquals(15.0, patterns.single().averageAmount, 0.0001)
    }

    @Test
    fun `getPatternsFromSnapshots keeps same merchant manual rules with different signatures`() = runTest {
        val now = ms(2026, Calendar.JANUARY, 15)
        every { timeProvider.now() } returns now
        every { recurringExpenseEngine.detectPatternsFromSnapshots(any(), any()) } returns emptyList()

        val patterns = provider.getPatternsFromSnapshots(
            expenseSnapshots = emptyList(),
            manualRecurring = listOf(
                manual(id = 1, merchant = "NETFLIX", amount = 15.0, nextDate = ms(2025, Calendar.DECEMBER, 20)),
                manual(id = 2, merchant = "Netflix", amount = 30.0, nextDate = ms(2025, Calendar.DECEMBER, 20)),
                manual(
                    id = 3,
                    merchant = "Netflix!",
                    amount = 15.0,
                    nextDate = ms(2025, Calendar.DECEMBER, 20),
                    frequency = RecurrenceFrequency.WEEKLY
                )
            )
        )

        assertEquals(3, patterns.size)
        assertTrue(patterns.any { it.id == 1L && it.averageAmount == 15.0 && it.frequency == RecurrenceFrequency.MONTHLY })
        assertTrue(patterns.any { it.id == 2L && it.averageAmount == 30.0 && it.frequency == RecurrenceFrequency.MONTHLY })
        assertTrue(patterns.any { it.id == 3L && it.averageAmount == 15.0 && it.frequency == RecurrenceFrequency.WEEKLY })
    }

    @Test
    fun `getPatternsFromSnapshots keeps same merchant detected rules when signatures differ from manual`() = runTest {
        val now = ms(2026, Calendar.JANUARY, 15)
        every { timeProvider.now() } returns now
        every { recurringExpenseEngine.detectPatternsFromSnapshots(any(), any()) } returns listOf(
            recurring(merchant = "NETFLIX!!", amount = 30.0, nextDate = ms(2026, Calendar.JANUARY, 20)),
            recurring(
                merchant = "Netflix",
                amount = 15.0,
                nextDate = ms(2026, Calendar.JANUARY, 22),
                frequency = RecurrenceFrequency.WEEKLY
            )
        )

        val patterns = provider.getPatternsFromSnapshots(
            expenseSnapshots = emptyList(),
            manualRecurring = listOf(
                manual(id = 1, merchant = "Netflix", amount = 15.0, nextDate = ms(2025, Calendar.DECEMBER, 20))
            )
        )

        assertEquals(3, patterns.size)
        assertTrue(patterns.any { it.id == 1L && it.averageAmount == 15.0 && it.frequency == RecurrenceFrequency.MONTHLY })
        assertTrue(patterns.any { it.id == null && it.averageAmount == 30.0 && it.frequency == RecurrenceFrequency.MONTHLY })
        assertTrue(patterns.any { it.id == null && it.averageAmount == 15.0 && it.frequency == RecurrenceFrequency.WEEKLY })
    }

    @Test
    fun `getPatterns rolls forward manual next date before filtering windows`() = runTest {
        val now = ms(2026, Calendar.JANUARY, 15)
        every { timeProvider.now() } returns now
        coEvery { expenseRepository.getExpenseSnapshotsSince(any()) } returns emptyList()
        coEvery { recurringExpenseRepository.getAll() } returns listOf(
            manual(id = 5, merchant = "Coffee Club", amount = 5.0, nextDate = ms(2025, Calendar.DECEMBER, 20))
        )
        every { recurringExpenseEngine.detectPatternsFromSnapshots(any(), any()) } returns emptyList()

        val patterns = provider.getPatterns()

        assertEquals(1, patterns.size)
        assertEquals(5.0, patterns.single().averageAmount, 0.0001)
        assertTrue(patterns.single().nextExpectedDate > now)
        assertTrue(patterns.single().nextExpectedDate <= ms(2026, Calendar.FEBRUARY, 20))
    }

    @Test
    fun `getConfirmedPatterns returns active manual recurring only without detected suggestions`() = runTest {
        val now = ms(2026, Calendar.JANUARY, 15)
        every { timeProvider.now() } returns now
        coEvery { recurringExpenseRepository.getAll() } returns listOf(
            manual(id = 7, merchant = "Rent", amount = 900.0, nextDate = ms(2025, Calendar.DECEMBER, 1))
        )

        val patterns = provider.getConfirmedPatterns()

        assertEquals(1, patterns.size)
        assertEquals("Rent", patterns.single().merchantName)
        assertEquals(1.0f, patterns.single().confidence)
    }

    private fun manual(
        id: Long,
        merchant: String,
        amount: Double,
        nextDate: Long,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY
    ) = ManualRecurringExpense(
        id = id,
        merchant = merchant,
        amount = amount,
        frequency = frequency,
        nextDate = nextDate
    )

    private fun recurring(
        merchant: String,
        amount: Double,
        nextDate: Long,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY
    ) = com.yourname.expensetracker.domain.model.RecurringPattern(
        merchantName = merchant,
        averageAmount = amount,
        currency = "EUR",
        frequency = frequency,
        periodVarianceDays = 0,
        amountVariancePercent = 0.0,
        nextExpectedDate = nextDate,
        confidence = 0.95f,
        previousDates = emptyList()
    )

    private fun ms(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}