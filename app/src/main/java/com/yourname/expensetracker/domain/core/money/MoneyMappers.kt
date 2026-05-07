package com.yourname.expensetracker.domain.core.money

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.FailedConversion
import com.yourname.expensetracker.domain.currency.MultiConversionAggregate

/**
 * Mapper utilities between old currency types (raw String codes, Double amounts)
 * and new domain core money types (CurrencyCode, MoneyAmount, MoneyAggregate).
 *
 * These mappers provide a bridge layer so that existing infrastructure
 * (CurrencyConverter, MultiCurrencyRepository) can produce the new
 * domain types without requiring an immediate rewrite.
 */

// ── String → CurrencyCode ──────────────────────────────────────────────

/** Convert a raw currency String to CurrencyCode, falling back to EUR with ASSUMED_LEGACY_EUR. */
fun String?.toCurrencyCodeOrLegacyEur(): CurrencyCode =
    CurrencyCode.parseOr(this, CurrencyCode.EUR)

// ── Expense → MoneyAmount ──────────────────────────────────────────────

/** Get the effective amount of an expense as a currency-safe MoneyAmount. */
fun Expense.toEffectiveMoneyAmount(): MoneyAmount =
    MoneyAmount(effectiveAmount, CurrencyCode.parseOr(currency, CurrencyCode.EUR))

/** Get the gross amount of an expense as a currency-safe MoneyAmount. */
fun Expense.toGrossMoneyAmount(): MoneyAmount =
    MoneyAmount(amount, CurrencyCode.parseOr(currency, CurrencyCode.EUR))

// ── ConversionResult → ConvertedMoney ──────────────────────────────────

/** Map an existing ConversionResult to the new ConvertedMoney type. */
fun ConversionResult.toConvertedMoney(originalCurrency: CurrencyCode): ConvertedMoney =
    ConvertedMoney.success(
        original = MoneyAmount(originalAmount, originalCurrency),
        convertedAmount = convertedAmount,
        convertedCurrency = CurrencyCode(targetCurrency),
        rateUsed = rateUsed,
        rateTimestamp = timestamp
    )

// ── MultiConversionAggregate → MoneyAggregate ─────────────────────────

/**
 * Map an existing MultiConversionAggregate to the new MoneyAggregate type.
 *
 * The source buckets are NOT available from MultiConversionAggregate (it only
 * has the final total), so the caller should provide them if available.
 */
fun MultiConversionAggregate.toMoneyAggregate(
    sourceBuckets: List<MoneyBucket> = emptyList()
): MoneyAggregate {
    val failures = failedConversions.map { oldFailure ->
        val reason = when (oldFailure.failureType) {
            FailedConversion.STALE_RATE -> FailureReason.RATE_STALE
            else -> FailureReason.MISSING_RATE
        }
        ConversionFailure(
            originalAmount = MoneyAmount(oldFailure.originalAmount, CurrencyCode(oldFailure.originalCurrency)),
            targetCurrency = CurrencyCode(targetCurrency),
            reason = reason
        )
    }

    return MoneyAggregate(
        displayAmount = total,
        displayCurrency = CurrencyCode(targetCurrency),
        sourceBuckets = sourceBuckets,
        conversionFailures = failures,
        isPartial = hasFailures,
        warningMessage = if (hasFailures) {
            "Total excludes ${failures.size} transaction(s) due to missing exchange rates"
        } else null
    )
}

// ── FailedConversion (old) → ConversionFailure (new) ───────────────────

/** Map an old FailedConversion to the new ConversionFailure type. */
fun FailedConversion.toConversionFailure(): ConversionFailure {
    val reason = when (failureType) {
        FailedConversion.STALE_RATE -> FailureReason.RATE_STALE
        else -> FailureReason.MISSING_RATE
    }
    return ConversionFailure(
        originalAmount = MoneyAmount(originalAmount, CurrencyCode(originalCurrency)),
        targetCurrency = CurrencyCode(targetCurrency),
        reason = reason
    )
}
