package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.model.ConfirmedOccurrence
import com.yourname.expensetracker.domain.model.FinancialForecast
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Calendar

/**
 * FC-HIGH-CONF-UNDERCOUNT: when confirmed (rule) occurrences exist, a detected-only pattern
 * (id == null) with confidence >= 0.90 is committed too. The spec rule: committed takes confidence
 * [0.90, 1.0]; likely takes [0.70, 0.90); a detected pattern is never counted in both.
 *
 * Real [SynthesisEngine]; only the clock and the currency converter are mocked. Synthetic data only.
 * Fixed now = 2026-03-15 12:00 (31 day month), so a pattern due on day 20 is inside the window.
 */
class SynthesisEngineDetectedCommittedTest {

    private lateinit var timeProvider: TimeProvider
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var engine: SynthesisEngine

    private val now: Long by lazy { dayMs(15) }

    @Before
    fun setUp() {
        timeProvider = mockk()
        every { timeProvider.now() } returns now
        currencyConverter = mockk()
        engine = SynthesisEngine(timeProvider, currencyConverter = currencyConverter)
    }

    // ── fixtures ───────────────────────────────────────────────────────

    private fun dayMs(day: Int, month: Int = Calendar.MARCH): Long =
        Calendar.getInstance().apply {
            set(2026, month, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun pace() = SpendingPace(
        currentMonthSpent = 0.0,
        daysElapsed = 15,
        daysInMonth = 31,
        projectedTotal = 0.0,
        previousMonthTotal = 1200.0,
        averageMonthlyTotal = 1200.0,
        pacePercentage = 100.0f,
        paceStatus = PaceStatus.ON_PACE,
        displayCurrency = "EUR",
    )

    private fun budget() = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = 2000.0,
        categoryName = null,
        spentAmount = 0.0,
        remainingAmount = 2000.0,
        percentUsed = 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = 0L,
        periodEnd = 0L,
        currency = "EUR",
        currencyAssumption = "USER_CONFIRMED",
        effectiveSpend = 0.0,
        effectiveHealthStatus = BudgetHealthStatus.ON_TRACK
    )

    /** The confirmed rule: one materialised occurrence of 100 EUR due on day 20. */
    private fun confirmedRule(amount: Double = 100.0, currency: String = "EUR") = ConfirmedOccurrence(
        dueDate = dayMs(20),
        expectedAmount = amount,
        expectedCurrency = currency,
        merchant = "Rent",
        categoryId = null
    )

    private fun detected(
        merchant: String = "Netflix",
        amount: Double = 15.0,
        currency: String = "EUR",
        confidence: Float = 0.95f,
        dueDay: Int = 22,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        id: Long? = null,
        dueMs: Long = dayMs(dueDay)
    ) = RecurringPattern(
        merchantName = merchant,
        averageAmount = amount,
        currency = currency,
        frequency = frequency,
        periodVarianceDays = 0,
        amountVariancePercent = 0.0,
        nextExpectedDate = dueMs,
        confidence = confidence,
        previousDates = emptyList(),
        id = id
    )

    private suspend fun forecast(
        patterns: List<RecurringPattern>,
        occurrences: List<ConfirmedOccurrence>
    ): FinancialForecast = engine.synthesize(
        pastSumDaily = emptyList(),
        recurringPatterns = patterns,
        plannedExpenses = emptyList(),
        savingsGoals = emptyList(),
        budgetStatuses = listOf(budget()),
        spendingPace = pace(),
        confirmedOccurrences = occurrences,
        displayCurrency = "EUR"
    )

    private fun stubUsdFailed() {
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } answers {
            ConversionOutcome.Failed(
                originalAmount = firstArg<Double>(),
                originalCurrency = secondArg<String>(),
                targetCurrency = thirdArg<String>(),
                rateBasis = RateBasis.LATEST_AVAILABLE,
                failureType = ConversionFailureType.MISSING_RATE,
                message = "test failure"
            )
        }
    }

    private fun stubUsdParity() {
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } answers {
            ConversionOutcome.Converted(
                originalAmount = firstArg<Double>(),
                originalCurrency = CurrencyCode(secondArg<String>()),
                convertedAmount = firstArg<Double>(),
                targetCurrency = CurrencyCode(thirdArg<String>()),
                rateUsed = 1.0,
                rateBasis = RateBasis.LATEST_AVAILABLE,
                rateValidDate = null,
                rateLastUpdated = now,
                rateSource = "test",
                conversionPath = ConversionPath.DIRECT
            )
        }
    }

    // ── FC-HIGH-CONF-UNDERCOUNT ────────────────────────────────────────

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT control confirmed rule alone is committed 100`() = runTest {
        val result = forecast(patterns = emptyList(), occurrences = listOf(confirmedRule()))

        assertEquals(100.0, result.components.totalCommitted, 0.0001)
        assertEquals(0.0, result.components.totalLikely, 0.0001)
        assertFalse(result.isPartial)
    }

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT confirmed rule 100 plus detected Netflix 15 at 0_95 is committed 115`() = runTest {
        val result = forecast(
            patterns = listOf(detected(confidence = 0.95f)),
            occurrences = listOf(confirmedRule())
        )

        assertEquals(115.0, result.components.totalCommitted, 0.0001)
        assertEquals("a committed pattern must not also be likely", 0.0, result.components.totalLikely, 0.0001)
        assertEquals(0, result.excludedCount)
        assertFalse(result.isPartial)
    }

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT boundary confidence exactly 0_90 counts as committed`() = runTest {
        val result = forecast(
            patterns = listOf(detected(confidence = 0.90f)),
            occurrences = listOf(confirmedRule())
        )

        assertEquals(115.0, result.components.totalCommitted, 0.0001)
        assertEquals(0.0, result.components.totalLikely, 0.0001)
    }

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT confidence 0_89 leaves committed unchanged and moves the pattern to likely`() = runTest {
        val result = forecast(
            patterns = listOf(detected(confidence = 0.89f)),
            occurrences = listOf(confirmedRule())
        )

        assertEquals(100.0, result.components.totalCommitted, 0.0001)
        assertEquals(15.0, result.components.totalLikely, 0.0001)
    }

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT confidence below the likely floor is neither committed nor likely`() = runTest {
        val result = forecast(
            patterns = listOf(detected(confidence = 0.69f)),
            occurrences = listOf(confirmedRule())
        )

        assertEquals(100.0, result.components.totalCommitted, 0.0001)
        assertEquals(0.0, result.components.totalLikely, 0.0001)
    }

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT a pattern backed by a rule id is not double counted with its occurrences`() = runTest {
        // The rule (id = 5) is already represented by its confirmed occurrence of 100.
        val result = forecast(
            patterns = listOf(detected(merchant = "Rent", amount = 100.0, confidence = 0.95f, id = 5L, dueDay = 20)),
            occurrences = listOf(confirmedRule())
        )

        assertEquals(100.0, result.components.totalCommitted, 0.0001)
    }

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT detected pattern due outside the month window is not committed`() = runTest {
        val result = forecast(
            patterns = listOf(detected(confidence = 0.95f, dueMs = dayMs(2, Calendar.APRIL))),
            occurrences = listOf(confirmedRule())
        )

        assertEquals(100.0, result.components.totalCommitted, 0.0001)
    }

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT detected pattern already past today is not committed`() = runTest {
        val result = forecast(
            patterns = listOf(detected(confidence = 0.95f, dueDay = 10)),
            occurrences = listOf(confirmedRule())
        )

        assertEquals(100.0, result.components.totalCommitted, 0.0001)
    }

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT weekly detected pattern counts every remaining occurrence in the month`() = runTest {
        // Weekly from day 16: 16, 23, 30 stay inside March (the next one, 6 April, does not) -> 3 x 15.
        val result = forecast(
            patterns = listOf(detected(frequency = RecurrenceFrequency.WEEKLY, dueDay = 16)),
            occurrences = listOf(confirmedRule())
        )

        assertEquals(145.0, result.components.totalCommitted, 0.0001)
    }

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT two detected patterns add up on top of the confirmed rule`() = runTest {
        val result = forecast(
            patterns = listOf(
                detected(merchant = "Netflix", amount = 15.0, confidence = 0.95f, dueDay = 22),
                detected(merchant = "Gym", amount = 30.0, confidence = 0.92f, dueDay = 20),
                detected(merchant = "Magazine", amount = 8.0, confidence = 0.80f, dueDay = 25)
            ),
            occurrences = listOf(confirmedRule())
        )

        // 100 + 15 + 30 committed; the 0.80 magazine is likely only.
        assertEquals(145.0, result.components.totalCommitted, 0.0001)
        assertEquals(8.0, result.components.totalLikely, 0.0001)
    }

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT control without confirmed occurrences a detected 0_95 pattern is committed`() = runTest {
        val result = forecast(patterns = listOf(detected(confidence = 0.95f)), occurrences = emptyList())

        assertEquals(15.0, result.components.totalCommitted, 0.0001)
        assertEquals(0.0, result.components.totalLikely, 0.0001)
    }

    private fun netflixOccurrence() = ConfirmedOccurrence(
        dueDate = dayMs(22),
        expectedAmount = 15.0,
        expectedCurrency = "EUR",
        merchant = "Netflix",
        categoryId = null
    )

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT detected Netflix 15_49 shadowed by confirmed Netflix rule 15_00 is committed and subtracted once`() = runTest {
        val rule = detected(merchant = "Netflix", amount = 15.0, confidence = 1.0f, id = 5L)
        val dup = detected(merchant = "Netflix", amount = 15.49, confidence = 0.95f)

        val withDuplicate = forecast(patterns = listOf(rule, dup), occurrences = listOf(netflixOccurrence()))
        val ruleOnly = forecast(patterns = listOf(rule), occurrences = listOf(netflixOccurrence()))

        assertEquals(15.0, withDuplicate.components.totalCommitted, 0.0001)
        assertEquals(0.0, withDuplicate.components.totalLikely, 0.0001)
        // Baseline: (1200 - 15.00) / 31 * 16 remaining days; a double subtraction would use 30.49.
        assertEquals((1200.0 - 15.0) / 31.0 * 16.0, withDuplicate.components.predictedDiscretionary, 0.0001)
        assertEquals(ruleOnly.components.predictedDiscretionary, withDuplicate.components.predictedDiscretionary, 0.0001)
    }

    @Test
    fun `shadow without confirmed occurrences rule 15_00 and detected 15_49 same merchant and frequency commit 15_00 once`() = runTest {
        val rule = detected(merchant = "Netflix", amount = 15.0, confidence = 1.0f, id = 5L)
        val dup = detected(merchant = "Netflix", amount = 15.49, confidence = 0.95f)

        val result = forecast(patterns = listOf(rule, dup), occurrences = emptyList())

        // |15.49 - 15.00| = 0.49 <= 20% of 15.00 (3.00) -> shadowed; only the rule's 15.00 is committed.
        assertEquals(15.0, result.components.totalCommitted, 0.0001)
        assertEquals(0.0, result.components.totalLikely, 0.0001)
    }

    @Test
    fun `shadow same merchant and frequency but amount far from the rule is not hidden`() = runTest {
        val rule = detected(merchant = "Apple", amount = 0.99, confidence = 1.0f, id = 5L)
        val other = detected(merchant = "Apple", amount = 10.99, confidence = 0.95f)

        val result = forecast(patterns = listOf(rule, other), occurrences = emptyList())

        // |10.99 - 0.99| = 10.00 > 20% of 0.99 (0.198) -> both counted: 0.99 + 10.99 = 11.98.
        assertEquals(11.98, result.components.totalCommitted, 0.0001)
    }

    @Test
    fun `shadowed detected pattern at 0_80 confidence does not appear in likely`() = runTest {
        val rule = detected(merchant = "Netflix", amount = 15.0, confidence = 1.0f, id = 5L)
        val dup = detected(merchant = "Netflix", amount = 15.49, confidence = 0.80f)

        val result = forecast(patterns = listOf(rule, dup), occurrences = listOf(netflixOccurrence()))

        assertEquals(15.0, result.components.totalCommitted, 0.0001)
        assertEquals(0.0, result.components.totalLikely, 0.0001)
    }

    @Test
    fun `blank merchant names cannot reach the shadow filter because RecurringPattern rejects them`() {
        // RecurringPattern requires a non-blank merchantName, so a blank shadow key is unreachable;
        // the filter's blank-key guard is defensive only.
        assertThrows(IllegalArgumentException::class.java) {
            detected(merchant = "  ", amount = 15.0, confidence = 0.95f)
        }
    }

    @Test
    fun `shadow requires the same currency so a USD detected pattern next to an EUR rule is not hidden`() = runTest {
        stubUsdParity()
        val rule = detected(merchant = "Netflix", amount = 15.0, currency = "EUR", confidence = 1.0f, id = 5L)
        val usd = detected(merchant = "Netflix", amount = 15.49, currency = "USD", confidence = 0.95f)

        val result = forecast(patterns = listOf(rule, usd), occurrences = emptyList())

        // Parity-stubbed conversion: 15.00 EUR + 15.49 USD(=15.49) = 30.49.
        assertEquals(30.49, result.components.totalCommitted, 0.0001)
        assertEquals(0, result.excludedCount)
    }

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT same merchant with a different frequency is still counted`() = runTest {
        val rule = detected(merchant = "Netflix", amount = 15.0, confidence = 1.0f, id = 5L)
        // Weekly from day 16: 16, 23, 30 -> 3 x 15.49 on top of the monthly rule occurrence.
        val weekly = detected(
            merchant = "Netflix", amount = 15.49, confidence = 0.95f,
            frequency = RecurrenceFrequency.WEEKLY, dueDay = 16
        )

        val withWeekly = forecast(patterns = listOf(rule, weekly), occurrences = listOf(netflixOccurrence()))
        val ruleOnly = forecast(patterns = listOf(rule), occurrences = listOf(netflixOccurrence()))

        assertEquals(15.0 + 3 * 15.49, withWeekly.components.totalCommitted, 0.0001)
        assertTrue(
            "the weekly pattern must still reduce the discretionary baseline",
            withWeekly.components.predictedDiscretionary < ruleOnly.components.predictedDiscretionary
        )
    }

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT foreign currency detected pattern without a rate is excluded and marks the result partial`() = runTest {
        stubUsdFailed()

        val result = forecast(
            patterns = listOf(detected(currency = "USD", confidence = 0.95f)),
            occurrences = listOf(confirmedRule())
        )

        // The unconvertible USD 15 must never be added raw, and must not silently vanish either.
        assertEquals(100.0, result.components.totalCommitted, 0.0001)
        assertTrue("an excluded foreign amount must mark the forecast partial", result.isPartial)
        assertTrue("the exclusion must be counted", result.excludedCount >= 1)
    }

    @Test
    fun `FC-HIGH-CONF-UNDERCOUNT foreign currency detected pattern with a rate is converted into committed`() = runTest {
        stubUsdParity()

        val result = forecast(
            patterns = listOf(detected(currency = "USD", confidence = 0.95f)),
            occurrences = listOf(confirmedRule())
        )

        assertEquals(115.0, result.components.totalCommitted, 0.0001)
        assertEquals(0, result.excludedCount)
        assertFalse(result.isPartial)
    }
}
