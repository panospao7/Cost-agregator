package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarning
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.toExpenseSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * P-06-4: [NetCashflowBalanceProvider] returns a figure only for the home currency and only when
 * every input is trustworthy. Anything else is null (unavailable), never 0. Cancellation always
 * propagates (G-CANCEL-02). Synthetic data only.
 */
class NetCashflowBalanceProviderTest {

    private val now = 1_700_000_000_000L
    private val ninetyDaysAgo = now - 90 * TimePeriodUtils.DAY_IN_MILLIS

    private lateinit var multiCurrencyRepository: MultiCurrencyRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var normalizer: AnalyticsCurrencyNormalizer
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var provider: NetCashflowBalanceProvider

    /** [rows] is the transaction count carried by the single EUR source bucket (0 means no rows). */
    private fun aggregate(amount: Double, partial: Boolean = false, rows: Int = 1) = MoneyAggregate(
        displayAmount = amount,
        displayCurrency = CurrencyCode.EUR,
        sourceBuckets = if (rows > 0) {
            listOf(MoneyBucket(CurrencyCode.EUR, amount, rows))
        } else {
            emptyList()
        },
        conversionFailures = emptyList(),
        isPartial = partial
    )

    private fun withdrawal(id: Long, amount: Double, isNotMine: Boolean = false): ExpenseSnapshot =
        Expense(
            id = id,
            amount = amount,
            merchant = "ATM $id",
            transactionType = TransactionType.WITHDRAWAL,
            date = now - 5 * TimePeriodUtils.DAY_IN_MILLIS,
            categoryId = 1L,
            isNotMine = isNotMine
        ).toExpenseSnapshot()

    /** A clean normalization result carrying [included] rows (peripheral normalizer is a stub). */
    private fun cleanNormalization(included: List<ExpenseSnapshot>): AnalyticsNormalizationResult =
        mockk<AnalyticsNormalizationResult> {
            every { severeWarnings } returns emptyList()
            every { excludedCount } returns 0
            every { includedExpenses } returns included
        }

    @Before
    fun setUp() {
        multiCurrencyRepository = mockk()
        expenseRepository = mockk()
        normalizer = mockk()
        currencySettingsRepository = mockk()
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns aggregate(1000.0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns aggregate(250.5)
        coEvery { expenseRepository.getExpensesBetween(ninetyDaysAgo, now) } returns emptyList()
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns cleanNormalization(emptyList())
        provider = NetCashflowBalanceProvider(
            multiCurrencyRepository,
            expenseRepository,
            normalizer,
            currencySettingsRepository,
            FakeTimeProvider(now)
        )
    }

    // ── Control: the home currency returns the real figure ──────────────────

    @Test
    fun `home currency returns deposits minus purchases over the 90 day window`() = runTest {
        assertEquals(749.5, provider.currentBalance("EUR")!!, 0.0)

        coVerify(exactly = 1) { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) }
        coVerify(exactly = 1) { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) }
    }

    @Test
    fun `home currency match is case insensitive`() = runTest {
        assertEquals(749.5, provider.currentBalance("eur")!!, 0.0)
    }

    @Test
    fun `withdrawals reduce the home currency figure`() = runTest {
        val withdrawals = listOf(withdrawal(1L, 100.0), withdrawal(2L, 49.5))
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns cleanNormalization(withdrawals)

        // 1000 - 250.5 - (100 + 49.5) = 600
        assertEquals(600.0, provider.currentBalance("EUR")!!, 0.0)
    }

    @Test
    fun `not-mine withdrawals do not reduce the figure`() = runTest {
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns
            cleanNormalization(listOf(withdrawal(1L, 100.0, isNotMine = true)))

        assertEquals(749.5, provider.currentBalance("EUR")!!, 0.0)
    }

    @Test
    fun `deposits 1000 and purchases 1500 is unavailable not a clamped zero`() = runTest {
        // Owner-approved change: this test used to pin the clamp to 0.0 for a negative net.
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns aggregate(1000.0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns aggregate(1500.0)

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `deposits 1500 and purchases 1000 returns 500`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns aggregate(1500.0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns aggregate(1000.0)

        assertEquals(500.0, provider.currentBalance("EUR")!!, 0.0)
    }

    @Test
    fun `zero deposit purchase and withdrawal rows is unavailable not zero`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns
            aggregate(0.0, rows = 0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns
            aggregate(0.0, rows = 0)

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `net exactly zero with rows present is a real zero balance`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns aggregate(500.0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns aggregate(500.0)

        val balance = provider.currentBalance("EUR")

        assertNotNull(balance)
        assertEquals(0.0, balance!!, 0.0)
    }

    @Test
    fun `a withdrawal row alone counts as a cashflow row`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns
            aggregate(100.0, rows = 0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns
            aggregate(0.0, rows = 0)
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns
            cleanNormalization(listOf(withdrawal(1L, 100.0)))

        val balance = provider.currentBalance("EUR")

        assertNotNull(balance)
        assertEquals(0.0, balance!!, 0.0)
    }

    @Test
    fun `negative net caused by a withdrawal is unavailable`() = runTest {
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns
            cleanNormalization(listOf(withdrawal(1L, 900.0)))

        // 1000 - 250.5 - 900 < 0
        assertNull(provider.currentBalance("EUR"))
    }

    // ── Unavailable (null), never 0 ─────────────────────────────────────────

    @Test
    fun `non home currency is unavailable and reads nothing`() = runTest {
        assertNull(provider.currentBalance("USD"))

        coVerify(exactly = 0) { multiCurrencyRepository.getHomeCurrencyDepositTotal(any(), any()) }
        coVerify(exactly = 0) { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) }
        coVerify(exactly = 0) { expenseRepository.getExpensesBetween(any(), any()) }
    }

    @Test
    fun `unresolved home currency is unavailable`() = runTest {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Failed("HOME_CURRENCY_UNAVAILABLE")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `home currency resolution failure is unavailable not zero`() = runTest {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } throws IllegalStateException("boom")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `deposit read failure is unavailable not zero`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } throws
            IllegalStateException("boom")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `purchase read failure is unavailable not the deposit total`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } throws
            IllegalStateException("boom")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `both reads failing is unavailable not zero`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } throws
            IllegalStateException("boom")
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } throws
            IllegalStateException("boom")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `partial deposit total is unavailable`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns
            aggregate(1000.0, partial = true)

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `partial purchase total is unavailable`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns
            aggregate(250.5, partial = true)

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `withdrawal read failure is unavailable`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(ninetyDaysAgo, now) } throws IllegalStateException("boom")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `withdrawal normalization failure is unavailable`() = runTest {
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } throws IllegalStateException("boom")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `excluded withdrawal rows are unavailable`() = runTest {
        // One input row that did not survive normalization: excludedCount = 1 - 0.
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns AnalyticsNormalizationResult(
            homeCurrency = "EUR",
            normalizedExpenses = emptyList(),
            includedExpenses = emptyList(),
            warnings = emptyList(),
            latestRateTimestamp = null,
            totalInputCount = 1
        )

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `severe withdrawal warnings are unavailable`() = runTest {
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns mockk<AnalyticsNormalizationResult> {
            every { severeWarnings } returns listOf(mockk<AnalyticsConversionWarning>())
            every { excludedCount } returns 0
            every { includedExpenses } returns emptyList()
        }

        assertNull(provider.currentBalance("EUR"))
    }

    // ── Cancellation propagates unchanged ───────────────────────────────────

    @Test
    fun `cancellation from home currency resolution propagates`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } throws cancellation

        assertSame(cancellation, runCatching { provider.currentBalance("EUR") }.exceptionOrNull())
    }

    @Test
    fun `cancellation from deposit read propagates and skips the purchase read`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } throws cancellation

        assertSame(cancellation, runCatching { provider.currentBalance("EUR") }.exceptionOrNull())
        coVerify(exactly = 0) { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) }
    }

    @Test
    fun `cancellation from purchase read propagates`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } throws cancellation

        assertSame(cancellation, runCatching { provider.currentBalance("EUR") }.exceptionOrNull())
    }

    @Test
    fun `cancellation from withdrawal read propagates`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { expenseRepository.getExpensesBetween(ninetyDaysAgo, now) } throws cancellation

        assertSame(cancellation, runCatching { provider.currentBalance("EUR") }.exceptionOrNull())
    }

    @Test
    fun `cancellation from withdrawal normalization propagates`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } throws cancellation

        assertSame(cancellation, runCatching { provider.currentBalance("EUR") }.exceptionOrNull())
    }
}
