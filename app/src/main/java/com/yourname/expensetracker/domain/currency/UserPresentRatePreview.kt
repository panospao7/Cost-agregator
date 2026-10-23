package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.SnapshotRounding
import com.yourname.expensetracker.domain.core.money.SnapshotRoundingUnavailableReason
import com.yourname.expensetracker.domain.core.money.roundSnapshot
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Advisory preview of a user-present save (domain spec sections 1.6, 5; INV-15, INV-20).
 * It carries no formatted text; the caller formats [ConvertedPreview.amountInHome] with the
 * shared money formatter. No variant carries the entered amount, a merchant or a zone; the only
 * currency code is the home code in [ConvertedPreview.home], and the only amount is the computed
 * [ConvertedPreview.amountInHome].
 */
sealed interface UserPresentRatePreviewResult {

    /** The currency is home: no conversion and no preview. */
    data object NotApplicableHome : UserPresentRatePreviewResult

    /**
     * The converted amount the flagged save stores as `baseAmount`: `amountInHome` is the
     * `Rounded.amount` of `roundSnapshot(parsedAmount * Eligible.rate, home)`.
     */
    data class ConvertedPreview(
        val amountInHome: Double,
        val home: CurrencyCode,
        val rateDate: LocalDate,
        val sourceLabel: RateSourceLabel
    ) : UserPresentRatePreviewResult

    /**
     * The rate is eligible but `roundSnapshot` produced no amount (invalid amount, or the home
     * currency's digits are unavailable). There is no amount to show; it is never shown as zero.
     */
    data class AmountUnavailable(
        val reason: SnapshotRoundingUnavailableReason,
        val rateDate: LocalDate,
        val sourceLabel: RateSourceLabel
    ) : UserPresentRatePreviewResult

    /** The save would be refused ([eligibility] is `Stale`, `None` or `Unsupported`). No amount. */
    data class Blocked(val eligibility: RateEligibility) : UserPresentRatePreviewResult
}

/**
 * Read-only use case for the entry view models (FG-12): evaluates eligibility through the single
 * [RateEligibilityEvaluator] and, for an eligible rate, computes the converted amount with the same
 * operands, order and rounding as the coordinator's flagged snapshot
 * (`parsedAmount * rate`, then `roundSnapshot(product, home)`), so the preview equals the stored
 * `baseAmount` when no rate is written in between (INV-7, INV-15, AE-38).
 *
 * No fetch, no formatting, no logging, no wall clock.
 */
class UserPresentRatePreview @Inject constructor(
    private val evaluator: RateEligibilityEvaluator
) {

    /** Store exceptions propagate; callers map them; CancellationException is never caught. */
    suspend fun preview(
        parsedAmount: Double,
        currency: CurrencyCode,
        home: CurrencyCode,
        expenseAtMillis: Long,
        entryZone: ZoneId
    ): UserPresentRatePreviewResult =
        when (val eligibility = evaluator.evaluate(currency, home, expenseAtMillis, entryZone)) {
            RateEligibility.Home -> UserPresentRatePreviewResult.NotApplicableHome
            is RateEligibility.Eligible -> when (val rounded = roundSnapshot(parsedAmount * eligibility.rate, home)) {
                is SnapshotRounding.Rounded -> UserPresentRatePreviewResult.ConvertedPreview(
                    amountInHome = rounded.amount,
                    home = home,
                    rateDate = eligibility.rateDate,
                    sourceLabel = eligibility.sourceLabel
                )
                is SnapshotRounding.Unavailable -> UserPresentRatePreviewResult.AmountUnavailable(
                    reason = rounded.reason,
                    rateDate = eligibility.rateDate,
                    sourceLabel = eligibility.sourceLabel
                )
            }
            is RateEligibility.Stale,
            RateEligibility.None,
            is RateEligibility.Unsupported -> UserPresentRatePreviewResult.Blocked(eligibility)
        }
}
