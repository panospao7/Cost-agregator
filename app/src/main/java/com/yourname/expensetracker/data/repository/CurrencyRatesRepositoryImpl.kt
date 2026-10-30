package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRateRefreshOutcome
import com.yourname.expensetracker.domain.currency.CurrencyRatesRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.SupportedCurrency
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacyGateReasonCodes
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
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
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val MAX_DAILY_BYTES = 256 * 1024
        private const val ACCESS_EXTERNAL_DTD_PROPERTY = "http://javax.xml.XMLConstants/property/accessExternalDTD"
        private const val ACCESS_EXTERNAL_SCHEMA_PROPERTY = "http://javax.xml.XMLConstants/property/accessExternalSchema"
        private const val ECB_NAMESPACE = "http://www.ecb.int/vocabulary/2002-08-01/eurofxref"
    }

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
        val bytes = readDailyCapped(stream)
        val payloadText = strictUtf8TextOrNull(bytes)
        if (payloadText == null || containsDtdDeclaration(payloadText)) {
            throw IllegalStateException("RATE_PAYLOAD_MALFORMED")
        }
        val document = secureDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(InputSource(StringReader(payloadText)))
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

    /**
     * Builds a namespace-aware factory with every hardening setting applied best-effort. Android's
     * Harmony/KXml-based factory rejects most of these features and attributes (for example
     * secure-processing throws ParserConfigurationException), so each setting is applied on its own and
     * an unsupported one is skipped silently (the exception is never logged). The JVM keeps full
     * hardening. DTD/entity rejection does not depend on the parser: the payload is strictly decoded as
     * UTF-8 (NUL/BOM rejected) and [containsDtdDeclaration] is enforced on that text before any
     * DocumentBuilder is created; the parser then receives the text as an [InputSource] character
     * stream so it cannot re-detect UTF-16/UTF-32.
     */
    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            bestEffortFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            bestEffortFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            bestEffortFeature("http://xml.org/sax/features/external-general-entities", false)
            bestEffortFeature("http://xml.org/sax/features/external-parameter-entities", false)
            bestEffortFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)

            try {
                setAttribute(ACCESS_EXTERNAL_DTD_PROPERTY, "")
            } catch (_: IllegalArgumentException) {
                // Unsupported on this platform; DTDs are rejected by the text pre-scan.
            }
            try {
                setAttribute(ACCESS_EXTERNAL_SCHEMA_PROPERTY, "")
            } catch (_: IllegalArgumentException) {
                // Unsupported on this platform.
            }
            try {
                isXIncludeAware = false
            } catch (_: UnsupportedOperationException) {
                // Unsupported on this platform.
            }
            try {
                isExpandEntityReferences = false
            } catch (_: UnsupportedOperationException) {
                // Unsupported on this platform.
            }
        }
    }

    private fun DocumentBuilderFactory.bestEffortFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: ParserConfigurationException) {
            // Unsupported on this platform (Android); DTDs are rejected by the text pre-scan.
        }
    }

    /**
     * Strictly decodes the capped payload as UTF-8. Returns null (treated as malformed by callers) for
     * malformed/unmappable sequences, any NUL char (UTF-16/UTF-32 interleaving) or a leading U+FEFF
     * (BOM). Decoding once here and handing the parser a character stream means the parser can never
     * re-sniff a different encoding that would bypass the DTD pre-scan.
     */
    private fun strictUtf8TextOrNull(bytes: ByteArray): String? {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        if (text.indexOf('\u0000') >= 0 || text.startsWith('﻿')) return null
        return text
    }

    /**
     * True when the decoded payload text contains a DOCTYPE or ENTITY declaration (case-insensitive).
     * Parser independent, so DTD/entity payloads are rejected on Android where parser features are
     * unsupported.
     */
    internal fun containsDtdDeclaration(text: String): Boolean {
        return text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)
    }

    /** Byte overload: strict UTF-8 decode first; an undecodable payload is rejected (true). */
    internal fun containsDtdDeclaration(bytes: ByteArray): Boolean {
        val text = strictUtf8TextOrNull(bytes) ?: return true
        return containsDtdDeclaration(text)
    }

    /**
     * Reads the daily ECB response with a hard cap of [MAX_DAILY_BYTES]. Overflow fails the refresh like a
     * parse failure (bounded message, no payload content).
     */
    private fun readDailyCapped(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_DAILY_BYTES) throw IllegalStateException("RATE_PAYLOAD_TOO_LARGE")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

internal data class EcbDailyRates(
    val publicationMillis: Long,
    val eurToCurrency: Map<String, Double>
)

internal enum class EcbPublicationDateFailureReason {
    MISSING,
    BLANK,
    MALFORMED,
    CONFLICTING
}

internal class EcbPublicationDateUnavailableException(
    val reasonCode: EcbPublicationDateFailureReason
) : IllegalStateException(reasonCode.name)
