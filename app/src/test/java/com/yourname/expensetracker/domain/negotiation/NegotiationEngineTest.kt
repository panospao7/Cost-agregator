package com.yourname.expensetracker.domain.negotiation

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.NegotiationOutcomeDao
import com.yourname.expensetracker.data.database.dao.SubscriptionPriceHistoryDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.NegotiationOutcomeEntity
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/** Fixed clock value returned by the per-row [NegotiationEngineTest.Fixture] time provider. */
private const val FIXED_NOW = 1_760_000_000_000L

/**
 * Contract tests for [SmartBillNegotiationEngine].
 *
 * Focuses on the engine's contract with its dependencies rather than
 * the full negotiation scoring logic (which is covered by integration tests).
 */
class NegotiationEngineTest {

    private val recurringExpenseRepository: RecurringExpenseRepository = mockk()
    private val priceHistoryDao: SubscriptionPriceHistoryDao = mockk()
    private val marketRateProvider: MarketRateProvider = mockk(relaxed = true)
    private val database: AppDatabase = mockk(relaxed = true)
    private val negotiationOutcomeDao: NegotiationOutcomeDao = mockk(relaxed = true)
    private val writeBarrier: DatabaseWriteBarrier = mockk(relaxed = true)
    private val timeProvider: TimeProvider = mockk(relaxed = true)

    @Before
    fun setUp() {
        // Make database.negotiationOutcomeDao() return our mock
        coEvery { database.negotiationOutcomeDao() } returns negotiationOutcomeDao
        // withTransaction compiles to the TOP-LEVEL static facade
        // androidx.room.RoomDatabaseKt.withTransaction (Room 2.7.2). Without
        // mockkStatic, the per-test coAnswers stubs record but never intercept:
        // the real Room body runs against the relaxed AppDatabase mock and
        // suspends forever (measured hang: 1 test passes, then silence —
        // docs/testing/test-sweep-outcomes-2026-09-18.md, §Hangs).
        mockkStatic("androidx.room.RoomDatabaseKt")
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun createEngine(
        marketRateProvider: MarketRateProvider = this.marketRateProvider,
        database: AppDatabase = this.database,
        writeBarrier: DatabaseWriteBarrier = this.writeBarrier,
        timeProvider: TimeProvider = this.timeProvider
    ) = SmartBillNegotiationEngine(
        recurringExpenseRepository = recurringExpenseRepository,
        priceHistoryDao = priceHistoryDao,
        negotiationOutcomeDao = negotiationOutcomeDao,
        marketRateProvider = marketRateProvider,
        database = database,
        writeBarrier = writeBarrier,
        timeProvider = timeProvider
    )

    /**
     * Fresh mocks + engine per table row, so recorded calls / stubs / verify counts
     * never leak from one row to the next. Strict mocks except where noted.
     */
    private class Fixture {
        val repo: RecurringExpenseRepository = mockk()
        val priceHistoryDao: SubscriptionPriceHistoryDao = mockk()
        val outcomeDao: NegotiationOutcomeDao = mockk(relaxed = true)
        val provider: MarketRateProvider = mockk()
        val database: AppDatabase = mockk(relaxed = true)
        val writeBarrier: DatabaseWriteBarrier = mockk(relaxed = true)
        val timeProvider: TimeProvider = mockk()

        val engine: SmartBillNegotiationEngine

        init {
            every { timeProvider.now() } returns FIXED_NOW
            coEvery { database.negotiationOutcomeDao() } returns outcomeDao
            engine = SmartBillNegotiationEngine(
                recurringExpenseRepository = repo,
                priceHistoryDao = priceHistoryDao,
                negotiationOutcomeDao = outcomeDao,
                marketRateProvider = provider,
                database = database,
                writeBarrier = writeBarrier,
                timeProvider = timeProvider
            )
        }

        /** Runs the transaction block inline (requires the class-level mockkStatic of RoomDatabaseKt). */
        fun stubTransactionPassThrough() {
            coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
                secondArg<suspend () -> Any>().invoke()
            }
        }
    }

    private fun subscription(
        id: Long,
        merchant: String,
        amount: Double,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        currency: String = "EUR"
    ) = ManualRecurringExpense(
        id = id, merchant = merchant, amount = amount, currency = currency,
        frequency = frequency,
        nextDate = FIXED_NOW,
        isSubscription = true, isActive = true
    )

    @Test
    fun `no recommendation when no data`() = runTest(timeout = 60.seconds) {
        // Given: no subscriptions exist
        coEvery { recurringExpenseRepository.getAll() } returns emptyList()

        val engine = createEngine()
        val opportunities = engine.analyzeNegotiationOpportunities()

        // Then: no negotiation opportunities are generated
        assertTrue("Expected empty opportunities when no subscriptions exist",
            opportunities.isEmpty())
    }

    @Test
    fun `repository failure propagates from analyze (not swallowed)`() = runTest(timeout = 60.seconds) {
        // Given: the repository throws (e.g., database error). analyzeNegotiationOpportunities
        // reads subscriptions outside any try/catch (SmartBillNegotiationEngine.kt:39).
        coEvery { recurringExpenseRepository.getAll() } throws RuntimeException("DB connection lost")

        val engine = createEngine()

        // Then: exactly that exception reaches the caller; no pass path without a throw.
        var thrown: Exception? = null
        try {
            engine.analyzeNegotiationOpportunities()
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("Repository failure must propagate, but analyze returned normally", thrown)
        assertEquals(RuntimeException::class.java, thrown!!.javaClass)
        assertEquals("DB connection lost", thrown.message)
    }

    @Test
    fun `market rate provider failure is handled gracefully as no opportunity`() = runTest(timeout = 60.seconds) {
        // findMarketRate catches non-cancellation provider exceptions and returns null
        // (SmartBillNegotiationEngine.kt:101-105), so the subscription yields no opportunity.
        val fx = Fixture()
        coEvery { fx.repo.getAll() } returns listOf(subscription(1L, "Netflix", 13.99))
        coEvery { fx.provider.getRates(any(), any(), any()) } throws RuntimeException("provider down")

        val opportunities = fx.engine.analyzeNegotiationOpportunities()

        assertEquals(emptyList<SmartBillNegotiationEngine.NegotiationOpportunity>(), opportunities)
        coVerify(exactly = 1) { fx.provider.getRates(ServiceType.STREAMING, "GR", "EUR") }
    }

    @Test
    fun `market rate staleness is detected correctly`() {
        val now = System.currentTimeMillis()

        // Fresh rate — last updated just now
        val freshRate = SmartBillNegotiationEngine.MarketRate(
            serviceType = SmartBillNegotiationEngine.ServiceType.INTERNET,
            providerName = "Test",
            averagePrice = 25.0,
            competitivePrice = 20.0,
            bestPrice = 15.0,
            unit = "month",
            competitors = emptyList(),
            lastUpdated = now
        )
        assertTrue("Fresh rate should NOT be stale", !freshRate.isStale(now))

        // Stale rate — last updated 31 days ago
        val staleRate = freshRate.copy(
            lastUpdated = now - 31L * 24 * 60 * 60 * 1000
        )
        assertTrue("Rate older than 30 days should be stale",
            staleRate.isStale(now))

        // Rate with zero lastUpdated should be stale
        val neverUpdated = freshRate.copy(lastUpdated = 0L)
        assertTrue("Rate with no update timestamp should be stale",
            neverUpdated.isStale(now))
    }

    @Test
    fun `negotiationEngine uses injected marketRateProvider`() = runTest(timeout = 60.seconds) {
        val provider: MarketRateProvider = mockk()
        coEvery { provider.getRates(any(), any(), any()) } returns MarketRateResult(
            quotes = listOf(
                MarketRateQuote(
                    providerName = "Netflix",
                    averageMonthlyPrice = 12.99,
                    competitiveMonthlyPrice = 7.99,
                    bestMonthlyPrice = 6.99,
                    currency = "EUR",
                    region = "GR",
                    confidence = MarketRateConfidence.MEDIUM
                )
            ),
            source = "test",
            lastUpdatedAt = System.currentTimeMillis()
        )

        val subscription = ManualRecurringExpense(
            id = 1L, merchant = "Netflix", amount = 13.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )

        coEvery { recurringExpenseRepository.getAll() } returns listOf(subscription)
        coEvery { priceHistoryDao.getAllPricesForSubscription(any()) } returns emptyList()

        val engine = createEngine(marketRateProvider = provider)
        val opportunities = engine.analyzeNegotiationOpportunities()

        assertEquals(1, opportunities.size)
    }

    private data class BillingCycleRow(
        val label: String,
        val id: Long,
        val merchant: String,
        val amount: Double,
        val frequency: RecurrenceFrequency,
        val quote: MarketRateQuote,
        val expectedMonthlyEquivalent: Double,
        val expectedRawBillingAmount: Double,
        /** Substring the script MUST contain (null = not asserted for this row). */
        val scriptMustContain: String?,
        /** Substring the script MUST NOT contain (null = not asserted for this row). */
        val scriptMustNotContain: String?
    )

    @Test
    fun `analyze reports monthly equivalent and raw billing amount per billing cycle`() = runTest(timeout = 60.seconds) {
        // The script uses String.format("%.2f") / Currency symbol with the default Locale
        // (SmartBillNegotiationEngine.kt:427-452); pin Locale.US so "10.00" is deterministic.
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val rows = listOf(
                BillingCycleRow(
                    label = "annual Netflix 120.00",
                    id = 1L, merchant = "Netflix", amount = 120.0,
                    frequency = RecurrenceFrequency.ANNUALLY,
                    quote = MarketRateQuote("Netflix", 13.99, 8.99, 6.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
                    expectedMonthlyEquivalent = 10.0,
                    expectedRawBillingAmount = 120.0,
                    scriptMustContain = "10.00",
                    scriptMustNotContain = "120.00"
                ),
                BillingCycleRow(
                    label = "monthly Spotify 9.99",
                    id = 2L, merchant = "Spotify", amount = 9.99,
                    frequency = RecurrenceFrequency.MONTHLY,
                    quote = MarketRateQuote("Spotify", 10.99, 6.99, 5.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
                    expectedMonthlyEquivalent = 9.99,
                    expectedRawBillingAmount = 9.99,
                    scriptMustContain = null,
                    scriptMustNotContain = null
                ),
                BillingCycleRow(
                    label = "quarterly Gym 90.00",
                    id = 3L, merchant = "Gym", amount = 90.0,
                    frequency = RecurrenceFrequency.QUARTERLY,
                    quote = MarketRateQuote("Gym", 39.99, 29.99, 24.99, "EUR", "GR", MarketRateConfidence.LOW),
                    expectedMonthlyEquivalent = 30.0,
                    expectedRawBillingAmount = 90.0,
                    scriptMustContain = null,
                    scriptMustNotContain = null
                )
            )
            assertEquals("billing-cycle table size", 3, rows.size)

            for (row in rows) {
                val fx = Fixture()
                coEvery { fx.repo.getAll() } returns
                    listOf(subscription(row.id, row.merchant, row.amount, row.frequency))
                coEvery { fx.priceHistoryDao.getAllPricesForSubscription(any()) } returns emptyList()
                coEvery { fx.provider.getRates(any(), any(), any()) } returns MarketRateResult(
                    quotes = listOf(row.quote), source = "test", lastUpdatedAt = FIXED_NOW
                )

                val opportunities = fx.engine.analyzeNegotiationOpportunities()

                assertEquals("[${row.label}] opportunity count", 1, opportunities.size)
                val opp = opportunities.single()
                assertEquals("[${row.label}] monthlyEquivalentPrice",
                    row.expectedMonthlyEquivalent, opp.monthlyEquivalentPrice, 0.01)
                assertEquals("[${row.label}] rawBillingAmount",
                    row.expectedRawBillingAmount, opp.rawBillingAmount, 0.01)

                val scriptText = opp.negotiationScript.opening +
                    opp.negotiationScript.talkingPoints.joinToString(" ")
                row.scriptMustContain?.let {
                    assertTrue("[${row.label}] script should mention monthly equivalent ($it)",
                        scriptText.contains(it))
                }
                row.scriptMustNotContain?.let {
                    assertTrue("[${row.label}] script must not mention raw billing amount ($it)",
                        !scriptText.contains(it))
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    private data class OutcomeWriteRow(
        val label: String,
        val id: Long,
        val frequency: RecurrenceFrequency,
        val oldAmount: Double,
        val outcome: SmartBillNegotiationEngine.NegotiationOutcome,
        val newPrice: Double,
        val savings: Double,
        val notes: String,
        /** Expected billing-cycle amount written to price history and to the subscription. */
        val expectedBillingAmount: Double,
        /** Absolute tolerance for [expectedBillingAmount] (0.0 = exact equality). */
        val delta: Double
    )

    @Test
    fun `recordNegotiationOutcome SUCCESS and PARTIAL persist outcome, price history and subscription per billing cycle`() = runTest(timeout = 60.seconds) {
        // PR8: monthly newPrice is stored as-is on the outcome entity, but converted back to the
        // billing-cycle amount for price history + subscription update
        // (SmartBillNegotiationEngine.kt:257-263, 619-640).
        val rows = listOf(
            OutcomeWriteRow(
                label = "SUCCESS monthly", id = 1L, frequency = RecurrenceFrequency.MONTHLY,
                oldAmount = 13.99, outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
                newPrice = 9.99, savings = 4.0, notes = "Negotiated",
                expectedBillingAmount = 9.99, delta = 0.0
            ),
            OutcomeWriteRow(
                label = "PARTIAL monthly", id = 1L, frequency = RecurrenceFrequency.MONTHLY,
                oldAmount = 13.99, outcome = SmartBillNegotiationEngine.NegotiationOutcome.PARTIAL,
                newPrice = 9.99, savings = 4.0, notes = "Negotiated",
                expectedBillingAmount = 9.99, delta = 0.0
            ),
            OutcomeWriteRow(
                label = "SUCCESS annual", id = 1L, frequency = RecurrenceFrequency.ANNUALLY,
                oldAmount = 120.0, outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
                newPrice = 7.0, savings = 3.0, notes = "Negotiated annual subscription",
                expectedBillingAmount = 84.0, delta = 0.0
            ),
            OutcomeWriteRow(
                label = "PARTIAL annual", id = 1L, frequency = RecurrenceFrequency.ANNUALLY,
                oldAmount = 120.0, outcome = SmartBillNegotiationEngine.NegotiationOutcome.PARTIAL,
                newPrice = 7.0, savings = 3.0, notes = "Partial on annual",
                expectedBillingAmount = 84.0, delta = 0.0
            ),
            // 117.26 * 7 / (365 / 12) = 26.98586301...
            OutcomeWriteRow(
                label = "SUCCESS weekly", id = 4L, frequency = RecurrenceFrequency.WEEKLY,
                oldAmount = 50.0, outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
                newPrice = 117.26, savings = 100.0, notes = "test",
                expectedBillingAmount = 26.98586301, delta = 1e-6
            ),
            // 167.26 * 14 / (365 / 12) = 76.98542466...
            OutcomeWriteRow(
                label = "SUCCESS biweekly", id = 5L, frequency = RecurrenceFrequency.BIWEEKLY,
                oldAmount = 100.0, outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
                newPrice = 167.26, savings = 50.0, notes = "test",
                expectedBillingAmount = 76.98542466, delta = 1e-6
            )
        )
        assertEquals("outcome-write table size", 6, rows.size)

        for (row in rows) {
            val fx = Fixture()
            coEvery { fx.repo.getById(row.id) } returns
                subscription(row.id, "Netflix", row.oldAmount, row.frequency)
            fx.stubTransactionPassThrough()
            coEvery { fx.priceHistoryDao.insert(any()) } returns 1L
            coEvery { fx.repo.update(any()) } returns Unit

            val result = fx.engine.recordNegotiationOutcome(
                subscriptionId = row.id,
                outcome = row.outcome,
                newPrice = row.newPrice,
                savings = row.savings,
                notes = row.notes
            )

            assertTrue("[${row.label}] result.isSuccess", result.isSuccess)

            val outcomeSlot = slot<NegotiationOutcomeEntity>()
            coVerify(exactly = 1) { fx.outcomeDao.insert(capture(outcomeSlot)) }
            assertEquals("[${row.label}] outcome.subscriptionId", row.id, outcomeSlot.captured.subscriptionId)
            assertEquals("[${row.label}] outcome.outcome", row.outcome.name, outcomeSlot.captured.outcome)
            assertEquals("[${row.label}] outcome.oldAmount", row.oldAmount, outcomeSlot.captured.oldAmount, 0.0)
            // outcome entity stores the user-facing monthly newPrice, NOT converted
            assertEquals("[${row.label}] outcome.newAmount", row.newPrice, outcomeSlot.captured.newAmount!!, 0.0)
            assertEquals("[${row.label}] outcome.savingsAmount", row.savings, outcomeSlot.captured.savingsAmount!!, 0.0)
            assertEquals("[${row.label}] outcome.notes", row.notes, outcomeSlot.captured.notes)
            assertEquals("[${row.label}] outcome.currency", "EUR", outcomeSlot.captured.currency)
            assertEquals("[${row.label}] outcome.createdAt", FIXED_NOW, outcomeSlot.captured.createdAt)

            val historySlot = slot<com.yourname.expensetracker.data.database.entity.SubscriptionPriceHistory>()
            coVerify(exactly = 1) { fx.priceHistoryDao.insert(capture(historySlot)) }
            assertEquals("[${row.label}] history.subscriptionId", row.id, historySlot.captured.subscriptionId)
            assertEquals("[${row.label}] history.amount",
                row.expectedBillingAmount, historySlot.captured.amount, row.delta)
            assertEquals("[${row.label}] history.changeReason",
                "Negotiation outcome: ${row.outcome.name}", historySlot.captured.changeReason)
            assertEquals("[${row.label}] history.recordedAt", FIXED_NOW, historySlot.captured.recordedAt)

            val updateSlot = slot<ManualRecurringExpense>()
            coVerify(exactly = 1) { fx.repo.update(capture(updateSlot)) }
            assertEquals("[${row.label}] update.id", row.id, updateSlot.captured.id)
            assertEquals("[${row.label}] update.amount",
                row.expectedBillingAmount, updateSlot.captured.amount, row.delta)
            assertEquals("[${row.label}] update.frequency", row.frequency, updateSlot.captured.frequency)
        }
    }

    @Test
    fun `negotiationFailure_persistsOutcomeButDoesNotUpdatePrice`() = runTest(timeout = 60.seconds) {
        val subscription = ManualRecurringExpense(
            id = 2L, merchant = "Netflix", amount = 13.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getById(2L) } returns subscription
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }

        val engine = createEngine()
        val result = engine.recordNegotiationOutcome(
            subscriptionId = 2L,
            outcome = SmartBillNegotiationEngine.NegotiationOutcome.FAILURE,
            newPrice = null,
            savings = null,
            notes = "Provider declined"
        )

        assertTrue(result.isSuccess)
        coVerify { negotiationOutcomeDao.insert(match { entity ->
            entity.subscriptionId == 2L &&
            entity.outcome == "FAILURE" &&
            entity.oldAmount == 13.99 &&
            entity.newAmount == null
        }) }
        coVerify(exactly = 0) { priceHistoryDao.insert(any()) }
        coVerify(exactly = 0) { recurringExpenseRepository.update(any()) }
    }

    @Test
    fun `negotiationWriteBlockedDuringRestore`() = runTest(timeout = 60.seconds) {
        coEvery { recurringExpenseRepository.getById(any()) } returns ManualRecurringExpense(
            id = 1L, merchant = "Netflix", amount = 13.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } throws
            DatabaseAccessBlockedException(
                accessType = com.yourname.expensetracker.data.backup.DatabaseAccessType.WRITE,
                operation = com.yourname.expensetracker.data.backup.DatabaseAccessOperation("test"),
                mode = com.yourname.expensetracker.data.backup.RestoreMaintenanceMode.Mode.RESTORE_PREPARING
            )

        val engine = createEngine()
        val result = engine.recordNegotiationOutcome(
            subscriptionId = 1L,
            outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
            newPrice = 9.99,
            savings = 4.0,
            notes = "Negotiated"
        )

        assertTrue(result.isFailure)
        assertTrue(
            "Expected DatabaseAccessBlockedException, got: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is DatabaseAccessBlockedException
        )
        coVerify(exactly = 0) { negotiationOutcomeDao.insert(any()) }
    }

    @Test
    fun `getNegotiationHistory_returnsPersistedOutcomes`() = runTest(timeout = 60.seconds) {
        val now = System.currentTimeMillis()
        val outcome1 = NegotiationOutcomeEntity(
            id = 1, subscriptionId = 1L, outcome = "SUCCESS",
            oldAmount = 13.99, newAmount = 9.99, currency = "EUR",
            savingsAmount = 4.0, notes = "Negotiated", marketRateSource = "test", createdAt = now
        )
        val outcome2 = NegotiationOutcomeEntity(
            id = 2, subscriptionId = 2L, outcome = "FAILURE",
            oldAmount = 15.99, newAmount = null, currency = "EUR",
            savingsAmount = null, notes = "Declined", marketRateSource = "test", createdAt = now
        )
        coEvery { negotiationOutcomeDao.getAll() } returns listOf(outcome1, outcome2)

        val engine = createEngine()
        val history = engine.getNegotiationHistory()

        // Persisted outcomes are returned unchanged and in DAO order
        // (SmartBillNegotiationEngine.kt:574-576).
        assertEquals(2, history.size)
        assertEquals(listOf(1L, 2L), history.map { it.id })
        assertEquals(listOf(1L, 2L), history.map { it.subscriptionId })
        assertEquals(listOf("SUCCESS", "FAILURE"), history.map { it.outcome })
        assertEquals(listOf<Double?>(9.99, null), history.map { it.newAmount })
        assertEquals(listOf<Double?>(4.0, null), history.map { it.savingsAmount })
        assertEquals(listOf(13.99, 15.99), history.map { it.oldAmount })
        assertEquals(listOf<String?>("Negotiated", "Declined"), history.map { it.notes })
        assertEquals(listOf(outcome1, outcome2), history)
        coVerify(exactly = 1) { negotiationOutcomeDao.getAll() }
    }

    @Test
    fun `recordNegotiationOutcome returns failure when subscription not found`() = runTest(timeout = 60.seconds) {
        coEvery { recurringExpenseRepository.getById(any()) } returns null
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit

        val engine = createEngine()
        val result = engine.recordNegotiationOutcome(
            subscriptionId = 999L,
            outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
            newPrice = 9.99,
            savings = 4.0,
            notes = "test"
        )

        assertTrue("Expected failure when subscription not found", result.isFailure)
        assertTrue(
            "Expected IllegalArgumentException",
            result.exceptionOrNull() is IllegalArgumentException
        )
        assertEquals("Subscription not found: 999", result.exceptionOrNull()?.message)
    }

    @Test
    fun `negotiationSuccess_rollsBackWhenPriceHistoryInsertFails`() = runTest(timeout = 60.seconds) {
        val subscription = ManualRecurringExpense(
            id = 1L, merchant = "Netflix", amount = 13.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )

        coEvery { recurringExpenseRepository.getById(1L) } returns subscription
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        coEvery { negotiationOutcomeDao.insert(any()) } returns 1L
        coEvery { priceHistoryDao.insert(any()) } throws RuntimeException("DB locked")

        val engine = createEngine()
        val result = engine.recordNegotiationOutcome(
            subscriptionId = 1L,
            outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
            newPrice = 9.99,
            savings = 4.0,
            notes = "test"
        )

        assertTrue("Expected failure when price history insert fails", result.isFailure)
        // The exact DAO error is surfaced via Result.failure (SmartBillNegotiationEngine.kt:646-648)
        // and the transaction body aborts before updating the subscription.
        assertEquals(RuntimeException::class.java, result.exceptionOrNull()?.javaClass)
        assertEquals("DB locked", result.exceptionOrNull()?.message)
        coVerify(exactly = 1) { negotiationOutcomeDao.insert(any()) }
        coVerify(exactly = 1) { priceHistoryDao.insert(any()) }
        coVerify(exactly = 0) { recurringExpenseRepository.update(any()) }
    }

    @Test
    fun `eydapWaterSubscription_generatesNegotiationOpportunity`() = runTest(timeout = 60.seconds) {
        val subscription = ManualRecurringExpense(
            id = 10L, merchant = "EYDAP", amount = 15.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getAll() } returns listOf(subscription)
        coEvery { priceHistoryDao.getAllPricesForSubscription(any()) } returns emptyList()

        val provider: MarketRateProvider = mockk()
        coEvery { provider.getRates(any(), any(), any()) } returns MarketRateResult(
            quotes = listOf(MarketRateQuote("EYDAP", 15.99, 11.99, 8.99, "EUR", "GR", MarketRateConfidence.LOW)),
            source = "test", lastUpdatedAt = System.currentTimeMillis()
        )

        val engine = createEngine(marketRateProvider = provider)
        val opportunities = engine.analyzeNegotiationOpportunities()

        assertEquals("EYDAP water subscription should generate opportunity", 1, opportunities.size)
        assertEquals("EYDAP", opportunities.first().serviceName)
    }

    @Test
    fun `greekWaterKeyword_generatesWaterServiceType`() = runTest(timeout = 60.seconds) {
        val subscription = ManualRecurringExpense(
            id = 11L, merchant = "ΥΔΡΕΥΣΗΣ ΔΗΜΟΥ", amount = 12.0, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getAll() } returns listOf(subscription)
        coEvery { priceHistoryDao.getAllPricesForSubscription(any()) } returns emptyList()

        val provider: MarketRateProvider = mockk()
        coEvery { provider.getRates(any(), any(), any()) } returns MarketRateResult(
            quotes = listOf(MarketRateQuote("EYDAP", 15.99, 11.99, 8.99, "EUR", "GR", MarketRateConfidence.LOW)),
            source = "test", lastUpdatedAt = System.currentTimeMillis()
        )

        val engine = createEngine(marketRateProvider = provider)
        val opportunities = engine.analyzeNegotiationOpportunities()

        assertEquals("Greek water keyword should generate opportunity", 1, opportunities.size)
    }

    @Test
    fun `vodafoneCu_matchesVodafoneMobileQuote_notCosmote`() = runTest(timeout = 60.seconds) {
        val subscription = ManualRecurringExpense(
            id = 20L, merchant = "Vodafone CU", amount = 24.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getAll() } returns listOf(subscription)
        coEvery { priceHistoryDao.getAllPricesForSubscription(any()) } returns emptyList()

        val provider: MarketRateProvider = mockk()
        coEvery {
            provider.getRates(
                serviceType = com.yourname.expensetracker.domain.negotiation.ServiceType.MOBILE,
                region = "GR",
                currency = "EUR"
            )
        } returns MarketRateResult(
            quotes = listOf(
                MarketRateQuote("Cosmote Mobile", 24.99, 14.99, 9.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
                MarketRateQuote("Vodafone Mobile", 22.99, 12.99, 8.99, "EUR", "GR", MarketRateConfidence.MEDIUM)
            ),
            source = "test", lastUpdatedAt = System.currentTimeMillis()
        )

        val engine = createEngine(marketRateProvider = provider)
        val opportunities = engine.analyzeNegotiationOpportunities()

        assertEquals(1, opportunities.size)
        // Provider name should contain "Vodafone", not "Cosmote"
        assertTrue("Expected Vodafone provider, got: ${opportunities.first().currentProvider}",
            opportunities.first().currentProvider.contains("Vodafone", ignoreCase = true))

        coVerify(exactly = 1) {
            provider.getRates(
                com.yourname.expensetracker.domain.negotiation.ServiceType.MOBILE,
                "GR", "EUR"
            )
        }
        coVerify(exactly = 0) {
            provider.getRates(
                com.yourname.expensetracker.domain.negotiation.ServiceType.INTERNET,
                "GR", "EUR"
            )
        }
    }

    @Test
    fun `cosmoteFiber_matchesCosmoteFiberQuote`() = runTest(timeout = 60.seconds) {
        val subscription = ManualRecurringExpense(
            id = 21L, merchant = "Cosmote Fiber", amount = 34.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getAll() } returns listOf(subscription)
        coEvery { priceHistoryDao.getAllPricesForSubscription(any()) } returns emptyList()

        val provider: MarketRateProvider = mockk()
        coEvery {
            provider.getRates(
                serviceType = com.yourname.expensetracker.domain.negotiation.ServiceType.INTERNET,
                region = "GR",
                currency = "EUR"
            )
        } returns MarketRateResult(
            quotes = listOf(
                MarketRateQuote("Vodafone Fiber", 32.99, 22.99, 18.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
                MarketRateQuote("Cosmote Fiber", 34.99, 24.99, 19.99, "EUR", "GR", MarketRateConfidence.MEDIUM)
            ),
            source = "test", lastUpdatedAt = System.currentTimeMillis()
        )

        val engine = createEngine(marketRateProvider = provider)
        val opportunities = engine.analyzeNegotiationOpportunities()

        assertEquals(1, opportunities.size)
        assertTrue("Expected Cosmote provider",
            opportunities.first().currentProvider.contains("Cosmote", ignoreCase = true))

        coVerify(exactly = 1) {
            provider.getRates(
                com.yourname.expensetracker.domain.negotiation.ServiceType.INTERNET,
                "GR", "EUR"
            )
        }
    }

    private data class GatingRow(
        val label: String,
        val id: Long,
        val merchant: String,
        val currency: String,
        val amount: Double,
        /** Quote the provider returns for [expectedProviderType]; null = provider must never be consulted. */
        val quote: MarketRateQuote?,
        val expectedOpportunityCount: Int,
        /** Expected getRates(type, "GR", "EUR") call count for [expectedProviderType]; any other type must be 0. */
        val expectedProviderCalls: Int,
        val expectedProviderType: ServiceType?
    )

    @Test
    fun `analyze gates subscriptions by classification and currency before consulting provider`() = runTest(timeout = 60.seconds) {
        val rows = listOf(
            // ENERGY is skipped after classification (SmartBillNegotiationEngine.kt:89-92)
            GatingRow(
                label = "DEI Energy (ENERGY skipped)", id = 22L, merchant = "DEI Energy",
                currency = "EUR", amount = 50.0, quote = null,
                expectedOpportunityCount = 0, expectedProviderCalls = 0, expectedProviderType = null
            ),
            // Non-EUR skipped before classification (SmartBillNegotiationEngine.kt:43-51)
            GatingRow(
                label = "USD Netflix (non-EUR skipped)", id = 25L, merchant = "Netflix",
                currency = "USD", amount = 13.99, quote = null,
                expectedOpportunityCount = 0, expectedProviderCalls = 0, expectedProviderType = null
            ),
            // Bare "Vodafone" has no MOBILE/INTERNET keyword -> unclassified -> null
            GatingRow(
                label = "plain Vodafone (unclassified)", id = 90L, merchant = "Vodafone",
                currency = "EUR", amount = 24.99, quote = null,
                expectedOpportunityCount = 0, expectedProviderCalls = 0, expectedProviderType = null
            ),
            // Control: EUR Netflix is classified STREAMING and consults the provider exactly once
            GatingRow(
                label = "EUR Netflix (control, opportunity produced)", id = 26L, merchant = "Netflix",
                currency = "EUR", amount = 13.99,
                quote = MarketRateQuote("Netflix", 12.99, 7.99, 6.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
                expectedOpportunityCount = 1, expectedProviderCalls = 1,
                expectedProviderType = ServiceType.STREAMING
            )
        )
        assertEquals("gating table size", 4, rows.size)

        for (row in rows) {
            val fx = Fixture()
            coEvery { fx.repo.getAll() } returns
                listOf(subscription(row.id, row.merchant, row.amount, currency = row.currency))
            coEvery { fx.priceHistoryDao.getAllPricesForSubscription(any()) } returns emptyList()
            if (row.quote != null && row.expectedProviderType != null) {
                coEvery { fx.provider.getRates(row.expectedProviderType, "GR", "EUR") } returns
                    MarketRateResult(quotes = listOf(row.quote), source = "test", lastUpdatedAt = FIXED_NOW)
            }

            val opportunities = fx.engine.analyzeNegotiationOpportunities()

            assertEquals("[${row.label}] opportunity count", row.expectedOpportunityCount, opportunities.size)
            if (row.expectedProviderType == null) {
                coVerify(exactly = 0) { fx.provider.getRates(any(), any(), any()) }
            } else {
                coVerify(exactly = row.expectedProviderCalls) {
                    fx.provider.getRates(row.expectedProviderType, "GR", "EUR")
                }
                coVerify(exactly = row.expectedProviderCalls) { fx.provider.getRates(any(), any(), any()) }
            }
        }
    }

    private data class InvalidOutcomeRow(
        val label: String,
        val id: Long,
        val outcome: SmartBillNegotiationEngine.NegotiationOutcome,
        val newPrice: Double?,
        val savings: Double?,
        val expectedMessage: String
    )

    @Test
    fun `recordNegotiationOutcome rejects invalid newPrice or savings with IllegalArgumentException and writes nothing`() = runTest(timeout = 60.seconds) {
        // Messages come from validateNegotiationOutcomeInput (SmartBillNegotiationEngine.kt:667-682).
        val newPriceMessage = "Successful or partial negotiation requires a finite positive new price"
        val savingsMessage = "Savings must be finite and non-negative"
        val rows = listOf(
            InvalidOutcomeRow("SUCCESS null newPrice", 30L,
                SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS, null, 4.0, newPriceMessage),
            InvalidOutcomeRow("PARTIAL null newPrice", 31L,
                SmartBillNegotiationEngine.NegotiationOutcome.PARTIAL, null, 2.0, newPriceMessage),
            InvalidOutcomeRow("SUCCESS +Infinity newPrice", 32L,
                SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS, Double.POSITIVE_INFINITY, 4.0, newPriceMessage),
            InvalidOutcomeRow("SUCCESS NaN newPrice", 33L,
                SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS, Double.NaN, 4.0, newPriceMessage),
            InvalidOutcomeRow("SUCCESS +Infinity savings", 34L,
                SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS, 9.99, Double.POSITIVE_INFINITY, savingsMessage),
            InvalidOutcomeRow("SUCCESS negative savings", 35L,
                SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS, 9.99, -1.0, savingsMessage)
        )
        assertEquals("invalid-outcome table size", 6, rows.size)

        for (row in rows) {
            val fx = Fixture()
            coEvery { fx.repo.getById(row.id) } returns subscription(row.id, "Netflix", 13.99)
            fx.stubTransactionPassThrough()
            coEvery { fx.priceHistoryDao.insert(any()) } returns 1L
            coEvery { fx.repo.update(any()) } returns Unit

            val result = fx.engine.recordNegotiationOutcome(
                subscriptionId = row.id,
                outcome = row.outcome,
                newPrice = row.newPrice,
                savings = row.savings,
                notes = "test"
            )

            assertTrue("[${row.label}] should fail", result.isFailure)
            val error = result.exceptionOrNull()
            assertTrue("[${row.label}] expected IllegalArgumentException, got: $error",
                error is IllegalArgumentException)
            assertEquals("[${row.label}] error message", row.expectedMessage, error?.message)
            coVerify(exactly = 0) { fx.outcomeDao.insert(any()) }
            coVerify(exactly = 0) { fx.priceHistoryDao.insert(any()) }
            coVerify(exactly = 0) { fx.repo.update(any()) }
        }
    }

    @Test
    fun `negotiationFailure_allowsNullNewPrice`() = runTest(timeout = 60.seconds) {
        val subscription = ManualRecurringExpense(
            id = 36L, merchant = "Netflix", amount = 13.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getById(36L) } returns subscription
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }

        val engine = createEngine()
        val result = engine.recordNegotiationOutcome(
            subscriptionId = 36L,
            outcome = SmartBillNegotiationEngine.NegotiationOutcome.FAILURE,
            newPrice = null,
            savings = null,
            notes = "Provider declined"
        )

        assertTrue("FAILURE with null newPrice should succeed", result.isSuccess)
    }

    @Test
    fun `findMarketRate_rethrowsCancellationException`() = runTest(timeout = 60.seconds) {
        val provider: MarketRateProvider = mockk()
        coEvery { provider.getRates(any(), any(), any()) } throws CancellationException("cancelled")
        // analyzeNegotiationOpportunities reads subscriptions before consulting
        // the provider; without this stub the strict mock throws before the
        // cancellation-under-test can surface.
        val subscription = ManualRecurringExpense(
            id = 1L, merchant = "Netflix", amount = 13.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getAll() } returns listOf(subscription)

        val engine = createEngine(marketRateProvider = provider)

        var caught = false
        try {
            engine.analyzeNegotiationOpportunities()
        } catch (e: CancellationException) {
            caught = true
        }

        assertTrue("CancellationException should be rethrown from findMarketRate", caught)
    }

    @Test
    fun `recordNegotiationOutcome_rethrowsCancellationException`() = runTest(timeout = 60.seconds) {
        val subscription = ManualRecurringExpense(
            id = 40L, merchant = "Netflix", amount = 13.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getById(40L) } returns subscription
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { database.withTransaction(any<suspend () -> Any>()) } throws CancellationException("cancelled")

        val engine = createEngine()

        var caught = false
        try {
            engine.recordNegotiationOutcome(
                subscriptionId = 40L,
                outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
                newPrice = 9.99,
                savings = 4.0,
                notes = "test"
            )
        } catch (e: CancellationException) {
            caught = true
        }

        assertTrue("CancellationException should be rethrown from recordNegotiationOutcome", caught)
    }

    @Test
    fun `recordNegotiationOutcome_writeBarrierBlocked_doesNotReadSubscription`() = runTest(timeout = 60.seconds) {
        // Because writeBarrier is checked BEFORE getById, getById should never be called
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } throws
            DatabaseAccessBlockedException(
                accessType = com.yourname.expensetracker.data.backup.DatabaseAccessType.WRITE,
                operation = com.yourname.expensetracker.data.backup.DatabaseAccessOperation("test"),
                mode = com.yourname.expensetracker.data.backup.RestoreMaintenanceMode.Mode.RESTORE_PREPARING
            )

        val engine = createEngine()
        val result = engine.recordNegotiationOutcome(
            subscriptionId = 1L,
            outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
            newPrice = 9.99,
            savings = 4.0,
            notes = "Negotiated"
        )

        assertTrue("Write barrier blocked should return failure", result.isFailure)
        coVerify(exactly = 0) { recurringExpenseRepository.getById(any()) }
        coVerify(exactly = 0) { negotiationOutcomeDao.insert(any()) }
        coVerify(exactly = 0) { priceHistoryDao.insert(any()) }
        coVerify(exactly = 0) { recurringExpenseRepository.update(any()) }
    }

    @Test
    fun `recordNegotiationOutcome_getByIdFailure_returnsFailure`() = runTest(timeout = 60.seconds) {
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { recurringExpenseRepository.getById(50L) } throws RuntimeException("DB error")

        val engine = createEngine()
        val result = engine.recordNegotiationOutcome(
            subscriptionId = 50L,
            outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
            newPrice = 9.99,
            savings = 4.0,
            notes = "test"
        )

        assertTrue("getById failure should return Result.failure", result.isFailure)
        assertEquals(RuntimeException::class.java, result.exceptionOrNull()?.javaClass)
        assertEquals("DB error", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { negotiationOutcomeDao.insert(any()) }
    }

    // ─────────────────────────────────────────────────────────────────
    // PR1: Currency normalization (lowercase → uppercase)
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `recordNegotiationOutcome_lowercaseEurCurrency_normalizesAndSucceeds`() = runTest(timeout = 60.seconds) {
        val subscription = ManualRecurringExpense(
            id = 50L, merchant = "Netflix", amount = 13.99, currency = "eur",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getById(50L) } returns subscription
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        coEvery { priceHistoryDao.insert(any()) } returns 1L
        coEvery { recurringExpenseRepository.update(any()) } returns Unit

        val engine = createEngine()
        val result = engine.recordNegotiationOutcome(
            subscriptionId = 50L,
            outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
            newPrice = 9.99,
            savings = 4.0,
            notes = "test"
        )

        assertTrue("Lowercase EUR should normalize and succeed", result.isSuccess)
        // Verify normalized currency is persisted
        val slot = slot<NegotiationOutcomeEntity>()
        coVerify { negotiationOutcomeDao.insert(capture(slot)) }
        assertEquals("EUR", slot.captured.currency)
    }

    // ─────────────────────────────────────────────────────────────────
    // PR2: Invalid subscription amounts are skipped (NaN, Inf, zero, negative)
    // ─────────────────────────────────────────────────────────────────

    private data class InvalidAmountRow(val label: String, val id: Long, val amount: Double)

    @Test
    fun `analyze skips subscriptions with invalid amount without consulting provider`() = runTest(timeout = 60.seconds) {
        // E1-REMAINING-002 (SmartBillNegotiationEngine.kt:53-61): NaN, +Inf, zero, negative are skipped.
        val rows = listOf(
            InvalidAmountRow("NaN amount", 60L, Double.NaN),
            InvalidAmountRow("+Infinity amount", 61L, Double.POSITIVE_INFINITY),
            InvalidAmountRow("zero amount", 62L, 0.0),
            InvalidAmountRow("negative amount", 63L, -5.0)
        )
        assertEquals("invalid-amount table size", 4, rows.size)

        for (row in rows) {
            val fx = Fixture()
            coEvery { fx.repo.getAll() } returns listOf(subscription(row.id, "Netflix", row.amount))

            val opportunities = fx.engine.analyzeNegotiationOpportunities()

            assertEquals("[${row.label}] opportunities", emptyList<SmartBillNegotiationEngine.NegotiationOpportunity>(), opportunities)
            coVerify(exactly = 0) { fx.provider.getRates(any(), any(), any()) }
            coVerify(exactly = 0) { fx.priceHistoryDao.getAllPricesForSubscription(any()) }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // PR3: Invalid provider quotes are filtered (zero, NaN, blank name)
    // ─────────────────────────────────────────────────────────────────

    private data class QuoteFilterRow(
        val label: String,
        val id: Long,
        val quotes: List<MarketRateQuote>,
        /** Expected currentProvider of the single opportunity; null = no opportunity expected. */
        val expectedProvider: String?
    )

    @Test
    fun `analyze filters invalid provider quotes before matching`() = runTest(timeout = 60.seconds) {
        // E1-REMAINING-003 (SmartBillNegotiationEngine.kt:107-116): zero, NaN, blank-name quotes are dropped.
        val validNetflix = MarketRateQuote("Netflix", 12.99, 7.99, 6.99, "EUR", "GR", MarketRateConfidence.MEDIUM)
        val rows = listOf(
            QuoteFilterRow("zero competitive price skipped", 70L,
                listOf(MarketRateQuote("Bad Provider", 10.0, 0.0, 5.0, "EUR", "GR", MarketRateConfidence.LOW), validNetflix),
                "Netflix"),
            QuoteFilterRow("NaN average price skipped", 71L,
                listOf(MarketRateQuote("Bad Provider", Double.NaN, 7.99, 5.0, "EUR", "GR", MarketRateConfidence.LOW), validNetflix),
                "Netflix"),
            QuoteFilterRow("blank provider name skipped", 73L,
                listOf(MarketRateQuote("  ", 10.0, 5.0, 4.0, "EUR", "GR", MarketRateConfidence.LOW), validNetflix),
                "Netflix"),
            QuoteFilterRow("only invalid quotes -> no opportunity", 72L,
                listOf(
                    MarketRateQuote("Bad Provider", 0.0, 0.0, 0.0, "EUR", "GR", MarketRateConfidence.LOW),
                    MarketRateQuote("Another Bad", Double.NaN, Double.NaN, Double.NaN, "EUR", "GR", MarketRateConfidence.LOW)
                ),
                null)
        )
        assertEquals("quote-filter table size", 4, rows.size)

        for (row in rows) {
            val fx = Fixture()
            coEvery { fx.repo.getAll() } returns listOf(subscription(row.id, "Netflix", 13.99))
            coEvery { fx.priceHistoryDao.getAllPricesForSubscription(any()) } returns emptyList()
            coEvery { fx.provider.getRates(ServiceType.STREAMING, "GR", "EUR") } returns
                MarketRateResult(quotes = row.quotes, source = "test", lastUpdatedAt = FIXED_NOW)

            val opportunities = fx.engine.analyzeNegotiationOpportunities()

            if (row.expectedProvider == null) {
                assertEquals("[${row.label}] opportunities",
                    emptyList<SmartBillNegotiationEngine.NegotiationOpportunity>(), opportunities)
            } else {
                assertEquals("[${row.label}] opportunity count", 1, opportunities.size)
                assertEquals("[${row.label}] currentProvider", row.expectedProvider, opportunities.single().currentProvider)
                // the dropped invalid quote must not leak into the alternatives list
                assertEquals("[${row.label}] alternativeProviders",
                    emptyList<String>(), opportunities.single().alternativeProviders)
            }
            coVerify(exactly = 1) { fx.provider.getRates(any(), any(), any()) }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // PR4: Unknown provider uses lowest competitive price as fallback
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `unknownProvider_usesLowestCompetitivePriceFallback`() = runTest(timeout = 60.seconds) {
        val subscription = ManualRecurringExpense(
            id = 80L, merchant = "Unknown Streaming Service", amount = 20.0, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getAll() } returns listOf(subscription)
        coEvery { priceHistoryDao.getAllPricesForSubscription(any()) } returns emptyList()

        val provider: MarketRateProvider = mockk()
        coEvery {
            provider.getRates(
                serviceType = com.yourname.expensetracker.domain.negotiation.ServiceType.STREAMING,
                region = "GR",
                currency = "EUR"
            )
        } returns MarketRateResult(
            quotes = listOf(
                MarketRateQuote("Netflix", 13.99, 8.99, 6.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
                MarketRateQuote("Disney+", 11.99, 6.99, 5.99, "EUR", "GR", MarketRateConfidence.MEDIUM)
            ),
            source = "test", lastUpdatedAt = System.currentTimeMillis()
        )

        val engine = createEngine(marketRateProvider = provider)
        val opportunities = engine.analyzeNegotiationOpportunities()

        assertEquals("Should still generate opportunity using fallback", 1, opportunities.size)
        // Lowest competitive price is Disney+ at 6.99
        assertEquals("Disney+", opportunities.first().currentProvider)
    }

}
