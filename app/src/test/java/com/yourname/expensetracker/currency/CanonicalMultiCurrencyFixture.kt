package com.yourname.expensetracker.currency

import com.yourname.expensetracker.data.database.dao.CurrencyTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.core.money.ConversionFailure
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.FailureReason
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// =============================================================================
// Gap D.2 — Canonical Multi-Currency Test Fixture
//
// This fixture is the "golden source" for verifying all future multi-currency
// fixes.  It demonstrates that 50 EUR + 100 USD at a rate of 0.92 = 142 EUR,
// NOT 150 (which is what raw sumOf { it.effectiveAmount } would produce).
//
// Usage:
//   val expenses = CanonicalMultiCurrencyFixture.createTestExpenses()
//   // ... wire up repository with fakes below ...
//   val aggregate = repository.getHomeCurrencyPurchaseTotal(start, end)
//   CanonicalMultiCurrencyFixture.verifyMoneyAggregate(aggregate, 142.0, CurrencyCode.EUR)
// =============================================================================

/**
 * Canonical multi-currency test fixture providing constants, test expenses,
 * and assertion helpers for verifying currency-aware aggregation.
 *
 * Scenario:
 *   Expense A:  50 EUR  (effectiveAmount = 50.0,  currency = "EUR")
 *   Expense B: 100 USD  (effectiveAmount = 100.0, currency = "USD")
 *   Exchange rate: 1 USD = 0.92 EUR
 *
 * Expected results:
 *   - Currency-aware total in EUR: 50 + (100 × 0.92) = 142 EUR
 *   - Raw wrong sum (mixed currency): 50 + 100 = 150 (must never appear in output)
 *   - Per-currency buckets: {EUR=50.0, USD=100.0}
 */
object CanonicalMultiCurrencyFixture {

    // ── Amounts ─────────────────────────────────────────────────────────────
    const val EUR_AMOUNT = 50.0
    const val USD_AMOUNT = 100.0

    // ── Exchange rate ───────────────────────────────────────────────────────
    const val USD_TO_EUR_RATE = 0.92

    // ── Expected correct total after conversion ─────────────────────────────
    // 50 EUR + (100 USD × 0.92) = 50 + 92 = 142
    const val EXPECTED_EUR_TOTAL = 142.0

    // ── Raw incorrect sum (must NEVER appear in output) ─────────────────────
    // 50 EUR + 100 USD = 150   ← what raw sumOf { it.effectiveAmount } gives
    const val RAW_WRONG_SUM = 150.0

    // ── Currency constants ──────────────────────────────────────────────────
    const val HOME_CURRENCY = "EUR"
    const val FOREIGN_CURRENCY = "USD"
    val HOME_CURRENCY_CODE = CurrencyCode(HOME_CURRENCY)
    val FOREIGN_CURRENCY_CODE = CurrencyCode(FOREIGN_CURRENCY)

    // ── Default date range ──────────────────────────────────────────────────
    const val START_DATE = 1700000000000L
    const val END_DATE = 1700086400000L // +24 hours

    // ── Test expense factories ──────────────────────────────────────────────

    /**
     * Create the two canonical test expenses (50 EUR + 100 USD).
     * All fields are populated with reasonable defaults.
     */
    fun createTestExpenses(
        eurId: Long = 1L,
        usdId: Long = 2L,
        date: Long = START_DATE
    ): List<Expense> = listOf(
        Expense(
            id = eurId,
            amount = EUR_AMOUNT,
            currency = HOME_CURRENCY,
            merchant = "Test Merchant EUR",
            transactionType = TransactionType.PURCHASE,
            date = date,
            rawNotificationId = null,
            categoryId = null,
            createdAt = date,
            paymentMethod = PaymentMethod.CARD,
            isManualEntry = false,
            notes = null,
            dedupeKey = "canonical-fixture-eur-$eurId",
            transferDirection = null,
            transferAccountName = null,
            isNotMine = false,
            ownerName = null,
            isSharedExpense = false,
            sharedWithName = null,
            mySharePercentage = null,
            myShareAmount = null,
            latitude = null,
            longitude = null,
            locationSource = null,
            placeId = null,
            backfillAttempts = 0,
            resolvedAddress = null,
            merchantKey = "canonical-merchant-eur",
            isBusinessExpense = false,
            businessPurpose = null,
            businessCategory = null,
            businessProject = null,
            requiresReceipt = false,
            splitTemplateId = null,
            splitVisualization = null
        ),
        Expense(
            id = usdId,
            amount = USD_AMOUNT,
            currency = FOREIGN_CURRENCY,
            merchant = "Test Merchant USD",
            transactionType = TransactionType.PURCHASE,
            date = date,
            rawNotificationId = null,
            categoryId = null,
            createdAt = date,
            paymentMethod = PaymentMethod.CARD,
            isManualEntry = false,
            notes = null,
            dedupeKey = "canonical-fixture-usd-$usdId",
            transferDirection = null,
            transferAccountName = null,
            isNotMine = false,
            ownerName = null,
            isSharedExpense = false,
            sharedWithName = null,
            mySharePercentage = null,
            myShareAmount = null,
            latitude = null,
            longitude = null,
            locationSource = null,
            placeId = null,
            backfillAttempts = 0,
            resolvedAddress = null,
            merchantKey = "canonical-merchant-usd",
            isBusinessExpense = false,
            businessPurpose = null,
            businessCategory = null,
            businessProject = null,
            requiresReceipt = false,
            splitTemplateId = null,
            splitVisualization = null
        )
    )

    // ── Verification helpers ────────────────────────────────────────────────

    /**
     * Assert that a [MoneyAggregate] has the expected display amount and currency,
     * is not partial, and has sensible source buckets.
     */
    fun verifyMoneyAggregate(
        aggregate: MoneyAggregate,
        expectedDisplayAmount: Double,
        expectedDisplayCurrency: CurrencyCode
    ) {
        assertEquals(
            "Display amount mismatch: expected $expectedDisplayAmount but got ${aggregate.displayAmount}",
            expectedDisplayAmount,
            aggregate.displayAmount,
            0.01
        )
        assertEquals(
            "Display currency mismatch: expected $expectedDisplayCurrency but got ${aggregate.displayCurrency}",
            expectedDisplayCurrency,
            aggregate.displayCurrency
        )
        assertFalse("Aggregate should not be partial, but isPartial = true", aggregate.isPartial)
        assertTrue(
            "Should have 1-2 source buckets, but got ${aggregate.sourceBuckets.size}",
            aggregate.sourceBuckets.size in 1..2
        )
        assertTrue(
            "Conversion failures should be empty, but got ${aggregate.conversionFailures}",
            aggregate.conversionFailures.isEmpty()
        )
        // Verify source buckets sum matches raw total (pre-conversion)
        val rawBucketSum = aggregate.sourceBuckets.sumOf { it.amount }
        assertEquals(
            "Raw conversion result ($expectedDisplayAmount) should be close to raw bucket sum ($rawBucketSum) " +
                "when one currency is the same as display currency",
            expectedDisplayAmount,
            rawBucketSum,
            USD_AMOUNT * (1.0 - USD_TO_EUR_RATE) + 0.01 // allow for USD→EUR discount
        )
    }

    /**
     * Verify that the raw (incorrect) sum of [Expense.effectiveAmount] equals
     * [RAW_WRONG_SUM].  This is a regression check: if this starts passing
     * with a different value, the test expenses may have changed.
     */
    fun verifyRawSumIsWrong(expenses: List<Expense>) {
        val rawSum = expenses.sumOf { it.effectiveAmount }
        assertEquals(
            "Raw sum of effectiveAmount (mixed currencies) should be $RAW_WRONG_SUM " +
                "for regression comparison, but got $rawSum",
            RAW_WRONG_SUM,
            rawSum,
            0.01
        )
    }

    /** Convenience: create both expenses and verify raw sum is wrong. */
    fun createTestExpensesAndVerifyRawSum(
        eurId: Long = 1L,
        usdId: Long = 2L,
        date: Long = START_DATE
    ): List<Expense> {
        val expenses = createTestExpenses(eurId, usdId, date)
        verifyRawSumIsWrong(expenses)
        return expenses
    }

    // ── Bucket helpers ──────────────────────────────────────────────────────

    /** Per-currency [MoneyBucket] list matching the canonical expenses. */
    fun expectedSourceBuckets(): List<MoneyBucket> = listOf(
        MoneyBucket(HOME_CURRENCY_CODE, EUR_AMOUNT, 1),
        MoneyBucket(FOREIGN_CURRENCY_CODE, USD_AMOUNT, 1)
    )

    /** Amount pairs ready for [CurrencyConverter.convertMultiple]. */
    fun amountsWithCurrencies(): List<Pair<Double, String>> = listOf(
        EUR_AMOUNT to HOME_CURRENCY,
        USD_AMOUNT to FOREIGN_CURRENCY
    )
}

// =============================================================================
// Fake implementations for testing
// =============================================================================

/**
 * Fake [ExchangeRateStore] that returns pre-configured rates.
 *
 * The default configuration provides:
 *   - USD → EUR = 0.92
 *   - EUR → USD = 1.0 / 0.92 ≈ 1.0869565...
 *
 * Add additional rates via [rates] map.
 */
class FakeExchangeRateStore(
    private val rates: Map<Pair<String, String>, Double> = mapOf<Pair<String, String>, Double>(
        (CanonicalMultiCurrencyFixture.FOREIGN_CURRENCY to CanonicalMultiCurrencyFixture.HOME_CURRENCY) to CanonicalMultiCurrencyFixture.USD_TO_EUR_RATE,
        (CanonicalMultiCurrencyFixture.HOME_CURRENCY to CanonicalMultiCurrencyFixture.FOREIGN_CURRENCY) to (1.0 / CanonicalMultiCurrencyFixture.USD_TO_EUR_RATE)
    )
) : ExchangeRateStore {

    private val storedRates = rates.map { (pair, rate) ->
        DomainExchangeRate(
            fromCurrency = pair.first,
            toCurrency = pair.second,
            rate = rate,
            lastUpdated = System.currentTimeMillis(),
            source = "test-fixture"
        )
    }.associateBy { it.fromCurrency.uppercase() to it.toCurrency.uppercase() }
        .toMutableMap()

    override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
        return storedRates[fromCurrency.uppercase() to toCurrency.uppercase()]
    }

    override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? {
        return storedRates[fromCurrency.uppercase() to toCurrency.uppercase()]
    }

    override suspend fun insertOrUpdate(rate: DomainExchangeRate) {
        storedRates[rate.fromCurrency.uppercase() to rate.toCurrency.uppercase()] = rate
    }

    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {
        rates.forEach { insertOrUpdate(it) }
    }

    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> {
        val matching = storedRates.values.filter {
            it.toCurrency.uppercase() == targetCurrency.uppercase()
        }
        return flowOf(matching)
    }

    override suspend fun getLatestRate(): DomainExchangeRate? {
        return storedRates.values.maxByOrNull { it.lastUpdated }
    }

    override suspend fun deleteOldRates(olderThan: Long) {
        storedRates.entries.removeAll { it.value.lastUpdated < olderThan }
    }
}

/**
 * Fake [CurrencySettingsRepository] with controllable home currency.
 *
 * Defaults to "EUR".  Call [setHomeCurrency] to change at any point in a test.
 */
class FakeCurrencySettingsRepository(
    initialHomeCurrency: String = CanonicalMultiCurrencyFixture.HOME_CURRENCY
) : CurrencySettingsRepository {

    private val homeCurrencyFlow = MutableStateFlow(initialHomeCurrency)
    private val lastRateUpdateFlow = MutableStateFlow(0L)

    override fun homeCurrency(): Flow<String> = homeCurrencyFlow

    override suspend fun setHomeCurrency(currencyCode: String) {
        homeCurrencyFlow.value = currencyCode.uppercase()
    }

    override fun lastRateUpdate(): Flow<Long> = lastRateUpdateFlow

    override suspend fun setLastRateUpdate(timestamp: Long) {
        lastRateUpdateFlow.value = timestamp
    }

    override suspend fun areRatesStale(thresholdMs: Long): Boolean {
        val lastUpdate = lastRateUpdateFlow.value
        val now = System.currentTimeMillis()
        return lastUpdate == 0L || (now - lastUpdate) > thresholdMs
    }

    override fun emergencyBuffer(): Flow<Double> = MutableStateFlow(500.0)

    override suspend fun setEmergencyBuffer(amount: Double) {
        // no-op for tests
    }

    override suspend fun clear() {
        homeCurrencyFlow.value = CanonicalMultiCurrencyFixture.HOME_CURRENCY
        lastRateUpdateFlow.value = 0L
    }
}

/**
 * Convenience function to create a [MultiCurrencyRepository] wired with fakes.
 *
 * This is the easiest way to test the canonical multi-currency scenario:
 * ```
 * val repo = createCanonicalRepository()
 * val aggregate = repo.getHomeCurrencyPurchaseTotal(START_DATE, END_DATE)
 * CanonicalMultiCurrencyFixture.verifyMoneyAggregate(aggregate, 142.0, CurrencyCode.EUR)
 * ```
 *
 * @param eurAmount  total EUR amount in the DAO (default 50.0)
 * @param usdAmount  total USD amount in the DAO (default 100.0)
 * @param eurTxCount number of EUR transactions (default 1)
 * @param usdTxCount number of USD transactions (default 1)
 * @param homeCurrency home currency code (default "EUR")
 * @param fixedTime fixed timestamp for FakeTimeProvider
 * @param usdToEurRate exchange rate USD → EUR (default 0.92)
 */
fun createCanonicalRepository(
    eurAmount: Double = CanonicalMultiCurrencyFixture.EUR_AMOUNT,
    usdAmount: Double = CanonicalMultiCurrencyFixture.USD_AMOUNT,
    eurTxCount: Int = 1,
    usdTxCount: Int = 1,
    homeCurrency: String = CanonicalMultiCurrencyFixture.HOME_CURRENCY,
    fixedTime: Long = CanonicalMultiCurrencyFixture.START_DATE,
    usdToEurRate: Double = CanonicalMultiCurrencyFixture.USD_TO_EUR_RATE
): MultiCurrencyRepository {

    // ── Fake DAO returning hardcoded CurrencyTotal rows ──────────────────
    val expenseDao = mockk<ExpenseDao>(relaxUnitFun = true)
    coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(
        CurrencyTotal(CanonicalMultiCurrencyFixture.HOME_CURRENCY, eurAmount, eurTxCount),
        CurrencyTotal(CanonicalMultiCurrencyFixture.FOREIGN_CURRENCY, usdAmount, usdTxCount)
    )
    coEvery { expenseDao.getAllSpentBetweenByCurrency(any(), any()) } returns listOf(
        CurrencyTotal(CanonicalMultiCurrencyFixture.HOME_CURRENCY, eurAmount, eurTxCount),
        CurrencyTotal(CanonicalMultiCurrencyFixture.FOREIGN_CURRENCY, usdAmount, usdTxCount)
    )

    // ── Fake CurrencySettingsRepository ──────────────────────────────────
    val settingsRepo = FakeCurrencySettingsRepository(initialHomeCurrency = homeCurrency)

    // ── Fake ExchangeRateStore → real CurrencyConverter ──────────────────
    val exchangeRateStore = FakeExchangeRateStore(
        rates = mapOf(
            (CanonicalMultiCurrencyFixture.FOREIGN_CURRENCY to homeCurrency) to usdToEurRate,
            (homeCurrency to CanonicalMultiCurrencyFixture.FOREIGN_CURRENCY) to (1.0 / usdToEurRate)
        )
    )
    val currencyConverter = CurrencyConverter(exchangeRateStore, FakeTimeProvider(fixedTime = fixedTime))

    // ── FakeTimeProvider ─────────────────────────────────────────────────
    val timeProvider = FakeTimeProvider(fixedTime = fixedTime)

    return MultiCurrencyRepository(
        expenseDao = expenseDao,
        currencyConverter = currencyConverter,
        timeProvider = timeProvider,
        currencySettingsRepository = settingsRepo
    )
}

// =============================================================================
// Part B: The Actual Test
// =============================================================================

/**
 * Test that verifies the canonical multi-currency conversion scenario:
 * 50 EUR + 100 USD at 0.92 rate = 142 EUR (not 150).
 */
class CanonicalMultiCurrencyFixtureTest {

    @Test
    fun `multi-currency conversion 50 EUR + 100 USD at 0_92 rate equals 142 EUR`() = runTest {
        // 1. Create test expenses and verify raw sum is wrong (regression check)
        val expenses = CanonicalMultiCurrencyFixture.createTestExpenses()
        CanonicalMultiCurrencyFixture.verifyRawSumIsWrong(expenses)
        assertEquals(2, expenses.size)
        assertEquals(CanonicalMultiCurrencyFixture.EUR_AMOUNT, expenses[0].amount, 0.01)
        assertEquals(CanonicalMultiCurrencyFixture.USD_AMOUNT, expenses[1].amount, 0.01)

        // 2. Create the fully-wired repository with all fakes
        val repository = createCanonicalRepository()

        // 3. Get purchase total (PURCHASE-filtered variant)
        val aggregate = repository.getHomeCurrencyPurchaseTotal(
            startDate = CanonicalMultiCurrencyFixture.START_DATE,
            endDate = CanonicalMultiCurrencyFixture.END_DATE
        )

        // 4. Verify the aggregate
        CanonicalMultiCurrencyFixture.verifyMoneyAggregate(
            aggregate = aggregate,
            expectedDisplayAmount = CanonicalMultiCurrencyFixture.EXPECTED_EUR_TOTAL,
            expectedDisplayCurrency = CanonicalMultiCurrencyFixture.HOME_CURRENCY_CODE
        )

        // 5. Explicit assertions for clarity
        assertEquals(
            "Converted total should be 142.0 EUR, not 150.0 (which is raw sumOf)",
            CanonicalMultiCurrencyFixture.EXPECTED_EUR_TOTAL,
            aggregate.displayAmount,
            0.01
        )
        assertEquals(
            "Display currency should be EUR",
            CanonicalMultiCurrencyFixture.HOME_CURRENCY_CODE,
            aggregate.displayCurrency
        )
        assertFalse("No conversion failures expected", aggregate.isPartial)
        assertTrue(
            "Should have 2 source buckets (EUR and USD), got ${aggregate.sourceBuckets.size}",
            aggregate.sourceBuckets.size == 2
        )

        // 6. Verify source buckets match expectations
        val eurBucket = aggregate.sourceBuckets.find { it.currency == CurrencyCode.EUR }
        val usdBucket = aggregate.sourceBuckets.find { it.currency == CurrencyCode.USD }

        assertTrue("EUR bucket must exist", eurBucket != null)
        assertTrue("USD bucket must exist", usdBucket != null)
        assertEquals(CanonicalMultiCurrencyFixture.EUR_AMOUNT, eurBucket!!.amount, 0.01)
        assertEquals(CanonicalMultiCurrencyFixture.USD_AMOUNT, usdBucket!!.amount, 0.01)
        assertEquals(1, eurBucket.transactionCount)
        assertEquals(1, usdBucket.transactionCount)
    }

    @Test
    fun `getHomeCurrencyTotal also returns correct converted total`() = runTest {
        // The type-agnostic variant should give the same result
        // when all expenses are PURCHASE
        val repository = createCanonicalRepository()

        val aggregate = repository.getHomeCurrencyTotal(
            startDate = CanonicalMultiCurrencyFixture.START_DATE,
            endDate = CanonicalMultiCurrencyFixture.END_DATE
        )

        assertEquals(
            CanonicalMultiCurrencyFixture.EXPECTED_EUR_TOTAL,
            aggregate.displayAmount,
            0.01
        )
        assertEquals(
            CanonicalMultiCurrencyFixture.HOME_CURRENCY_CODE,
            aggregate.displayCurrency
        )
    }

    @Test
    fun `raw sumOf effectiveAmount equals 150 for regression comparison`() {
        val expenses = CanonicalMultiCurrencyFixture.createTestExpenses()
        val rawSum = expenses.sumOf { it.effectiveAmount }
        assertEquals(
            "Raw sum should be 150.0 (demonstrating the bug)",
            CanonicalMultiCurrencyFixture.RAW_WRONG_SUM,
            rawSum,
            0.01
        )
    }

    @Test
    fun `createTestExpensesAndVerifyRawSum returns expenses and checks raw sum`() {
        val expenses = CanonicalMultiCurrencyFixture.createTestExpensesAndVerifyRawSum()
        assertEquals(2, expenses.size)
    }

    @Test
    fun `FakeExchangeRateStore returns configured rates`() = runTest {
        val store = FakeExchangeRateStore()

        val usdToEur = store.getRate("USD", "EUR")
        assertEquals(CanonicalMultiCurrencyFixture.USD_TO_EUR_RATE, usdToEur!!.rate, 0.0001)
        assertEquals("USD", usdToEur.fromCurrency)
        assertEquals("EUR", usdToEur.toCurrency)

        val eurToUsd = store.getRate("EUR", "USD")
        val expectedInverse = 1.0 / CanonicalMultiCurrencyFixture.USD_TO_EUR_RATE
        assertEquals(expectedInverse, eurToUsd!!.rate, 0.0001)
        assertEquals("EUR", eurToUsd.fromCurrency)
        assertEquals("USD", eurToUsd.toCurrency)

        val unknown = store.getRate("GBP", "EUR")
        assertEquals(null, unknown)
    }

    @Test
    fun `FakeCurrencySettingsRepository defaults to EUR`() = runTest {
        val repo = FakeCurrencySettingsRepository()
        val currency = repo.homeCurrency().first()
        assertEquals(CanonicalMultiCurrencyFixture.HOME_CURRENCY, currency)
    }

    @Test
    fun `empty repository returns zero aggregate`() = runTest {
        val expenseDao = mockk<ExpenseDao>(relaxUnitFun = true)
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns emptyList()
        coEvery { expenseDao.getAllSpentBetweenByCurrency(any(), any()) } returns emptyList()

        val settingsRepo = FakeCurrencySettingsRepository()
        val exchangeRateStore = FakeExchangeRateStore()
        val timeProvider = FakeTimeProvider()
        val currencyConverter = CurrencyConverter(exchangeRateStore, timeProvider)

        val repo = MultiCurrencyRepository(
            expenseDao = expenseDao,
            currencyConverter = currencyConverter,
            timeProvider = timeProvider,
            currencySettingsRepository = settingsRepo
        )

        val aggregate = repo.getHomeCurrencyPurchaseTotal(0L, 9999999999999L)
        assertEquals(0.0, aggregate.displayAmount, 0.01)
        assertTrue("Source buckets should be empty for zero total", aggregate.sourceBuckets.isEmpty())
        assertFalse("Empty aggregate should not be partial", aggregate.isPartial)
    }
}
