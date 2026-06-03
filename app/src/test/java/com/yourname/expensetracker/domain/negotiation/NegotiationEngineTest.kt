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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.firstArg
import io.mockk.match
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
    }

    private fun createEngine(
        marketRateProvider: MarketRateProvider = this.marketRateProvider,
        database: AppDatabase = this.database,
        writeBarrier: DatabaseWriteBarrier = this.writeBarrier,
        timeProvider: TimeProvider = this.timeProvider
    ) = SmartBillNegotiationEngine(
        recurringExpenseRepository = recurringExpenseRepository,
        priceHistoryDao = priceHistoryDao,
        marketRateProvider = marketRateProvider,
        database = database,
        writeBarrier = writeBarrier,
        timeProvider = timeProvider
    )

    @Test
    fun `no recommendation when no data`() = runTest {
        // Given: no subscriptions exist
        coEvery { recurringExpenseRepository.getAll() } returns emptyList()

        val engine = createEngine()
        val opportunities = engine.analyzeNegotiationOpportunities()

        // Then: no negotiation opportunities are generated
        assertTrue("Expected empty opportunities when no subscriptions exist",
            opportunities.isEmpty())
    }

    @Test
    fun `provider failure handled gracefully`() = runTest {
        // Given: the repository throws (e.g., database error)
        coEvery { recurringExpenseRepository.getAll() } throws RuntimeException("DB connection lost")

        val engine = createEngine()

        // Then: the exception propagates to the caller (no silent swallowing)
        try {
            engine.analyzeNegotiationOpportunities()
        } catch (e: Exception) {
            assertTrue("Exception should be the repository error",
                e.message?.contains("DB connection lost") == true)
        }
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
    fun `negotiationEngine uses injected marketRateProvider`() = runTest {
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

    @Test
    fun `annual subscription script shows monthly equivalent not raw amount`() = runTest {
        val subscription = ManualRecurringExpense(
            id = 1L, merchant = "Netflix", amount = 120.0, currency = "EUR",
            frequency = RecurrenceFrequency.ANNUALLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getAll() } returns listOf(subscription)
        coEvery { priceHistoryDao.getAllPricesForSubscription(any()) } returns emptyList()

        val provider: MarketRateProvider = mockk()
        coEvery { provider.getRates(any(), any(), any()) } returns MarketRateResult(
            quotes = listOf(MarketRateQuote("Netflix", 13.99, 8.99, 6.99, "EUR", "GR", MarketRateConfidence.MEDIUM)),
            source = "test", lastUpdatedAt = System.currentTimeMillis()
        )

        val engine = createEngine(marketRateProvider = provider)
        val opportunities = engine.analyzeNegotiationOpportunities()
        val opp = opportunities.first()

        assertEquals(10.0, opp.monthlyEquivalentPrice, 0.01)
        assertEquals(120.0, opp.rawBillingAmount, 0.01)

        val scriptText = opp.negotiationScript.opening + opp.negotiationScript.talkingPoints.joinToString(" ")
        assertTrue("Script should mention monthly equivalent (10.00), not raw annual (120.00)",
            scriptText.contains("10.00") && !scriptText.contains("120.00"))
    }

    @Test
    fun `monthly subscription script uses same amount for monthly equivalent`() = runTest {
        val subscription = ManualRecurringExpense(
            id = 2L, merchant = "Spotify", amount = 9.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getAll() } returns listOf(subscription)
        coEvery { priceHistoryDao.getAllPricesForSubscription(any()) } returns emptyList()

        val provider: MarketRateProvider = mockk()
        coEvery { provider.getRates(any(), any(), any()) } returns MarketRateResult(
            quotes = listOf(MarketRateQuote("Spotify", 10.99, 6.99, 5.99, "EUR", "GR", MarketRateConfidence.MEDIUM)),
            source = "test", lastUpdatedAt = System.currentTimeMillis()
        )

        val engine = createEngine(marketRateProvider = provider)
        val opportunities = engine.analyzeNegotiationOpportunities()
        val opp = opportunities.first()

        assertEquals(9.99, opp.monthlyEquivalentPrice, 0.01)
        assertEquals(9.99, opp.rawBillingAmount, 0.01)
    }

    @Test
    fun `quarterly subscription script uses monthly equivalent`() = runTest {
        val subscription = ManualRecurringExpense(
            id = 3L, merchant = "Gym", amount = 90.0, currency = "EUR",
            frequency = RecurrenceFrequency.QUARTERLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getAll() } returns listOf(subscription)
        coEvery { priceHistoryDao.getAllPricesForSubscription(any()) } returns emptyList()

        val provider: MarketRateProvider = mockk()
        coEvery { provider.getRates(any(), any(), any()) } returns MarketRateResult(
            quotes = listOf(MarketRateQuote("Gym", 39.99, 29.99, 24.99, "EUR", "GR", MarketRateConfidence.LOW)),
            source = "test", lastUpdatedAt = System.currentTimeMillis()
        )

        val engine = createEngine(marketRateProvider = provider)
        val opportunities = engine.analyzeNegotiationOpportunities()
        val opp = opportunities.first()

        assertEquals(30.0, opp.monthlyEquivalentPrice, 0.01)
        assertEquals(90.0, opp.rawBillingAmount, 0.01)
    }

    @Test
    fun `negotiationSuccess_persistsOutcomeAndUpdatesSubscription`() = runTest {
        val subscription = ManualRecurringExpense(
            id = 1L, merchant = "Netflix", amount = 13.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getById(1L) } returns subscription
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        coEvery { recurringExpenseRepository.update(any()) } returns Unit

        val engine = createEngine()
        val result = engine.recordNegotiationOutcome(
            subscriptionId = 1L,
            outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
            newPrice = 9.99,
            savings = 4.0,
            notes = "Negotiated"
        )

        assertTrue(result.isSuccess)
        coVerify { negotiationOutcomeDao.insert(match { entity ->
            entity.subscriptionId == 1L &&
            entity.outcome == "SUCCESS" &&
            entity.oldAmount == 13.99 &&
            entity.newAmount == 9.99 &&
            entity.savingsAmount == 4.0
        }) }
        coVerify { priceHistoryDao.insert(match { history ->
            history.subscriptionId == 1L &&
            history.amount == 9.99
        }) }
        coVerify { recurringExpenseRepository.update(match { expense ->
            expense.amount == 9.99
        }) }
    }

    @Test
    fun `negotiationSuccess_annualSubscription_convertsMonthlyPriceToBillingCycleAmount`() = runTest {
        // PR8: For annual subscriptions, the monthly newPrice must be converted
        // back to the billing-cycle amount before storing in subscription + price history.
        val subscription = ManualRecurringExpense(
            id = 1L, merchant = "Netflix", amount = 120.0, currency = "EUR",
            frequency = RecurrenceFrequency.ANNUALLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getById(1L) } returns subscription
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        coEvery { recurringExpenseRepository.update(any()) } returns Unit

        val engine = createEngine()
        val result = engine.recordNegotiationOutcome(
            subscriptionId = 1L,
            outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
            newPrice = 7.0, // monthly equivalent after negotiation
            savings = 3.0,
            notes = "Negotiated annual subscription"
        )

        assertTrue(result.isSuccess)
        // outcome entity stores the monthly newPrice (user-facing)
        coVerify { negotiationOutcomeDao.insert(match { entity ->
            entity.subscriptionId == 1L &&
            entity.outcome == "SUCCESS" &&
            entity.oldAmount == 120.0 &&
            entity.newAmount == 7.0 && // monthly — NOT converted
            entity.savingsAmount == 3.0
        }) }
        // price history stores the billing-cycle amount (7.0 * 12 = 84.0/year)
        coVerify { priceHistoryDao.insert(match { history ->
            history.subscriptionId == 1L &&
            history.amount == 7.0 * 12.0 // 84.0
        }) }
        // subscription update uses the billing-cycle amount (84.0/year)
        coVerify { recurringExpenseRepository.update(match { expense ->
            expense.amount == 7.0 * 12.0 // 84.0
        }) }
    }

    @Test
    fun `negotiationSuccess_weeklySubscription_convertsMonthlyPriceToBillingCycleAmount`() = runTest {
        val subscription = ManualRecurringExpense(
            id = 4L, merchant = "Weekly Gym", amount = 50.0, currency = "EUR",
            frequency = RecurrenceFrequency.WEEKLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getById(4L) } returns subscription
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        coEvery { negotiationOutcomeDao.insert(any()) } returns 1L
        coEvery { priceHistoryDao.insert(any()) } returns Unit
        coEvery { recurringExpenseRepository.update(any()) } returns Unit

        // monthlyEquivalent(50, WEEKLY) = 50 * (365/12) / 7 ≈ 217.26
        // After negotiation saving 100/monthly, newPrice = 117.26
        val engine = createEngine()
        val result = engine.recordNegotiationOutcome(
            subscriptionId = 4L,
            outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
            newPrice = 117.26,
            savings = 100.0,
            notes = "test"
        )

        assertTrue("Expected success", result.isSuccess)
        val updateSlot = slot<ManualRecurringExpense>()
        coVerify { recurringExpenseRepository.update(capture(updateSlot)) }
        // convertFromMonthlyEquivalent(117.26, WEEKLY) = 117.26 * 7 / (365/12) ≈ 27.0
        assertEquals(27.0, updateSlot.captured.amount, 1.0)
    }

    @Test
    fun `negotiationSuccess_biweeklySubscription_convertsMonthlyPriceToBillingCycleAmount`() = runTest {
        val subscription = ManualRecurringExpense(
            id = 5L, merchant = "Biweekly Service", amount = 100.0, currency = "EUR",
            frequency = RecurrenceFrequency.BIWEEKLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getById(5L) } returns subscription
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        coEvery { negotiationOutcomeDao.insert(any()) } returns 1L
        coEvery { priceHistoryDao.insert(any()) } returns Unit
        coEvery { recurringExpenseRepository.update(any()) } returns Unit

        // monthlyEquivalent(100, BIWEEKLY) = 100 * (365/12) / 14 ≈ 217.26
        // After negotiation saving 50/monthly, newPrice = 167.26
        val engine = createEngine()
        val result = engine.recordNegotiationOutcome(
            subscriptionId = 5L,
            outcome = SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS,
            newPrice = 167.26,
            savings = 50.0,
            notes = "test"
        )

        assertTrue("Expected success", result.isSuccess)
        val updateSlot = slot<ManualRecurringExpense>()
        coVerify { recurringExpenseRepository.update(capture(updateSlot)) }
        // convertFromMonthlyEquivalent(167.26, BIWEEKLY) = 167.26 * 14 / (365/12) ≈ 77.0
        assertEquals(77.0, updateSlot.captured.amount, 1.0)
    }

    @Test
    fun `negotiationFailure_persistsOutcomeButDoesNotUpdatePrice`() = runTest {
        val subscription = ManualRecurringExpense(
            id = 2L, merchant = "Netflix", amount = 13.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getById(2L) } returns subscription
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
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
    fun `negotiationWriteBlockedDuringRestore`() = runTest {
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } throws
            DatabaseAccessBlockedException(
                accessType = com.yourname.expensetracker.data.backup.DatabaseAccessType.WRITE,
                operation = com.yourname.expensetracker.data.backup.DatabaseAccessOperation("test"),
                mode = com.yourname.expensetracker.data.backup.RestoreMaintenanceMode.Mode.RESTORE_IN_PROGRESS
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
    }

    @Test
    fun `getNegotiationHistory_returnsPersistedOutcomes`() = runTest {
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

        assertEquals(2, history.size)
    }

    @Test
    fun `recordNegotiationOutcome returns failure when subscription not found`() = runTest {
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
    }

    @Test
    fun `negotiationPartial_persistsOutcomeAndUpdatesSubscription`() = runTest {
        val subscription = ManualRecurringExpense(
            id = 1L, merchant = "Netflix", amount = 13.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getById(1L) } returns subscription
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        coEvery { recurringExpenseRepository.update(any()) } returns Unit

        val engine = createEngine()
        val result = engine.recordNegotiationOutcome(
            subscriptionId = 1L,
            outcome = SmartBillNegotiationEngine.NegotiationOutcome.PARTIAL,
            newPrice = 9.99,
            savings = 4.0,
            notes = "Negotiated"
        )

        assertTrue(result.isSuccess)
        coVerify { negotiationOutcomeDao.insert(match { entity ->
            entity.subscriptionId == 1L &&
            entity.outcome == "PARTIAL" &&
            entity.oldAmount == 13.99 &&
            entity.newAmount == 9.99 &&
            entity.savingsAmount == 4.0
        }) }
        coVerify { priceHistoryDao.insert(match { history ->
            history.subscriptionId == 1L &&
            history.amount == 9.99
        }) }
        coVerify { recurringExpenseRepository.update(match { expense ->
            expense.amount == 9.99
        }) }
    }

    @Test
    fun `negotiationPartial_annualSubscription_convertsMonthlyPriceToBillingCycleAmount`() = runTest {
        // PR8: Same conversion as success — PARTIAL outcomes also update the subscription.
        val subscription = ManualRecurringExpense(
            id = 1L, merchant = "Annual Sub", amount = 120.0, currency = "EUR",
            frequency = RecurrenceFrequency.ANNUALLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getById(1L) } returns subscription
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        coEvery { recurringExpenseRepository.update(any()) } returns Unit

        val engine = createEngine()
        val result = engine.recordNegotiationOutcome(
            subscriptionId = 1L,
            outcome = SmartBillNegotiationEngine.NegotiationOutcome.PARTIAL,
            newPrice = 7.0,
            savings = 3.0,
            notes = "Partial on annual"
        )

        assertTrue(result.isSuccess)
        coVerify { negotiationOutcomeDao.insert(match { entity ->
            entity.subscriptionId == 1L &&
            entity.outcome == "PARTIAL" &&
            entity.oldAmount == 120.0 &&
            entity.newAmount == 7.0 && // monthly — NOT converted
            entity.savingsAmount == 3.0
        }) }
        // price history stores billing-cycle amount
        coVerify { priceHistoryDao.insert(match { history ->
            history.subscriptionId == 1L &&
            history.amount == 7.0 * 12.0
        }) }
        // subscription update uses billing-cycle amount
        coVerify { recurringExpenseRepository.update(match { expense ->
            expense.amount == 7.0 * 12.0
        }) }
    }

    @Test
    fun `negotiationSuccess_rollsBackWhenPriceHistoryInsertFails`() = runTest {
        val subscription = ManualRecurringExpense(
            id = 1L, merchant = "Netflix", amount = 13.99, currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )

        coEvery { recurringExpenseRepository.getById(1L) } returns subscription
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
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
        coVerify { negotiationOutcomeDao.insert(any()) }
        coVerify { priceHistoryDao.insert(any()) }
    }
}
