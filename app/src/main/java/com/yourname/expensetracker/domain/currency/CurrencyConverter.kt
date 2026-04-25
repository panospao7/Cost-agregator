package com.yourname.expensetracker.domain.currency

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

data class FailedConversion(
    val originalAmount: Double,
    val originalCurrency: String,
    val targetCurrency: String,
    val reason: String
)

data class MultiConversionAggregate(
    val total: Double,
    val targetCurrency: String,
    val failedConversions: List<FailedConversion>
) {
    val hasFailures: Boolean get() = failedConversions.isNotEmpty()
}

@Singleton
class CurrencyConverter @Inject constructor(
    private val exchangeRateStore: ExchangeRateStore
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
        val directRate = exchangeRateStore.getRate(
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
        val toEurRate = exchangeRateStore.getRate(
            fromCurrency.uppercase(),
            DEFAULT_BASE_CURRENCY
        )
        val fromEurRate = exchangeRateStore.getRate(
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
     * Returns a strict aggregate result in the target currency and a list of failures.
     *
     * IMPORTANT: Failed conversions are NOT added to total to avoid mixing currencies.
     */
    suspend fun convertMultiple(
        amounts: List<Pair<Double, String>>,
        targetCurrency: String
    ): MultiConversionAggregate = withContext(Dispatchers.IO) {
        var total = 0.0
        val failures = mutableListOf<FailedConversion>()

        for ((amount, currency) in amounts) {
            val converted = convert(amount, currency, targetCurrency)
            if (converted != null) {
                total += converted.convertedAmount
            } else {
                failures += FailedConversion(
                    originalAmount = amount,
                    originalCurrency = currency,
                    targetCurrency = targetCurrency,
                    reason = "Missing exchange rate from ${currency.uppercase()} to ${targetCurrency.uppercase()}"
                )
                Timber.w("Could not convert $amount $currency to $targetCurrency")
            }
        }

        MultiConversionAggregate(
            total = total,
            targetCurrency = targetCurrency,
            failedConversions = failures
        )
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
        if (!isValidRate(rate)) {
            Timber.w("Ignoring invalid exchange rate %s -> %s = %s", fromCurrency, toCurrency, rate)
            return
        }
        val exchangeRate = DomainExchangeRate(
            fromCurrency = fromCurrency.uppercase(),
            toCurrency = toCurrency.uppercase(),
            rate = rate,
            lastUpdated = System.currentTimeMillis(),
            source = source
        )
        exchangeRateStore.insertOrUpdate(exchangeRate)
    }

    /**
     * Store multiple exchange rates at once.
     */
    suspend fun storeRates(
        rates: List<Triple<String, String, Double>>,
        source: String = "api"
    ) {
        val exchangeRates = rates.mapNotNull { (from, to, rate) ->
            if (!isValidRate(rate)) {
                Timber.w("Skipping invalid exchange rate %s -> %s = %s", from, to, rate)
                return@mapNotNull null
            }
            DomainExchangeRate(
                fromCurrency = from.uppercase(),
                toCurrency = to.uppercase(),
                rate = rate,
                lastUpdated = System.currentTimeMillis(),
                source = source
            )
        }
        exchangeRateStore.insertOrUpdateAll(exchangeRates)
    }

    private fun isValidRate(rate: Double): Boolean = rate.isFinite() && rate > 0.0

    /**
     * Check if an exchange rate exists for a currency pair.
     */
    suspend fun hasRate(fromCurrency: String, toCurrency: String): Boolean {
        return exchangeRateStore.getRate(fromCurrency.uppercase(), toCurrency.uppercase()) != null
    }

    /**
     * Get the last time exchange rates were updated.
     */
    suspend fun getLastUpdateTime(): Long? {
        return exchangeRateStore.getLatestRate()?.lastUpdated
    }

    /**
     * Get all available rates for a base currency.
     */
    fun getAllRatesForBase(baseCurrency: String) =
        exchangeRateStore.getAllRatesForBase(baseCurrency.uppercase())

    /**
     * Delete old exchange rates (older than specified time).
     */
    suspend fun cleanupOldRates(olderThan: Long) {
        exchangeRateStore.deleteOldRates(olderThan)
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
