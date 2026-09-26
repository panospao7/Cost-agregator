package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.currency.SupportedCurrency
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.StaleRatePolicy
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

class CurrencyRatesRepositoryImplTest {

    private val publicationMillis = Instant.parse("2026-09-25T00:00:00Z").toEpochMilli()
    private val fetchedNextDayMillis = Instant.parse("2026-09-26T12:00:00Z").toEpochMilli()
    private val dayMillis = 24L * 60L * 60L * 1000L

    @Test
    fun `real ECB nesting parses default namespace publication and rates`() {
        val fixture = fixture()

        val parsed = fixture.repository.parseEcbDailyRates(stream(ecbXml()))

        assertEquals(publicationMillis, parsed.publicationMillis)
        assertEquals(1.0, parsed.eurToCurrency.getValue("EUR"), 0.0)
        assertEquals(1.1403, parsed.eurToCurrency.getValue("USD"), 0.0)
        assertEquals(0.8735, parsed.eurToCurrency.getValue("GBP"), 0.0)
    }

    @Test
    fun `publication fetched next day persists UTC date and fetch timestamp`() = runTest {
        val fixture = fixture(now = fetchedNextDayMillis)

        val storedCount = fixture.repository.refreshFromStream("USD", stream(ecbXml()))

        assertTrue(storedCount > 0)
        assertTrue(fixture.store.rates.isNotEmpty())
        assertTrue(fixture.store.rates.values.all { it.validDate == publicationMillis })
        assertTrue(fixture.store.rates.values.all { it.lastUpdated == fetchedNextDayMillis })
        assertTrue(fixture.store.rates.values.all { it.source == "ecb" })
        assertEquals(listOf(fetchedNextDayMillis), fixture.settings.lastRateUpdates)
    }

    @Test
    fun `repeat download on another day upserts the same pair and publication date`() = runTest {
        val fixture = fixture(now = fetchedNextDayMillis)
        fixture.repository.refreshFromStream("USD", stream(ecbXml()))
        val firstKeys = fixture.store.rates.keys.toSet()

        fixture.time.currentMillis = fetchedNextDayMillis + dayMillis
        fixture.repository.refreshFromStream("USD", stream(ecbXml()))

        assertEquals(firstKeys, fixture.store.rates.keys)
        assertTrue(fixture.store.rates.values.all { it.validDate == publicationMillis })
        assertTrue(fixture.store.rates.values.all { it.lastUpdated == fetchedNextDayMillis + dayMillis })
        assertEquals(
            listOf(fetchedNextDayMillis, fetchedNextDayMillis + dayMillis),
            fixture.settings.lastRateUpdates
        )
    }

    @Test
    fun `direct and derived pairs share publication date and obey historical boundary`() = runTest {
        val fixture = fixture(now = fetchedNextDayMillis)
        fixture.repository.refreshFromStream("USD", stream(ecbXml()))

        val usdToEur = fixture.store.rate("USD", "EUR", publicationMillis)
        val usdToGbp = fixture.store.rate("USD", "GBP", publicationMillis)
        assertNotNull(usdToEur)
        assertNotNull(usdToGbp)
        assertEquals(1.0 / 1.1403, usdToEur!!.rate, 0.0000001)
        assertEquals(0.8735 / 1.1403, usdToGbp!!.rate, 0.0000001)
        assertEquals(publicationMillis, usdToEur.validDate)
        assertEquals(publicationMillis, usdToGbp.validDate)

        val onPublication = fixture.converter.convertAsOf(10.0, "USD", "GBP", publicationMillis)
        val beforePublication = fixture.converter.convertAsOf(10.0, "USD", "GBP", publicationMillis - 1L)
        assertTrue(onPublication is ConversionResult)
        assertNull(beforePublication)
    }

    @Test
    fun `provider catalog currencies are persisted and convertible`() = runTest {
        val providerCurrencies = SupportedCurrency.catalog.filter { it.code != "EUR" }
        val rates = providerCurrencies.mapIndexed { index, currency ->
            "<Cube currency='" + currency.code + "' rate='" + (1.01 + index * 0.01) + "'/>"
        }.joinToString("\n")
        val fixture = fixture()

        fixture.repository.refreshFromStream("EUR", stream(ecbXml(rates = rates)))

        providerCurrencies.forEach { currency ->
            assertNotNull(
                "Expected provider quote for ${currency.code}",
                fixture.store.rate(currency.code, "EUR", publicationMillis)
            )
            val typed = fixture.converter.convertOutcome(
                amount = 10.0,
                fromCurrency = currency.code,
                toCurrency = "EUR",
                rateBasis = RateBasis.LATEST_AVAILABLE,
                stalePolicy = StaleRatePolicy.None
            )
            assertTrue("Expected typed conversion for ${currency.code}", typed is ConversionOutcome.Converted)
            assertNotNull(fixture.converter.convert(10.0, currency.code, "EUR"))
        }
    }

    @Test
    fun `catalog currency omitted by provider is missing rate not unsupported`() = runTest {
        val fixture = fixture()
        fixture.repository.refreshFromStream(
            "EUR",
            stream(ecbXml(rates = "<Cube currency='USD' rate='1.1403'/>"))
        )

        val typed = fixture.converter.convertOutcome(
            amount = 10.0,
            fromCurrency = "CNY",
            toCurrency = "EUR",
            rateBasis = RateBasis.LATEST_AVAILABLE,
            stalePolicy = StaleRatePolicy.None
        )

        assertTrue(typed is ConversionOutcome.Failed)
        assertEquals(ConversionFailureType.MISSING_RATE, (typed as ConversionOutcome.Failed).failureType)
        assertNull(fixture.converter.convert(10.0, "CNY", "EUR"))
    }

    @Test
    fun `missing blank malformed and conflicting dates fail without mutation`() = runTest {
        val cases = listOf(
            ecbXml(timeAttribute = null) to EcbPublicationDateFailureReason.MISSING,
            ecbXml(timeAttribute = "") to EcbPublicationDateFailureReason.BLANK,
            ecbXml(timeAttribute = "2025-02-29") to EcbPublicationDateFailureReason.MALFORMED,
            conflictingDatesXml() to EcbPublicationDateFailureReason.CONFLICTING
        )

        cases.forEach { (xml, expectedReason) ->
            val fixture = fixture(now = fetchedNextDayMillis)
            try {
                fixture.repository.refreshFromStream("USD", stream(xml))
                fail("Expected typed publication-date failure for $expectedReason")
            } catch (error: EcbPublicationDateUnavailableException) {
                assertEquals(expectedReason, error.reasonCode)
                assertEquals(expectedReason.name, error.message)
            }
            assertTrue(fixture.store.rates.isEmpty())
            assertTrue(fixture.settings.lastRateUpdates.isEmpty())
        }
    }

    @Test
    fun `publication date is independent of default locale and timezone`() = runTest {
        val originalLocale = Locale.getDefault()
        val originalTimeZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val fixture = fixture(now = fetchedNextDayMillis)

            fixture.repository.refreshFromStream("USD", stream(ecbXml()))

            assertTrue(fixture.store.rates.values.all { it.validDate == publicationMillis })
        } finally {
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun `privacy denial performs no network storage or settings work`() = runTest {
        val fixture = fixture(
            now = fetchedNextDayMillis,
            privacyDecision = PrivacyDecision.Denied("PRIVACY_BLOCKED")
        )

        val count = fixture.repository.refresh("USD")

        assertEquals(0, count)
        assertEquals(1, fixture.privacyGate.checkCount)
        assertTrue(fixture.store.rates.isEmpty())
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())
    }

    @Test
    fun `nonfinite and nonpositive rates remain excluded`() = runTest {
        val fixture = fixture(now = fetchedNextDayMillis)
        val xml = ecbXml(
            rates = """
                <Cube currency='USD' rate='NaN'/>
                <Cube currency='JPY' rate='0'/>
                <Cube currency='CAD' rate='-1'/>
                <Cube currency='GBP' rate='0.8735'/>
            """.trimIndent()
        )

        fixture.repository.refreshFromStream("EUR", stream(xml))

        assertTrue(fixture.store.rates.isNotEmpty())
        assertFalse(fixture.store.rates.keys.any { it.from == "USD" || it.to == "USD" })
        assertFalse(fixture.store.rates.keys.any { it.from == "JPY" || it.to == "JPY" })
        assertFalse(fixture.store.rates.keys.any { it.from == "CAD" || it.to == "CAD" })
        assertTrue(fixture.store.rates.keys.any { it.from == "EUR" && it.to == "GBP" })
    }

    @Test
    fun `XML external entity payload is rejected before mutation`() = runTest {
        val fixture = fixture(now = fetchedNextDayMillis)
        val xml = """
            <?xml version='1.0' encoding='UTF-8'?>
            <!DOCTYPE Envelope [<!ENTITY external SYSTEM 'file:///definitely-not-readable'>]>
            <gesmes:Envelope
                xmlns:gesmes='http://www.gesmes.org/xml/2002-08-01'
                xmlns='http://www.ecb.int/vocabulary/2002-08-01/eurofxref'>
                <Cube><Cube time='2026-09-25'><Cube currency='USD' rate='&external;'/></Cube></Cube>
            </gesmes:Envelope>
        """.trimIndent()

        try {
            fixture.repository.refreshFromStream("USD", stream(xml))
            fail("DOCTYPE payload must be rejected")
        } catch (error: Exception) {
            assertTrue(error is SAXParseException)
        }
        assertTrue(fixture.store.rates.isEmpty())
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())
    }

    private fun fixture(
        now: Long = fetchedNextDayMillis,
        privacyDecision: PrivacyDecision = PrivacyDecision.Allowed
    ): Fixture {
        val store = PairDateRateStore()
        val time = MutableTimeProvider(now)
        val settings = RecordingCurrencySettingsRepository()
        val privacyGate = FixedPrivacyGate(privacyDecision)
        val converter = CurrencyConverter(store, time)
        val repository = CurrencyRatesRepositoryImpl(
            currencyConverter = converter,
            currencySettingsRepository = settings,
            timeProvider = time,
            ioDispatcher = Dispatchers.Unconfined,
            privacyGate = privacyGate
        )
        return Fixture(repository, converter, store, time, settings, privacyGate)
    }

    private fun ecbXml(
        timeAttribute: String? = "2026-09-25",
        rates: String = """
            <Cube currency='USD' rate='1.1403'/>
            <Cube currency='GBP' rate='0.8735'/>
        """.trimIndent()
    ): String {
        val time = when (timeAttribute) {
            null -> ""
            else -> " time='$timeAttribute'"
        }
        val indentedRates = rates.lineSequence()
            .joinToString("\n") { "                        $it" }
        return """
            <?xml version='1.0' encoding='UTF-8'?>
            <gesmes:Envelope
                xmlns:gesmes='http://www.gesmes.org/xml/2002-08-01'
                xmlns='http://www.ecb.int/vocabulary/2002-08-01/eurofxref'>
                <Cube>
                    <Cube$time>
                        $indentedRates
                    </Cube>
                </Cube>
            </gesmes:Envelope>
        """.trimIndent()
    }

    private fun conflictingDatesXml(): String = """
        <?xml version='1.0' encoding='UTF-8'?>
        <gesmes:Envelope
            xmlns:gesmes='http://www.gesmes.org/xml/2002-08-01'
            xmlns='http://www.ecb.int/vocabulary/2002-08-01/eurofxref'>
            <Cube>
                <Cube time='2026-09-25'><Cube currency='USD' rate='1.1403'/></Cube>
                <Cube time='2026-09-24'><Cube currency='USD' rate='1.1390'/></Cube>
            </Cube>
        </gesmes:Envelope>
    """.trimIndent()

    private fun stream(xml: String) = ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))

    private data class Fixture(
        val repository: CurrencyRatesRepositoryImpl,
        val converter: CurrencyConverter,
        val store: PairDateRateStore,
        val time: MutableTimeProvider,
        val settings: RecordingCurrencySettingsRepository,
        val privacyGate: FixedPrivacyGate
    )
}

private data class PairDateKey(val from: String, val to: String, val validDate: Long)

private class PairDateRateStore : ExchangeRateStore {
    val rates = linkedMapOf<PairDateKey, DomainExchangeRate>()

    fun rate(from: String, to: String, validDate: Long): DomainExchangeRate? =
        rates[PairDateKey(from, to, validDate)]

    override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? =
        getLatestRateForPair(fromCurrency, toCurrency)

    override suspend fun getLatestRateForPair(
        fromCurrency: String,
        toCurrency: String
    ): DomainExchangeRate? = rates.values
        .asSequence()
        .filter { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency }
        .maxWithOrNull(
            compareBy<DomainExchangeRate> { it.validDate ?: Long.MIN_VALUE }
                .thenBy { it.lastUpdated }
        )

    override suspend fun getRateAsOf(
        fromCurrency: String,
        toCurrency: String,
        atMillis: Long
    ): DomainExchangeRate? = rates.values
        .asSequence()
        .filter {
            it.fromCurrency == fromCurrency &&
                it.toCurrency == toCurrency &&
                (it.validDate ?: Long.MAX_VALUE) <= atMillis
        }
        .maxByOrNull { it.validDate ?: Long.MIN_VALUE }

    override suspend fun insertOrUpdate(rate: DomainExchangeRate) {
        val validDate = requireNotNull(rate.validDate)
        rates[PairDateKey(rate.fromCurrency, rate.toCurrency, validDate)] = rate
    }

    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {
        rates.forEach { insertOrUpdate(it) }
    }

    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> =
        flowOf(rates.values.filter { it.toCurrency == targetCurrency })

    override suspend fun getLatestRate(): DomainExchangeRate? =
        rates.values.maxByOrNull { it.lastUpdated }

    override suspend fun deleteOldRates(olderThan: Long) {
        rates.entries.removeAll { it.value.lastUpdated < olderThan }
    }
}

private class MutableTimeProvider(var currentMillis: Long) : TimeProvider {
    override fun now(): Long = currentMillis
}

private class FixedPrivacyGate(private val decision: PrivacyDecision) : PrivacyGate {
    var checkCount: Int = 0

    override suspend fun check(
        capability: PrivacyCapability,
        context: Map<String, String>
    ): PrivacyDecision {
        checkCount += 1
        return decision
    }
}

private class RecordingCurrencySettingsRepository : CurrencySettingsRepository {
    private val homeCurrency = MutableStateFlow("EUR")
    private val lastUpdate = MutableStateFlow(0L)
    private val emergencyBuffer = MutableStateFlow(500.0)
    val lastRateUpdates = mutableListOf<Long>()

    override fun homeCurrency(): Flow<String> = homeCurrency

    override suspend fun setHomeCurrency(currencyCode: String) {
        homeCurrency.value = currencyCode
    }

    override fun lastRateUpdate(): Flow<Long> = lastUpdate

    override suspend fun setLastRateUpdate(timestamp: Long) {
        lastUpdate.value = timestamp
        lastRateUpdates += timestamp
    }

    override suspend fun areRatesStale(thresholdMs: Long): Boolean = false

    override fun emergencyBuffer(): Flow<Double> = emergencyBuffer

    override suspend fun setEmergencyBuffer(amount: Double) {
        emergencyBuffer.value = amount
    }

    override suspend fun clear() {
        homeCurrency.value = ""
        lastUpdate.value = 0L
        emergencyBuffer.value = 500.0
        lastRateUpdates.clear()
    }
}
