package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.data.database.dao.ExchangeRateDao
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supported currencies in the app.
 */
enum class SupportedCurrency(val code: String, val symbol: String, val displayName: String) {
    EUR("EUR", "€", "Euro"),
    USD("USD", "$", "US Dollar"),
    GBP("GBP", "£", "British Pound"),
    JPY("JPY", "¥", "Japanese Yen"),
    CHF("CHF", "Fr", "Swiss Franc"),
    CAD("CAD", "C$", "Canadian Dollar"),
    AUD("AUD", "A$", "Australian Dollar"),
    SEK("SEK", "kr", "Swedish Krona"),
    NOK("NOK", "kr", "Norwegian Krone"),
    DKK("DKK", "kr", "Danish Krone"),
    PLN("PLN", "zł", "Polish Zloty"),
    CZK("CZK", "Kč", "Czech Koruna"),
    HUF("HUF", "Ft", "Hungarian Forint"),
    RON("RON", "lei", "Romanian Leu"),
    BGN("BGN", "лв", "Bulgarian Lev"),
    HRK("HRK", "kn", "Croatian Kuna"),
    ISK("ISK", "kr", "Icelandic Krona");

    companion object {
        fun fromCode(code: String): SupportedCurrency? {
            return values().find { it.code == code.uppercase() }
        }
    }
}

/**
 * Result of a currency conversion.
 */
data class ConversionResult(
    val originalAmount: Double,
    val originalCurrency: String,
    val convertedAmount: Double,
    val targetCurrency: String,
    val rateUsed: Double,
    val timestamp: Long
)

@Singleton
class CurrencyConverter @Inject constructor(
    private val exchangeRateDao: ExchangeRateDao
) {
    companion object {
        const val DEFAULT_BASE_CURRENCY = "EUR"
    }

    /**
     * Convert an amount from one currency to another.
     * Returns null if no exchange rate is available.
     */
    suspend fun convert(
        amount: Double,
        fromCurrency: String,
        toCurrency: String
    ): ConversionResult? = withContext(Dispatchers.IO) {
        if (fromCurrency.uppercase() == toCurrency.uppercase()) {
            return@withContext ConversionResult(
                originalAmount = amount,
                originalCurrency = fromCurrency,
                convertedAmount = amount,
                targetCurrency = toCurrency,
                rateUsed = 1.0,
                timestamp = System.currentTimeMillis()
            )
        }

        // Try direct rate first
        val directRate = exchangeRateDao.getRate(
            fromCurrency.uppercase(),
            toCurrency.uppercase()
        )

        if (directRate != null) {
            return@withContext ConversionResult(
                originalAmount = amount,
                originalCurrency = fromCurrency,
                convertedAmount = amount * directRate.rate,
                targetCurrency = toCurrency,
                rateUsed = directRate.rate,
                timestamp = directRate.lastUpdated
            )
        }

        // Try via EUR as intermediate
        val toEurRate = exchangeRateDao.getRate(
            fromCurrency.uppercase(),
            DEFAULT_BASE_CURRENCY
        )
        val fromEurRate = exchangeRateDao.getRate(
            DEFAULT_BASE_CURRENCY,
            toCurrency.uppercase()
        )

        if (toEurRate != null && fromEurRate != null) {
            val combinedRate = toEurRate.rate * fromEurRate.rate
            return@withContext ConversionResult(
                originalAmount = amount,
                originalCurrency = fromCurrency,
                convertedAmount = amount * combinedRate,
                targetCurrency = toCurrency,
                rateUsed = combinedRate,
                timestamp = maxOf(toEurRate.lastUpdated, fromEurRate.lastUpdated)
            )
        }

        Timber.w("No exchange rate available for $fromCurrency to $toCurrency")
        null
    }

    /**
     * Convert a list of amounts in various currencies to a single target currency.
     * Returns the total in the target currency.
     */
    suspend fun convertMultiple(
        amounts: List<Pair<Double, String>>,
        targetCurrency: String
    ): Double = withContext(Dispatchers.IO) {
        var total = 0.0
        for ((amount, currency) in amounts) {
            val converted = convert(amount, currency, targetCurrency)
            if (converted != null) {
                total += converted.convertedAmount
            } else {
                // If conversion fails, add amount as-is (assumes it's already in target currency)
                total += amount
                Timber.w("Could not convert $amount $currency to $targetCurrency")
            }
        }
        total
    }

    /**
     * Store an exchange rate.
     */
    suspend fun storeRate(
        fromCurrency: String,
        toCurrency: String,
        rate: Double,
        source: String = "manual"
    ) {
        val exchangeRate = ExchangeRate(
            fromCurrency = fromCurrency.uppercase(),
            toCurrency = toCurrency.uppercase(),
            rate = rate,
            source = source
        )
        exchangeRateDao.insertOrUpdate(exchangeRate)
    }

    /**
     * Store multiple exchange rates at once.
     */
    suspend fun storeRates(
        rates: List<Triple<String, String, Double>>,
        source: String = "api"
    ) {
        val exchangeRates = rates.map { (from, to, rate) ->
            ExchangeRate(
                fromCurrency = from.uppercase(),
                toCurrency = to.uppercase(),
                rate = rate,
                source = source
            )
        }
        exchangeRateDao.insertOrUpdateAll(exchangeRates)
    }

    /**
     * Check if an exchange rate exists for a currency pair.
     */
    suspend fun hasRate(fromCurrency: String, toCurrency: String): Boolean {
        return exchangeRateDao.getRate(fromCurrency.uppercase(), toCurrency.uppercase()) != null
    }

    /**
     * Get the last time exchange rates were updated.
     */
    suspend fun getLastUpdateTime(): Long? {
        return exchangeRateDao.getLatestRate()?.lastUpdated
    }

    /**
     * Get all available rates for a base currency.
     */
    fun getAllRatesForBase(baseCurrency: String) =
        exchangeRateDao.getAllRatesForBase(baseCurrency.uppercase())

    /**
     * Delete old exchange rates (older than specified time).
     */
    suspend fun cleanupOldRates(olderThan: Long) {
        exchangeRateDao.deleteOldRates(olderThan)
    }

    /**
     * Get a formatted string for an amount with currency symbol.
     */
    fun formatAmount(amount: Double, currencyCode: String): String {
        val currency = SupportedCurrency.fromCode(currencyCode)
        val symbol = currency?.symbol ?: currencyCode
        return "$symbol${String.format("%.2f", amount)}"
    }
}
