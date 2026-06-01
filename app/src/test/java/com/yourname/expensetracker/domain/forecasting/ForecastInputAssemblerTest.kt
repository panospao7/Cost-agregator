package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class ForecastInputAssemblerTest {

    private lateinit var timeProvider: TimeProvider
    private lateinit var analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var recurringLifecycleCoordinator: RecurringLifecycleCoordinator
    private lateinit var recurringOccurrenceDao: RecurringOccurrenceDao
    private lateinit var databaseReadBarrier: DatabaseReadBarrier
    private lateinit var assembler: ForecastInputAssembler

    @Before
    fun setup() {
        timeProvider = mockk()
        analyticsCurrencyNormalizer = mockk()
        currencySettingsRepository = mockk()
        currencyConverter = mockk(relaxed = true)
        recurringLifecycleCoordinator = mockk(relaxed = true)
        recurringOccurrenceDao = mockk(relaxed = true)
        databaseReadBarrier = mockk(relaxed = true)
        assembler = ForecastInputAssembler(
            timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = currencySettingsRepository,
            currencyConverter = currencyConverter,
            recurringLifecycleCoordinator = recurringLifecycleCoordinator,
            recurringOccurrenceDao = recurringOccurrenceDao,
            databaseReadBarrier = databaseReadBarrier
        )
        every { timeProvider.now() } returns ms(2026, Calendar.JANUARY, 15, 12)
    }

    // ── P6-CURRENT-025: fail-closed home currency ──────────────────────────

    @Test
    fun `assemble home currency failure does not default to eur`() = runTest {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Failed("datastore read error")

        val thrown = runCatching {
            assembler.assemble(
                expenses = emptyList(),
                manualRecurringEntities = emptyList(),
                detectedRecurringPatterns = emptyList(),
                plannedExpenses = emptyList(),
                savingsGoals = emptyList(),
                budgetStatuses = emptyList()
            )
        }.exceptionOrNull()

        // Fail-closed: must throw rather than silently assume EUR.
        assertTrue("Expected IllegalStateException, got $thrown", thrown is IllegalStateException)
        // No EUR assumption: normalization (which would carry the assumed currency)
        // must never run when home currency is unavailable.
        coVerify(exactly = 0) { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) }
    }

    // ── P6-CURRENT-024: read paths must not write ──────────────────────────

    @Test
    fun `assemble uses projectOccurrences and never generateOccurrences on read path`() = runTest {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } returns
            AnalyticsNormalizationResult(
                homeCurrency = "EUR",
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(any(), any(), any()) } returns emptyList()
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns emptyList()

        val rule = ManualRecurringExpense(
            id = 1,
            merchant = "Netflix",
            amount = 15.0,
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = ms(2026, Calendar.JANUARY, 20, 0)
        )

        assembler.assemble(
            expenses = emptyList(),
            manualRecurringEntities = listOf(rule),
            detectedRecurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList()
        )

        coVerify(exactly = 1) { recurringLifecycleCoordinator.projectOccurrences(1L, any(), any()) }
        coVerify(exactly = 0) {
            recurringLifecycleCoordinator.generateOccurrences(any(), any(), any(), any())
        }
        // Reading already-materialized occurrences is guarded by the read barrier.
        verify(atLeast = 1) { databaseReadBarrier.checkReadAllowed(any<String>()) }
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

    // ── DBG-03: paused-rule materialized occurrence must not leak into forecast ──

    @Test
    fun `DBG-03 assemble excludes paused rule materialized occurrence`() = runTest {
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } returns
            AnalyticsNormalizationResult(
                homeCurrency = "EUR",
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(any(), any(), any()) } returns emptyList()

        val due = ms(2026, Calendar.JANUARY, 20, 0)
        // DAO holds previously-materialized PLANNED rows for BOTH an active rule (id=1)
        // and a PAUSED rule (id=2). Only the active rule's occurrence must survive.
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns listOf(
            occurrence(ruleId = 1L, dueDate = due, amount = 800.0, merchant = "Rent"),
            occurrence(ruleId = 2L, dueDate = due, amount = 9.99, merchant = "Paused Gym")
        )

        val activeRule = ManualRecurringExpense(
            id = 1L, merchant = "Rent", amount = 800.0,
            frequency = RecurrenceFrequency.MONTHLY, nextDate = due, isActive = true
        )
        val pausedRule = ManualRecurringExpense(
            id = 2L, merchant = "Paused Gym", amount = 9.99,
            frequency = RecurrenceFrequency.MONTHLY, nextDate = due, isActive = false
        )

        val result = assembler.assemble(
            expenses = emptyList(),
            manualRecurringEntities = listOf(activeRule, pausedRule),
            detectedRecurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            homeCurrency = "EUR"
        )

        val merchants = result.confirmedOccurrences.map { it.merchant }
        assertTrue(merchants.contains("Rent"))
        assertFalse(merchants.contains("Paused Gym"))
    }

    // ── P6-P1-08: planned expenses normalized through MoneyNormalizationEngine ──

    @Test
    fun `P6-P1-08 planned expenses in home currency pass through unchanged`() = runTest {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } returns
            AnalyticsNormalizationResult(
                homeCurrency = "EUR",
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(any(), any(), any()) } returns emptyList()
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns emptyList()

        val planned = listOf(
            PlannedExpense(
                id = 1L, description = "Groceries", amount = 200.0,
                currency = "EUR", date = ms(2026, Calendar.JANUARY, 20, 0),
                categoryId = null, isRecurring = false,
                priority = PlannedExpensePriority.MUST
            ),
            PlannedExpense(
                id = 2L, description = "Dinner", amount = 50.0,
                currency = "EUR", date = ms(2026, Calendar.JANUARY, 22, 0),
                categoryId = null, isRecurring = false,
                priority = PlannedExpensePriority.LIKELY
            )
        )

        val result = assembler.assemble(
            expenses = emptyList(),
            manualRecurringEntities = emptyList(),
            detectedRecurringPatterns = emptyList(),
            plannedExpenses = planned,
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            homeCurrency = "EUR"
        )

        assertThat(result.plannedExpenses).hasSize(2)
        assertThat(result.plannedExpenses[0].amount).isEqualTo(200.0)
        assertThat(result.plannedExpenses[0].currency).isEqualTo("EUR")
        assertThat(result.plannedExpenses[1].amount).isEqualTo(50.0)
        assertThat(result.plannedExpenses[1].currency).isEqualTo("EUR")
        // No conversion failures for home-currency expenses
        assertThat(result.dataQuality.excludedPlannedCount).isEqualTo(0)
    }

    @Test
    fun `P6-P1-08 foreign currency planned expenses converted through engine`() = runTest {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } returns
            AnalyticsNormalizationResult(
                homeCurrency = "EUR",
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(any(), any(), any()) } returns emptyList()
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns emptyList()

        // The default MoneyNormalizationEngine uses currencyConverter internally.
        // aggregateBuckets with BucketDatePolicy.Latest calls convertOutcome with LATEST_AVAILABLE.
        coEvery {
            currencyConverter.convertOutcome(
                amount = 100.0,
                fromCurrency = "USD",
                toCurrency = "EUR",
                rateBasis = RateBasis.LATEST_AVAILABLE,
                atMillis = null,
                stalePolicy = any()
            )
        } returns com.yourname.expensetracker.domain.core.money.ConversionOutcome.Converted(
            originalAmount = 100.0,
            originalCurrency = CurrencyCode("USD"),
            convertedAmount = 85.0,
            targetCurrency = CurrencyCode("EUR"),
            rateUsed = 0.85,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            rateValidDate = null,
            rateLastUpdated = null,
            rateSource = "test",
            conversionPath = com.yourname.expensetracker.domain.core.money.ConversionPath.DIRECT
        )

        val planned = listOf(
            PlannedExpense(
                id = 1L, description = "Amazon", amount = 100.0,
                currency = "USD", date = ms(2026, Calendar.JANUARY, 25, 0),
                categoryId = null, isRecurring = false,
                priority = PlannedExpensePriority.MUST
            )
        )

        val result = assembler.assemble(
            expenses = emptyList(),
            manualRecurringEntities = emptyList(),
            detectedRecurringPatterns = emptyList(),
            plannedExpenses = planned,
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            homeCurrency = "EUR"
        )

        assertThat(result.plannedExpenses).hasSize(1)
        // 100 USD → 85 EUR at 0.85 rate
        assertThat(result.plannedExpenses[0].amount).isEqualTo(85.0)
        assertThat(result.plannedExpenses[0].currency).isEqualTo("EUR")
        assertThat(result.dataQuality.excludedPlannedCount).isEqualTo(0)
    }

    @Test
    fun `P6-P1-08 conversion failure excludes all expenses in that currency`() = runTest {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } returns
            AnalyticsNormalizationResult(
                homeCurrency = "EUR",
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(any(), any(), any()) } returns emptyList()
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns emptyList()

        // Conversion fails for USD → EUR
        coEvery {
            currencyConverter.convertOutcome(
                amount = 100.0,
                fromCurrency = "USD",
                toCurrency = "EUR",
                rateBasis = RateBasis.LATEST_AVAILABLE,
                atMillis = null,
                stalePolicy = any()
            )
        } returns com.yourname.expensetracker.domain.core.money.ConversionOutcome.Failed(
            originalAmount = 100.0,
            originalCurrency = "USD",
            targetCurrency = "EUR",
            rateBasis = RateBasis.LATEST_AVAILABLE,
            failureType = com.yourname.expensetracker.domain.core.money.ConversionFailureType.MISSING_RATE,
            message = "Rate unavailable"
        )

        val planned = listOf(
            PlannedExpense(
                id = 1L, description = "Amazon", amount = 100.0,
                currency = "USD", date = ms(2026, Calendar.JANUARY, 25, 0),
                categoryId = null, isRecurring = false,
                priority = PlannedExpensePriority.MUST
            )
        )

        val result = assembler.assemble(
            expenses = emptyList(),
            manualRecurringEntities = emptyList(),
            detectedRecurringPatterns = emptyList(),
            plannedExpenses = planned,
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            homeCurrency = "EUR"
        )

        // Expense excluded due to conversion failure
        assertThat(result.plannedExpenses).isEmpty()
        assertThat(result.dataQuality.excludedPlannedCount).isEqualTo(1)
        assertTrue(result.dataQuality.conversionWarnings.any { it.contains("USD") })
    }

    @Test
    fun `P6-P1-08 multiple foreign currencies each converted through engine`() = runTest {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } returns
            AnalyticsNormalizationResult(
                homeCurrency = "EUR",
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(any(), any(), any()) } returns emptyList()
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns emptyList()

        // USD → EUR at 0.85
        coEvery {
            currencyConverter.convertOutcome(
                amount = 100.0,
                fromCurrency = "USD",
                toCurrency = "EUR",
                rateBasis = RateBasis.LATEST_AVAILABLE,
                atMillis = null,
                stalePolicy = any()
            )
        } returns com.yourname.expensetracker.domain.core.money.ConversionOutcome.Converted(
            originalAmount = 100.0,
            originalCurrency = CurrencyCode("USD"),
            convertedAmount = 85.0,
            targetCurrency = CurrencyCode("EUR"),
            rateUsed = 0.85,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            rateValidDate = null,
            rateLastUpdated = null,
            rateSource = "test",
            conversionPath = com.yourname.expensetracker.domain.core.money.ConversionPath.DIRECT
        )

        // GBP → EUR at 1.16
        coEvery {
            currencyConverter.convertOutcome(
                amount = 50.0,
                fromCurrency = "GBP",
                toCurrency = "EUR",
                rateBasis = RateBasis.LATEST_AVAILABLE,
                atMillis = null,
                stalePolicy = any()
            )
        } returns com.yourname.expensetracker.domain.core.money.ConversionOutcome.Converted(
            originalAmount = 50.0,
            originalCurrency = CurrencyCode("GBP"),
            convertedAmount = 58.0,
            targetCurrency = CurrencyCode("EUR"),
            rateUsed = 1.16,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            rateValidDate = null,
            rateLastUpdated = null,
            rateSource = "test",
            conversionPath = com.yourname.expensetracker.domain.core.money.ConversionPath.DIRECT
        )

        val planned = listOf(
            PlannedExpense(
                id = 1L, description = "Amazon", amount = 100.0,
                currency = "USD", date = ms(2026, Calendar.JANUARY, 25, 0),
                categoryId = null, isRecurring = false,
                priority = PlannedExpensePriority.MUST
            ),
            PlannedExpense(
                id = 2L, description = "Hotel", amount = 50.0,
                currency = "GBP", date = ms(2026, Calendar.JANUARY, 26, 0),
                categoryId = null, isRecurring = false,
                priority = PlannedExpensePriority.MUST
            )
        )

        val result = assembler.assemble(
            expenses = emptyList(),
            manualRecurringEntities = emptyList(),
            detectedRecurringPatterns = emptyList(),
            plannedExpenses = planned,
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            homeCurrency = "EUR"
        )

        assertThat(result.plannedExpenses).hasSize(2)
        // 100 USD → 85 EUR
        assertThat(result.plannedExpenses[0].amount).isEqualTo(85.0)
        assertThat(result.plannedExpenses[0].currency).isEqualTo("EUR")
        // 50 GBP → 58 EUR
        assertThat(result.plannedExpenses[1].amount).isEqualTo(58.0)
        assertThat(result.plannedExpenses[1].currency).isEqualTo("EUR")
        assertThat(result.dataQuality.excludedPlannedCount).isEqualTo(0)
    }

    // ── DBG-06: barrier-blocked materialized read marks the forecast partial ──

    @Test
    fun `DBG-06 assemble marks partial when materialized read barrier-blocked`() = runTest {
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } returns
            AnalyticsNormalizationResult(
                homeCurrency = "EUR",
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(any(), any(), any()) } returns emptyList()
        // Restore barrier blocks the materialized read. Projections bypass the barrier and
        // would otherwise surface PLANNED bills WITHOUT SKIPPED/CANCELLED overrides.
        every { databaseReadBarrier.checkReadAllowed(any<String>()) } throws DatabaseAccessBlockedException(
            accessType = DatabaseAccessType.READ,
            operation = DatabaseAccessOperation("ForecastInputAssembler.assemble.readOccurrences"),
            mode = RestoreMaintenanceMode.Mode.RESTORE_STAGING
        )

        val rule = ManualRecurringExpense(
            id = 1L, merchant = "Rent", amount = 800.0,
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = ms(2026, Calendar.JANUARY, 20, 0)
        )

        val result = assembler.assemble(
            expenses = emptyList(),
            manualRecurringEntities = listOf(rule),
            detectedRecurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            homeCurrency = "EUR"
        )

        // Degraded: recurring section flagged unreliable rather than silently dropping overrides.
        assertTrue(result.dataQuality.isPartial)
        assertTrue(
            result.dataQuality.conversionWarnings.any { it.contains("RECURRING_OCCURRENCES_UNAVAILABLE") }
        )
    }

    private fun occurrence(
        ruleId: Long,
        dueDate: Long,
        amount: Double,
        merchant: String,
        status: String = "PLANNED",
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY
    ): RecurringOccurrence = RecurringOccurrence(
        sourceType = RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE,
        sourceId = ruleId,
        occurrenceKey = "${RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE}|$ruleId|$dueDate|${frequency.name}",
        dueDate = dueDate,
        status = status,
        expectedAmount = amount,
        expectedCurrency = "EUR",
        frequency = frequency.name,
        merchant = merchant,
        categoryId = null
    )

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