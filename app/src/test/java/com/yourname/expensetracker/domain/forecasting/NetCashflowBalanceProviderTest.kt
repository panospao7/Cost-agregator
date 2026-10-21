package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B1-5: NetCashflowBalanceProvider must keep the 0.0 fallback for ordinary failures but
 * must never convert cancellation into that fallback (G-CANCEL-02).
 */
class NetCashflowBalanceProviderTest {

    private val now = 1_700_000_000_000L
    private val ninetyDaysAgo = now - 90 * TimePeriodUtils.DAY_IN_MILLIS
    private val repository: MultiCurrencyRepository = mockk()
    private val provider = NetCashflowBalanceProvider(repository, FakeTimeProvider(now))

    private fun aggregate(amount: Double) = MoneyAggregate(
        displayAmount = amount,
        displayCurrency = CurrencyCode.EUR,
        sourceBuckets = emptyList(),
        conversionFailures = emptyList()
    )

    @Test
    fun `currentBalance returns deposits minus expenses over the 90 day window`() = runTest {
        coEvery { repository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns aggregate(1000.0)
        coEvery { repository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns aggregate(250.5)

        assertEquals(749.5, provider.currentBalance("EUR")!!, 0.0)

        coVerify(exactly = 1) { repository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) }
        coVerify(exactly = 1) { repository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) }
    }

    @Test
    fun `currentBalance coerces negative net cashflow to zero`() = runTest {
        coEvery { repository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns aggregate(100.0)
        coEvery { repository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns aggregate(300.0)

        assertEquals(0.0, provider.currentBalance("EUR")!!, 0.0)
    }

    @Test
    fun `currentBalance falls back to zero deposits when deposit lookup fails with non-cancellation`() = runTest {
        coEvery { repository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } throws IllegalStateException("boom")
        coEvery { repository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns aggregate(40.0)

        // deposits fallback 0.0 - expenses 40.0 -> coerced to 0.0
        assertEquals(0.0, provider.currentBalance("EUR")!!, 0.0)
    }

    @Test
    fun `currentBalance falls back to zero expenses when expense lookup fails with non-cancellation`() = runTest {
        coEvery { repository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns aggregate(120.0)
        coEvery { repository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } throws IllegalStateException("boom")

        // deposits 120.0 - expenses fallback 0.0
        assertEquals(120.0, provider.currentBalance("EUR")!!, 0.0)
    }

    @Test
    fun `currentBalance returns zero when both lookups fail with non-cancellation`() = runTest {
        coEvery { repository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } throws IllegalStateException("boom")
        coEvery { repository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } throws IllegalStateException("boom")

        assertEquals(0.0, provider.currentBalance("EUR")!!, 0.0)
    }

    @Test
    fun `currentBalance rethrows CancellationException from deposit lookup`() = runTest {
        coEvery { repository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } throws CancellationException("cancelled")
        coEvery { repository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns aggregate(10.0)

        var thrown: CancellationException? = null
        try {
            provider.currentBalance("EUR")
            fail("Expected CancellationException, but currentBalance returned")
        } catch (e: CancellationException) {
            thrown = e
        }
        assertNotNull(thrown)
        assertEquals("cancelled", thrown!!.message)
        coVerify(exactly = 0) { repository.getHomeCurrencyPurchaseTotal(any(), any()) }
    }

    @Test
    fun `currentBalance rethrows CancellationException from expense lookup`() = runTest {
        coEvery { repository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns aggregate(500.0)
        coEvery { repository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } throws CancellationException("cancelled")

        var thrown: CancellationException? = null
        try {
            provider.currentBalance("EUR")
            fail("Expected CancellationException, but currentBalance returned")
        } catch (e: CancellationException) {
            thrown = e
        }
        assertTrue(thrown is CancellationException)
        assertEquals("cancelled", thrown!!.message)
    }
}
