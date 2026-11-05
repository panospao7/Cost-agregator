package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * E-02-4: percentiles and the high-amount threshold are home-currency values.
 * Rewritten from the pre-E-02-4 version, which stubbed the raw
 * getAmountsForPercentileCalc path (no longer used by the calculator).
 */
class SpendingThresholdCalculatorTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var expenseDao: ExpenseDao
    private lateinit var timeProvider: TimeProvider
    private lateinit var rateStore: FakeRateStore
    private lateinit var settings: CurrencySettingsRepository
    private val homeCurrency = MutableStateFlow("EUR")
    private lateinit var calculator: SpendingThresholdCalculator

    @Before
    fun setup() {
        expenseDao = mockk()
        timeProvider = mockk()
        every { timeProvider.now() } returns NOW
        rateStore = FakeRateStore()
        settings = mockk()
        every { settings.homeCurrency() } returns homeCurrency
        calculator = SpendingThresholdCalculator(
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            ioDispatcher = testDispatcher,
            analyticsCurrencyNormalizer = AnalyticsCurrencyNormalizer(CurrencyConverter(rateStore, timeProvider = mockk())),
            currencySettingsRepository = settings
        )
    }

    @Test
    fun `E-02-4 empty history returns the 50 minimum threshold`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns emptyList()

        assertEquals(50.0, calculator.calculateHighAmountThreshold("u"), 0.001)
    }

    @Test
    fun `E-02-4 P90 of ten EUR purchases is interpolated and above the minimum`() = runTest(testDispatcher) {
        val rows = (1..10).map { purchase(it.toLong(), it * 100.0) }
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns rows

        // 100..1000: index 0.9*9 = 8.1 -> 900 + 0.1*100 = 910
        assertEquals(910.0, calculator.calculateHighAmountThreshold("u"), 0.001)
    }

    @Test
    fun `E-02-4 two 50 EUR purchases plus 5000 JPY at 0_0062 give P90 50 not 5000`() = runTest(testDispatcher) {
        rateStore.putRate("JPY", "EUR", 0.0062)
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns listOf(
            purchase(1, 50.0), purchase(2, 50.0), purchase(3, 5000.0, currency = "JPY")
        )

        val percentiles = calculator.calculatePercentiles("u")

        // sorted home values: 31, 50, 50 => P90 = 50 (a raw mixed sample would put P90 near 4000+).
        assertEquals(3, percentiles.sampleSize)
        assertEquals(50.0, percentiles.p90, 0.001)
        assertEquals(50.0, percentiles.p50, 0.001)
    }

    @Test
    fun `E-02-4 threshold with ten samples is not inflated by an unconverted foreign amount`() = runTest(testDispatcher) {
        rateStore.putRate("JPY", "EUR", 0.0062)
        val rows = (1..9).map { purchase(it.toLong(), 50.0) } + purchase(10, 5000.0, currency = "JPY")
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns rows

        // home values: 31 and nine 50s => P90 = 50. Raw mixing would give about 545.
        assertEquals(50.0, calculator.calculateHighAmountThreshold("u"), 0.001)
    }

    @Test
    fun `E-02-4 missing JPY rate throws and no value is cached so a read after the failure window with the rate succeeds`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns listOf(
            purchase(1, 50.0), purchase(2, 50.0), purchase(3, 5000.0, currency = "JPY")
        )

        try {
            calculator.calculatePercentiles("u")
            fail("Expected MoneyDisplayUnavailableException for the missing JPY rate")
        } catch (expected: MoneyDisplayUnavailableException) {
            // fail closed; no percentile value may be cached
        }

        // The failure itself is remembered for FAILURE_WINDOW_MS, so read again after the window.
        rateStore.putRate("JPY", "EUR", 0.0062)
        setClock(NOW + FAILURE_WINDOW_MS)
        val percentiles = calculator.calculatePercentiles("u")

        assertEquals(50.0, percentiles.p90, 0.001)
        coVerify(exactly = 2) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `negative cache two failing calls within 5 minutes read the DAO once and give the same reason`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns missingRateRows()

        val first = unavailable()
        setClock(NOW + FAILURE_WINDOW_MS - 1)
        val second = unavailable()

        assertEquals(first.reasonCode, second.reasonCode)
        coVerify(exactly = 1) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `negative cache a call at 5 minutes reads the DAO again`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns missingRateRows()

        unavailable()
        setClock(NOW + FAILURE_WINDOW_MS)
        unavailable()

        coVerify(exactly = 2) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `negative cache a clock that moved backwards does not reuse the failure`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns missingRateRows()

        unavailable()
        setClock(NOW - 1_000L)
        unavailable()

        coVerify(exactly = 2) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `negative cache a home currency change bypasses the cached failure`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns missingRateRows()
        unavailable()

        rateStore.putRate("JPY", "USD", 0.01)
        rateStore.putRate("EUR", "USD", 2.0)
        homeCurrency.value = "USD"
        setClock(NOW + 1_000L)
        val percentiles = calculator.calculatePercentiles("u")

        assertEquals(2, percentiles.sampleSize)
        coVerify(exactly = 2) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `negative cache refreshThresholds clears the failure inside the window`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns missingRateRows()
        unavailable()

        rateStore.putRate("JPY", "EUR", 0.0062)
        calculator.refreshThresholds("u")
        setClock(NOW + 1_000L)
        val percentiles = calculator.calculatePercentiles("u")

        assertEquals(2, percentiles.sampleSize)
        coVerify(exactly = 2) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `negative cache a later success for another home currency clears the failure entry`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns missingRateRows()
        unavailable() // EUR failure at NOW, read 1

        rateStore.putRate("JPY", "USD", 0.01)
        rateStore.putRate("EUR", "USD", 2.0)
        homeCurrency.value = "USD"
        setClock(NOW + 60_000L)
        calculator.calculatePercentiles("u") // USD success, read 2, must drop the EUR failure entry

        homeCurrency.value = "EUR"
        setClock(NOW + 120_000L)
        unavailable() // still no JPY->EUR rate; reads again (read 3) instead of replaying the old entry

        coVerify(exactly = 3) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `negative cache cancellation is never cached`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } throws
            CancellationException("cancelled") andThen listOf(purchase(1, 100.0))

        try {
            calculator.calculatePercentiles("u")
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            // propagated
        }
        val percentiles = calculator.calculatePercentiles("u")

        assertEquals(1, percentiles.sampleSize)
        coVerify(exactly = 2) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `E-02-4 same home currency reuses the cache within the TTL`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns listOf(purchase(1, 100.0))

        calculator.calculatePercentiles("u")
        calculator.calculatePercentiles("u")

        coVerify(exactly = 1) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `E-02-4 cache is keyed by home currency so changing home recalculates`() = runTest(testDispatcher) {
        rateStore.putRate("EUR", "USD", 2.0)
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns listOf(purchase(1, 100.0))

        val inEur = calculator.calculatePercentiles("u")
        homeCurrency.value = "USD"
        val inUsd = calculator.calculatePercentiles("u")

        assertEquals(100.0, inEur.p90, 0.001)
        assertEquals(200.0, inUsd.p90, 0.001)
        coVerify(exactly = 2) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `refreshThreshold clears cache for default user`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returnsMany listOf(
            tenPurchases(100.0), // P90 = 910
            tenPurchases(200.0) // P90 = 1820
        )

        val threshold1 = calculator.getThreshold()
        val cachedAgain = calculator.getThreshold()
        calculator.refreshThreshold()
        val threshold2 = calculator.getThreshold()

        assertEquals(910.0, threshold1, 0.001)
        assertEquals("inside the TTL and before refresh the cached value is reused", 910.0, cachedAgain, 0.001)
        assertEquals("after refresh the threshold is recalculated from fresh rows", 1820.0, threshold2, 0.001)
        coVerify(exactly = 2) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `calculateHighAmountThreshold returns minimum threshold when insufficient data`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns emptyList()

        val threshold = calculator.calculateHighAmountThreshold("test-user")

        assertEquals("Should return minimum threshold when no data", 50.0, threshold, 0.01)
    }

    @Test
    fun `calculateHighAmountThreshold returns minimum threshold below the ten sample floor even for large amounts`() =
        runTest(testDispatcher) {
            coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
                (1..9).map { purchase(it.toLong(), 1000.0) }

            assertEquals(50.0, calculator.calculateHighAmountThreshold("test-user"), 0.01)
        }

    @Test
    fun `calculateHighAmountThreshold returns P90 for typical spending`() = runTest(testDispatcher) {
        // 10, 20, ..., 100: index 0.9 * 9 = 8.1 -> 90 + 0.1 * 10 = 91
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
            (1..10).map { purchase(it.toLong(), it * 10.0) }

        val threshold = calculator.calculateHighAmountThreshold("test-user")

        assertEquals("P90 of 10..100 is 91 and above the 50 minimum", 91.0, threshold, 0.01)
    }

    @Test
    fun `calculateHighAmountThreshold enforces minimum threshold`() = runTest(testDispatcher) {
        // 5, 10, ..., 50: ten samples, P90 = 45 + 0.1 * 5 = 45.5, which is below the 50 minimum
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
            (1..10).map { purchase(it.toLong(), it * 5.0) }

        val threshold = calculator.calculateHighAmountThreshold("test-user")

        assertEquals("Should return minimum threshold (50) when P90 is below minimum", 50.0, threshold, 0.01)
    }

    @Test
    fun `calculateHighAmountThreshold uses correct user ID for caching`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returnsMany listOf(
            tenPurchases(100.0), // first read, user-1: P90 = 910
            tenPurchases(200.0) // second read, user-2: P90 = 1820
        )

        val user1First = calculator.calculateHighAmountThreshold("user-1")
        val user1Second = calculator.calculateHighAmountThreshold("user-1")
        val user2 = calculator.calculateHighAmountThreshold("user-2")

        assertEquals(910.0, user1First, 0.001)
        assertEquals("Same user gets the cached threshold", 910.0, user1Second, 0.001)
        assertEquals("A different user is not served user-1's cache entry", 1820.0, user2, 0.001)
        coVerify(exactly = 2) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `getThreshold convenience method works for single-user`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns tenPurchases(100.0)

        val threshold = calculator.getThreshold()

        assertEquals("Should return the calculated P90 threshold", 910.0, threshold, 0.001)
        assertEquals("getThreshold shares the cache entry of the default user", 910.0, calculator.getThreshold(), 0.001)
        coVerify(exactly = 1) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `calculatePercentiles computes correct P50 P75 P90`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
            (1..10).map { purchase(it.toLong(), it * 10.0) }

        val percentiles = calculator.calculatePercentiles("test-user")

        assertEquals(10, percentiles.sampleSize)
        assertEquals("P50 of 10..100", 55.0, percentiles.p50, 0.001)
        assertEquals("P75 of 10..100", 77.5, percentiles.p75, 0.001)
        assertEquals("P90 of 10..100", 91.0, percentiles.p90, 0.001)
    }

    @Test
    fun `calculatePercentiles handles single transaction`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns listOf(purchase(1, 100.0))

        val percentiles = calculator.calculatePercentiles("test-user")

        assertEquals(1, percentiles.sampleSize)
        assertEquals("P50 should be 100 for single transaction", 100.0, percentiles.p50, 0.01)
        assertEquals("P90 should be 100 for single transaction", 100.0, percentiles.p90, 0.01)
    }

    @Test
    fun `calculatePercentiles reads normalized rows and not the aggregate percentile DAO path`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
            (1..5).map { purchase(it.toLong(), it * 20.0) }

        calculator.calculatePercentiles("aggregate-check")

        coVerify(exactly = 1) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
        coVerify(exactly = 0) { expenseDao.getAmountsForPercentileCalc(any(), any()) }
    }

    @Test
    fun `refreshThresholds prevents stale in-flight recompute from overwriting cache`() = runTest(testDispatcher) {
        // Regression for ISSUE-2: a compute that started before refreshThresholds() must not
        // write its result back after the invalidation (generation check on write-back).
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } coAnswers {
            reads++
            if (reads == 1) {
                gate.await()
                (1..5).map { purchase(it.toLong(), it * 100.0) } // stale snapshot, 5 samples
            } else {
                (1..3).map { purchase(it.toLong(), it * 10.0) } // post-refresh snapshot, 3 samples
            }
        }

        val inFlight = async { calculator.calculatePercentiles("user-a") }
        runCurrent() // in-flight compute is now suspended inside the DAO read, generation captured
        calculator.refreshThresholds("user-a")
        gate.complete(Unit)
        val staleResult = inFlight.await()

        assertEquals("the in-flight caller still receives its own result", 5, staleResult.sampleSize)

        val afterRefresh = calculator.calculatePercentiles("user-a")

        assertEquals("the stale in-flight result must not have been cached", 3, afterRefresh.sampleSize)
        coVerify(exactly = 2) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `refreshThresholds does not block caching of a compute that starts after the refresh`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns listOf(purchase(1, 100.0))

        calculator.calculatePercentiles("user-a")
        calculator.refreshThresholds("user-a")
        calculator.calculatePercentiles("user-a") // recompute after refresh: write-back is allowed
        calculator.calculatePercentiles("user-a") // served from the cache

        coVerify(exactly = 2) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `expired cache entry is replaced by fresh recompute — TTL semantics preserved`() = runTest(testDispatcher) {
        // Regression for ISSUE-5: an expired (but still present) cache entry must be
        // replaced by the next recompute; the old containsKey guard broke this.
        val stale = run {
            coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
                (1..10).map { purchase(it.toLong(), it * 10.0) }
            calculator.calculatePercentiles("user-b")
        }

        setClock(NOW + 25 * 60 * 60 * 1000L) // older than the 24 h TTL
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
            (1..10).map { purchase(it.toLong(), 400.0 + it * 100.0) }
        val fresh = calculator.calculatePercentiles("user-b")

        assertEquals(91.0, stale.p90, 0.001)
        assertEquals("Fresh compute must replace the expired entry", 1310.0, fresh.p90, 0.001) // 500..1400: 1300 + 0.1 * 100
        coVerify(exactly = 2) { expenseDao.getExpensesBetweenUncapped(any(), any()) }

        // The replacement is itself cached for the new TTL window.
        calculator.calculatePercentiles("user-b")
        coVerify(exactly = 2) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `cache entry just under the 24 hour TTL is still reused`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns listOf(purchase(1, 100.0))

        calculator.calculatePercentiles("user-b")
        setClock(NOW + 24 * 60 * 60 * 1000L - 1)
        calculator.calculatePercentiles("user-b")

        coVerify(exactly = 1) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `E-02-4 CancellationException from the home currency source propagates`() = runTest(testDispatcher) {
        every { settings.homeCurrency() } returns flow { throw CancellationException("cancelled") }

        try {
            calculator.calculatePercentiles("u")
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            // propagated, not converted into MoneyDisplayUnavailableException
        }
    }

    @Test
    fun `E-02-4 home currency failure maps to MoneyDisplayUnavailableException`() = runTest(testDispatcher) {
        every { settings.homeCurrency() } returns flow { throw IllegalStateException("settings down") }

        try {
            calculator.calculatePercentiles("u")
            fail("Expected MoneyDisplayUnavailableException")
        } catch (expected: MoneyDisplayUnavailableException) {
            // fail closed
        }
    }

    @Test
    fun `E-02-4 homeCurrencyAmountOrNull table`() = runTest(testDispatcher) {
        // Home EUR. Same-currency amount is returned as is.
        assertEquals(60.0, calculator.homeCurrencyAmountOrNull(purchase(1, 60.0))!!, 0.0)
        // Foreign amount with a usable EUR base snapshot returns the base amount.
        assertEquals(30.0, calculator.homeCurrencyAmountOrNull(
            purchase(2, 5000.0, currency = "JPY", baseAmount = 30.0, baseCurrency = "EUR")
        )!!, 0.0)
        // Foreign amount with zero base is not comparable.
        assertNull(calculator.homeCurrencyAmountOrNull(
            purchase(3, 5000.0, currency = "JPY", baseAmount = 0.0, baseCurrency = "EUR")
        ))
        // Base snapshot in a different currency than home is not comparable.
        assertNull(calculator.homeCurrencyAmountOrNull(
            purchase(4, 5000.0, currency = "JPY", baseAmount = 60.0, baseCurrency = "USD")
        ))
    }

    @Test
    fun `E-02-4 homeCurrencyAmountOrNull is null when home currency is unavailable and rethrows cancellation`() =
        runTest(testDispatcher) {
            every { settings.homeCurrency() } returns flow { throw IllegalStateException("settings down") }
            assertNull(calculator.homeCurrencyAmountOrNull(purchase(1, 60.0)))

            every { settings.homeCurrency() } returns flow { throw CancellationException("cancelled") }
            try {
                calculator.homeCurrencyAmountOrNull(purchase(1, 60.0))
                fail("CancellationException must propagate")
            } catch (expected: CancellationException) {
                // propagated
            }
        }

    @Test
    fun normalizedThresholdUsesMinimumForSparseInput() {
        assertEquals(50.0, calculator.calculateHighAmountThresholdFromNormalizedAmounts(listOf(10.0, 20.0, 30.0)), 0.01)
    }

    @Test
    fun normalizedThresholdComputesP90WithoutRawDao() {
        val amounts = (1..10).map { it.toDouble() * 100.0 }

        assertEquals(910.0, calculator.calculateHighAmountThresholdFromNormalizedAmounts(amounts), 0.01)
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
        coVerify(exactly = 0) { expenseDao.getAmountsForPercentileCalc(any(), any()) }
    }

    /** Ten EUR purchases of base, 2*base, ..., 10*base. */
    private fun tenPurchases(base: Double) = (1..10).map { purchase(it.toLong(), it * base) }

    /** Reuses the class's mocked clock: later calls see the new time. */
    private fun setClock(now: Long) {
        every { timeProvider.now() } returns now
    }

    /** A EUR row plus a JPY row; with no JPY->EUR rate the home-currency read fails. */
    private fun missingRateRows() = listOf(purchase(1, 50.0), purchase(2, 5000.0, currency = "JPY"))

    private suspend fun unavailable(): MoneyDisplayUnavailableException {
        val failure = runCatching { calculator.calculatePercentiles("u") }.exceptionOrNull()
        assertTrue("expected MoneyDisplayUnavailableException but was $failure", failure is MoneyDisplayUnavailableException)
        return failure as MoneyDisplayUnavailableException
    }

    private fun purchase(
        id: Long,
        amount: Double,
        currency: String = "EUR",
        baseAmount: Double = 0.0,
        baseCurrency: String = "EUR"
    ) = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = "Merchant $id",
        transactionType = TransactionType.PURCHASE,
        date = NOW - 1_000L,
        createdAt = NOW,
        baseAmount = baseAmount,
        baseCurrency = baseCurrency
    )

    private class FakeRateStore : ExchangeRateStore {
        private val rates = mutableMapOf<Pair<String, String>, DomainExchangeRate>()

        fun putRate(from: String, to: String, rate: Double) {
            rates[from to to] = DomainExchangeRate(
                fromCurrency = from, toCurrency = to, rate = rate, lastUpdated = 1234L, validDate = 0L
            )
        }

        override suspend fun getRate(fromCurrency: String, toCurrency: String) = rates[fromCurrency to toCurrency]
        override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String) =
            rates[fromCurrency to toCurrency]
        override suspend fun insertOrUpdate(rate: DomainExchangeRate) {
            rates[rate.fromCurrency to rate.toCurrency] = rate
        }
        override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) = rates.forEach { insertOrUpdate(it) }
        override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long) =
            getRate(fromCurrency, toCurrency)
        override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = emptyFlow()
        override suspend fun getLatestRate(): DomainExchangeRate? = rates.values.maxByOrNull { it.lastUpdated }
        override suspend fun deleteOldRates(olderThan: Long) {
            rates.entries.removeAll { (_, rate) -> rate.lastUpdated < olderThan }
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val FAILURE_WINDOW_MS = 5 * 60 * 1000L
    }
}
