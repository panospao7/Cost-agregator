package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRatesRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
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
        private val PRIORITY_CURRENCIES = listOf(
            "USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "CNY", "SEK", "NZD",
            "MXN", "SGD", "HKD", "NOK", "KRW", "TRY", "RUB", "INR", "BRL", "ZAR"
        )
    }

    override suspend fun refresh(homeCurrency: String): Int = withContext(ioDispatcher) {
        // PRIVACY GATE: Check privacy gate before external HTTP call
        val gateCheck = privacyGate.check(PrivacyCapability.CLOUD_AI_GENERAL)
        if (gateCheck.blocksExecution()) {
            Timber.w("CurrencyRatesRepository: blocked by privacy gate: ${gateCheck.reason()}")
            return@withContext 0
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

            connection.inputStream.use { stream ->
                refreshFromStream(homeCurrency, stream)
            }
        } finally {
            connection.disconnect()
        }
    }

    internal suspend fun refreshFromStream(homeCurrency: String, stream: InputStream): Int {
        val parsedRates = parseEcbDailyRates(stream)
        val eurToCurrency = parsedRates.eurToCurrency

        val base = homeCurrency.uppercase(Locale.US)
        if (!eurToCurrency.containsKey(base)) {
            throw IllegalStateException("Provider did not include home currency $base")
        }

        val supported = (PRIORITY_CURRENCIES + base + "EUR")
            .map { it.uppercase(Locale.US) }
            .distinct()
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

internal enum class EcbPublicationDateFailureReason {
    MISSING,
    BLANK,
    MALFORMED,
    CONFLICTING
}

internal class EcbPublicationDateUnavailableException(
    val reasonCode: EcbPublicationDateFailureReason
) : IllegalStateException(reasonCode.name)
