package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supported currencies in the app.
 */
enum class SupportedCurrency(val code: String, val symbol: String, val displayName: String, val isActive: Boolean = true) {
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
    HRK("HRK", "kn", "Croatian Kuna", isActive = false),
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
    private val exchangeRateStore: ExchangeRateStore,
    private val timeProvider: TimeProvider
) {
    companion object {
        const val DEFAULT_BASE_CURRENCY = "EUR"
        const val MAX_RATE_AGE_MS = 24 * 60 * 60 * 1000L  // 24 hours
    }

    /**
     * Convert an amount from one currency to another at the **current** (latest) exchange rate.
     *
     * ## Historical rates (CURR-2)
     * This method does **not** accept a date parameter. All lookups use the
     * **latest available rate** stored for the currency pair. For reports and
     * snapshots that require historically accurate conversion (e.g. converting
     * an expense at its purchase-date rate), use [convertAsOf] instead.
     *
     * > **When to use which:**
     * > - Use `convert()` for real-time display, latest-rate aggregates, or
     * >   any context where "what is the value right now" is the right answer.
     * > - Use `convertAsOf(amount, from, to, atMillis)` when you need the
     * >   rate that was valid on a specific past date (e.g. `expense.date`).
     *
     * Returns null if no exchange rate is available.
     *
     * CURR-15: Prefer [convert(amount, fromCurrency, toCurrency)] with
     * CurrencyCode parameters where possible.
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
                timestamp = timeProvider.now()
            )
        }

        // Try direct rate first
        val directRate = exchangeRateStore.getRate(
            fromCurrency.uppercase(),
            toCurrency.uppercase()
        )

        if (directRate != null) {
            // Check staleness — if the rate is older than the threshold, treat as unavailable
            val now = timeProvider.now()
            if ((now - directRate.lastUpdated) > MAX_RATE_AGE_MS) {
                Timber.d("Rate from %s to %s is stale (last updated %d ms ago)", fromCurrency, toCurrency, now - directRate.lastUpdated)
            } else {
                return@withContext ConversionResult(
                    originalAmount = amount,
                    originalCurrency = fromCurrency,
                    convertedAmount = amount * directRate.rate,
                    targetCurrency = toCurrency,
                    rateUsed = directRate.rate,
                    timestamp = directRate.lastUpdated
                )
            }
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
            // Check staleness — if either leg is stale, treat the composite as unavailable
            val now = timeProvider.now()
            if ((now - toEurRate.lastUpdated) > MAX_RATE_AGE_MS || (now - fromEurRate.lastUpdated) > MAX_RATE_AGE_MS) {
                Timber.d("Composite rate via EUR is stale for %s -> %s", fromCurrency, toCurrency)
            } else {
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
        }

        Timber.w("No exchange rate available for $fromCurrency to $toCurrency")
        null
    }

    /**
     * Convert an amount from one currency to another using the exchange rate
     * valid as of [atMillis] (epoch ms).
     *
     * This enables historically-accurate currency conversion for reports and
     * cash-flow projections on past dates. The lookup queries
     * [ExchangeRateDao.getRateAsOf] which returns the most recent rate whose
     * [ExchangeRate.validDate] ≤ [atMillis].
     *
     * Falls back through direct rate → via EUR intermediate, matching the
     * same strategy as [convert]. Returns null if no historical rate is found.
     *
     * CURR-15: Prefer [convertAsOf(amount, fromCurrency, toCurrency, atMillis)]
     * with CurrencyCode parameters where possible.
     */
    suspend fun convertAsOf(
        amount: Double,
        fromCurrency: String,
        toCurrency: String,
        atMillis: Long
    ): ConversionResult? = withContext(Dispatchers.IO) {
        if (fromCurrency.uppercase() == toCurrency.uppercase()) {
            return@withContext ConversionResult(
                originalAmount = amount,
                originalCurrency = fromCurrency,
                convertedAmount = amount,
                targetCurrency = toCurrency,
                rateUsed = 1.0,
                timestamp = atMillis
            )
        }

        // Try direct rate first
        val directRate = exchangeRateStore.getRateAsOf(
            fromCurrency.uppercase(),
            toCurrency.uppercase(),
            atMillis
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
        val toEurRate = exchangeRateStore.getRateAsOf(
            fromCurrency.uppercase(),
            DEFAULT_BASE_CURRENCY,
            atMillis
        )
        val fromEurRate = exchangeRateStore.getRateAsOf(
            DEFAULT_BASE_CURRENCY,
            toCurrency.uppercase(),
            atMillis
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

        Timber.w("No exchange rate available for $fromCurrency to $toCurrency as of $atMillis")
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
            lastUpdated = timeProvider.now(),
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
                lastUpdated = timeProvider.now(),
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
     * Get all available rates for a target currency.
     * CURR-10: Renamed from getAllRatesForBase — the underlying query filters on
     * `toCurrency`, so this returns rates that *target* the given currency.
     */
    fun getRatesToCurrency(targetCurrency: String) =
        exchangeRateStore.getRatesToCurrency(targetCurrency.uppercase())

    /**
     * Delete old exchange rates (older than specified time).
     */
    suspend fun cleanupOldRates(olderThan: Long) {
        exchangeRateStore.deleteOldRates(olderThan)
    }

    // ── CurrencyCode-typed overloads (CURR-15) ─────────────────────────

    /**
     * Convert amount using [CurrencyCode] typed parameters.
     * @see [convert]
     */
    @JvmName("convertWithCurrencyCode")
    suspend fun convert(amount: Double, fromCurrency: CurrencyCode, toCurrency: CurrencyCode): ConversionResult? =
        convert(amount, fromCurrency.code, toCurrency.code)

    /**
     * Convert amount as of a date using [CurrencyCode] typed parameters.
     * @see [convertAsOf]
     */
    @JvmName("convertAsOfWithCurrencyCode")
    suspend fun convertAsOf(amount: Double, fromCurrency: CurrencyCode, toCurrency: CurrencyCode, atMillis: Long): ConversionResult? =
        convertAsOf(amount, fromCurrency.code, toCurrency.code, atMillis)

    /**
     * Store a rate using [CurrencyCode] typed parameters.
     * @see [storeRate]
     */
    @JvmName("storeRateWithCurrencyCode")
    suspend fun storeRate(fromCurrency: CurrencyCode, toCurrency: CurrencyCode, rate: Double, source: String = "manual") {
        storeRate(fromCurrency.code, toCurrency.code, rate, source)
    }

    /**
     * Check for rate existence using [CurrencyCode] typed parameters.
     * @see [hasRate]
     */
    @JvmName("hasRateWithCurrencyCode")
    suspend fun hasRate(fromCurrency: CurrencyCode, toCurrency: CurrencyCode): Boolean =
        hasRate(fromCurrency.code, toCurrency.code)

    // ── Formatter ──────────────────────────────────────────────────────

    /**
     * Get a formatted string for an amount with currency symbol.
     *
     * @deprecated Use [com.yourname.expensetracker.domain.util.CurrencyFormatter.formatMoney]
     * instead, which uses `java.util.Currency.getDefaultFractionDigits` and properly
     * handles locale-specific formatting (e.g. JPY with 0 decimal places, BHD with 3).
     */
    @Deprecated(
        message = "Use CurrencyFormatter.formatMoney() which respects locale and fraction digits",
        replaceWith = ReplaceWith(
            "CurrencyFormatter.formatMoney(amount, currencyCode)",
            "com.yourname.expensetracker.domain.util.CurrencyFormatter"
        ),
        level = DeprecationLevel.WARNING
    )
    fun formatAmount(amount: Double, currencyCode: String): String {
        val currency = SupportedCurrency.fromCode(currencyCode)
        val symbol = currency?.symbol ?: currencyCode
        return "$symbol${String.format("%.2f", amount)}"
    }
}
