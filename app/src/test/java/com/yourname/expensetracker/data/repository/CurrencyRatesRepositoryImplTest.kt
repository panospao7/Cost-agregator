package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRateRefreshOutcome
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
import com.yourname.expensetracker.domain.privacy.PrivacyGateReasonCodes
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.currency.CurrencyRateHistoryOutcome
import com.yourname.expensetracker.domain.currency.RateHistoryFailureReason
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.Timeout
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.time.LocalDate
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
            privacyDecision = PrivacyDecision.Denied(PrivacyGateReasonCodes.EXCHANGE_RATE_DOWNLOAD_DISABLED)
        )

        val outcome = fixture.repository.refresh("USD")

        assertEquals(CurrencyRateRefreshOutcome.DisabledByPrivacy, outcome)
        assertEquals(1, fixture.privacyGate.checkCount)
        assertEquals(listOf(PrivacyCapability.EXCHANGE_RATE_DOWNLOAD), fixture.privacyGate.checkedCapabilities)
        assertTrue(fixture.store.rates.isEmpty())
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())
    }

    @Test
    fun `fail closed privacy decision returns bounded reason code without any work`() = runTest {
        val fixture = fixture(
            now = fetchedNextDayMillis,
            privacyDecision = PrivacyDecision.FailClosed(PrivacyGateReasonCodes.PRIVACY_BLOCKED)
        )

        val outcome = fixture.repository.refresh("USD")

        assertEquals(
            CurrencyRateRefreshOutcome.PrivacyCheckFailed(PrivacyGateReasonCodes.PRIVACY_BLOCKED),
            outcome
        )
        assertEquals(listOf(PrivacyCapability.EXCHANGE_RATE_DOWNLOAD), fixture.privacyGate.checkedCapabilities)
        assertTrue(fixture.store.rates.isEmpty())
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())
    }

    @Test
    fun `fail closed with unbounded reason text is mapped to PRIVACY_BLOCKED`() = runTest {
        val fixture = fixture(
            now = fetchedNextDayMillis,
            privacyDecision = PrivacyDecision.FailClosed("raw /storage/emulated/0/secret path or SQL text")
        )

        val outcome = fixture.repository.refresh("USD")

        assertEquals(
            CurrencyRateRefreshOutcome.PrivacyCheckFailed(PrivacyGateReasonCodes.PRIVACY_BLOCKED),
            outcome
        )
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
        } catch (error: IllegalStateException) {
            // Rejected by the explicit parser-independent DTD pre-scan, not by a parser feature.
            assertEquals("RATE_PAYLOAD_MALFORMED", error.message)
        }
        assertTrue(fixture.store.rates.isEmpty())
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())
    }

    @Test
    fun `daily payload with DOCTYPE fails like a malformed payload and stores nothing`() = runTest {
        val malformed = fixture(now = fetchedNextDayMillis)
        val malformedFailure = try {
            malformed.repository.refreshFromStream("USD", stream("<not-closed"))
            null
        } catch (error: Exception) {
            error
        }
        assertNotNull("a malformed daily payload must fail", malformedFailure)

        val fixture = fixture(now = fetchedNextDayMillis)
        val doctypeXml = ecbXml().replaceFirst("?>", "?>\n<!DOCTYPE gesmes:Envelope [<!ENTITY synthetic 'x'>]>")
        val doctypeFailure = try {
            fixture.repository.refreshFromStream("USD", stream(doctypeXml))
            null
        } catch (error: Exception) {
            error
        }

        assertNotNull("a DOCTYPE daily payload must fail", doctypeFailure)
        assertTrue(fixture.store.rates.isEmpty())
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())
        assertTrue(malformed.store.rates.isEmpty())
    }

    private val dailyCapBytes = 256 * 1024

    private fun paddedToBytes(xml: String, totalBytes: Int): ByteArray {
        val base = xml.toByteArray(Charsets.UTF_8)
        require(base.size <= totalBytes)
        return base + ByteArray(totalBytes - base.size) { ' '.code.toByte() }
    }

    @Test
    fun `daily payload one byte over the 256 KiB cap fails RATE_PAYLOAD_TOO_LARGE and stores nothing`() = runTest {
        val oversized = paddedToBytes(ecbXml(), dailyCapBytes + 1)
        assertEquals(dailyCapBytes + 1, oversized.size)

        try {
            fixture().repository.parseEcbDailyRates(ByteArrayInputStream(oversized))
            fail("Expected RATE_PAYLOAD_TOO_LARGE")
        } catch (error: IllegalStateException) {
            assertEquals("RATE_PAYLOAD_TOO_LARGE", error.message)
        }

        val f = fixture(now = fetchedNextDayMillis)
        try {
            f.repository.refreshFromStream("USD", ByteArrayInputStream(oversized))
            fail("Expected RATE_PAYLOAD_TOO_LARGE")
        } catch (error: IllegalStateException) {
            assertEquals("RATE_PAYLOAD_TOO_LARGE", error.message)
        }
        assertTrue(f.store.rates.isEmpty())
        assertTrue(f.settings.lastRateUpdates.isEmpty())
    }

    @Test
    fun `daily payload of exactly 256 KiB still parses and stores rates`() = runTest {
        val atCap = paddedToBytes(ecbXml(), dailyCapBytes)
        assertEquals(dailyCapBytes, atCap.size)

        val parsed = fixture().repository.parseEcbDailyRates(ByteArrayInputStream(atCap))
        assertEquals(1.1403, parsed.eurToCurrency.getValue("USD"), 0.0)

        val f = fixture(now = fetchedNextDayMillis)
        val storedCount = f.repository.refreshFromStream("USD", ByteArrayInputStream(atCap))
        assertTrue(storedCount > 0)
        assertTrue(f.store.rates.isNotEmpty())
        assertEquals(listOf(fetchedNextDayMillis), f.settings.lastRateUpdates)
    }

    @Test
    fun `containsDtdDeclaration is true for DOCTYPE or ENTITY in any case and false for a normal payload`() {
        val repo = fixture().repository
        fun detects(text: String) = repo.containsDtdDeclaration(text.toByteArray(Charsets.UTF_8))

        assertTrue(detects("<?xml version=\"1.0\"?><!DOCTYPE x><a/>"))
        assertTrue(detects("<?xml version=\"1.0\"?><!doctype x><a/>"))
        assertTrue(detects("<?xml version=\"1.0\"?><!DocType x><a/>"))
        assertTrue(detects("<a><!ENTITY e \"v\"></a>"))
        assertTrue(detects("<a><!entity e \"v\"></a>"))

        assertFalse(detects(ecbXml()))
        assertFalse(detects(historyXml(datesBack(3))))
        assertFalse(detects(""))
        // Boundary: near-miss spellings are not a DTD declaration.
        assertFalse(detects("<a>DOCTYPE and ENTITY words without the markup opener</a>"))
        assertFalse(detects("<a><!-- DOCTYPE --></a>"))
    }

    @Test
    fun `lowercase doctype history payload is PAYLOAD_MALFORMED with zero rows stored`() = runTest {
        val xml = historyXml(datesBack(2)).replaceFirst("?>", "?><!doctype gesmes:envelope [<!entity e 'x'>]>")
        val fixture = fixture(now = histNow)
        fixture.repository.serve(fakeConnection(body = stream(xml)))

        val outcome = fixture.repository.downloadRecentHistory("EUR")

        assertEquals(CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.PAYLOAD_MALFORMED), outcome)
        assertEquals(1, connectionsOpened)
        assertTrue(fixture.store.rates.isEmpty())
        assertEquals(0, fixture.store.insertAllCalls)
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())
    }

    // ── Strict UTF-8 payload decoding (both parse paths) ────────────────────

    private val bom8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val bomLe = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    private fun utf16LeWithBom(text: String) = bomLe + text.toByteArray(Charsets.UTF_16LE)

    private fun withInvalidUtf8Inside(xml: String): ByteArray {
        val head = xml.substringBefore("?>") + "?><!--"
        val tail = "-->" + xml.substringAfter("?>")
        return head.toByteArray(Charsets.UTF_8) + byteArrayOf(0xC3.toByte(), 0x28) + tail.toByteArray(Charsets.UTF_8)
    }

    private fun assertHistoryBytesMalformed(bytes: ByteArray) {
        try {
            fixture().repository.parseEcbHistoryRates(ByteArrayInputStream(bytes), histNow)
            fail("Expected PAYLOAD_MALFORMED")
        } catch (error: EcbHistoryPayloadException) {
            assertEquals(RateHistoryFailureReason.PAYLOAD_MALFORMED, error.reason)
        }
    }

    private fun assertDailyBytesMalformedAndNothingStored(bytes: ByteArray) = runTest {
        try {
            fixture().repository.parseEcbDailyRates(ByteArrayInputStream(bytes))
            fail("Expected RATE_PAYLOAD_MALFORMED")
        } catch (error: IllegalStateException) {
            assertEquals("RATE_PAYLOAD_MALFORMED", error.message)
        }
        val f = fixture(now = fetchedNextDayMillis)
        val failure = try {
            f.repository.refreshFromStream("USD", ByteArrayInputStream(bytes))
            null
        } catch (error: Exception) {
            error
        }
        assertNotNull("a non strict UTF-8 daily payload must fail", failure)
        assertTrue(f.store.rates.isEmpty())
        assertTrue(f.settings.lastRateUpdates.isEmpty())
    }

    @Test
    fun `UTF-16LE payload with BOM and DOCTYPE is malformed on history and daily paths`() {
        val doctype = "?>\n<!DOCTYPE gesmes:Envelope [<!ENTITY synthetic 'x'>]>"
        assertHistoryBytesMalformed(utf16LeWithBom(historyXml(datesBack(2)).replaceFirst("?>", doctype)))
        assertDailyBytesMalformedAndNothingStored(utf16LeWithBom(ecbXml().replaceFirst("?>", doctype)))
    }

    @Test
    fun `UTF-16BE payload without BOM is malformed on history and daily paths`() {
        assertHistoryBytesMalformed(historyXml(datesBack(2)).toByteArray(Charsets.UTF_16BE))
        assertDailyBytesMalformedAndNothingStored(ecbXml().toByteArray(Charsets.UTF_16BE))
    }

    @Test
    fun `UTF-8 payload with leading BOM is malformed on history and daily paths`() {
        assertHistoryBytesMalformed(bom8 + historyXml(datesBack(2)).toByteArray(Charsets.UTF_8))
        assertDailyBytesMalformedAndNothingStored(bom8 + ecbXml().toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `invalid UTF-8 byte sequence is malformed on history and daily paths`() {
        assertHistoryBytesMalformed(withInvalidUtf8Inside(historyXml(datesBack(2))))
        assertDailyBytesMalformedAndNothingStored(withInvalidUtf8Inside(ecbXml()))
    }

    @Test
    fun `control plain UTF-8 payload still parses on history and daily paths`() {
        val days = fixture().repository.parseEcbHistoryRates(stream(historyXml(datesBack(2))), histNow)
        assertEquals(2, days.size)
        val daily = fixture().repository.parseEcbDailyRates(stream(ecbXml()))
        assertNotNull(daily)
    }

    // ── MEC 1b: history parser (INV-1b-7, INV-1b-9, C6-C8) ─────────────────

    private val histNewest = LocalDate.of(2026, 10, 29)
    private val histNow = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()

    // 29 synthetic ECB-listed codes. 24 are in the app catalog, plus implicit EUR = 25 catalog codes,
    // so one day yields 25 x 24 = 600 stored pairs.
    private val ecbCodes = listOf(
        "USD", "JPY", "CZK", "DKK", "GBP", "HUF", "PLN", "RON", "SEK", "CHF", "ISK", "NOK", "TRY", "AUD",
        "BRL", "CAD", "CNY", "HKD", "IDR", "ILS", "INR", "KRW", "MXN", "MYR", "NZD", "PHP", "SGD", "THB", "ZAR"
    )
    private val pairsPerFixtureDay = 600

    private fun datesBack(count: Int, newest: LocalDate = histNewest): List<LocalDate> =
        (0 until count).map { newest.minusDays(it.toLong()) }

    private fun historyXml(
        dates: List<LocalDate>,
        codes: List<String> = ecbCodes,
        singleLine: Boolean = true,
        quote: String = "\"",
        rateText: (Int, Int) -> String = { c, d -> (1.0 + (c + 1) * 0.01 + d * 0.0001).toString() }
    ): String {
        val nl = if (singleLine) "" else "\n"
        val t1 = if (singleLine) "" else "\t"
        val t2 = if (singleLine) "" else "\t\t"
        val t3 = if (singleLine) "" else "\t\t\t"
        val q = quote
        val sb = StringBuilder()
        sb.append("<?xml version=${q}1.0$q encoding=${q}UTF-8$q?>$nl")
        sb.append("<gesmes:Envelope xmlns:gesmes=${q}http://www.gesmes.org/xml/2002-08-01$q ")
        sb.append("xmlns=${q}http://www.ecb.int/vocabulary/2002-08-01/eurofxref$q>$nl")
        sb.append("${t1}<gesmes:subject>Reference rates</gesmes:subject>$nl")
        sb.append("${t1}<Cube>$nl")
        dates.forEachIndexed { d, date ->
            sb.append("${t2}<Cube time=$q$date$q>$nl")
            codes.forEachIndexed { c, code ->
                sb.append("${t3}<Cube currency=$q$code$q rate=$q${rateText(c, d)}$q/>$nl")
            }
            sb.append("${t2}</Cube>$nl")
        }
        sb.append("${t1}</Cube>$nl")
        sb.append("</gesmes:Envelope>")
        return sb.toString()
    }

    private fun assertHistoryReason(expected: RateHistoryFailureReason, xml: String) {
        try {
            fixture().repository.parseEcbHistoryRates(stream(xml), histNow)
            fail("Expected $expected")
        } catch (error: EcbHistoryPayloadException) {
            assertEquals(expected, error.reason)
        }
    }

    @Test
    fun `INV-1b-7 valid 64 day single line file parses newest first with all listed codes`() {
        val days = fixture().repository.parseEcbHistoryRates(stream(historyXml(datesBack(64))), histNow)

        assertEquals(64, days.size)
        assertEquals(histNewest, days.first().date)
        assertEquals(histNewest.minusDays(63), days.last().date)
        assertEquals(days.map { it.date }.sortedDescending(), days.map { it.date })
        assertTrue(days.all { it.eurToCurrency.size == 30 && it.eurToCurrency.getValue("EUR") == 1.0 })
        assertEquals(1.01, days.first().eurToCurrency.getValue("USD"), 1e-12)
        assertEquals(1.0405, days[5].eurToCurrency.getValue("DKK"), 1e-12)
    }

    @Test
    fun `INV-1b-7 tab indented single quoted variant parses identically`() {
        val repo = fixture().repository
        val compact = repo.parseEcbHistoryRates(stream(historyXml(datesBack(5))), histNow)
        val pretty = repo.parseEcbHistoryRates(
            stream(historyXml(datesBack(5), singleLine = false, quote = "'")),
            histNow
        )

        assertEquals(compact, pretty)
    }

    @Test
    fun `C8 DOCTYPE payload is rejected by the hardened parser on the JVM`() {
        val xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///nope\">]>" +
            historyXml(datesBack(2)).substringAfter("?>")
        assertHistoryReason(RateHistoryFailureReason.PAYLOAD_MALFORMED, xml)
    }

    @Test
    fun `INV-1b-9 file with no dated node is PAYLOAD_EMPTY`() {
        assertHistoryReason(RateHistoryFailureReason.PAYLOAD_EMPTY, historyXml(emptyList()))
    }

    @Test
    fun `INV-1b-9 day cap 70 is accepted and 71 is out of bounds`() {
        val repo = fixture().repository
        assertEquals(70, repo.parseEcbHistoryRates(stream(historyXml(datesBack(70))), histNow).size)

        assertHistoryReason(RateHistoryFailureReason.PAYLOAD_OUT_OF_BOUNDS, historyXml(datesBack(71)))
    }

    @Test
    fun `INV-1b-9 span cap 100 days is accepted and 101 is out of bounds`() {
        val repo = fixture().repository
        val ok = listOf(histNewest, histNewest.minusDays(100))
        assertEquals(2, repo.parseEcbHistoryRates(stream(historyXml(ok)), histNow).size)

        val tooWide = listOf(histNewest, histNewest.minusDays(101))
        assertHistoryReason(RateHistoryFailureReason.PAYLOAD_OUT_OF_BOUNDS, historyXml(tooWide))
    }

    @Test
    fun `INV-1b-9 future dated day is out of bounds and today is accepted`() {
        val repo = fixture().repository
        val today = listOf(LocalDate.of(2026, 10, 30), histNewest)
        assertEquals(2, repo.parseEcbHistoryRates(stream(historyXml(today)), histNow).size)

        val future = listOf(LocalDate.of(2026, 11, 2), histNewest)
        assertHistoryReason(RateHistoryFailureReason.PAYLOAD_OUT_OF_BOUNDS, historyXml(future))
    }

    @Test
    fun `INV-1b-7 duplicate date blank date and bad date are PAYLOAD_MALFORMED`() {
        assertHistoryReason(
            RateHistoryFailureReason.PAYLOAD_MALFORMED,
            historyXml(listOf(histNewest, histNewest))
        )
        assertHistoryReason(
            RateHistoryFailureReason.PAYLOAD_MALFORMED,
            historyXml(datesBack(2)).replaceFirst("time=\"2026-10-29\"", "time=\"\"")
        )
        assertHistoryReason(
            RateHistoryFailureReason.PAYLOAD_MALFORMED,
            historyXml(datesBack(2)).replaceFirst("time=\"2026-10-29\"", "time=\"2026-13-01\"")
        )
    }

    @Test
    fun `INV-1b-7 a day whose rates are all non numeric rejects the whole payload`() {
        val xml = historyXml(datesBack(3)) { c, d -> if (d == 1) "n/a" else (1.5 + c * 0.01).toString() }
        assertHistoryReason(RateHistoryFailureReason.PAYLOAD_MALFORMED, xml)
    }

    // ── MEC 1b: history network phase (INV-1b-1, 3, 6, 9; C1-C5, C10) ─────────
    //
    // The repository exposes a @VisibleForTesting `historyConnectionOpener` seam, invoked only after the
    // privacy gate and the home-currency precheck. No real network and no JDK class mocking is used.

    /** Fails fast instead of hanging the suite if a regression blocks a test. */
    @get:Rule
    val testTimeout: Timeout = Timeout.seconds(60)

    private var connectionsOpened = 0

    private fun CurrencyRatesRepositoryImpl.serve(connection: HttpURLConnection) {
        connectionsOpened = 0
        historyConnectionOpener = {
            connectionsOpened += 1
            connection
        }
    }

    /** An opener that must never be called; counts invocations and fails if reached. */
    private fun CurrencyRatesRepositoryImpl.serveNothing() {
        connectionsOpened = 0
        historyConnectionOpener = {
            connectionsOpened += 1
            throw AssertionError("connection opener must not be called")
        }
    }

    private fun fakeConnection(
        status: Int = 200,
        contentLength: Long = -1L,
        body: InputStream = ByteArrayInputStream(ByteArray(0))
    ): HttpURLConnection {
        val connection = mockk<HttpURLConnection>(relaxed = true)
        every { connection.responseCode } returns status
        every { connection.contentLengthLong } returns contentLength
        every { connection.inputStream } returns body
        return connection
    }

    private fun okHistoryConnection(dayCount: Int = 64) =
        fakeConnection(body = stream(historyXml(datesBack(dayCount))))

    private fun repositoryWithGate(gate: PrivacyGate): CurrencyRatesRepositoryImpl {
        val time = MutableTimeProvider(histNow)
        return CurrencyRatesRepositoryImpl(
            currencyConverter = CurrencyConverter(PairDateRateStore(), time),
            currencySettingsRepository = RecordingCurrencySettingsRepository(),
            timeProvider = time,
            ioDispatcher = Dispatchers.Unconfined,
            privacyGate = gate
        )
    }

    @Test
    fun `INV-1b-3 denied gate returns DisabledByPrivacy and opens no connection`() = runTest {
        val fixture = fixture(
            now = histNow,
            privacyDecision = PrivacyDecision.Denied(PrivacyGateReasonCodes.EXCHANGE_RATE_DOWNLOAD_DISABLED)
        )
        fixture.repository.serveNothing()

        val outcome = fixture.repository.downloadRecentHistory("EUR")

        assertEquals(CurrencyRateHistoryOutcome.DisabledByPrivacy, outcome)
        assertEquals(0, connectionsOpened)
        assertEquals(listOf(PrivacyCapability.EXCHANGE_RATE_DOWNLOAD), fixture.privacyGate.checkedCapabilities)
        assertTrue(fixture.store.rates.isEmpty())
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())
    }

    @Test
    fun `INV-1b-3 fail closed gate with unbounded reason maps to PRIVACY_BLOCKED without a connection`() = runTest {
        val fixture = fixture(
            now = histNow,
            privacyDecision = PrivacyDecision.FailClosed("raw /storage/emulated/0/secret path")
        )
        fixture.repository.serveNothing()

        val outcome = fixture.repository.downloadRecentHistory("EUR")

        assertEquals(CurrencyRateHistoryOutcome.PrivacyCheckFailed(PrivacyGateReasonCodes.PRIVACY_BLOCKED), outcome)
        assertEquals(0, connectionsOpened)
    }

    @Test
    fun `INV-1b-3 gate that throws fails closed as PrivacyCheckFailed with no connection`() = runTest {
        val throwingGate = object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                throw IllegalStateException("gate exploded")
        }
        val repository = repositoryWithGate(throwingGate)
        repository.serveNothing()

        val outcome = repository.downloadRecentHistory("EUR")

        assertEquals(CurrencyRateHistoryOutcome.PrivacyCheckFailed(PrivacyGateReasonCodes.PRIVACY_BLOCKED), outcome)
        assertEquals(0, connectionsOpened)
    }

    @Test
    fun `C1 C2 redirect 301 is HTTP_ERROR with one connection and no Location read or request headers`() = runTest {
        val connection = fakeConnection(status = 301)
        val fixture = fixture(now = histNow)
        fixture.repository.serve(connection)

        val outcome = fixture.repository.downloadRecentHistory("EUR")

        assertEquals(CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.HTTP_ERROR), outcome)
        assertEquals(1, connectionsOpened)
        verify { connection.instanceFollowRedirects = false }
        verify { connection.useCaches = false }
        verify(exactly = 0) { connection.setRequestProperty(any(), any()) }
        verify(exactly = 0) { connection.addRequestProperty(any(), any()) }
        verify(exactly = 0) { connection.getHeaderField(any<String>()) }
        verify(atLeast = 1) { connection.disconnect() }
        assertTrue(fixture.store.rates.isEmpty())
    }

    @Test
    fun `INV-1b-9 not found 404 is HTTP_ERROR and stores nothing`() = runTest {
        val fixture = fixture(now = histNow)
        fixture.repository.serve(fakeConnection(status = 404))

        val outcome = fixture.repository.downloadRecentHistory("EUR")

        assertEquals(CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.HTTP_ERROR), outcome)
        assertTrue(fixture.store.rates.isEmpty())
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())
    }

    @Test
    fun `C4 declared Content-Length above 512 KiB is rejected before the body is read`() = runTest {
        val connection = fakeConnection(contentLength = 512L * 1024L + 1L)
        val repository = fixture(now = histNow).repository
        repository.serve(connection)

        val outcome = repository.downloadRecentHistory("EUR")

        assertEquals(CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.PAYLOAD_TOO_LARGE), outcome)
        verify(exactly = 0) { connection.inputStream }
    }

    @Test
    fun `C6 streamed body over 512 KiB without Content-Length is PAYLOAD_TOO_LARGE`() = runTest {
        val fixture = fixture(now = histNow)
        fixture.repository.serve(
            fakeConnection(contentLength = -1L, body = ByteArrayInputStream(ByteArray(512 * 1024 + 1) { 'a'.code.toByte() }))
        )

        val outcome = fixture.repository.downloadRecentHistory("EUR")

        assertEquals(CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.PAYLOAD_TOO_LARGE), outcome)
        assertTrue(fixture.store.rates.isEmpty())
    }

    @Test
    fun `C10 SocketTimeoutException is TIMEOUT and other IOException is OFFLINE`() = runTest {
        val timeoutConnection = fakeConnection()
        every { timeoutConnection.connect() } throws SocketTimeoutException("read timed out")
        val timeoutRepository = fixture(now = histNow).repository
        timeoutRepository.serve(timeoutConnection)
        assertEquals(
            CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.TIMEOUT),
            timeoutRepository.downloadRecentHistory("EUR")
        )
        verify(atLeast = 1) { timeoutConnection.disconnect() }

        val offlineConnection = fakeConnection()
        every { offlineConnection.connect() } throws IOException("Unable to resolve host")
        val offlineRepository = fixture(now = histNow).repository
        offlineRepository.serve(offlineConnection)
        assertEquals(
            CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.OFFLINE),
            offlineRepository.downloadRecentHistory("EUR")
        )
    }

    // ── MEC 1b: store, home currency, single-flight, cancellation (INV-1b-6, 10, 11, 12, 21) ──

    @Test
    fun `INV-1b-10 INV-1b-11 64 day download stores catalog cross pairs in one bulk write`() = runTest {
        val fixture = fixture(now = histNow)
        fixture.repository.serve(okHistoryConnection(64))

        val outcome = fixture.repository.downloadRecentHistory("EUR")

        assertEquals(
            CurrencyRateHistoryOutcome.Stored(
                days = 64,
                rows = 64 * pairsPerFixtureDay,
                earliestDate = histNewest.minusDays(63),
                latestDate = histNewest
            ),
            outcome
        )
        assertEquals(38_400, fixture.store.rates.size)
        assertEquals(1, fixture.store.insertAllCalls)
        assertEquals(listOf(histNow), fixture.settings.lastRateUpdates)
        assertTrue(fixture.store.rates.values.all { it.source == "ecb" && it.lastUpdated == histNow })
        assertTrue(fixture.store.rates.keys.none { it.from == it.to })
        // IDR is ECB-listed but not in the app catalog: never stored.
        assertTrue(fixture.store.rates.keys.none { it.from == "IDR" || it.to == "IDR" })
        val newestMidnight = histNewest.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val usdToGbp = fixture.store.rate("USD", "GBP", newestMidnight)
        assertNotNull(usdToGbp)
        assertEquals(1.05 / 1.01, usdToGbp!!.rate, 1e-9)
        assertEquals(newestMidnight - 63 * dayMillis, fixture.store.rates.keys.minOf { it.validDate })
    }

    @Test
    fun `INV-1b-12 repeating the download keeps the row count and never deletes or cleans up`() = runTest {
        val fixture = fixture(now = histNow)
        fixture.repository.serve(okHistoryConnection(64))
        fixture.repository.downloadRecentHistory("EUR")
        val firstCount = fixture.store.rates.size
        fixture.repository.serve(okHistoryConnection(64))

        val second = fixture.repository.downloadRecentHistory("EUR")

        assertTrue(second is CurrencyRateHistoryOutcome.Stored)
        assertEquals(64 * pairsPerFixtureDay, (second as CurrencyRateHistoryOutcome.Stored).rows)
        assertEquals(firstCount, fixture.store.rates.size)
        assertEquals(2, fixture.store.insertAllCalls)
        assertEquals(0, fixture.store.deleteOldRatesCalls)
    }

    @Test
    fun `INV-1b-10 home currency absent from the newest day is HOME_QUOTE_UNAVAILABLE and stores nothing`() = runTest {
        val fixture = fixture(now = histNow)
        fixture.repository.serve(okHistoryConnection(3))

        val outcome = fixture.repository.downloadRecentHistory("RUB")

        assertEquals(CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.HOME_QUOTE_UNAVAILABLE), outcome)
        assertTrue(fixture.store.rates.isEmpty())
        assertEquals(0, fixture.store.insertAllCalls)
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())
    }

    @Test
    fun `INV-1b-10 unknown home currency is INVALID_HOME_CURRENCY and stores nothing`() = runTest {
        val fixture = fixture(now = histNow)
        fixture.repository.serveNothing()

        val outcome = fixture.repository.downloadRecentHistory("XYZ")

        assertEquals(CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.INVALID_HOME_CURRENCY), outcome)
        assertEquals("home precheck must run before any connection is opened", 0, connectionsOpened)
        assertTrue(fixture.store.rates.isEmpty())
        assertEquals(0, fixture.store.insertAllCalls)
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())
    }

    @Test
    fun `INV-1b-10 blank and unknown home currency codes are rejected before opening a connection`() = runTest {
        val fixture = fixture(now = histNow)
        fixture.repository.serveNothing()

        listOf("", "  ", "ZZZ", "EURO").forEach { home ->
            assertEquals(
                "home=<$home>",
                CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.INVALID_HOME_CURRENCY),
                fixture.repository.downloadRecentHistory(home)
            )
        }
        assertEquals(0, connectionsOpened)
        assertTrue(fixture.store.rates.isEmpty())
    }

    @Test
    fun `INV-1b-6 second concurrent call is AlreadyInProgress and opens no second connection`() = runTest {
        val fixture = fixture(now = histNow)
        var second: CurrencyRateHistoryOutcome? = null
        val xml = historyXml(datesBack(3)).toByteArray(Charsets.UTF_8)
        val hooked = object : InputStream() {
            private val inner = ByteArrayInputStream(xml)
            private var first = true
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (first) {
                    first = false
                    second = runBlocking { fixture.repository.downloadRecentHistory("EUR") }
                }
                return inner.read(b, off, len)
            }
        }
        fixture.repository.serve(fakeConnection(body = hooked))

        val first = fixture.repository.downloadRecentHistory("EUR")

        assertEquals(CurrencyRateHistoryOutcome.AlreadyInProgress, second)
        assertTrue(first is CurrencyRateHistoryOutcome.Stored)
        assertEquals(1, connectionsOpened)

        // The lock is released after the first call: a later call runs normally.
        fixture.repository.serve(okHistoryConnection(3))
        assertTrue(fixture.repository.downloadRecentHistory("EUR") is CurrencyRateHistoryOutcome.Stored)
    }

    @Test
    fun `INV-1b-21 cancellation propagates is never mapped and disconnects the connection`() = runTest {
        val fixture = fixture(now = histNow)
        var jobRef: Job? = null
        var thrown: Throwable? = null
        var returned: CurrencyRateHistoryOutcome? = null
        val cancelling = object : InputStream() {
            private var first = true
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!first) return -1
                first = false
                jobRef?.cancel()
                b.fill(' '.code.toByte(), off, off + 10)
                return 10
            }
        }
        val connection = fakeConnection(body = cancelling)
        fixture.repository.serve(connection)

        val job = launch(Dispatchers.Unconfined) {
            jobRef = coroutineContext[Job]
            try {
                returned = fixture.repository.downloadRecentHistory("EUR")
            } catch (e: CancellationException) {
                thrown = e
                throw e
            }
        }
        job.join()

        assertTrue("CancellationException must propagate", thrown is CancellationException)
        assertEquals(null, returned)
        verify(atLeast = 1) { connection.disconnect() }
        assertTrue(fixture.store.rates.isEmpty())
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())
    }

    // ── MEC 1b: one constant log line per attempt (C9, C11) ───────────────────

    private suspend fun captureTimber(block: suspend () -> Unit): List<Triple<Int, String, Throwable?>> {
        val lines = mutableListOf<Triple<Int, String, Throwable?>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines += Triple(priority, message, t)
            }
        }
        Timber.plant(tree)
        try {
            block()
        } finally {
            Timber.uproot(tree)
        }
        return lines
    }

    private fun assertSingleConstantLine(expectedEnum: String, lines: List<Triple<Int, String, Throwable?>>) {
        assertEquals("lines were ${lines.map { it.second }}", 1, lines.size)
        assertEquals("RATE_HISTORY_DOWNLOAD $expectedEnum", lines.single().second)
        assertNull(lines.single().third)
        listOf("://", "ecb", "2026", "USD", "EUR", "38400", "64", "Exception").forEach {
            assertFalse("line leaks $it", lines.single().second.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `C9 C11 exactly one constant line per attempt for stored failed blocked and offline`() = runTest {
        val stored = captureTimber {
            val repository = fixture(now = histNow).repository
            repository.serve(okHistoryConnection(64))
            repository.downloadRecentHistory("EUR")
        }
        assertSingleConstantLine("STORED", stored)

        val http = captureTimber {
            val repository = fixture(now = histNow).repository
            repository.serve(fakeConnection(status = 404))
            repository.downloadRecentHistory("EUR")
        }
        assertSingleConstantLine("HTTP_ERROR", http)

        val offline = captureTimber {
            val connection = fakeConnection()
            every { connection.connect() } throws IOException("https://www.ecb.europa.eu/stats unreachable")
            val repository = fixture(now = histNow).repository
            repository.serve(connection)
            repository.downloadRecentHistory("EUR")
        }
        assertSingleConstantLine("OFFLINE", offline)

        val denied = captureTimber {
            fixture(
                now = histNow,
                privacyDecision = PrivacyDecision.Denied(PrivacyGateReasonCodes.EXCHANGE_RATE_DOWNLOAD_DISABLED)
            ).repository.downloadRecentHistory("EUR")
        }
        assertSingleConstantLine("DISABLED_BY_PRIVACY", denied)
    }

    @Test
    fun `C9 INV-1b-21 cancelled attempt logs one constant CANCELLED line`() = runTest {
        var jobRef: Job? = null
        val cancelling = object : InputStream() {
            private var first = true
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!first) return -1
                first = false
                jobRef?.cancel()
                return 1.also { b[off] = ' '.code.toByte() }
            }
        }
        val repository = fixture(now = histNow).repository
        repository.serve(fakeConnection(body = cancelling))

        val lines = captureTimber {
            val job = launch(Dispatchers.Unconfined) {
                jobRef = coroutineContext[Job]
                repository.downloadRecentHistory("EUR")
            }
            job.join()
        }

        assertSingleConstantLine("CANCELLED", lines)
    }

    @Test
    fun `F3b INV-1b-11 a store exception is STORE_FAILED and never updates the last rate timestamp`() = runTest {
        val settings = RecordingCurrencySettingsRepository()
        val repository = repositoryOf(
            FailingBulkStore(PairDateRateStore()) { IllegalStateException("disk full") },
            settings
        )
        repository.serve(okHistoryConnection(3))

        val lines = captureTimber {
            assertEquals(
                CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.STORE_FAILED),
                repository.downloadRecentHistory("EUR")
            )
        }

        assertTrue(settings.lastRateUpdates.isEmpty())
        assertSingleConstantLine("STORE_FAILED", lines)
    }

    @Test
    fun `F3c INV-1b-13 setLastRateUpdate failure after a committed store still returns Stored`() = runTest {
        val store = PairDateRateStore()
        val settings = ThrowingLastUpdateSettings(RecordingCurrencySettingsRepository())
        val repository = repositoryOf(store, settings)
        repository.serve(okHistoryConnection(3))

        val outcome = repository.downloadRecentHistory("EUR")

        assertEquals(
            CurrencyRateHistoryOutcome.Stored(
                days = 3,
                rows = 3 * pairsPerFixtureDay,
                earliestDate = histNewest.minusDays(2),
                latestDate = histNewest
            ),
            outcome
        )
        assertEquals(1, settings.setCalls)
        assertEquals(3 * pairsPerFixtureDay, store.rates.size)
        assertEquals(1, store.insertAllCalls)
    }

    @Test
    fun `F4 opener IOException is OFFLINE with one constant line and never throws`() = runTest {
        val fixture = fixture(now = histNow)
        fixture.repository.historyConnectionOpener = {
            connectionsOpened += 1
            throw IOException("https://www.ecb.europa.eu/stats unreachable")
        }
        connectionsOpened = 0

        val lines = captureTimber {
            assertEquals(
                CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.OFFLINE),
                fixture.repository.downloadRecentHistory("EUR")
            )
        }

        assertEquals(1, connectionsOpened)
        assertSingleConstantLine("OFFLINE", lines)
        assertTrue(fixture.store.rates.isEmpty())
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())
        // The single-flight lock was released: a later call runs normally.
        fixture.repository.serve(okHistoryConnection(3))
        assertTrue(fixture.repository.downloadRecentHistory("EUR") is CurrencyRateHistoryOutcome.Stored)
    }

    @Test
    fun `F4 opener CancellationException propagates and is never mapped to OFFLINE`() = runTest {
        val repository = fixture(now = histNow).repository
        repository.historyConnectionOpener = { throw CancellationException("cancelled while opening") }
        var thrown: Throwable? = null
        var returned: CurrencyRateHistoryOutcome? = null

        captureTimber {
            try {
                returned = repository.downloadRecentHistory("EUR")
            } catch (e: CancellationException) {
                thrown = e
            }
        }

        assertTrue("CancellationException must propagate", thrown is CancellationException)
        assertNull(returned)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `F3a 30 second deadline returns TIMEOUT and disconnects the stuck connection`() = runTest {
        val readStarted = CountDownLatch(1)
        val released = CountDownLatch(1)
        var unblockedByDisconnect = false
        val stuckBody = object : InputStream() {
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                readStarted.countDown()
                unblockedByDisconnect = released.await(20, TimeUnit.SECONDS)
                throw IOException("socket closed")
            }
        }
        val connection = fakeConnection(body = stuckBody)
        every { connection.disconnect() } answers { released.countDown() }
        val store = PairDateRateStore()
        val settings = RecordingCurrencySettingsRepository()
        val repository = repositoryOf(
            store,
            settings,
            VirtualDelayDispatcher(Dispatchers.IO, StandardTestDispatcher(testScheduler))
        )
        repository.serve(connection)

        val result = async(Dispatchers.Default) { repository.downloadRecentHistory("EUR") }
        assertTrue("read never started", readStarted.await(10, TimeUnit.SECONDS))

        testScheduler.advanceTimeBy(29_999L)
        testScheduler.runCurrent()
        assertFalse("deadline fired early", result.isCompleted)
        verify(exactly = 0) { connection.disconnect() }

        testScheduler.advanceTimeBy(2L)
        testScheduler.runCurrent()
        assertEquals(CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.TIMEOUT), result.await())
        verify(atLeast = 1) { connection.disconnect() }
        assertTrue("stuck read was not unblocked by disconnect()", unblockedByDisconnect)
        assertTrue(store.rates.isEmpty())
        assertTrue(settings.lastRateUpdates.isEmpty())
    }

    // ── MEC 1b: reviewer findings F3 / F4 (deadline, store failure, best-effort settings, opener) ──

    // ── RA-FX-1 / N-07-1..4: daily refresh network phase (dailyConnectionOpener seam, never a real URL) ──

    private var dailyConnectionsOpened = 0

    private fun CurrencyRatesRepositoryImpl.serveDaily(connection: HttpURLConnection) {
        dailyConnectionsOpened = 0
        dailyConnectionOpener = {
            dailyConnectionsOpened += 1
            connection
        }
    }

    /** An opener that must never be called; counts invocations and fails if reached. */
    private fun CurrencyRatesRepositoryImpl.serveDailyNothing() {
        dailyConnectionsOpened = 0
        dailyConnectionOpener = {
            dailyConnectionsOpened += 1
            throw AssertionError("daily connection opener must not be called")
        }
    }

    private fun okDailyConnection(timeAttribute: String? = "2026-09-25") =
        fakeConnection(body = stream(ecbXml(timeAttribute = timeAttribute)))

    private inline fun <T> withDefaultTimeZone(zoneId: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
            return block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun utcMidnight(isoDate: String): Long =
        LocalDate.parse(isoDate).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `N-07-1 daily connection disables redirects and caches and sends no request headers`() = runTest {
        val connection = okDailyConnection()
        val fixture = fixture(now = fetchedNextDayMillis)
        fixture.repository.serveDaily(connection)

        val outcome = fixture.repository.refresh("USD")

        assertTrue("Expected Refreshed but was $outcome", outcome is CurrencyRateRefreshOutcome.Refreshed)
        assertTrue((outcome as CurrencyRateRefreshOutcome.Refreshed).storedPairs > 0)
        assertEquals(1, dailyConnectionsOpened)
        verify { connection.instanceFollowRedirects = false }
        verify { connection.useCaches = false }
        verify(exactly = 0) { connection.instanceFollowRedirects = true }
        verify(exactly = 0) { connection.useCaches = true }
        verify(exactly = 0) { connection.setRequestProperty(any(), any()) }
        verify(exactly = 0) { connection.addRequestProperty(any(), any()) }
        verify(exactly = 0) { connection.getHeaderField(any<String>()) }
        verify(atLeast = 1) { connection.disconnect() }
        assertTrue(fixture.store.rates.isNotEmpty())
    }

    @Test
    fun `N-07-1 daily 3xx fails with the HTTP N message without following Location or reading the body`() = runTest {
        listOf(300, 301, 302, 303, 307, 308).forEach { status ->
            val connection = fakeConnection(status = status, body = stream(ecbXml()))
            val fixture = fixture(now = fetchedNextDayMillis)
            fixture.repository.serveDaily(connection)

            try {
                fixture.repository.refresh("USD")
                fail("Expected a failed refresh for HTTP $status")
            } catch (error: IllegalStateException) {
                assertEquals("Rate provider returned HTTP $status", error.message)
            }

            assertEquals("status=$status", 1, dailyConnectionsOpened)
            verify { connection.instanceFollowRedirects = false }
            verify(exactly = 0) { connection.getHeaderField(any<String>()) }
            verify(exactly = 0) { connection.inputStream }
            verify(atLeast = 1) { connection.disconnect() }
            assertTrue("status=$status", fixture.store.rates.isEmpty())
            assertTrue("status=$status", fixture.settings.lastRateUpdates.isEmpty())
        }
    }

    @Test
    fun `N-07-1 daily 404 and 500 keep the HTTP N message and the 2xx boundary 299 still refreshes`() = runTest {
        listOf(404, 500).forEach { status ->
            val fixture = fixture(now = fetchedNextDayMillis)
            fixture.repository.serveDaily(fakeConnection(status = status))
            try {
                fixture.repository.refresh("USD")
                fail("Expected a failed refresh for HTTP $status")
            } catch (error: IllegalStateException) {
                assertEquals("Rate provider returned HTTP $status", error.message)
            }
            assertTrue(fixture.store.rates.isEmpty())
        }

        val fixture = fixture(now = fetchedNextDayMillis)
        fixture.repository.serveDaily(fakeConnection(status = 299, body = stream(ecbXml())))
        assertTrue(fixture.repository.refresh("USD") is CurrencyRateRefreshOutcome.Refreshed)
    }

    @Test
    fun `N-07-2 daily declared Content-Length above 256 KiB is RATE_PAYLOAD_TOO_LARGE and the stream is never read`() = runTest {
        val connection = fakeConnection(contentLength = dailyCapBytes.toLong() + 1L, body = stream(ecbXml()))
        val fixture = fixture(now = fetchedNextDayMillis)
        fixture.repository.serveDaily(connection)

        try {
            fixture.repository.refresh("USD")
            fail("Expected RATE_PAYLOAD_TOO_LARGE")
        } catch (error: IllegalStateException) {
            assertEquals("RATE_PAYLOAD_TOO_LARGE", error.message)
        }

        verify(exactly = 0) { connection.inputStream }
        verify(atLeast = 1) { connection.disconnect() }
        assertTrue(fixture.store.rates.isEmpty())
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())
    }

    @Test
    fun `N-07-2 daily declared Content-Length of exactly 256 KiB or unknown still refreshes`() = runTest {
        listOf(dailyCapBytes.toLong(), -1L, 0L).forEach { declared ->
            val fixture = fixture(now = fetchedNextDayMillis)
            fixture.repository.serveDaily(fakeConnection(contentLength = declared, body = stream(ecbXml())))

            val outcome = fixture.repository.refresh("USD")

            assertTrue("declared=$declared outcome=$outcome", outcome is CurrencyRateRefreshOutcome.Refreshed)
            assertTrue(fixture.store.rates.isNotEmpty())
        }
    }

    @Test
    fun `N-07-2 publication date of today plus 1 UTC passes and today plus 2 is OUT_OF_BOUNDS in any device time zone`() {
        val justBeforeUtcMidnight = Instant.parse("2026-09-25T23:59:59Z").toEpochMilli()
        val justAfterUtcMidnight = Instant.parse("2026-09-26T00:00:01Z").toEpochMilli()
        // Far east (UTC+14), far west (UTC-11), US west coast and a mid east zone, plus UTC as control.
        listOf("UTC", "Pacific/Kiritimati", "Pacific/Pago_Pago", "America/Los_Angeles", "Asia/Tokyo").forEach { zone ->
            withDefaultTimeZone(zone) {
                assertDailyDateBound(zone, justBeforeUtcMidnight, accepted = "2026-09-26", rejected = "2026-09-27")
                assertDailyDateBound(zone, justAfterUtcMidnight, accepted = "2026-09-27", rejected = "2026-09-28")
            }
        }
    }

    private fun assertDailyDateBound(zone: String, nowMillis: Long, accepted: String, rejected: String) {
        val parsed = fixture(now = nowMillis).repository.parseEcbDailyRates(stream(ecbXml(timeAttribute = accepted)))
        assertEquals("zone=$zone accepted=$accepted", utcMidnight(accepted), parsed.publicationMillis)

        try {
            fixture(now = nowMillis).repository.parseEcbDailyRates(stream(ecbXml(timeAttribute = rejected)))
            fail("zone=$zone: $rejected must be rejected as OUT_OF_BOUNDS")
        } catch (error: EcbPublicationDateUnavailableException) {
            assertEquals("zone=$zone rejected=$rejected", EcbPublicationDateFailureReason.OUT_OF_BOUNDS, error.reasonCode)
            assertEquals("OUT_OF_BOUNDS", error.message)
        }
    }

    @Test
    fun `N-07-2 daily refresh with a publication date of today plus 2 fails OUT_OF_BOUNDS and stores nothing`() = runTest {
        val now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()
        val connection = okDailyConnection(timeAttribute = "2026-09-27")
        val fixture = fixture(now = now)
        fixture.repository.serveDaily(connection)

        try {
            fixture.repository.refresh("USD")
            fail("Expected OUT_OF_BOUNDS")
        } catch (error: EcbPublicationDateUnavailableException) {
            assertEquals(EcbPublicationDateFailureReason.OUT_OF_BOUNDS, error.reasonCode)
        }

        verify(atLeast = 1) { connection.disconnect() }
        assertTrue(fixture.store.rates.isEmpty())
        assertTrue(fixture.settings.lastRateUpdates.isEmpty())

        // Control: the same file one day earlier (today + 1) refreshes.
        val control = fixture(now = now)
        control.repository.serveDaily(okDailyConnection(timeAttribute = "2026-09-26"))
        assertTrue(control.repository.refresh("USD") is CurrencyRateRefreshOutcome.Refreshed)
        assertTrue(control.store.rates.values.all { it.validDate == utcMidnight("2026-09-26") })
    }

    @Test
    fun `KNOWN LIMIT device clock more than a day behind the ECB publication date fails the refresh as OUT_OF_BOUNDS`() = runTest {
        // Documents today's behaviour only: a device clock set behind the publication date by more than one
        // day makes the (valid) published file look future-dated, so the refresh fails and nothing is stored.
        val clockThreeDaysBehind = Instant.parse("2026-09-22T12:00:00Z").toEpochMilli()
        val connection = okDailyConnection(timeAttribute = "2026-09-25")
        val behind = fixture(now = clockThreeDaysBehind)
        behind.repository.serveDaily(connection)

        try {
            behind.repository.refresh("USD")
            fail("Known limit: a clock more than a day behind is currently rejected")
        } catch (error: EcbPublicationDateUnavailableException) {
            assertEquals(EcbPublicationDateFailureReason.OUT_OF_BOUNDS, error.reasonCode)
        }
        verify(atLeast = 1) { connection.disconnect() }
        assertTrue(behind.store.rates.isEmpty())
        assertTrue(behind.settings.lastRateUpdates.isEmpty())

        // Boundary: exactly one day behind (clock date + 1 == publication date) still refreshes.
        val oneDayBehind = fixture(now = Instant.parse("2026-09-24T12:00:00Z").toEpochMilli())
        oneDayBehind.repository.serveDaily(okDailyConnection(timeAttribute = "2026-09-25"))
        assertTrue(oneDayBehind.repository.refresh("USD") is CurrencyRateRefreshOutcome.Refreshed)
    }

    @Test
    fun `N-07-3 gate that throws fails closed as PrivacyCheckFailed PRIVACY_BLOCKED with no daily connection`() = runTest {
        val throwingGate = object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                throw IllegalStateException("gate exploded")
        }
        val repository = repositoryWithGate(throwingGate)
        repository.serveDailyNothing()

        val outcome = repository.refresh("USD")

        assertEquals(CurrencyRateRefreshOutcome.PrivacyCheckFailed(PrivacyGateReasonCodes.PRIVACY_BLOCKED), outcome)
        assertEquals(0, dailyConnectionsOpened)
    }

    @Test
    fun `N-07-3 CancellationException from the gate is rethrown and never mapped to an outcome`() = runTest {
        val cancellingGate = object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                throw CancellationException("gate cancelled")
        }
        val repository = repositoryWithGate(cancellingGate)
        repository.serveDailyNothing()
        var thrown: Throwable? = null
        var returned: CurrencyRateRefreshOutcome? = null

        try {
            returned = repository.refresh("USD")
        } catch (e: CancellationException) {
            thrown = e
        }

        assertTrue("CancellationException must propagate", thrown is CancellationException)
        assertNull(returned)
        assertEquals(0, dailyConnectionsOpened)
    }

    @Test
    fun `N-07-4 two concurrent refreshes never hold two daily connections at once`() = runTest {
        val firstReading = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondPastGate = CountDownLatch(1)
        val gateCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision {
                if (gateCalls.incrementAndGet() == 2) secondPastGate.countDown()
                return PrivacyDecision.Allowed
            }
        }
        val gatedBody = object : InputStream() {
            private val inner = ByteArrayInputStream(ecbXml().toByteArray(Charsets.UTF_8))
            private var gated = true
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (gated) {
                    gated = false
                    firstReading.countDown()
                    if (!releaseFirst.await(20, TimeUnit.SECONDS)) throw IOException("test gate never released")
                }
                return inner.read(b, off, len)
            }
        }
        val openNow = java.util.concurrent.atomic.AtomicInteger(0)
        val maxOpen = java.util.concurrent.atomic.AtomicInteger(0)
        val opened = java.util.concurrent.atomic.AtomicInteger(0)
        val connections = listOf(fakeConnection(body = gatedBody), okDailyConnection())
        connections.forEach { connection ->
            every { connection.disconnect() } answers { openNow.decrementAndGet() }
        }
        val store = PairDateRateStore()
        val settings = RecordingCurrencySettingsRepository()
        val time = MutableTimeProvider(fetchedNextDayMillis)
        val repository = CurrencyRatesRepositoryImpl(
            currencyConverter = CurrencyConverter(store, time),
            currencySettingsRepository = settings,
            timeProvider = time,
            ioDispatcher = Dispatchers.IO,
            privacyGate = gate
        )
        repository.dailyConnectionOpener = {
            val index = opened.getAndIncrement()
            maxOpen.accumulateAndGet(openNow.incrementAndGet()) { a, b -> maxOf(a, b) }
            connections[index]
        }

        try {
            val first = async(Dispatchers.Default) { repository.refresh("USD") }
            assertTrue("first refresh never started reading", firstReading.await(10, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) { repository.refresh("USD") }
            assertTrue("second refresh never passed the gate", secondPastGate.await(10, TimeUnit.SECONDS))
            releaseFirst.countDown()

            val outcomes = listOf(first.await(), second.await())

            assertTrue(outcomes.all { it is CurrencyRateRefreshOutcome.Refreshed })
            assertEquals("connections were open at the same time", 1, maxOpen.get())
            assertEquals(2, opened.get())
            assertEquals(0, openNow.get())
            assertEquals(2, settings.lastRateUpdates.size)
            connections.forEach { verify(exactly = 1) { it.disconnect() } }
        } finally {
            releaseFirst.countDown()
        }
    }

    @Test
    fun `N-07-4 cancelling a refresh stuck in a read disconnects the connection and unblocks the read`() = runTest {
        val readStarted = CountDownLatch(1)
        val released = CountDownLatch(1)
        val unblockedByDisconnect = java.util.concurrent.atomic.AtomicBoolean(false)
        val stuckBody = object : InputStream() {
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                readStarted.countDown()
                unblockedByDisconnect.set(released.await(20, TimeUnit.SECONDS))
                throw IOException("socket closed")
            }
        }
        val connection = fakeConnection(body = stuckBody)
        every { connection.disconnect() } answers { released.countDown() }
        val store = PairDateRateStore()
        val settings = RecordingCurrencySettingsRepository()
        val repository = repositoryOf(store, settings, Dispatchers.IO)
        repository.serveDaily(connection)

        val job = async(Dispatchers.Default) { repository.refresh("USD") }
        assertTrue("read never started", readStarted.await(10, TimeUnit.SECONDS))
        verify(exactly = 0) { connection.disconnect() }

        // Only disconnect() releases the stuck read; join() returns once the read has been unblocked.
        job.cancel()
        job.join()

        assertTrue("refresh must end cancelled", job.isCancelled)
        verify(atLeast = 1) { connection.disconnect() }
        assertTrue("stuck read was not unblocked by disconnect()", unblockedByDisconnect.get())
        assertTrue(store.rates.isEmpty())
        assertTrue(settings.lastRateUpdates.isEmpty())
    }

    // ── RA-FX-1 / N-07-3: the gate is decided again after the single-flight wait (fix 1.0.4 S6) ──

    private class HeldRefreshRun(
        val first: CurrencyRateRefreshOutcome,
        val second: CurrencyRateRefreshOutcome,
        val openerCalls: Int,
        val gateCallsWhileFirstHeldLock: Int,
        val secondDoneWhileFirstHeldLock: Boolean,
        val gateCallsAtEnd: Int
    )

    /**
     * Refresh 1 is parked inside the daily opener, so it holds the single-flight lock. Refresh 2 is started
     * only after refresh 1 is parked and is released to run only after its pre-lock gate check (gate call
     * 3) has happened. Only then is the gate switched by [flipTo], and refresh 1 is released. Real IO
     * threads; both refreshes are awaited before returning.
     */
    private suspend fun refreshWhileGateFlips(flipTo: () -> PrivacyDecision): HeldRefreshRun = coroutineScope {
        val openerEntered = CountDownLatch(1)
        val releaseOpener = CountDownLatch(1)
        val secondPastPreLockGate = CountDownLatch(1)
        val openerCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = SwitchableGate { PrivacyDecision.Allowed }
        gate.onCall = { callNumber -> if (callNumber == 3) secondPastPreLockGate.countDown() }
        val time = MutableTimeProvider(fetchedNextDayMillis)
        val repository = CurrencyRatesRepositoryImpl(
            currencyConverter = CurrencyConverter(PairDateRateStore(), time),
            currencySettingsRepository = RecordingCurrencySettingsRepository(),
            timeProvider = time,
            ioDispatcher = Dispatchers.IO,
            privacyGate = gate
        )
        repository.dailyConnectionOpener = {
            if (openerCalls.incrementAndGet() != 1) throw AssertionError("a second daily connection was opened")
            openerEntered.countDown()
            if (!releaseOpener.await(20, TimeUnit.SECONDS)) throw IOException("test gate never released")
            okDailyConnection()
        }

        try {
            val first = async(Dispatchers.Default) { repository.refresh("USD") }
            assertTrue("refresh 1 never reached the opener", openerEntered.await(10, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) { repository.refresh("USD") }
            assertTrue("refresh 2 never passed the pre-lock gate", secondPastPreLockGate.await(10, TimeUnit.SECONDS))

            gate.behavior = flipTo
            // Refresh 1 still holds the lock: refresh 2 must be parked on it, not past it. Time only gives a
            // wrong result a chance to show; it cannot make this fail on a correct implementation.
            Thread.sleep(300)
            val callsWhileHeld = gate.calls.get()
            val secondDoneWhileHeld = second.isCompleted

            releaseOpener.countDown()
            HeldRefreshRun(
                first = first.await(),
                second = second.await(),
                openerCalls = openerCalls.get(),
                gateCallsWhileFirstHeldLock = callsWhileHeld,
                secondDoneWhileFirstHeldLock = secondDoneWhileHeld,
                gateCallsAtEnd = gate.calls.get()
            )
        } finally {
            releaseOpener.countDown()
        }
    }

    @Test
    fun `RA-FX-1 a refresh queued behind another is DisabledByPrivacy when the gate is denied during the wait`() = runTest {
        val run = refreshWhileGateFlips {
            PrivacyDecision.Denied(PrivacyGateReasonCodes.EXCHANGE_RATE_DOWNLOAD_DISABLED)
        }

        // Control: refresh 1 passed both of its own checks before the flip and completed normally.
        assertTrue("first was ${run.first}", run.first is CurrencyRateRefreshOutcome.Refreshed)
        // Refresh 2 had passed its pre-lock check, and was parked on the lock while refresh 1 held it.
        assertEquals(3, run.gateCallsWhileFirstHeldLock)
        assertFalse("refresh 2 finished while refresh 1 held the lock", run.secondDoneWhileFirstHeldLock)
        // The denial can only come from a gate check made after the wait (a fourth call).
        assertEquals(CurrencyRateRefreshOutcome.DisabledByPrivacy, run.second)
        assertEquals(4, run.gateCallsAtEnd)
        assertEquals("only refresh 1 may open a connection", 1, run.openerCalls)
    }

    @Test
    fun `RA-FX-1 a refresh queued behind another fails closed when the gate throws during the wait`() = runTest {
        val run = refreshWhileGateFlips { throw IllegalStateException("gate exploded after the wait") }

        assertTrue("first was ${run.first}", run.first is CurrencyRateRefreshOutcome.Refreshed)
        assertEquals(3, run.gateCallsWhileFirstHeldLock)
        assertFalse("refresh 2 finished while refresh 1 held the lock", run.secondDoneWhileFirstHeldLock)
        assertEquals(
            CurrencyRateRefreshOutcome.PrivacyCheckFailed(PrivacyGateReasonCodes.PRIVACY_BLOCKED),
            run.second
        )
        assertEquals(4, run.gateCallsAtEnd)
        assertEquals("only refresh 1 may open a connection", 1, run.openerCalls)
    }

    @Test
    fun `RA-FX-1 CancellationException from the gate inside the lock is rethrown and never mapped`() = runTest {
        val gate = SwitchableGate { PrivacyDecision.Allowed }
        // Call 1 is the pre-lock check; call 2 is the check inside the single-flight lock.
        gate.onCall = { callNumber ->
            if (callNumber == 2) throw CancellationException("gate cancelled inside the lock")
        }
        val repository = repositoryWithGate(gate)
        repository.serveDailyNothing()
        var thrown: Throwable? = null
        var returned: CurrencyRateRefreshOutcome? = null

        try {
            returned = repository.refresh("USD")
        } catch (e: CancellationException) {
            thrown = e
        }

        assertTrue("CancellationException must propagate", thrown is CancellationException)
        assertNull(returned)
        assertEquals(2, gate.calls.get())
        assertEquals(0, dailyConnectionsOpened)

        // The lock is not left held: a later refresh with an allowed gate runs to completion.
        repository.serveDaily(okDailyConnection())
        assertTrue(repository.refresh("USD") is CurrencyRateRefreshOutcome.Refreshed)
        assertEquals(1, dailyConnectionsOpened)
    }

    /** Store whose bulk write always fails with [failure]; everything else delegates to a real fake. */
    private class FailingBulkStore(
        private val delegate: ExchangeRateStore,
        private val failure: () -> Throwable
    ) : ExchangeRateStore by delegate {
        override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {
            throw failure()
        }
    }

    /** Settings whose lastRateUpdate write always throws; counts the attempts. */
    private class ThrowingLastUpdateSettings(
        delegate: CurrencySettingsRepository
    ) : CurrencySettingsRepository by delegate {
        var setCalls = 0

        override suspend fun setLastRateUpdate(timestamp: Long) {
            setCalls += 1
            throw IOException("settings write failed")
        }
    }

    /** Dispatches on a real pool but takes timeouts from the virtual [virtual] scheduler. */
    @OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private class VirtualDelayDispatcher(
        private val real: CoroutineDispatcher,
        private val virtual: TestDispatcher
    ) : CoroutineDispatcher(), Delay by virtual {
        override fun dispatch(context: CoroutineContext, block: Runnable) = real.dispatch(context, block)
    }

    private fun repositoryOf(
        store: ExchangeRateStore,
        settings: CurrencySettingsRepository,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    ): CurrencyRatesRepositoryImpl {
        val time = MutableTimeProvider(histNow)
        return CurrencyRatesRepositoryImpl(
            currencyConverter = CurrencyConverter(store, time),
            currencySettingsRepository = settings,
            timeProvider = time,
            ioDispatcher = dispatcher,
            privacyGate = FixedPrivacyGate(PrivacyDecision.Allowed)
        )
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

    var insertAllCalls = 0
    var deleteOldRatesCalls = 0

    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {
        insertAllCalls += 1
        rates.forEach { insertOrUpdate(it) }
    }

    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> =
        flowOf(rates.values.filter { it.toCurrency == targetCurrency })

    override suspend fun getLatestRate(): DomainExchangeRate? =
        rates.values.maxByOrNull { it.lastUpdated }

    override suspend fun deleteOldRates(olderThan: Long) {
        deleteOldRatesCalls += 1
        rates.entries.removeAll { it.value.lastUpdated < olderThan }
    }
}

private class MutableTimeProvider(var currentMillis: Long) : TimeProvider {
    override fun now(): Long = currentMillis
}

/** Thread-safe gate whose answer can change between calls; [onCall] runs first with the 1-based call number. */
private class SwitchableGate(@Volatile var behavior: () -> PrivacyDecision) : PrivacyGate {
    val calls = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    var onCall: (Int) -> Unit = {}

    override suspend fun check(
        capability: PrivacyCapability,
        context: Map<String, String>
    ): PrivacyDecision {
        // onCall runs after behavior() has read its decision, so a test released by onCall
        // cannot flip the gate underneath the call that released it.
        val callNumber = calls.incrementAndGet()
        try {
            return behavior()
        } finally {
            onCall(callNumber)
        }
    }
}

private class FixedPrivacyGate(private val decision: PrivacyDecision) : PrivacyGate {
    var checkCount: Int = 0
    val checkedCapabilities = mutableListOf<PrivacyCapability>()

    override suspend fun check(
        capability: PrivacyCapability,
        context: Map<String, String>
    ): PrivacyDecision {
        checkCount += 1
        checkedCapabilities += capability
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
