package com.yourname.expensetracker.data.repository

import androidx.annotation.VisibleForTesting
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRateHistoryOutcome
import com.yourname.expensetracker.domain.currency.CurrencyRateRefreshOutcome
import com.yourname.expensetracker.domain.currency.CurrencyRatesRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.RateHistoryDay
import com.yourname.expensetracker.domain.currency.RateHistoryFailureReason
import com.yourname.expensetracker.domain.currency.SupportedCurrency
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacyGateReasonCodes
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import timber.log.Timber

@Singleton
class CurrencyRatesRepositoryImpl @Inject constructor(
    private val currencyConverter: CurrencyConverter,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val privacyGate: PrivacyGate
) : CurrencyRatesRepository {

    companion object {
        private const val ECB_DAILY_RATES_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
        private const val TIMEOUT_MS = 10_000
        private const val ACCESS_EXTERNAL_DTD_PROPERTY = "http://javax.xml.XMLConstants/property/accessExternalDTD"
        private const val ACCESS_EXTERNAL_SCHEMA_PROPERTY = "http://javax.xml.XMLConstants/property/accessExternalSchema"
        private const val ECB_NAMESPACE = "http://www.ecb.int/vocabulary/2002-08-01/eurofxref"

        // MEC 1b history download (INV-1b-1, INV-1b-9). The only history endpoint; no fallback.
        private const val ECB_HISTORY_90D_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml"
        internal const val MAX_HISTORY_BYTES = 512 * 1024
        internal const val MAX_HISTORY_DAYS = 70
        internal const val MAX_HISTORY_SPAN_DAYS = 100L
        internal const val HISTORY_DEADLINE_MS = 30_000L
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val HISTORY_CODE_CANCELLED = "CANCELLED"

        /** Row cap = MAX_HISTORY_DAYS x c x (c - 1), c = catalog size (28 -> 52,920). */
        internal val MAX_HISTORY_ROWS: Int =
            SupportedCurrency.catalog.size.let { c -> MAX_HISTORY_DAYS * c * (c - 1) }
    }

    private val historyMutex = Mutex()

    /**
     * Test seam (F5): the only way the history download opens a connection, invoked solely inside the
     * attempt after the privacy gate. Production never sets it; the default targets the ECB 90-day file.
     */
    @VisibleForTesting
    internal var historyConnectionOpener: () -> HttpURLConnection =
        { URL(ECB_HISTORY_90D_URL).openConnection() as HttpURLConnection }

    override suspend fun refresh(homeCurrency: String): CurrencyRateRefreshOutcome = withContext(ioDispatcher) {
        // PRIVACY GATE: exchange-rate download has its own capability (default OFF); check before any HTTP call.
        val gateCheck = privacyGate.check(PrivacyCapability.EXCHANGE_RATE_DOWNLOAD)
        if (gateCheck.blocksExecution()) {
            val blockedOutcome = when (gateCheck) {
                is PrivacyDecision.Denied -> CurrencyRateRefreshOutcome.DisabledByPrivacy
                else -> {
                    val reason = gateCheck.reason()
                    CurrencyRateRefreshOutcome.PrivacyCheckFailed(
                        if (reason in PrivacyGateReasonCodes.ALL) reason else PrivacyGateReasonCodes.PRIVACY_BLOCKED
                    )
                }
            }
            Timber.w("CurrencyRatesRepository: refresh blocked by privacy gate (%s)", blockedOutcome::class.simpleName)
            return@withContext blockedOutcome
        }

        val connection = (URL(ECB_DAILY_RATES_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Rate provider returned HTTP ${connection.responseCode}")
            }

            val storedPairs = connection.inputStream.use { stream ->
                refreshFromStream(homeCurrency, stream)
            }
            CurrencyRateRefreshOutcome.Refreshed(storedPairs)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun downloadRecentHistory(homeCurrency: String): CurrencyRateHistoryOutcome =
        withContext(ioDispatcher) {
            // C3: the privacy gate runs before the single-flight lock and before any connection object exists.
            val gateCheck = try {
                privacyGate.check(PrivacyCapability.EXCHANGE_RATE_DOWNLOAD)
            } catch (e: CancellationException) {
                logHistoryAttempt(HISTORY_CODE_CANCELLED)
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                null
            }
            if (gateCheck == null) {
                // Fail closed: a gate that cannot answer blocks the download.
                val failed = CurrencyRateHistoryOutcome.PrivacyCheckFailed(PrivacyGateReasonCodes.PRIVACY_BLOCKED)
                logHistoryAttempt(historyOutcomeCode(failed))
                return@withContext failed
            }
            if (gateCheck.blocksExecution()) {
                val blocked = when (gateCheck) {
                    is PrivacyDecision.Denied -> CurrencyRateHistoryOutcome.DisabledByPrivacy
                    else -> {
                        val reason = gateCheck.reason()
                        CurrencyRateHistoryOutcome.PrivacyCheckFailed(
                            if (reason in PrivacyGateReasonCodes.ALL) reason else PrivacyGateReasonCodes.PRIVACY_BLOCKED
                        )
                    }
                }
                logHistoryAttempt(historyOutcomeCode(blocked))
                return@withContext blocked
            }

            // F3: an unsupported home code fails before any lock or connection (HOME_QUOTE_UNAVAILABLE is
            // judged later, on the parsed newest day).
            if (SupportedCurrency.fromCode(homeCurrency) == null) {
                val invalidHome = CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.INVALID_HOME_CURRENCY)
                logHistoryAttempt(historyOutcomeCode(invalidHome))
                return@withContext invalidHome
            }

            // INV-1b-6: single-flight per process; a second caller opens no connection.
            if (!historyMutex.tryLock()) {
                logHistoryAttempt(historyOutcomeCode(CurrencyRateHistoryOutcome.AlreadyInProgress))
                return@withContext CurrencyRateHistoryOutcome.AlreadyInProgress
            }
            try {
                val outcome = try {
                    withTimeoutOrNull(HISTORY_DEADLINE_MS) { runHistoryAttempt(homeCurrency) }
                        ?: CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.TIMEOUT)
                } catch (e: CancellationException) {
                    // INV-1b-21: one constant diagnostic, then the cancellation always propagates.
                    logHistoryAttempt(HISTORY_CODE_CANCELLED)
                    throw e
                }
                logHistoryAttempt(historyOutcomeCode(outcome))
                outcome
            } finally {
                historyMutex.unlock()
            }
        }

    /**
     * Opens the connection (never before the gate), reads, parses and stores. Returns a closed
     * outcome for every failure; only [CancellationException] escapes. The connection is
     * disconnected on completion and when the surrounding scope is cancelled (user Cancel or the
     * 30 s deadline), which also unblocks a stuck socket read (C5).
     */
    private suspend fun runHistoryAttempt(homeCurrency: String): CurrencyRateHistoryOutcome = coroutineScope {
        val connection: HttpURLConnection = historyConnectionOpener().apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false // C1: a 3xx is HTTP_ERROR; Location is never read.
            useCaches = false // C2: no cache; no request properties or conditional headers are set.
        }
        val worker = async { fetchParseAndStoreHistory(connection, homeCurrency) }
        try {
            worker.await()
        } finally {
            // Also runs on user Cancel / the 30 s deadline: disconnect unblocks a stuck read (C5).
            connection.disconnect()
        }
    }

    private suspend fun fetchParseAndStoreHistory(
        connection: HttpURLConnection,
        homeCurrency: String
    ): CurrencyRateHistoryOutcome {
        val body: ByteArray = try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                return CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.HTTP_ERROR)
            }
            // C4: reject a declared length above the cap before reading (-1 = unknown, e.g. gzip).
            if (connection.contentLengthLong > MAX_HISTORY_BYTES) {
                return CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.PAYLOAD_TOO_LARGE)
            }
            val read = connection.inputStream.use { readCapped(it) }
            if (read.tooLarge) {
                return CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.PAYLOAD_TOO_LARGE)
            }
            read.bytes
        } catch (e: CancellationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            return CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.TIMEOUT)
        } catch (_: IOException) {
            return CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.OFFLINE)
        } catch (_: Exception) {
            // Closed mapping (C10): any other network-phase failure is reported as OFFLINE.
            return CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.OFFLINE)
        }

        currentCoroutineContext().ensureActive()
        val nowMillis = timeProvider.now()
        val days: List<RateHistoryDay> = try {
            parseEcbHistoryRates(ByteArrayInputStream(body), nowMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EcbHistoryPayloadException) {
            return CurrencyRateHistoryOutcome.Failed(e.reason)
        } catch (_: Exception) {
            return CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.PAYLOAD_MALFORMED)
        }

        // INV-1b-10: home is judged on the newest day only; nothing is stored on failure.
        val base = SupportedCurrency.fromCode(homeCurrency)?.code
            ?: return CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.INVALID_HOME_CURRENCY)
        if (!days.first().eurToCurrency.containsKey(base)) {
            return CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.HOME_QUOTE_UNAVAILABLE)
        }

        // INV-1b-9(c): row total checked before any row is built.
        val catalogCodes = SupportedCurrency.catalog.map { it.code }
        var plannedRows = 0L
        for (day in days) {
            val listed = catalogCodes.count { day.eurToCurrency.containsKey(it) }
            plannedRows += listed.toLong() * (listed - 1)
        }
        if (plannedRows > MAX_HISTORY_ROWS) {
            return CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.PAYLOAD_OUT_OF_BOUNDS)
        }

        currentCoroutineContext().ensureActive()
        // INV-1b-11: ONE converter call -> ONE insertOrUpdateAll (barrier + one Room transaction).
        val rows = try {
            currencyConverter.storeRateHistory(days, source = "ecb")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.STORE_FAILED)
        }
        if (rows == 0) {
            return CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.PAYLOAD_EMPTY)
        }

        // INV-1b-13: best effort after the commit; never after a cancellation.
        currentCoroutineContext().ensureActive()
        try {
            currencySettingsRepository.setLastRateUpdate(timeProvider.now())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The rows are committed; the outcome stays Stored.
        }
        return CurrencyRateHistoryOutcome.Stored(
            days = days.size,
            rows = rows,
            earliestDate = days.last().date,
            latestDate = days.first().date
        )
    }

    /** Reads at most [MAX_HISTORY_BYTES] of the (decompressed) stream; sets [HistoryBody.tooLarge] beyond it (C6). */
    private suspend fun readCapped(stream: InputStream): HistoryBody {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_HISTORY_BYTES) return HistoryBody(ByteArray(0), tooLarge = true)
            out.write(buffer, 0, n)
        }
        return HistoryBody(out.toByteArray(), tooLarge = false)
    }

    /** One constant-format line per attempt (C9, C11): event name plus enum name only. */
    private fun logHistoryAttempt(outcomeCode: String) {
        Timber.i("RATE_HISTORY_DOWNLOAD %s", outcomeCode)
    }

    private fun historyOutcomeCode(outcome: CurrencyRateHistoryOutcome): String = when (outcome) {
        is CurrencyRateHistoryOutcome.Stored -> "STORED"
        CurrencyRateHistoryOutcome.DisabledByPrivacy -> "DISABLED_BY_PRIVACY"
        is CurrencyRateHistoryOutcome.PrivacyCheckFailed -> "PRIVACY_CHECK_FAILED"
        CurrencyRateHistoryOutcome.AlreadyInProgress -> "ALREADY_IN_PROGRESS"
        is CurrencyRateHistoryOutcome.Failed -> outcome.reason.name
        CurrencyRateHistoryOutcome.Cancelled -> HISTORY_CODE_CANCELLED
    }

    /**
     * Parses the ECB 90-day file by a fixed-depth walk Cube > Cube@time > Cube@currency/@rate (C7) with
     * the shared hardened factory. All caps are checked before any row is built. Returns the days newest
     * first. Throws [EcbHistoryPayloadException] with a bounded reason; any other parser failure is the
     * caller's PAYLOAD_MALFORMED.
     */
    internal fun parseEcbHistoryRates(stream: InputStream, nowMillis: Long): List<RateHistoryDay> {
        val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(stream)
        val root = document.documentElement
            ?: throw EcbHistoryPayloadException(RateHistoryFailureReason.PAYLOAD_EMPTY)

        val datedNodes = ArrayList<Element>()
        for (outer in childCubes(root)) {
            for (dated in childCubes(outer)) {
                if (!dated.hasAttribute("time")) continue
                datedNodes.add(dated)
                if (datedNodes.size > MAX_HISTORY_DAYS) {
                    throw EcbHistoryPayloadException(RateHistoryFailureReason.PAYLOAD_OUT_OF_BOUNDS)
                }
            }
        }
        if (datedNodes.isEmpty()) {
            throw EcbHistoryPayloadException(RateHistoryFailureReason.PAYLOAD_EMPTY)
        }

        val todayUtc = java.time.Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val dates = HashSet<LocalDate>()
        for (node in datedNodes) {
            val text = node.getAttribute("time")
            if (text.isBlank()) throw EcbHistoryPayloadException(RateHistoryFailureReason.PAYLOAD_MALFORMED)
            val date = try {
                LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (_: DateTimeParseException) {
                throw EcbHistoryPayloadException(RateHistoryFailureReason.PAYLOAD_MALFORMED)
            }
            if (!dates.add(date)) {
                throw EcbHistoryPayloadException(RateHistoryFailureReason.PAYLOAD_MALFORMED)
            }
            if (date.isAfter(todayUtc.plusDays(1))) {
                throw EcbHistoryPayloadException(RateHistoryFailureReason.PAYLOAD_OUT_OF_BOUNDS)
            }
        }
        val newest = dates.max()
        val oldest = dates.min()
        if (ChronoUnit.DAYS.between(oldest, newest) > MAX_HISTORY_SPAN_DAYS) {
            throw EcbHistoryPayloadException(RateHistoryFailureReason.PAYLOAD_OUT_OF_BOUNDS)
        }

        val days = ArrayList<RateHistoryDay>(datedNodes.size)
        for (node in datedNodes) {
            // A dated Cube nested inside a dated Cube is malformed (INV-1b-7).
            val rateNodes = childCubes(node)
            if (rateNodes.any { it.hasAttribute("time") }) {
                throw EcbHistoryPayloadException(RateHistoryFailureReason.PAYLOAD_MALFORMED)
            }
            val eurToCurrency = mutableMapOf("EUR" to 1.0)
            var validChildren = 0
            for (element in rateNodes) {
                val currency = element.getAttribute("currency").uppercase(Locale.ROOT)
                val rate = element.getAttribute("rate").toDoubleOrNull() ?: continue
                if (currency.isNotBlank() && rate.isFinite() && rate > 0.0) {
                    eurToCurrency[currency] = rate
                    validChildren++
                }
            }
            if (validChildren == 0) {
                throw EcbHistoryPayloadException(RateHistoryFailureReason.PAYLOAD_MALFORMED)
            }
            days.add(
                RateHistoryDay(
                    date = LocalDate.parse(node.getAttribute("time"), DateTimeFormatter.ISO_LOCAL_DATE),
                    eurToCurrency = eurToCurrency
                )
            )
        }
        days.sortByDescending { it.date }
        return days
    }

    private fun childCubes(parent: Element): List<Element> {
        val children = parent.childNodes
        val result = ArrayList<Element>()
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            if (element.namespaceURI == ECB_NAMESPACE && element.localName == "Cube") {
                result.add(element)
            }
        }
        return result
    }

    internal suspend fun refreshFromStream(homeCurrency: String, stream: InputStream): Int {
        val parsedRates = parseEcbDailyRates(stream)
        val eurToCurrency = parsedRates.eurToCurrency

        val base = SupportedCurrency.fromCode(homeCurrency)?.code
            ?: throw IllegalArgumentException("INVALID_HOME_CURRENCY")
        if (!eurToCurrency.containsKey(base)) {
            throw IllegalStateException("HOME_CURRENCY_QUOTE_UNAVAILABLE")
        }

        val supported = SupportedCurrency.catalog
            .map { it.code }
            .filter { eurToCurrency.containsKey(it) }
        val rates = mutableListOf<Triple<String, String, Double>>()
        for (from in supported) {
            val fromEur = eurToCurrency[from] ?: continue
            for (to in supported) {
                if (from == to) continue
                val toEur = eurToCurrency[to] ?: continue
                val computedRate = toEur / fromEur
                if (computedRate.isFinite() && computedRate > 0.0) {
                    rates.add(Triple(from, to, computedRate))
                }
            }
        }

        currencyConverter.storeRates(
            rates,
            source = "ecb",
            validDate = parsedRates.publicationMillis
        )
        if (rates.isNotEmpty()) {
            currencySettingsRepository.setLastRateUpdate(timeProvider.now())
        }
        return rates.size
    }

    internal fun parseEcbDailyRates(stream: InputStream): EcbDailyRates {
        val document = secureDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(stream)
        val cubeNodes = document.getElementsByTagNameNS(ECB_NAMESPACE, "Cube")
        val datedNodes = buildList {
            for (index in 0 until cubeNodes.length) {
                val element = cubeNodes.item(index) as? Element ?: continue
                if (element.hasAttribute("time")) {
                    add(element)
                }
            }
        }

        if (datedNodes.isEmpty()) {
            throw EcbPublicationDateUnavailableException(EcbPublicationDateFailureReason.MISSING)
        }
        if (datedNodes.size != 1) {
            throw EcbPublicationDateUnavailableException(EcbPublicationDateFailureReason.CONFLICTING)
        }

        val datedNode = datedNodes.single()
        val publicationDateText = datedNode.getAttribute("time")
        if (publicationDateText.isBlank()) {
            throw EcbPublicationDateUnavailableException(EcbPublicationDateFailureReason.BLANK)
        }
        val publicationDate = try {
            LocalDate.parse(publicationDateText, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: DateTimeParseException) {
            throw EcbPublicationDateUnavailableException(EcbPublicationDateFailureReason.MALFORMED)
        }

        val eurToCurrency = mutableMapOf("EUR" to 1.0)
        val rateNodes = datedNode.childNodes
        for (index in 0 until rateNodes.length) {
            val element = rateNodes.item(index) as? Element ?: continue
            if (element.namespaceURI != ECB_NAMESPACE || element.localName != "Cube") continue
            val currency = element.getAttribute("currency").uppercase(Locale.ROOT)
            val rate = element.getAttribute("rate").toDoubleOrNull() ?: continue
            if (currency.isNotBlank() && rate.isFinite() && rate > 0.0) {
                eurToCurrency[currency] = rate
            }
        }

        return EcbDailyRates(
            publicationMillis = publicationDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            eurToCurrency = eurToCurrency
        )
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)

            setAttribute(ACCESS_EXTERNAL_DTD_PROPERTY, "")
            setAttribute(ACCESS_EXTERNAL_SCHEMA_PROPERTY, "")

            isXIncludeAware = false
            isExpandEntityReferences = false
        }
    }
}

internal data class EcbDailyRates(
    val publicationMillis: Long,
    val eurToCurrency: Map<String, Double>
)

/** Body of a history response; [tooLarge] is the explicit flag behind PAYLOAD_TOO_LARGE (C6). */
internal class HistoryBody(val bytes: ByteArray, val tooLarge: Boolean)

/** Bounded parser failure of the history file; the message is the enum name only. */
internal class EcbHistoryPayloadException(
    val reason: RateHistoryFailureReason
) : IllegalStateException(reason.name)

internal enum class EcbPublicationDateFailureReason {
    MISSING,
    BLANK,
    MALFORMED,
    CONFLICTING
}

internal class EcbPublicationDateUnavailableException(
    val reasonCode: EcbPublicationDateFailureReason
) : IllegalStateException(reasonCode.name)
