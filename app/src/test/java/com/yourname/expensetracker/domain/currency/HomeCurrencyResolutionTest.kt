package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import org.junit.Assert.*
import org.junit.Test

/**
 * PR 5 — Home currency failure propagation tests.
 * Covers CURR-70F-09: HomeCurrencyResolution must be used in financial math.
 */
class HomeCurrencyResolutionTest {

    @Test
    fun `Resolved returns currency code`() {
        val resolution = HomeCurrencyResolution.Resolved(CurrencyCode("USD"))
        assertEquals(CurrencyCode("USD"), resolution.currencyOrNull)
    }

    @Test
    fun `FirstRunDefault returns default currency`() {
        val resolution = HomeCurrencyResolution.FirstRunDefault(CurrencyCode.EUR)
        assertEquals(CurrencyCode.EUR, resolution.currencyOrNull)
    }

    @Test
    fun `Failed returns null currency`() {
        val resolution = HomeCurrencyResolution.Failed("DataStore corrupted")
        assertNull(resolution.currencyOrNull)
    }

    @Test
    fun `CurrencySettingsRepository default resolveHomeCurrency returns Resolved for valid code`() {
        // This tests the default interface implementation
        val repo = object : CurrencySettingsRepository {
            override fun homeCurrency() = kotlinx.coroutines.flow.flowOf("USD")
            override suspend fun setHomeCurrency(currencyCode: String) {}
            override fun lastRateUpdate() = kotlinx.coroutines.flow.flowOf(0L)
            override suspend fun setLastRateUpdate(timestamp: Long) {}
            override suspend fun areRatesStale(thresholdMs: Long) = false
            override fun emergencyBuffer() = kotlinx.coroutines.flow.flowOf(500.0)
            override suspend fun setEmergencyBuffer(amount: Double) {}
            override suspend fun clear() {}
        }
        kotlinx.coroutines.test.runTest {
            val result = repo.resolveHomeCurrency()
            assertTrue(result is HomeCurrencyResolution.Resolved)
            assertEquals(CurrencyCode("USD"), (result as HomeCurrencyResolution.Resolved).currency)
        }
    }

    @Test
    fun `CurrencySettingsRepository default resolveHomeCurrency returns FirstRunDefault for blank`() {
        val repo = object : CurrencySettingsRepository {
            override fun homeCurrency() = kotlinx.coroutines.flow.flowOf("")
            override suspend fun setHomeCurrency(currencyCode: String) {}
            override fun lastRateUpdate() = kotlinx.coroutines.flow.flowOf(0L)
            override suspend fun setLastRateUpdate(timestamp: Long) {}
            override suspend fun areRatesStale(thresholdMs: Long) = false
            override fun emergencyBuffer() = kotlinx.coroutines.flow.flowOf(500.0)
            override suspend fun setEmergencyBuffer(amount: Double) {}
            override suspend fun clear() {}
        }
        kotlinx.coroutines.test.runTest {
            val result = repo.resolveHomeCurrency()
            assertTrue(result is HomeCurrencyResolution.FirstRunDefault)
            assertEquals(CurrencyCode.EUR, (result as HomeCurrencyResolution.FirstRunDefault).currency)
        }
    }

    @Test
    fun `CurrencySettingsRepository default resolveHomeCurrency returns Failed on exception`() {
        val repo = object : CurrencySettingsRepository {
            override fun homeCurrency() = kotlinx.coroutines.flow.flow<String> { throw RuntimeException("DataStore error") }
            override suspend fun setHomeCurrency(currencyCode: String) {}
            override fun lastRateUpdate() = kotlinx.coroutines.flow.flowOf(0L)
            override suspend fun setLastRateUpdate(timestamp: Long) {}
            override suspend fun areRatesStale(thresholdMs: Long) = false
            override fun emergencyBuffer() = kotlinx.coroutines.flow.flowOf(500.0)
            override suspend fun setEmergencyBuffer(amount: Double) {}
            override suspend fun clear() {}
        }
        kotlinx.coroutines.test.runTest {
            val result = repo.resolveHomeCurrency()
            assertTrue(result is HomeCurrencyResolution.Failed)
            assertTrue((result as HomeCurrencyResolution.Failed).reason.contains("DataStore error"))
        }
    }
}
