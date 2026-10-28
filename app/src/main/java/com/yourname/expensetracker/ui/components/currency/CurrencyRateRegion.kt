package com.yourname.expensetracker.ui.components.currency

import com.yourname.expensetracker.domain.currency.CurrencyUnsetReason
import com.yourname.expensetracker.domain.currency.RateSourceLabel
import java.time.LocalDate

/**
 * manual-entry-currency (ux-flows section 10): the state of the currency/rate region under the
 * amount, shared by add-expense and receipt review. Model only: enums, codes and [LocalDate]; no
 * copy strings and no composables (those arrive with the UI slice).
 *
 * Money rule: no variant other than [Eligible] carries an amount, and an unavailable amount is
 * never represented as zero. Nothing here is persisted, logged or placed in a SavedStateHandle.
 */
sealed interface CurrencyRegionUi {

    /** Home currency not loaded yet: control disabled, no preview. */
    data object Loading : CurrencyRegionUi

    /** Edit mode (INV-23): the currency is a read-only chip, no preview. */
    data object ReadOnly : CurrencyRegionUi

    /** The selected currency is the home currency: no conversion, no preview. */
    data object Home : CurrencyRegionUi

    /** No currency chosen yet ([reason] is the policy's bounded reason). Save is disabled. */
    data class Unset(val reason: CurrencyUnsetReason) : CurrencyRegionUi

    /** The entered amount fails the selected currency's rules; the preview and card are hidden. */
    data class AmountInvalid(val kind: CurrencyAmountInvalidKind) : CurrencyRegionUi

    /** An evaluation started and has not produced a result yet; nothing is shown for it (under 250 ms). */
    data object Pending : CurrencyRegionUi

    /** The evaluation is taking longer than the display delay: "Checking exchange rate". Save stays enabled. */
    data object Evaluating : CurrencyRegionUi

    /**
     * A stored rate qualifies. [rateDate] is the rate's public date (no time zone). [formattedAmountInHome]
     * is the home-currency amount formatted by the shared money formatter (the stored snapshot value), or
     * null when no amount is entered or [amountUnavailable] is true. Never "0" in place of unavailable.
     */
    data class Eligible(
        val rateDate: LocalDate,
        val sourceLabel: RateSourceLabel,
        val formattedAmountInHome: String?,
        val amountUnavailable: Boolean
    ) : CurrencyRegionUi

    /**
     * The save would be, or was, refused. [latestRateDate] is set only for [CurrencyBlockVariant.STALE].
     * [origin] is [CurrencyBlockOrigin.SAVE] for a post-tap refusal (assertive announcement).
     */
    data class Blocked(
        val variant: CurrencyBlockVariant,
        val latestRateDate: LocalDate?,
        val origin: CurrencyBlockOrigin
    ) : CurrencyRegionUi
}

/** Which rule the entered amount fails for the selected currency. Bounded. */
enum class CurrencyAmountInvalidKind {
    NOT_POSITIVE,
    PRECISION_EXCEEDED,
    ABOVE_CAP
}

/** Why the card is shown. Mirrors the rate eligibility and refusal reasons. Bounded. */
enum class CurrencyBlockVariant {
    NONE,
    STALE,
    UNSUPPORTED,
    HOME_UNRESOLVED
}

/** Where a [CurrencyRegionUi.Blocked] came from. */
enum class CurrencyBlockOrigin {
    PREVIEW,
    SAVE
}

/** The exchange-rate download setting as the entry screen sees it. [UNKNOWN] is treated as [OFF] (fail closed). */
enum class CurrencyRatesSetting {
    ON,
    OFF,
    UNKNOWN
}

/** Progress and bounded outcome of a user-started "Refresh rates". */
enum class CurrencyRefreshStatus {
    IDLE,
    RUNNING,
    DONE_STILL_BLOCKED,
    FAILED_OFFLINE,
    FAILED_HOME_UNAVAILABLE,
    FAILED
}

/** What the open-exchange-rate-setting action needs from the host (decision D-11). */
enum class CurrencyOpenSettingRequest {
    NONE,

    /** An amount was entered: ask the user to confirm before leaving the form. */
    CONFIRM_NEEDED,

    /** Navigate now; the host calls the consume function afterwards. */
    NAVIGATE
}
