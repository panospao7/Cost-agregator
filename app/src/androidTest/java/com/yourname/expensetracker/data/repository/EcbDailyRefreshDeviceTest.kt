package com.yourname.expensetracker.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRateRefreshOutcome
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/**
 * Device proof that the daily ECB parse works on Android's own XML parser (which rejects several
 * hardening features that the JVM accepts) and still rejects DTD payloads.
 *
 * NEEDS NETWORK for [liveRefreshAgainstRealEcbFileStoresRates]. That live test skips (assumption)
 * unless this class is targeted explicitly by the instrumentation "class" argument, so it never
 * fails the default connected suite when the network is unavailable. Run explicitly by class
 * filter for the 1.0.3 hotfix gate:
 * `com.yourname.expensetracker.data.repository.EcbDailyRefreshDeviceTest`.
 *
 * Uses a real in-memory Room [AppDatabase], real [CurrencyConverter], real [ExchangeRateStoreAdapter]
 * and real DAO; only the privacy gate (always allowed), settings and clock are test doubles.
 */
@RunWith(AndroidJUnit4::class)
class EcbDailyRefreshDeviceTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: CurrencyRatesRepositoryImpl

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(ApplicationProvider.getApplicationContext()).build()
        val maintenance = mockk<RestoreMaintenanceMode>()
        every { maintenance.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        val store = ExchangeRateStoreAdapter(database.exchangeRateDao(), DatabaseWriteBarrier(maintenance))
        val time = object : TimeProvider {
            override fun now(): Long = System.currentTimeMillis()
        }
        repository = CurrencyRatesRepositoryImpl(
            currencyConverter = CurrencyConverter(store, time),
            currencySettingsRepository = InMemoryCurrencySettings(),
            timeProvider = time,
            ioDispatcher = Dispatchers.IO,
            privacyGate = AlwaysAllowedGate()
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun parseEcbDailyRates_validPayload_succeedsOnAndroidParser() {
        val parsed = repository.parseEcbDailyRates(ByteArrayInputStream(ecbXml().toByteArray(Charsets.UTF_8)))

        assertEquals(1.0, parsed.eurToCurrency.getValue("EUR"), 0.0)
        assertEquals(1.1403, parsed.eurToCurrency.getValue("USD"), 0.0)
        assertTrue(parsed.publicationMillis > 0L)
    }

    @Test
    fun parseEcbDailyRates_doctypePayload_isRejected() {
        val xml = "<!DOCTYPE x [<!ENTITY a 'b'>]>" + ecbXml()
        try {
            repository.parseEcbDailyRates(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            fail("DOCTYPE payload must be rejected")
        } catch (error: IllegalStateException) {
            assertEquals("RATE_PAYLOAD_MALFORMED", error.message)
        }
    }

    @Test
    fun liveRefreshAgainstRealEcbFileStoresRates() = runBlocking {
        requireExplicitLiveRun()
        val outcome = repository.refresh("EUR")

        assertTrue("Expected Refreshed but was $outcome", outcome is CurrencyRateRefreshOutcome.Refreshed)
        assertTrue((outcome as CurrencyRateRefreshOutcome.Refreshed).storedPairs > 0)
        assertTrue(database.exchangeRateDao().getRateCount() > 0)
    }

    private fun requireExplicitLiveRun() {
        Assume.assumeTrue(
            "live ECB test runs only by explicit class filter",
            InstrumentationRegistry.getArguments().getString("class")
                ?.contains(EcbDailyRefreshDeviceTest::class.java.name) == true
        )
    }

    private fun ecbXml(): String = """
        <?xml version='1.0' encoding='UTF-8'?>
        <gesmes:Envelope
            xmlns:gesmes='http://www.gesmes.org/xml/2002-08-01'
            xmlns='http://www.ecb.int/vocabulary/2002-08-01/eurofxref'>
            <Cube>
                <Cube time='2026-09-25'>
                    <Cube currency='USD' rate='1.1403'/>
                    <Cube currency='GBP' rate='0.8735'/>
                </Cube>
            </Cube>
        </gesmes:Envelope>
    """.trimIndent()

    private class AlwaysAllowedGate : PrivacyGate {
        override suspend fun check(
            capability: PrivacyCapability,
            context: Map<String, String>
        ): PrivacyDecision = PrivacyDecision.Allowed
    }

    private class InMemoryCurrencySettings : CurrencySettingsRepository {
        private val home = MutableStateFlow("EUR")
        private val lastUpdate = MutableStateFlow(0L)
        private val buffer = MutableStateFlow(500.0)

        override fun homeCurrency(): Flow<String> = home
        override suspend fun setHomeCurrency(currencyCode: String) { home.value = currencyCode }
        override fun lastRateUpdate(): Flow<Long> = lastUpdate
        override suspend fun setLastRateUpdate(timestamp: Long) { lastUpdate.value = timestamp }
        override suspend fun areRatesStale(thresholdMs: Long): Boolean = false
        override fun emergencyBuffer(): Flow<Double> = buffer
        override suspend fun setEmergencyBuffer(amount: Double) { buffer.value = amount }
        override suspend fun clear() {
            home.value = ""
            lastUpdate.value = 0L
            buffer.value = 500.0
        }
    }
}
