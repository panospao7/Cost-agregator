package com.yourname.expensetracker.domain.subscription

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao
import com.yourname.expensetracker.data.database.dao.SubscriptionPriceHistoryDao
import com.yourname.expensetracker.data.database.dao.SubscriptionUsageDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.SubscriptionCandidate
import com.yourname.expensetracker.data.database.entity.SubscriptionPriceHistory
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SubscriptionManagerEngineTest {

    private lateinit var engine: SubscriptionManagerEngine
    private val database: AppDatabase = mockk(relaxed = true)
    private val recurringExpenseRepository: RecurringExpenseRepository = mockk(relaxed = true)
    private val priceHistoryDao: SubscriptionPriceHistoryDao = mockk(relaxed = true)
    private val usageDao: SubscriptionUsageDao = mockk(relaxed = true)
    private val timeProvider = FakeTimeProvider(1_700_000_000_000L)
    private val currencyConverter: CurrencyConverter = mockk(relaxed = true)
    private val currencySettingsRepository: CurrencySettingsRepository = mockk(relaxed = true)
    private val candidateDao: SubscriptionCandidateDao = mockk(relaxed = true)
    private val writeBarrier: DatabaseWriteBarrier = mockk(relaxed = true)

    private fun createEngine(): SubscriptionManagerEngine {
        return SubscriptionManagerEngine(
            database = database,
            recurringExpenseRepository = recurringExpenseRepository,
            priceHistoryDao = priceHistoryDao,
            usageDao = usageDao,
            timeProvider = timeProvider,
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            candidateDao = candidateDao,
            writeBarrier = writeBarrier
        )
    }

    @Before
    fun setup() {
        // Reset mocks between tests
        clearMocks(database, recurringExpenseRepository, priceHistoryDao, usageDao,
            currencyConverter, currencySettingsRepository, candidateDao, writeBarrier,
            answers = false)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // validateAndCreate — rejection tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `validateAndCreate rejects NaN amount`() = runTest {
        engine = createEngine()
        val request = CreateSubscriptionRequest(
            merchant = "Netflix",
            amount = Double.NaN,
            currency = "USD",
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = 1_000_000L
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.validateAndCreate(request) }
        }
        assertTrue(ex.message?.contains("finite positive amount") == true)
    }

    @Test
    fun `validateAndCreate rejects infinite amount`() = runTest {
        engine = createEngine()
        val request = CreateSubscriptionRequest(
            merchant = "Netflix",
            amount = Double.POSITIVE_INFINITY,
            currency = "USD",
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = 1_000_000L
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.validateAndCreate(request) }
        }
    }

    @Test
    fun `validateAndCreate rejects zero amount`() = runTest {
        engine = createEngine()
        val request = CreateSubscriptionRequest(
            merchant = "Netflix",
            amount = 0.0,
            currency = "USD",
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = 1_000_000L
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.validateAndCreate(request) }
        }
    }

    @Test
    fun `validateAndCreate rejects currency 123`() = runTest {
        engine = createEngine()
        val request = CreateSubscriptionRequest(
            merchant = "Netflix",
            amount = 10.0,
            currency = "123",
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = 1_000_000L
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.validateAndCreate(request) }
        }
        assertTrue(ex.message?.contains("Invalid currency code") == true)
    }

    @Test
    fun `validateAndCreate rejects non-ASCII currency`() = runTest {
        engine = createEngine()
        val request = CreateSubscriptionRequest(
            merchant = "Netflix",
            amount = 10.0,
            currency = "\u20AC\u20AC\u20AC",
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = 1_000_000L
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.validateAndCreate(request) }
        }
    }

    @Test
    fun `validateAndCreate normalizes lowercase currency`() = runTest {
        // withTransaction inline mock removed — mockk(relaxed=true) handles underlying RoomDatabase methods
        engine = createEngine()
        coEvery { recurringExpenseRepository.insert(any()) } returns 1L

        val request = CreateSubscriptionRequest(
            merchant = "Spotify",
            amount = 9.99,
            currency = "usd",
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = 1_000_000L
        )
        val result = engine.validateAndCreate(request)

        assertTrue(result.isSuccess)
        val subscription = result.getOrThrow()
        assertEquals("USD", subscription.currency)
        coVerify { priceHistoryDao.insert(match { it.currency == "USD" }) }
    }

    @Test
    fun `validateAndCreate rejects blank merchant`() = runTest {
        engine = createEngine()
        val request = CreateSubscriptionRequest(
            merchant = "  ",
            amount = 10.0,
            currency = "USD",
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = 1_000_000L
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            runBlocking { engine.validateAndCreate(request) }
        }
        assertTrue(ex.message?.contains("Merchant is required") == true)
    }

    @Test
    fun `validateAndCreate rejects start date zero`() = runTest {
        engine = createEngine()
        val request = CreateSubscriptionRequest(
            merchant = "Netflix",
            amount = 10.0,
            currency = "USD",
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = 0L
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.validateAndCreate(request) }
        }
        assertTrue(ex.message?.contains("Start date must be set") == true)
    }

    @Test
    fun `validateAndCreate succeeds with valid input`() = runTest {
        // withTransaction inline mock removed — mockk(relaxed=true) handles underlying RoomDatabase methods
        engine = createEngine()
        coEvery { recurringExpenseRepository.insert(any()) } returns 1L

        val request = CreateSubscriptionRequest(
            merchant = " Disney+ ",
            amount = 7.99,
            currency = "eur",
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = 1_000_000L
        )
        val result = engine.validateAndCreate(request)

        assertTrue(result.isSuccess)
        val subscription = result.getOrThrow()
        assertEquals("Disney+", subscription.merchant)
        assertEquals("EUR", subscription.currency)
        assertEquals(7.99, subscription.amount, 0.001)
        assertEquals(1L, subscription.id)
        coVerify { recurringExpenseRepository.insert(any()) }
        coVerify { priceHistoryDao.insert(any()) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // acceptCandidate — rejection tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `acceptCandidate rejects invalid currency`() = runTest {
        engine = createEngine()
        val candidate = SubscriptionCandidate(
            id = 1,
            merchant = "Test Merchant",
            canonicalMerchant = "test merchant",
            averageAmount = 15.0,
            currency = "INVALID",
            detectedInterval = "monthly",
            confidence = 0.9,
            transactionCount = 3,
            firstSeen = 1000L,
            lastSeen = 2000L,
            estimatedAnnualCost = 180.0
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.acceptCandidate(candidate, RecurrenceFrequency.MONTHLY, 3000L) }
        }
    }

    @Test
    fun `acceptCandidate rejects infinite average amount`() = runTest {
        engine = createEngine()
        val candidate = SubscriptionCandidate(
            id = 2,
            merchant = "Test Merchant",
            canonicalMerchant = "test merchant",
            averageAmount = Double.POSITIVE_INFINITY,
            currency = "USD",
            detectedInterval = "monthly",
            confidence = 0.9,
            transactionCount = 3,
            firstSeen = 1000L,
            lastSeen = 2000L,
            estimatedAnnualCost = 180.0
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.acceptCandidate(candidate, RecurrenceFrequency.MONTHLY, 3000L) }
        }
    }

    @Test
    fun `acceptCandidate rejects next date zero`() = runTest {
        engine = createEngine()
        val candidate = SubscriptionCandidate(
            id = 3,
            merchant = "Test Merchant",
            canonicalMerchant = "test merchant",
            averageAmount = 15.0,
            currency = "USD",
            detectedInterval = "monthly",
            confidence = 0.9,
            transactionCount = 3,
            firstSeen = 1000L,
            lastSeen = 2000L,
            estimatedAnnualCost = 180.0
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.acceptCandidate(candidate, RecurrenceFrequency.MONTHLY, 0L) }
        }
    }

    @Test
    fun `acceptCandidate succeeds with valid candidate`() = runTest {
        // withTransaction inline mock removed — mockk(relaxed=true) handles underlying RoomDatabase methods
        engine = createEngine()
        coEvery { recurringExpenseRepository.insert(any()) } returns 5L

        val candidate = SubscriptionCandidate(
            id = 4,
            merchant = "  Spotify  ",
            canonicalMerchant = "spotify",
            averageAmount = 9.99,
            currency = "usd",
            detectedInterval = "monthly",
            confidence = 0.95,
            transactionCount = 6,
            firstSeen = 1000L,
            lastSeen = 5000L,
            estimatedAnnualCost = 119.88
        )
        val subscriptionId = engine.acceptCandidate(candidate, RecurrenceFrequency.MONTHLY, 6000L)

        assertEquals(5L, subscriptionId)
        coVerify { recurringExpenseRepository.insert(match { it.merchant == "Spotify" }) }
        coVerify { priceHistoryDao.insert(match { it.currency == "USD" }) }
        coVerify { candidateDao.markAsConverted(4, 5L, timeProvider.now()) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // recordPriceChange — tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `recordPriceChange rejects NaN`() = runTest {
        engine = createEngine()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.recordPriceChange(1L, Double.NaN) }
        }
    }

    @Test
    fun `recordPriceChange rejects infinity`() = runTest {
        engine = createEngine()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.recordPriceChange(1L, Double.POSITIVE_INFINITY) }
        }
    }

    @Test
    fun `recordPriceChange rejects zero or negative`() = runTest {
        engine = createEngine()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.recordPriceChange(1L, 0.0) }
        }
    }

    @Test
    fun `recordPriceChange rejects negative amount`() = runTest {
        engine = createEngine()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.recordPriceChange(1L, -5.0) }
        }
    }

    @Test
    fun `recordPriceChange preserves subscription currency in price history`() = runTest {
        // withTransaction inline mock removed — mockk(relaxed=true) handles underlying RoomDatabase methods
        engine = createEngine()
        val subscription = ManualRecurringExpense(
            id = 10,
            merchant = "Netflix",
            amount = 10.0,
            currency = "USD",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = 1_000_000L,
            isSubscription = true
        )
        coEvery { priceHistoryDao.getLatestPrice(10) } returns null
        coEvery { recurringExpenseRepository.getById(10) } returns subscription
        coEvery { recurringExpenseRepository.getAll() } returns listOf(subscription)
        coEvery { priceHistoryDao.insert(any()) } returns 1L

        engine.recordPriceChange(10, 12.0, "Price increase")

        coVerify {
            priceHistoryDao.insert(match {
                it.currency == "USD" && it.amount == 12.0
            })
        }
    }

    @Test
    fun `recordPriceChange succeeds with valid amount`() = runTest {
        // withTransaction inline mock removed — mockk(relaxed=true) handles underlying RoomDatabase methods
        engine = createEngine()
        val subscription = ManualRecurringExpense(
            id = 20,
            merchant = "Spotify",
            amount = 9.99,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = 1_000_000L,
            isSubscription = true
        )
        coEvery { priceHistoryDao.getLatestPrice(20) } returns null
        coEvery { recurringExpenseRepository.getById(20) } returns subscription
        coEvery { recurringExpenseRepository.getAll() } returns listOf(subscription)

        engine.recordPriceChange(20, 11.99, "Premium increase")

        coVerify { priceHistoryDao.insert(match { it.amount == 11.99 }) }
        coVerify { recurringExpenseRepository.update(match { it.amount == 11.99 }) }
    }

    @Test
    fun `recordPriceChange_missingSubscription_doesNotFallbackToEUR`() = runTest {
        coEvery { recurringExpenseRepository.getById(999L) } returns null
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit

        val engine = createEngine()

        assertFailsWith<IllegalStateException>(
            "Expected IllegalStateException when subscription not found"
        ) {
            engine.recordPriceChange(
                subscriptionId = 999L,
                newAmount = 15.0,
                reason = "Test price change"
            )
        }

        // Verify no DAO operations happen after the early exception
        coVerify(exactly = 0) { priceHistoryDao.insert(any()) }
        coVerify(exactly = 0) { recurringExpenseRepository.update(any()) }
    }

    @Test
    fun `recordPriceChange_validSubscription_usesSubscriptionCurrency`() = runTest {
        val subscription = ManualRecurringExpense(
            id = 1L, merchant = "Netflix", amount = 13.99, currency = "USD",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = System.currentTimeMillis(),
            isSubscription = true, isActive = true
        )
        coEvery { recurringExpenseRepository.getById(1L) } returns subscription
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        coEvery { priceHistoryDao.getLatestPrice(any()) } returns null
        coEvery { recurringExpenseRepository.getAll() } returns listOf(subscription)
        coEvery { priceHistoryDao.insert(any()) } returns 1L
        coEvery { recurringExpenseRepository.update(any()) } returns Unit

        val engine = createEngine()
        engine.recordPriceChange(
            subscriptionId = 1L,
            newAmount = 15.0,
            reason = "Test price change"
        )

        val slot = slot<SubscriptionPriceHistory>()
        coVerify { priceHistoryDao.insert(capture(slot)) }
        assertEquals("USD", slot.captured.currency)
    }
}
