package com.yourname.expensetracker.domain.cashflow

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.MoneyNormalizationEngine
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Tests for [CashFlowCalculator].
 *
 * ## Test gaps (not yet covered):
 * - Weekly rule multiple bills: verify that when multiple recurring bills fall due
 *   in the same week, the calculator correctly accumulates each one rather than
 *   de-duplicating or skipping patterns.
 * - PAID exclusion: test that expenses/bills already marked as paid are excluded
 *   from upcoming-obligation and cash-flow projections.
 * - Sorted by due date: confirm that the output list of daily cash-flow entries
 *   is sorted chronologically by due date, especially when expenses span multiple
 *   days with tie-breaking rules.
 * - Generation failure: test that when [RecurringExpenseEngine.getPatterns] throws,
 *   the calculator degrades gracefully (e.g. falls back to known expenses only).
 */
class CashFlowCalculatorTest : AnalyticsEngineTestBase() {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var recurringExpenseEngine: RecurringExpenseEngine
    private lateinit var recurringExpenseRepository: RecurringExpenseRepository
    private lateinit var recurringPatternsProvider: MergedRecurringPatternsProvider
    private lateinit var recurringLifecycleCoordinator: RecurringLifecycleCoordinator
    private lateinit var recurringOccurrenceDao: RecurringOccurrenceDao
    private lateinit var databaseReadBarrier: com.yourname.expensetracker.data.backup.DatabaseReadBarrier
    private lateinit var calculator: CashFlowCalculator

    @Before
    override fun setUp() {
        super.setUp()
        expenseRepository = mockk(relaxed = true)
        recurringExpenseEngine = mockk(relaxed = true)
        recurringExpenseRepository = mockk(relaxed = true)
        recurringPatternsProvider = mockk<MergedRecurringPatternsProvider>(relaxed = true)
        recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)
        recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true)
        databaseReadBarrier = mockk(relaxed = true)
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true).also {
            every { it.homeCurrency() } returns flowOf("EUR")
            coEvery { it.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        }

        // P6-P1-11: Mock normalization engine to return the sum of effectiveAmounts.
        val normalizationEngine = mockk<MoneyNormalizationEngine>(relaxed = true)
        coEvery { normalizationEngine.aggregateExpenses(any(), any(), any(), any()) } answers {
            val expenses = firstArg<List<Expense>>()
            val homeCurrency = secondArg<CurrencyCode>()
            val total = expenses.sumOf { it.effectiveAmount }
            com.yourname.expensetracker.domain.core.money.MoneyAggregate(
                displayAmount = total,
                displayCurrency = homeCurrency,
                sourceBuckets = emptyList(),
                conversionFailures = emptyList()
            )
        }

        calculator = CashFlowCalculator(
            expenseRepository = expenseRepository,
            recurringPatternsProvider = recurringPatternsProvider,
            timeProvider = timeProvider,
            recurringLifecycleCoordinator = recurringLifecycleCoordinator,
            recurringOccurrenceDao = recurringOccurrenceDao,
            analyticsCurrencyNormalizer = mockk(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            currencyConverter = mockk(relaxed = true),
            databaseReadBarrier = databaseReadBarrier,
            normalizationEngine = normalizationEngine
        )
    }

    @Test
    fun `daily cashflow computes starting income expenses recurring and ending balances`() = runTest {
        val d1 = ms("2026-04-01")
        val d2 = ms("2026-04-02")
        val d3 = ms("2026-04-03")

        val tx = listOf(
            expense(d1, 200.0, TransactionType.DEPOSIT),
            expense(d1, 40.0, TransactionType.PURCHASE),
            expense(d2, 30.0, TransactionType.PURCHASE)
        )

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns tx
        every { expenseRepository.getAllExpenses() } returns flowOf(tx)
        coEvery { recurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            RecurringPattern(
                merchantName = "Netflix",
                averageAmount = 10.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = d2,
                confidence = 1.0f,
                previousDates = emptyList()
            )
        )

        val result = calculator.calculateDailyCashFlow(Date(d1), Date(ms("2026-04-04")), startingBalance = MoneyAmount(100.0, CurrencyCode("EUR")))

        assertEquals(3, result.size)

        // Day 1: 100 + 200 - 40 - 0 = 260
        assertApproxEquals(100.0, result[0].startingBalance)
        assertApproxEquals(260.0, result[0].endingBalance)
        assertEquals(CashFlowRiskLevel.LOW, result[0].riskLevel)

        // Day 2: 260 + 0 - 30 - 10 = 220
        assertApproxEquals(260.0, result[1].startingBalance)
        assertApproxEquals(220.0, result[1].endingBalance)
        assertEquals(1, result[1].predictedRecurring.size)
        assertEquals(CashFlowRiskLevel.LOW, result[1].riskLevel)
    }

    @Test
    fun `no income path handles expense only days and negative balances`() = runTest {
        val d1 = ms("2026-04-01")
        val tx = listOf(expense(d1, 80.0, TransactionType.PURCHASE))
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns tx
        every { expenseRepository.getAllExpenses() } returns flowOf(tx)
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(Date(d1), Date(ms("2026-04-02")), startingBalance = MoneyAmount(50.0, CurrencyCode("EUR")))

        assertEquals(1, result.size)
        assertApproxEquals(-30.0, result.first().endingBalance)
        assertEquals(CashFlowRiskLevel.HIGH, result.first().riskLevel)
    }

    @Test
    fun `no expenses path preserves balance and emits no recurring`() = runTest {
        val d1 = ms("2026-04-01")
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        every { expenseRepository.getAllExpenses() } returns flowOf(emptyList())
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(Date(d1), Date(ms("2026-04-03")), startingBalance = MoneyAmount(600.0, CurrencyCode("EUR")))

        assertEquals(2, result.size)
        assertTrue(result.all { it.endingBalance == 600.0 })
        assertTrue(result.all { it.riskLevel == CashFlowRiskLevel.NONE })
    }

    @Test
    fun `upcoming bills returns patterns within next N days`() = runTest {
        val now = ms("2026-04-01")
        every { timeProvider.now() } returns now

        val all = listOf(expense(now, 1.0, TransactionType.PURCHASE))
        every { expenseRepository.getAllExpenses() } returns flowOf(all)

        val inRange = RecurringPattern(
            merchantName = "Gym",
            averageAmount = 20.0,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = ms("2026-04-05"),
            confidence = 0.9f,
            previousDates = emptyList()
        )
        val outOfRange = inRange.copy(merchantName = "Rent", nextExpectedDate = ms("2026-05-10"))

        coEvery { recurringPatternsProvider.getConfirmedPatterns() } returns listOf(inRange, outOfRange)

        val upcoming = calculator.getUpcomingBills(daysAhead = 10)
        assertEquals(1, upcoming.size)
        assertEquals("Gym", upcoming.first().merchantName)
    }

    /**
     * A.9 regression: CashFlowCalculator must process all rows even when
     * the expense count exceeds the old LIMIT 2000 default.
     *
     * The calculator calls [ExpenseRepository.getExpensesBetween] which,
     * after the Batch 1 repository-level fix, delegates to the uncapped
     * DAO query.  This test feeds 2500 expenses spanning two days and
     * verifies that the cumulative balance includes every row.
     */
    @Test
    fun `A9 regression - cashflow includes all rows beyond old 2000 limit`() = runTest {
        val d1 = ms("2026-04-01")
        val d2 = ms("2026-04-02")

        // 1500 purchases on day 1 (€1 each) + 1000 purchases on day 2 (€1 each) = 2500 total
        val expenses = mutableListOf<Expense>()
        for (i in 1..1500) {
            expenses.add(expense(d1, 1.0, TransactionType.PURCHASE))
        }
        for (i in 1..1000) {
            expenses.add(expense(d2, 1.0, TransactionType.PURCHASE))
        }

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns expenses
        every { expenseRepository.getAllExpenses() } returns flowOf(expenses)
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(
            Date(d1), Date(ms("2026-04-03")), startingBalance = MoneyAmount(3000.0, CurrencyCode("EUR"))
        )

        assertEquals(2, result.size)

        // Day 1: 3000 - 1500 = 1500
        assertApproxEquals(3000.0, result[0].startingBalance)
        assertApproxEquals(1500.0, result[0].endingBalance)

        // Day 2: 1500 - 1000 = 500
        assertApproxEquals(1500.0, result[1].startingBalance)
        assertApproxEquals(500.0, result[1].endingBalance)

        // Total expenses across both days must equal 2500
        val totalExpenseCount = result[0].expenses.size + result[1].expenses.size
        assertEquals(2500, totalExpenseCount)
    }

    // ============================================================================
    // A.10 Batch 5 – movement-aware classification tests
    // ============================================================================

    /**
     * A.10 Batch 5: TRANSFER rows with transferDirection == INCOMING must be
     * treated as inflow.  TRANSFER rows with transferDirection == OUTGOING must
     * be treated as outflow.  UNKNOWN rows and transfers without a direction
     * must NOT affect the cash-flow balance.
     */
    @Test
    fun `A10 Batch5 - incoming transfer is inflow and outgoing transfer is outflow`() = runTest {
        val d1 = ms("2026-04-01")

        val tx = listOf(
            expense(d1, 100.0, TransactionType.DEPOSIT),
            expense(d1, 25.0, TransactionType.PURCHASE),
            expense(d1, 500.0, TransactionType.TRANSFER, transferDir = TransferDirection.INCOMING),  // inflow
            expense(d1, 200.0, TransactionType.TRANSFER, transferDir = TransferDirection.OUTGOING),  // outflow
            expense(d1, 300.0, TransactionType.UNKNOWN)     // should be ignored
        )

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns tx
        every { expenseRepository.getAllExpenses() } returns flowOf(tx)
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(
            Date(d1), Date(ms("2026-04-02")), startingBalance = MoneyAmount(0.0, CurrencyCode("EUR"))
        )

        assertEquals(1, result.size)
        // Balance should be 0 + 100 (deposit) + 500 (incoming transfer)
        //                      - 25 (purchase) - 200 (outgoing transfer) = 375
        // Unknown (300) must NOT be subtracted
        assertApproxEquals(375.0, result[0].endingBalance)
        // income list has the deposit + incoming transfer
        assertEquals(2, result[0].income.size)
        // expense list has purchase + outgoing transfer
        assertEquals(2, result[0].expenses.size)
    }

    /**
     * A.10 Batch 5: TRANSFER rows without a transferDirection (null) must
     * be excluded from both inflow and outflow, just like UNKNOWN.
     */
    @Test
    fun `A10 Batch5 - transfer without direction and unknown rows do not affect cash-flow balance`() = runTest {
        val d1 = ms("2026-04-01")

        val tx = listOf(
            expense(d1, 100.0, TransactionType.DEPOSIT),
            expense(d1, 25.0, TransactionType.PURCHASE),
            expense(d1, 500.0, TransactionType.TRANSFER),  // no direction -> ignored
            expense(d1, 300.0, TransactionType.UNKNOWN)     // should be ignored
        )

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns tx
        every { expenseRepository.getAllExpenses() } returns flowOf(tx)
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(
            Date(d1), Date(ms("2026-04-02")), startingBalance = MoneyAmount(0.0, CurrencyCode("EUR"))
        )

        assertEquals(1, result.size)
        // Balance should be 0 + 100 (deposit) - 25 (purchase) = 75
        // Transfer (500, no direction) and Unknown (300) must NOT be counted
        assertApproxEquals(75.0, result[0].endingBalance)
        // income list has only the deposit
        assertEquals(1, result[0].income.size)
        // expense list has only the purchase
        assertEquals(1, result[0].expenses.size)
    }

    /**
     * A.10 Batch 5: WITHDRAWAL must still count as an outflow for cash-flow
     * balance purposes (it moves money out of the account), even though it
     * is not canonical "spending" for ratio purposes.
     */
    @Test
    fun `A10 Batch5 - withdrawal counted as outflow for balance`() = runTest {
        val d1 = ms("2026-04-01")

        val tx = listOf(
            expense(d1, 200.0, TransactionType.DEPOSIT),
            expense(d1, 50.0, TransactionType.WITHDRAWAL)
        )

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns tx
        every { expenseRepository.getAllExpenses() } returns flowOf(tx)
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(
            Date(d1), Date(ms("2026-04-02")), startingBalance = MoneyAmount(100.0, CurrencyCode("EUR"))
        )

        assertEquals(1, result.size)
        // 100 + 200 - 50 = 250
        assertApproxEquals(250.0, result[0].endingBalance)
    }

    /**
     * A.10 Batch 5: classification must be purely type-based, never sign-based.
     * Previously a negative amount would cause the row to be mis-classified as
     * income.  After the fix, a PURCHASE with a negative amount stays on the
     * expense side.
     */
    @Test
    fun `A10 Batch5 - negative amount purchase stays on expense side`() = runTest {
        val d1 = ms("2026-04-01")

        // A refund recorded as a negative-amount PURCHASE
        val tx = listOf(expense(d1, -10.0, TransactionType.PURCHASE))

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns tx
        every { expenseRepository.getAllExpenses() } returns flowOf(tx)
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(
            Date(d1), Date(ms("2026-04-02")), startingBalance = MoneyAmount(100.0, CurrencyCode("EUR"))
        )

        assertEquals(1, result.size)
        // income list must be empty (no sign-based leakage)
        assertEquals(0, result[0].income.size)
        // expense list has the purchase
        assertEquals(1, result[0].expenses.size)
        // Balance: 100 + 0 - (-10) = 110
        assertApproxEquals(110.0, result[0].endingBalance)
    }

    // ============================================================================
    // P6-CURRENT-024 – read paths must not write (projection, not generation)
    // ============================================================================

    @Test
    fun `cashflow uses projectOccurrences and never generateOccurrences on read path`() = runTest {
        val d1 = ms("2026-04-01")
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { recurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            manualPattern(id = 1L, merchant = "Rent", amount = 800.0, nextDate = d1)
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(1L, any(), any()) } returns listOf(
            occurrence(ruleId = 1L, dueDate = d1, amount = 800.0, merchant = "Rent")
        )
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns emptyList()

        calculator.calculateDailyCashFlow(Date(d1), Date(ms("2026-04-02")), startingBalance = MoneyAmount(0.0, CurrencyCode("EUR")))

        coVerify(exactly = 1) { recurringLifecycleCoordinator.projectOccurrences(1L, any(), any()) }
        coVerify(exactly = 0) {
            recurringLifecycleCoordinator.generateOccurrences(any(), any(), any(), any())
        }
        verify(atLeast = 1) { databaseReadBarrier.checkReadAllowed(any<String>()) }
    }

    @Test
    fun `cashflow already-materialized occurrence path is read unchanged via read barrier`() = runTest {
        val d1 = ms("2026-04-01")
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { recurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            manualPattern(id = 1L, merchant = "Rent", amount = 800.0, nextDate = d1)
        )
        // Projection yields nothing; the already-materialized row must still surface.
        coEvery { recurringLifecycleCoordinator.projectOccurrences(1L, any(), any()) } returns emptyList()
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns listOf(
            occurrence(ruleId = 1L, dueDate = d1, amount = 800.0, merchant = "Rent")
        )

        val result = calculator.calculateDailyCashFlow(Date(d1), Date(ms("2026-04-02")), startingBalance = MoneyAmount(0.0, CurrencyCode("EUR")))

        assertEquals(1, result[0].predictedRecurring.size)
        assertEquals("Rent", result[0].predictedRecurring.first().merchantName)
        verify(atLeast = 1) { databaseReadBarrier.checkReadAllowed(any<String>()) }
    }

    // ============================================================================
    // P6-CURRENT-018 – occurrence-generation failure fallback + partial flag
    // ============================================================================

    @Test
    fun `cashflow marks partial when occurrence projection fails`() = runTest {
        val d1 = ms("2026-04-01")
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { recurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            manualPattern(id = 1L, merchant = "Rent", amount = 800.0, nextDate = d1)
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(1L, any(), any()) } throws
            RuntimeException("projection boom")
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(Date(d1), Date(ms("2026-04-02")), startingBalance = MoneyAmount(0.0, CurrencyCode("EUR")))

        assertEquals(1, result.size)
        // Failure flagged, not silently dropped.
        assertTrue(result[0].occurrenceGenerationFailed)
        assertEquals(1, result[0].failedOccurrenceRuleCount)
        assertTrue(result[0].isPartial)
        // Bill recovered via ad-hoc fallback (NOT dropped to zero UI signal).
        assertEquals(1, result[0].predictedRecurring.size)
        assertEquals("Rent", result[0].predictedRecurring.first().merchantName)
    }

    @Test
    fun `cashflow not partial when projection succeeds`() = runTest {
        val d1 = ms("2026-04-01")
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { recurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            manualPattern(id = 1L, merchant = "Rent", amount = 800.0, nextDate = d1)
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(1L, any(), any()) } returns listOf(
            occurrence(ruleId = 1L, dueDate = d1, amount = 800.0, merchant = "Rent")
        )
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(Date(d1), Date(ms("2026-04-02")), startingBalance = MoneyAmount(0.0, CurrencyCode("EUR")))

        assertFalse(result[0].occurrenceGenerationFailed)
        assertEquals(0, result[0].failedOccurrenceRuleCount)
    }

    // ============================================================================
    // P6-CURRENT-019 – content-aware dedup (merchantKey + amount + currency + day)
    // ============================================================================

    @Test
    fun `cashflow dedupes recurring by merchantkey amount date not name`() = runTest {
        val d1 = ms("2026-04-01")
        // Actual expense "Netflix"; predicted detected pattern "Netflix!" — same
        // merchantKey ("netflix") but a DIFFERENT lowercase-trim name ("netflix!"),
        // so the OLD name-only dedup would have kept it. Content-aware dedup removes it.
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(d1, 15.0, TransactionType.PURCHASE, merchant = "Netflix")
        )
        coEvery { recurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            detectedPattern(merchant = "Netflix!", amount = 15.0, nextDate = d1)
        )

        val result = calculator.calculateDailyCashFlow(Date(d1), Date(ms("2026-04-02")), startingBalance = MoneyAmount(0.0, CurrencyCode("EUR")))

        // Predicted suppressed because it matches the actual expense by content.
        assertTrue(result[0].predictedRecurring.isEmpty())
    }

    @Test
    fun `cashflow two identical predictions same day deduped`() = runTest {
        val d1 = ms("2026-04-01")
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { recurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            detectedPattern(merchant = "Spotify", amount = 9.99, nextDate = d1),
            detectedPattern(merchant = "Spotify", amount = 9.99, nextDate = d1)
        )

        val result = calculator.calculateDailyCashFlow(Date(d1), Date(ms("2026-04-02")), startingBalance = MoneyAmount(0.0, CurrencyCode("EUR")))

        // Predicted-vs-predicted dedup collapses identical projections to one.
        assertEquals(1, result[0].predictedRecurring.size)
    }

    @Test
    fun `cashflow distinct same-merchant bills are NOT merged`() = runTest {
        val d1 = ms("2026-04-01")
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        // Same merchant, materially different amounts (15 vs 70) → legitimately distinct.
        coEvery { recurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            detectedPattern(merchant = "Netflix", amount = 15.0, nextDate = d1),
            detectedPattern(merchant = "Netflix", amount = 70.0, nextDate = d1)
        )

        val result = calculator.calculateDailyCashFlow(Date(d1), Date(ms("2026-04-02")), startingBalance = MoneyAmount(0.0, CurrencyCode("EUR")))

        // Both bills must survive — amount is part of the dedup key.
        assertEquals(2, result[0].predictedRecurring.size)
        assertTrue(result[0].predictedRecurring.any { it.averageAmount == 15.0 })
        assertTrue(result[0].predictedRecurring.any { it.averageAmount == 70.0 })
    }

    // ============================================================================
    // P6-CURRENT-020 – typed starting balance currency precondition
    // ============================================================================

    @Test
    fun `cashflow_starting_balance_currency_mismatch_rejected`() = runTest {
        val d1 = ms("2026-04-01")
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()

        // Home currency resolves to EUR (see setUp); a USD starting balance must be
        // rejected outright rather than auto-converted.
        val outcome = runCatching {
            calculator.calculateDailyCashFlow(
                Date(d1),
                Date(ms("2026-04-02")),
                startingBalance = MoneyAmount(100.0, CurrencyCode("USD"))
            )
        }

        assertTrue(
            "Expected IllegalArgumentException for currency mismatch, got ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is IllegalArgumentException
        )
    }

    // ============================================================================
    // DBG-03 – inactive (paused) rule's materialized occurrence must not leak in
    // ============================================================================

    @Test
    fun `cashflow inactive rule materialized planned occurrence is not shown`() = runTest {
        val d1 = ms("2026-04-01")
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()

        // Only the ACTIVE rule (id=1) is returned by getConfirmedPatterns — this mirrors
        // production where RecurringExpenseRepository.getAll() yields active rules only.
        // The PAUSED rule (id=2) is therefore NOT in the derived ruleIds set.
        coEvery { recurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            manualPattern(id = 1L, merchant = "Rent", amount = 800.0, nextDate = d1)
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(1L, any(), any()) } returns listOf(
            occurrence(ruleId = 1L, dueDate = d1, amount = 800.0, merchant = "Rent")
        )
        // The DAO still holds a previously-materialized PLANNED row for the paused rule (id=2).
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns listOf(
            occurrence(ruleId = 1L, dueDate = d1, amount = 800.0, merchant = "Rent"),
            occurrence(ruleId = 2L, dueDate = d1, amount = 9.99, merchant = "Paused Gym")
        )

        val result = calculator.calculateDailyCashFlow(
            Date(d1), Date(ms("2026-04-02")), startingBalance = MoneyAmount(0.0, CurrencyCode("EUR"))
        )

        val predicted = result[0].predictedRecurring
        // Active rule's bill is present; the paused rule's leaked occurrence is filtered out.
        assertTrue(predicted.any { it.merchantName == "Rent" })
        assertFalse(predicted.any { it.merchantName == "Paused Gym" })
    }

    // ============================================================================
    // DBG-06 – restore-barrier-blocked materialized read marks the result partial
    // ============================================================================

    @Test
    fun `cashflow marks partial when materialized read blocked by restore barrier`() = runTest {
        val d1 = ms("2026-04-01")
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { recurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            manualPattern(id = 1L, merchant = "Rent", amount = 800.0, nextDate = d1)
        )
        // Projection succeeds (bypasses the barrier) and yields a PLANNED bill.
        coEvery { recurringLifecycleCoordinator.projectOccurrences(1L, any(), any()) } returns listOf(
            occurrence(ruleId = 1L, dueDate = d1, amount = 800.0, merchant = "Rent")
        )
        // The materialized read (which would carry a CANCELLED override) is blocked by restore.
        every { databaseReadBarrier.checkReadAllowed(any<String>()) } throws DatabaseAccessBlockedException(
            accessType = DatabaseAccessType.READ,
            operation = DatabaseAccessOperation("CashFlowCalculator.calculateDailyCashFlow.readOccurrences"),
            mode = RestoreMaintenanceMode.Mode.RESTORE_STAGING
        )

        val result = calculator.calculateDailyCashFlow(
            Date(d1), Date(ms("2026-04-02")), startingBalance = MoneyAmount(0.0, CurrencyCode("EUR"))
        )

        assertEquals(1, result.size)
        // Degraded: the recurring section is flagged partial rather than silently
        // presenting projection-only data (which omits SKIPPED/CANCELLED overrides).
        assertTrue(result[0].isPartial)
        // The narrow projection-failure flag must NOT be set — projection succeeded.
        assertFalse(result[0].occurrenceGenerationFailed)
        assertEquals(0, result[0].failedOccurrenceRuleCount)
    }

    @Test
    fun `getUpcomingBills degrades gracefully when materialized read blocked by restore barrier`() = runTest {
        val now = ms("2026-04-01")
        every { timeProvider.now() } returns now

        // A manual rule (id != null) so ruleIds is non-empty — the materialized read
        // branch and its checkReadAllowed("...getUpcomingBills.readOccurrences") site
        // only execute when at least one manual rule is present.
        coEvery { recurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            manualPattern(id = 1L, merchant = "Rent", amount = 800.0, nextDate = ms("2026-04-03"))
        )
        // Projection bypasses the barrier and yields a PLANNED bill in range.
        coEvery { recurringLifecycleCoordinator.projectOccurrences(1L, any(), any()) } returns listOf(
            occurrence(ruleId = 1L, dueDate = ms("2026-04-03"), amount = 800.0, merchant = "Rent")
        )
        // The materialized read at getUpcomingBills.readOccurrences is blocked by restore.
        every {
            databaseReadBarrier.checkReadAllowed("CashFlowCalculator.getUpcomingBills.readOccurrences")
        } throws DatabaseAccessBlockedException(
            accessType = DatabaseAccessType.READ,
            operation = DatabaseAccessOperation("CashFlowCalculator.getUpcomingBills.readOccurrences"),
            mode = RestoreMaintenanceMode.Mode.RESTORE_STAGING
        )

        // getUpcomingBills returns List<RecurringPattern> with NO partial/degraded flag.
        // The real contract on a barrier block (DatabaseAccessBlockedException is caught
        // by the generic catch and degraded to emptyList() for the materialized read) is:
        // the call does NOT throw and projection-derived PLANNED bills still surface.
        val upcoming = calculator.getUpcomingBills(daysAhead = 10)

        // Graceful degradation: projection-only result survives the blocked materialized read.
        assertTrue(upcoming.any { it.merchantName == "Rent" })
        // Confirm the blocked barrier site was actually exercised (not skipped).
        verify(atLeast = 1) {
            databaseReadBarrier.checkReadAllowed("CashFlowCalculator.getUpcomingBills.readOccurrences")
        }
    }

    // ============================================================================
    // T4 Tier 3 – day-key boundary tests (formatDayKey via TimePeriodUtils)
    // ============================================================================

    @Test
    fun `T4T3 leap day occurrence lands on feb 29 not adjacent days`() = runTest {
        val leapDay = ms("2024-02-29")
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { recurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            manualPattern(id = 1L, merchant = "Rent", amount = 800.0, nextDate = leapDay)
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(1L, any(), any()) } returns listOf(
            occurrence(ruleId = 1L, dueDate = leapDay, amount = 800.0, merchant = "Rent")
        )
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(
            Date(leapDay), Date(ms("2024-03-01")), startingBalance = MoneyAmount(0.0, CurrencyCode("EUR"))
        )

        assertEquals(1, result.size)
        assertEquals(ms("2024-02-29"), result[0].date.time)
        assertEquals(listOf("Rent"), result[0].predictedRecurring.map { it.merchantName })
    }

    @Test
    fun `T4T3 month and year boundary occurrences keep distinct day keys`() = runTest {
        val dec31 = ms("2025-12-31")
        val jan1 = ms("2026-01-01")
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { recurringPatternsProvider.getConfirmedPatterns() } returns listOf(
            manualPattern(id = 1L, merchant = "Rent", amount = 800.0, nextDate = dec31),
            manualPattern(id = 2L, merchant = "Gym", amount = 20.0, nextDate = jan1)
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(1L, any(), any()) } returns listOf(
            occurrence(ruleId = 1L, dueDate = dec31, amount = 800.0, merchant = "Rent")
        )
        coEvery { recurringLifecycleCoordinator.projectOccurrences(2L, any(), any()) } returns listOf(
            occurrence(ruleId = 2L, dueDate = jan1, amount = 20.0, merchant = "Gym")
        )
        coEvery { recurringOccurrenceDao.getByDateRange(any(), any()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(
            Date(dec31), Date(ms("2026-01-02")), startingBalance = MoneyAmount(0.0, CurrencyCode("EUR"))
        )

        assertEquals(2, result.size)
        assertEquals(ms("2025-12-31"), result[0].date.time)
        assertEquals(listOf("Rent"), result[0].predictedRecurring.map { it.merchantName })
        assertEquals(ms("2026-01-01"), result[1].date.time)
        assertEquals(listOf("Gym"), result[1].predictedRecurring.map { it.merchantName })
    }

    @Test
    fun `T4T3 formatDayKey emits zero padded yyyy-MM-dd for leap and boundary dates`() {
        assertEquals("2024-02-29", calculator.formatDayKey(ms("2024-02-29")))
        assertEquals("2025-12-31", calculator.formatDayKey(ms("2025-12-31")))
        assertEquals("2026-01-01", calculator.formatDayKey(ms("2026-01-01")))
        assertEquals("2026-03-05", calculator.formatDayKey(ms("2026-03-05")))
    }

    private fun manualPattern(
        id: Long,
        merchant: String,
        amount: Double,
        nextDate: Long,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY
    ): RecurringPattern = RecurringPattern(
        id = id,
        merchantName = merchant,
        averageAmount = amount,
        currency = "EUR",
        frequency = frequency,
        periodVarianceDays = 0,
        amountVariancePercent = 0.0,
        nextExpectedDate = nextDate,
        confidence = 1.0f,
        previousDates = emptyList()
    )

    private fun detectedPattern(
        merchant: String,
        amount: Double,
        nextDate: Long,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY
    ): RecurringPattern = RecurringPattern(
        id = null,
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

    private fun expense(
        date: Long,
        amount: Double,
        type: TransactionType,
        transferDir: TransferDirection? = null,
        merchant: String = "T"
    ) = Expense(
        amount = amount,
        merchant = merchant,
        transactionType = type,
        date = date,
        transferDirection = transferDir
    )

    private fun ms(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}