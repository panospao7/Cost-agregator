package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarning
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarningType
import com.yourname.expensetracker.domain.analytics.NormalizedExpenseSnapshot
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.toExpenseSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Boundary tests for [HistoricalSpendingDistribution] verifying correct behaviour
 * after the A.5 Batch 2A migration to [TimePeriodUtils].
 *
 * Key properties under test:
 * - Week bucketing via `TimePeriodUtils.getStartOfWeek` (Monday-start, DST-safe)
 * - Distinct-day counting via `TimePeriodUtils.getStartOfDay` (not raw ms division)
 * - Calendar-aware week enumeration via `TimePeriodUtils.addDays` (not fixed ms)
 * - Lookback window uses `TimePeriodUtils.addMonths` (not fixed-day approximation)
 */
class HistoricalSpendingDistributionBoundaryTest {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var currencySettingsRepository: CurrencySettingsRepository

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun toEpochMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    private fun expense(
        id: Long,
        date: Long,
        amount: Double,
        type: TransactionType = TransactionType.PURCHASE,
        isNotMine: Boolean = false
    ): Expense = Expense(
        id = id,
        amount = amount,
        merchant = "TestMerchant",
        transactionType = type,
        date = date,
        isNotMine = isNotMine
    )

    private fun completedLookbackWeekStarts(now: Long): List<Long> {
        val lookbackStart = TimePeriodUtils.getStartOfWeek(TimePeriodUtils.addMonths(now, -18))
        val currentWeekStart = TimePeriodUtils.getStartOfWeek(now)
        val weekStarts = mutableListOf<Long>()
        var cursor = lookbackStart
        while (cursor < currentWeekStart) {
            weekStarts.add(cursor)
            cursor = TimePeriodUtils.addDays(cursor, 7)
        }
        return weekStarts
    }

    @Before
    fun setUp() {
        expenseRepository = mockk(relaxed = true)
        timeProvider = FakeTimeProvider(toEpochMs(2026, 4, 15, 12, 0)) // Wednesday
        analyticsCurrencyNormalizer = mockk(relaxed = true)
        currencySettingsRepository = mockk()
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } answers {
            val expenses = firstArg<List<Expense>>()
            AnalyticsNormalizationResult(
                homeCurrency = "EUR", normalizedExpenses = expenses.map {
                    com.yourname.expensetracker.domain.analytics.NormalizedExpenseSnapshot(it.toExpenseSnapshot(), it.currency, it.effectiveAmount, it.effectiveAmount)
                },
                includedExpenses = expenses.map { it.toExpenseSnapshot() },
                warnings = emptyList(), latestRateTimestamp = null, totalInputCount = expenses.size
            )
        }
    }

    // ========================================================================
    // Week bucketing — Monday-start, locale-independent
    // ========================================================================

    @Test
    fun `excluded history cannot become quiet zero spend weeks`() = runTest {
        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } returns AnalyticsNormalizationResult(
            "EUR", emptyList(), emptyList(), emptyList(), null, totalInputCount = 10
        )
        val distribution = HistoricalSpendingDistribution(expenseRepository, timeProvider, analyticsCurrencyNormalizer, currencySettingsRepository)
        org.junit.Assert.assertNull(distribution.computeDistribution("EUR"))
    }

    @Test
    fun `settings cancellation propagates instead of becoming an unavailable fit`() = runTest {
        every { currencySettingsRepository.homeCurrency() } returns kotlinx.coroutines.flow.flow {
            throw kotlinx.coroutines.CancellationException("TEST_CANCELLED")
        }
        val distribution = HistoricalSpendingDistribution(expenseRepository, timeProvider, analyticsCurrencyNormalizer, currencySettingsRepository)
        val error = runCatching { distribution.computeDistribution() }.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun `explicit EUR basis does not reread or relabel through changed settings`() = runTest {
        every { currencySettingsRepository.homeCurrency() } returns flowOf("USD")
        val distribution = HistoricalSpendingDistribution(expenseRepository, timeProvider, analyticsCurrencyNormalizer, currencySettingsRepository)
        distribution.computeDistribution("EUR")
        io.mockk.coVerify(exactly = 1) { analyticsCurrencyNormalizer.normalizeExpenses(any(), "EUR") }
        io.mockk.verify(exactly = 0) { currencySettingsRepository.homeCurrency() }
    }

    @Test
    fun invalidExplicitCurrencyIsUnavailableBeforeReadingHistory() = runTest {
        val distribution = HistoricalSpendingDistribution(
            expenseRepository,
            timeProvider,
            analyticsCurrencyNormalizer,
            currencySettingsRepository
        )

        val result = distribution.computeDistributionResult("XYZ")

        assertTrue(result is HistoricalDistributionResult.Unavailable)
        assertEquals(
            MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY,
            (result as HistoricalDistributionResult.Unavailable).reasonCode
        )
        coVerify(exactly = 0) { expenseRepository.getExpensesBetween(any(), any()) }
        coVerify(exactly = 0) { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) }
    }

    @Test
    fun settingsFailureIsUnavailableAndNeverFallsBackToEur() = runTest {
        every { currencySettingsRepository.homeCurrency() } returns kotlinx.coroutines.flow.flow {
            throw IllegalStateException("settings unavailable")
        }
        val distribution = HistoricalSpendingDistribution(
            expenseRepository,
            timeProvider,
            analyticsCurrencyNormalizer,
            currencySettingsRepository
        )

        val result = distribution.computeDistributionResult()

        assertTrue(result is HistoricalDistributionResult.Unavailable)
        assertEquals(
            MoneyDisplayUnavailableReasonCode.HOME_CURRENCY_UNAVAILABLE,
            (result as HistoricalDistributionResult.Unavailable).reasonCode
        )
        coVerify(exactly = 0) { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) }
    }

    @Test
    fun `normalization cancellation propagates instead of creating quiet history`() = runTest {
        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } throws
            kotlinx.coroutines.CancellationException("TEST_CANCELLED")
        val distribution = HistoricalSpendingDistribution(expenseRepository, timeProvider, analyticsCurrencyNormalizer, currencySettingsRepository)
        val error = runCatching { distribution.computeDistribution("EUR") }.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun `partial excluded history remains unavailable instead of fitting quiet weeks`() = runTest {
        val now = timeProvider.now()
        val excludedDate = TimePeriodUtils.addDays(
            TimePeriodUtils.getStartOfWeek(now),
            -7
        )
        val excluded = expense(99L, excludedDate, 250.0)
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(excluded)
        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } returns AnalyticsNormalizationResult(
            homeCurrency = "EUR",
            normalizedExpenses = emptyList(),
            includedExpenses = emptyList(),
            warnings = listOf(
                com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarning(
                    type = com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
                    message = "test",
                    affectedTransactionCount = 1
                )
            ),
            latestRateTimestamp = null,
            totalInputCount = 1
        )

        val distribution = HistoricalSpendingDistribution(
            expenseRepository,
            timeProvider,
            analyticsCurrencyNormalizer,
            currencySettingsRepository
        )

        assertNull(distribution.computeDistribution("EUR"))
    }

    @Test
    fun `typed result preserves unavailable quality and normalization counts`() = runTest {
        val excluded = expense(100L, TimePeriodUtils.addDays(timeProvider.now(), -7), 250.0)
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(excluded)
        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } returns AnalyticsNormalizationResult(
            homeCurrency = "EUR",
            normalizedExpenses = emptyList(),
            includedExpenses = emptyList(),
            warnings = listOf(
                com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarning(
                    type = com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
                    message = "test",
                    affectedTransactionCount = 1
                )
            ),
            latestRateTimestamp = null,
            totalInputCount = 1
        )

        val result = HistoricalSpendingDistribution(
            expenseRepository,
            timeProvider,
            analyticsCurrencyNormalizer,
            currencySettingsRepository
        ).computeDistributionResult("EUR")

        assertTrue(result is HistoricalDistributionResult.Unavailable)
        val unavailable = result as HistoricalDistributionResult.Unavailable
        assertEquals(
            com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE,
            unavailable.reasonCode
        )
        assertEquals(1, unavailable.totalInputCount)
        assertEquals(0, unavailable.includedInputCount)
        assertEquals(1, unavailable.excludedInputCount)
        assertEquals(1, unavailable.warnings.single().affectedTransactionCount)
    }

    @Test
    fun `successful fits retain normalized counts rate basis and timestamp`() = runTest {
        val expenses = completedLookbackWeekStarts(timeProvider.now()).flatMapIndexed { weekIndex, start ->
            (0..3).map { day ->
                expense((weekIndex * 4 + day + 1).toLong(), TimePeriodUtils.addDays(start, day),
                    100.0 + (weekIndex % 5) * 5.0)
            }
        }
        val distribution = HistoricalSpendingDistribution(
            expenseRepository, timeProvider, analyticsCurrencyNormalizer, currencySettingsRepository
        )
        for (sourceCurrency in listOf("EUR", "USD", "GBP")) {
            val rawExpenses = expenses.map { it.copy(currency = sourceCurrency) }
            coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns rawExpenses
            coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), "EUR") } answers {
                val rows = firstArg<List<Expense>>()
                val normalizedRows = rows.map {
                    NormalizedExpenseSnapshot(
                        snapshot = it.copy(currency = "EUR").toExpenseSnapshot(),
                        originalCurrency = it.currency,
                        originalEffectiveAmount = it.effectiveAmount,
                        normalizedEffectiveAmount = it.effectiveAmount,
                        rateBasis = if (sourceCurrency == "EUR") "IDENTITY" else "TRANSACTION_DATE",
                        rateValidDate = it.date,
                        rateLastUpdated = 123456L
                    )
                }
                AnalyticsNormalizationResult(
                    homeCurrency = "EUR", normalizedExpenses = normalizedRows,
                    includedExpenses = normalizedRows.map { it.snapshot }, warnings = emptyList(),
                    latestRateTimestamp = 123456L, totalInputCount = rows.size
                )
            }

            val result = distribution.computeDistributionResult("EUR")

            assertTrue(result is HistoricalDistributionResult.Available)
            val fit = (result as HistoricalDistributionResult.Available).fit
            assertTrue(fit.isUsable)
            assertEquals(ConversionQuality.COMPLETE, fit.moneyQuality.quality)
            assertEquals(expenses.size, fit.moneyQuality.totalInputCount)
            assertEquals(expenses.size, fit.moneyQuality.includedInputCount)
            assertEquals(0, fit.moneyQuality.excludedInputCount)
            assertEquals(RateBasis.TRANSACTION_DATE, fit.moneyQuality.requestedRateBasis)
            assertEquals(
                if (sourceCurrency == "EUR") RateBasis.IDENTITY else RateBasis.TRANSACTION_DATE,
                fit.moneyQuality.actualRateBasis
            )
            assertEquals(123456L, fit.moneyQuality.latestRateTimestamp)
            assertTrue(fit.moneyQuality.warnings.isEmpty())
        }
    }

    @Test
    fun `stale rate history remains unavailable with quality counts warnings and timestamp`() = runTest {
        val raw = expense(101L, TimePeriodUtils.addDays(timeProvider.now(), -7), 250.0).copy(currency = "USD")
        val snapshot = raw.copy(currency = "EUR").toExpenseSnapshot()
        val warning = AnalyticsConversionWarning(
            type = AnalyticsConversionWarningType.STALE_EXCHANGE_RATE,
            message = "Stale exchange rate",
            affectedTransactionCount = 1
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(raw)
        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), "EUR") } returns AnalyticsNormalizationResult(
            homeCurrency = "EUR",
            normalizedExpenses = listOf(NormalizedExpenseSnapshot(
                snapshot, "USD", raw.effectiveAmount, snapshot.effectiveAmount,
                rateBasis = "TRANSACTION_DATE", qualityWarning = AnalyticsConversionWarningType.STALE_EXCHANGE_RATE
            )),
            includedExpenses = listOf(snapshot), warnings = listOf(warning),
            latestRateTimestamp = 123456L, totalInputCount = 1
        )

        val result = HistoricalSpendingDistribution(
            expenseRepository, timeProvider, analyticsCurrencyNormalizer, currencySettingsRepository
        ).computeDistributionResult("EUR")

        assertTrue(result is HistoricalDistributionResult.Unavailable)
        val unavailable = result as HistoricalDistributionResult.Unavailable
        assertEquals(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE, unavailable.reasonCode)
        assertEquals(ConversionQuality.ESTIMATED, unavailable.quality)
        assertEquals(1, unavailable.totalInputCount)
        assertEquals(1, unavailable.includedInputCount)
        assertEquals(0, unavailable.excludedInputCount)
        assertEquals(listOf(warning), unavailable.warnings)
        assertEquals(123456L, unavailable.latestRateTimestamp)
        assertEquals(RateBasis.TRANSACTION_DATE, unavailable.requestedRateBasis)
    }

    @Test
    fun `partially converted history retains excluded counts without a fit`() = runTest {
        val included = expense(201L, TimePeriodUtils.addDays(timeProvider.now(), -7), 100.0)
        val excluded = included.copy(id = 202L, currency = "USD")
        val snapshot = included.toExpenseSnapshot()
        val warning = AnalyticsConversionWarning(
            type = AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
            message = "Missing exchange rate", affectedTransactionCount = 1
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(included, excluded)
        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), "EUR") } returns AnalyticsNormalizationResult(
            homeCurrency = "EUR",
            normalizedExpenses = listOf(NormalizedExpenseSnapshot(snapshot, "EUR", 100.0, 100.0)),
            includedExpenses = listOf(snapshot), warnings = listOf(warning),
            latestRateTimestamp = 123456L, totalInputCount = 2
        )

        val result = HistoricalSpendingDistribution(
            expenseRepository, timeProvider, analyticsCurrencyNormalizer, currencySettingsRepository
        ).computeDistributionResult("EUR")

        assertTrue(result is HistoricalDistributionResult.Unavailable)
        val unavailable = result as HistoricalDistributionResult.Unavailable
        assertEquals(ConversionQuality.PARTIAL, unavailable.quality)
        assertEquals(2, unavailable.totalInputCount)
        assertEquals(1, unavailable.includedInputCount)
        assertEquals(1, unavailable.excludedInputCount)
        assertEquals(listOf(warning), unavailable.warnings)
        assertEquals(123456L, unavailable.latestRateTimestamp)
    }

    // Pure TimePeriodUtils boundary cases (week key Monday, Sunday/next-Monday bucket, distinct-day
    // grouping, addDays(7) enumeration, addMonths(-18) lookback) were removed from this class: they
    // exercise no HistoricalSpendingDistribution code and are covered by TimePeriodUtilsTest and
    // TimePeriodUtilsT4CBatch*Test.

    // ========================================================================
    // Integration: distribution computation with boundary-edge data
    // ========================================================================

    @Test
    fun `computeDistribution excludes current partial week`() = runTest {
        val now = toEpochMs(2026, 4, 15, 12, 0)  // Wednesday mid-week
        timeProvider.setTime(now)

        val currentWeekStart = TimePeriodUtils.getStartOfWeek(now)

        // Create expenses: one in current week (should be excluded from weeks),
        // and enough in prior weeks to fit a distribution
        val expenses = mutableListOf<Expense>()
        var id = 1L

        // 8 full weeks of data, 4 transactions/week on different days
        for (weekOffset in 1..8) {
            val weekStart = TimePeriodUtils.addDays(currentWeekStart, -(weekOffset * 7))
            for (dayOffset in 0..3) {
                val date = TimePeriodUtils.addDays(weekStart, dayOffset) + 36_000_000L // +10 hours
                expenses.add(expense(id++, date, 50.0 + dayOffset * 10.0))
            }
        }

        // Add one expense in the current partial week
        val currentWeekExpense = expense(id++, now, 999.0)
        expenses.add(currentWeekExpense)

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns expenses

        val distribution = HistoricalSpendingDistribution(expenseRepository, timeProvider, analyticsCurrencyNormalizer = analyticsCurrencyNormalizer, currencySettingsRepository = currencySettingsRepository)
        val result = distribution.computeDistribution()

        assertNotNull("Distribution should be computed", result)
        // The current-week expense should not appear in any weekly total
        // because the range ends at currentWeekStart (exclusive).
        // With 8 weeks of 4 distinct days each (>= 3 minimum), all should qualify.
        assertTrue("Should have qualifying weeks",
            result!!.qualifyingWeekCount >= 4)
    }

    @Test
    fun `distribution weekly totals use effectiveAmount`() = runTest {
        val now = toEpochMs(2026, 4, 15, 12, 0)
        timeProvider.setTime(now)

        val expenses = mutableListOf<Expense>()
        var id = 1L

        // Populate the complete production lookback so FCST-16's intentional
        // quiet-week observations do not dominate this weekly-total contract test.
        // Add slight variation per week so sigma > 0 (isUsable requires non-zero sigma).
        for ((weekIndex, weekStart) in completedLookbackWeekStarts(now).withIndex()) {
            val amountPerTransaction = 100.0 + (weekIndex % 5) * 5.0
            for (dayOffset in 0..3) {
                val date = TimePeriodUtils.addDays(weekStart, dayOffset) + 36_000_000L
                expenses.add(expense(id++, date, amountPerTransaction))
            }
        }

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns expenses

        val distribution = HistoricalSpendingDistribution(expenseRepository, timeProvider, currencySettingsRepository = currencySettingsRepository, analyticsCurrencyNormalizer = analyticsCurrencyNormalizer)
        val result = distribution.computeDistribution()

        assertNotNull("Distribution should be computed", result)
        assertTrue("Should have at least 4 qualifying weeks", result!!.qualifyingWeekCount >= 4)
        assertTrue("Distribution should be usable", result.isUsable)
        // Each week has 4 expenses, with weekly totals varying slightly
        // All trimmed totals should be positive and in a reasonable range
        for (total in result.trimmedWeeklyTotals) {
            assertTrue("Weekly total should be positive", total > 0.0)
            assertTrue("Weekly total should be in expected range", total in 400.0..600.0)
        }
    }

    // ========================================================================
    // Filtering: only PURCHASE + WITHDRAWAL, not isNotMine
    // ========================================================================

    @Test
    fun `distribution filters out non-spending transaction types and isNotMine`() = runTest {
        val now = toEpochMs(2026, 4, 15, 12, 0)
        timeProvider.setTime(now)

        val expenses = mutableListOf<Expense>()
        var id = 1L

        // Populate every completed lookback week so the assertion isolates
        // transaction filtering rather than FCST-16 quiet-week expansion.
        for (weekStart in completedLookbackWeekStarts(now)) {
            for (dayOffset in 0..3) {
                val date = TimePeriodUtils.addDays(weekStart, dayOffset) + 36_000_000L
                // PURCHASE — should count
                expenses.add(expense(id++, date, 100.0, TransactionType.PURCHASE))
                // DEPOSIT — should NOT count
                expenses.add(expense(id++, date, 500.0, TransactionType.DEPOSIT))
                // Someone else's PURCHASE — should NOT count
                expenses.add(expense(id++, date, 700.0, TransactionType.PURCHASE, isNotMine = true))
            }
        }

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns expenses

        val distribution = HistoricalSpendingDistribution(expenseRepository, timeProvider, analyticsCurrencyNormalizer = analyticsCurrencyNormalizer, currencySettingsRepository = currencySettingsRepository)
        val result = distribution.computeDistribution()

        assertNotNull("Distribution should be computed", result)
        // Each qualifying week should have total = 4 * $100 = $400, not $2400
        for (total in result!!.trimmedWeeklyTotals) {
            assertEquals("Weekly total should only include purchases", 400.0, total, 0.01)
        }
    }
}
