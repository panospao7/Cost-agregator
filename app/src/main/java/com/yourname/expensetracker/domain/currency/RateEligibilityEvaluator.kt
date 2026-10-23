package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.StaleRatePolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** How an eligible rate was found. */
enum class RateEligibilityPath {
    DIRECT,
    VIA_BASE_CURRENCY
}

/** Provenance label of an eligible rate. [ECB] only if every leg's source is `ecb`. */
enum class RateSourceLabel {
    ECB,
    OTHER
}

/** Why a currency is not usable for a user-present save. Bounded. */
enum class RateUnsupportedReason {
    /** Not in [SupportedCurrency.catalog]. */
    NOT_IN_CATALOG,

    /** In the catalog but not selectable for new entries (inactive or no rate source) and not home. */
    NOT_SELECTABLE
}

/**
 * Result of [RateEligibilityEvaluator.evaluate] (domain spec section 1.3).
 * No variant carries a currency code, amount or merchant.
 */
sealed interface RateEligibility {

    /** The currency is home: no conversion, no preview, always eligible. */
    data object Home : RateEligibility

    /**
     * A stored rate dated on or before the expense's local date, at most
     * [RateEligibilityEvaluator.MAX_ELIGIBLE_AGE_DAYS] calendar days older.
     * [rate] is home units per 1 unit of the currency (finite, > 0), unrounded.
     * For a via-base-currency rate [rateDate] is the date of the older leg.
     */
    data class Eligible(
        val rate: Double,
        val rateDate: LocalDate,
        val ageDays: Int,
        val path: RateEligibilityPath,
        val sourceLabel: RateSourceLabel
    ) : RateEligibility

    /** A rate dated on or before the expense date exists but is more than 7 calendar days older. */
    data class Stale(val latestRateDate: LocalDate, val ageDays: Int) : RateEligibility

    /** No usable rate dated on or before the expense date (including rows without a valid date). */
    data object None : RateEligibility

    /** The currency cannot be used for a user-present save. */
    data class Unsupported(val reason: RateUnsupportedReason) : RateEligibility
}

/**
 * Sole decision point for "does a stored rate qualify for a user-present save" (INV-1, INV-2).
 * Read-only and pure over stored rates (INV-3, INV-20): no wall clock, no `lastUpdated`, no amount,
 * no network, no logging.
 *
 * Rule (domain spec section 3):
 * 1. `currency == home` -> [RateEligibility.Home], before any catalog or rate check.
 * 2. currency not in the catalog -> `Unsupported(NOT_IN_CATALOG)`; not selectable for [home] ->
 *    `Unsupported(NOT_SELECTABLE)`.
 * 3. `localDate` = the instant's calendar date in the injected `entryZone`.
 * 4. The rate is read once through the converter with the transaction-date basis and the bound
 *    `localDate` at UTC midnight, so a stored rate (dated at UTC midnight of its ECB date) is
 *    found exactly when `rateDate <= localDate`. Lookup order is the converter's (direct first,
 *    then via EUR); no "freshest of both" search.
 * 5. No rate (a `Failed` conversion outcome) or no valid date -> `None`.
 * 6. `rateDate` = UTC calendar date of the rate's valid date (older leg for via-EUR);
 *    `ageDays` = calendar days between `rateDate` and `localDate`; `<= 7` (inclusive) -> `Eligible`,
 *    else `Stale`.
 *
 * Fail-closed details: a rate that is not finite and positive, without a valid date, or dated after
 * the bound is `None`; so is a conversion that returns no rate (including a home currency outside the
 * catalog).
 */
class RateEligibilityEvaluator @Inject constructor(
    private val currencyConverter: CurrencyConverter
) {

    /**
     * Store exceptions propagate; callers map them; CancellationException is never caught.
     *
     * @param expenseAtMillis the expense instant (epoch millis), as in the create request.
     * @param entryZone the zone captured when the entry session started; the only zone used.
     */
    suspend fun evaluate(
        currency: CurrencyCode,
        home: CurrencyCode,
        expenseAtMillis: Long,
        entryZone: ZoneId
    ): RateEligibility {
        if (currency == home) return RateEligibility.Home

        if (SupportedCurrency.fromCode(currency.code) == null) {
            return RateEligibility.Unsupported(RateUnsupportedReason.NOT_IN_CATALOG)
        }
        if (!CurrencySelectionPolicy.isSelectable(currency, home, CurrencyEntryMode.STANDARD)) {
            return RateEligibility.Unsupported(RateUnsupportedReason.NOT_SELECTABLE)
        }

        val localDate = Instant.ofEpochMilli(expenseAtMillis).atZone(entryZone).toLocalDate()
        val boundMillis = localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val outcome = currencyConverter.convertOutcome(
            1.0,
            currency.code,
            home.code,
            RateBasis.TRANSACTION_DATE,
            boundMillis,
            StaleRatePolicy.None
        )
        val converted = outcome as? ConversionOutcome.Converted ?: return RateEligibility.None

        val path = when (converted.conversionPath) {
            ConversionPath.DIRECT -> RateEligibilityPath.DIRECT
            ConversionPath.VIA_BASE_CURRENCY -> RateEligibilityPath.VIA_BASE_CURRENCY
            ConversionPath.IDENTITY -> return RateEligibility.None
        }
        val rate = converted.rateUsed
        if (!rate.isFinite() || rate <= 0.0) return RateEligibility.None
        val validDateMillis = converted.rateValidDate ?: return RateEligibility.None

        val rateDate = Instant.ofEpochMilli(validDateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val ageDays = ChronoUnit.DAYS.between(rateDate, localDate)
        if (ageDays < 0) return RateEligibility.None

        return if (ageDays <= MAX_ELIGIBLE_AGE_DAYS) {
            RateEligibility.Eligible(
                rate = rate,
                rateDate = rateDate,
                ageDays = ageDays.toInt(),
                path = path,
                sourceLabel = sourceLabelOf(converted.rateSource)
            )
        } else {
            RateEligibility.Stale(latestRateDate = rateDate, ageDays = ageDays.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    /** `ECB` iff the source is non-empty and every `+`-joined leg equals `ecb`. */
    private fun sourceLabelOf(source: String?): RateSourceLabel {
        if (source.isNullOrEmpty()) return RateSourceLabel.OTHER
        return if (source.split(LEG_SEPARATOR).all { it == ECB_SOURCE }) RateSourceLabel.ECB else RateSourceLabel.OTHER
    }

    companion object {
        /** Inclusive limit in calendar days (domain spec section 3, owner decision). */
        const val MAX_ELIGIBLE_AGE_DAYS: Long = 7

        private const val ECB_SOURCE = "ecb"
        private const val LEG_SEPARATOR = "+"
    }
}
