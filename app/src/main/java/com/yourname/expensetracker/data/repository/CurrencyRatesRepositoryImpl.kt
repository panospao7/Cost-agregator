package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRatesRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

@Singleton
class CurrencyRatesRepositoryImpl @Inject constructor(
    private val currencyConverter: CurrencyConverter,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : CurrencyRatesRepository {

    companion object {
        private const val ECB_DAILY_RATES_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
        private const val TIMEOUT_MS = 10_000
        private const val ACCESS_EXTERNAL_DTD_PROPERTY = "http://javax.xml.XMLConstants/property/accessExternalDTD"
        private const val ACCESS_EXTERNAL_SCHEMA_PROPERTY = "http://javax.xml.XMLConstants/property/accessExternalSchema"
        private val PRIORITY_CURRENCIES = listOf(
            "USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "CNY", "SEK", "NZD",
            "MXN", "SGD", "HKD", "NOK", "KRW", "TRY", "RUB", "INR", "BRL", "ZAR"
        )
    }

    override suspend fun refresh(homeCurrency: String): Int = withContext(ioDispatcher) {
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

            val document = connection.inputStream.use { stream ->
                secureDocumentBuilderFactory()
                    .newDocumentBuilder()
                    .parse(stream)
            }

            val eurToCurrency = mutableMapOf<String, Double>()
            eurToCurrency["EUR"] = 1.0

            val nodes = document.getElementsByTagName("Cube")
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as? Element ?: continue
                val currency = element.getAttribute("currency")?.uppercase(Locale.US).orEmpty()
                val rate = element.getAttribute("rate")?.toDoubleOrNull() ?: continue
                if (currency.isNotBlank() && rate.isFinite() && rate > 0.0) {
                    eurToCurrency[currency] = rate
                }
            }

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

            currencyConverter.storeRates(rates, source = "ecb")
            currencySettingsRepository.setLastRateUpdate(timeProvider.now())
            rates.size
        } finally {
            connection.disconnect()
        }
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
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
