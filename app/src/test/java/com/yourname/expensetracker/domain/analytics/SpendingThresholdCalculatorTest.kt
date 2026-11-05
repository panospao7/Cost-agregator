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
    fun `E-02-4 refreshThreshold forces recalculation`() = runTest(testDispatcher) {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns listOf(purchase(1, 100.0))

        calculator.getThreshold()
        calculator.refreshThreshold()
        calculator.getThreshold()

        coVerify(exactly = 2) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
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
    fun `normalized threshold uses minimum for sparse input`() {
        assertEquals(50.0, calculator.calculateHighAmountThresholdFromNormalizedAmounts(listOf(10.0, 20.0, 30.0)), 0.01)
    }

    @Test
    fun `normalized threshold computes P90 without touching the DAO`() {
        val amounts = (1..10).map { it.toDouble() * 100.0 }

        assertEquals(910.0, calculator.calculateHighAmountThresholdFromNormalizedAmounts(amounts), 0.01)
    }

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
