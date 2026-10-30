package com.yourname.expensetracker.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExchangeRateDao
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRateHistoryOutcome
import com.yourname.expensetracker.domain.currency.CurrencyRateRefreshOutcome
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.RateHistoryFailureReason
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * MEC 1b candidate device check (privacy condition C8 and the live ECB history download).
 *
 * This class needs network access (tests 2 and 3 reach the real ECB host; test 4 toggles airplane
 * mode through the shell). It is not part of any default suite: the validation runner runs it
 * explicitly by class filter (connected-test-class) at the 1b candidate gate, on an API 35 emulator.
 *
 * Real in-memory Room [AppDatabase], real [CurrencyConverter], real [ExchangeRateStoreAdapter] and
 * real [ExchangeRateDao]. Only the privacy gate, the clock and the settings store are fakes. No
 * payload or response text is ever logged or put in an assertion message.
 */
@RunWith(AndroidJUnit4::class)
class CurrencyRateHistoryDeviceTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ExchangeRateDao
    private lateinit var repository: CurrencyRatesRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.exchangeRateDao()
        val time = SystemClockTime()
        val barrier = DatabaseWriteBarrier(RestoreMaintenanceMode(context, time))
        val converter = CurrencyConverter(ExchangeRateStoreAdapter(dao, barrier), time)
        repository = CurrencyRatesRepositoryImpl(
            currencyConverter = converter,
            currencySettingsRepository = NoopSettings(),
            timeProvider = time,
            ioDispatcher = Dispatchers.IO,
            privacyGate = AllowAllGate()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val validBody = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01"
            xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
          <Cube><Cube time="2026-10-28"><Cube currency="USD" rate="1.1"/><Cube currency="GBP" rate="0.9"/></Cube></Cube>
        </gesmes:Envelope>
    """.trimIndent()

    private val doctypeBody = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE gesmes:Envelope [<!ENTITY synthetic "x">]>
        <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01"
            xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
          <Cube><Cube time="2026-10-28"><Cube currency="USD" rate="1.1"/></Cube></Cube>
        </gesmes:Envelope>
    """.trimIndent()

    private fun bytes(xml: String) = ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))

    @Test
    fun c8_doctypePayload_isMalformed_onAndroidParser() = runBlocking {
        val now = System.currentTimeMillis()
        // (a) Control: the best-effort hardened factory builds and parses valid payloads on this device
        // (history and daily paths); no ParserConfigurationException escapes the factory.
        assertEquals(1, repository.parseEcbHistoryRates(bytes(validBody), now).size)
        assertEquals(1.1, repository.parseEcbDailyRates(bytes(validBody)).eurToCurrency.getValue("USD"), 0.0)
        // DTD rejection is the explicit byte pre-scan, independent of parser feature support.
        assertTrue(repository.containsDtdDeclaration(doctypeBody.toByteArray(Charsets.UTF_8)))
        assertTrue(!repository.containsDtdDeclaration(validBody.toByteArray(Charsets.UTF_8)))
        // (b) The DOCTYPE payload through the opener seam is PAYLOAD_MALFORMED and stores nothing.
        repository.historyConnectionOpener = { FakeOkConnection(doctypeBody.toByteArray(Charsets.UTF_8)) }
        val outcome = repository.downloadRecentHistory("EUR")
        assertEquals(CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.PAYLOAD_MALFORMED), outcome)
        assertEquals(0, dao.getRateCount())
    }

    @Test
    fun live_dailyRefresh_storesRows() = runBlocking {
        val outcome = repository.refresh("EUR")
        assertTrue(
            "daily outcome must be Refreshed: ${outcome::class.simpleName}",
            outcome is CurrencyRateRefreshOutcome.Refreshed
        )
        outcome as CurrencyRateRefreshOutcome.Refreshed
        assertTrue("storedPairs must be positive", outcome.storedPairs > 0)
        assertTrue("DAO must hold rows after the daily refresh", dao.getRateCount() > 0)
    }

    @Test
    fun live_download_storesRows_andIsIdempotent() = runBlocking {
        val startedAt = System.nanoTime()
        val first = repository.downloadRecentHistory("EUR")
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("first outcome must be Stored (elapsedMs=$elapsedMs): ${first::class.simpleName}", first is CurrencyRateHistoryOutcome.Stored)
        first as CurrencyRateHistoryOutcome.Stored
        assertTrue("days=${first.days}", first.days in 1..70)
        assertTrue("rows=${first.rows}", first.rows > 0)
        assertTrue("earliest must not be after latest", !first.earliestDate.isAfter(first.latestDate))
        assertTrue("download took ${elapsedMs}ms, limit 20000ms", elapsedMs < 20_000)
        assertEquals("DAO rows must equal the reported rows", first.rows, dao.getRateCount())

        val second = repository.downloadRecentHistory("EUR")
        assertTrue("second outcome must be Stored: ${second::class.simpleName}", second is CurrencyRateHistoryOutcome.Stored)
        assertEquals("REPLACE must keep the row count unchanged", first.rows, dao.getRateCount())
    }

    @Test
    fun live_cancel_endsCancelledOrNoPartialWrite() = runBlocking {
        val job = launch(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            repository.downloadRecentHistory("EUR")
        }
        delay(50)
        job.cancelAndJoin()
        assertTrue("the download job must end cancelled", job.isCancelled)
        val afterCancel = dao.getRateCount()

        // All-or-nothing: the table holds either nothing or exactly one completed download.
        dao.deleteAllRates()
        val fresh = repository.downloadRecentHistory("EUR")
        assertTrue("fresh run must be Stored: ${fresh::class.simpleName}", fresh is CurrencyRateHistoryOutcome.Stored)
        val fullCount = dao.getRateCount()
        assertTrue("fullCount=$fullCount", fullCount > 0)
        assertTrue("partial write after cancel: count=$afterCancel, full=$fullCount", afterCancel == 0 || afterCancel == fullCount)
    }

    @Test
    fun airplaneMode_mapsToOfflineOrTimeout() = runBlocking {
        try {
            shell("cmd connectivity airplane-mode enable")
            delay(3_000)
            val outcome = repository.downloadRecentHistory("EUR")
            assertTrue(
                "offline must map to OFFLINE or TIMEOUT, got ${outcome::class.simpleName}",
                outcome == CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.OFFLINE) ||
                    outcome == CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.TIMEOUT)
            )
            assertEquals(0, dao.getRateCount())
        } finally {
            shell("cmd connectivity airplane-mode disable")
            awaitConnectivity()
        }
    }

    private fun shell(command: String) {
        val pfd: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private suspend fun awaitConnectivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        repeat(30) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) return
            delay(1_000)
        }
    }
}

private class SystemClockTime : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}

private class AllowAllGate : PrivacyGate {
    override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
        PrivacyDecision.Allowed
}

private class NoopSettings : CurrencySettingsRepository {
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
    override suspend fun clear() { lastUpdate.value = 0L }
}

/** Fake HTTP 200 connection serving a fixed synthetic body; never touches the network. */
private class FakeOkConnection(private val body: ByteArray) : HttpURLConnection(URL("https://example.invalid/")) {
    override fun connect() {}
    override fun disconnect() {}
    override fun usingProxy(): Boolean = false
    override fun getResponseCode(): Int = 200
    override fun getContentLengthLong(): Long = body.size.toLong()
    override fun getInputStream(): InputStream = ByteArrayInputStream(body)
}
