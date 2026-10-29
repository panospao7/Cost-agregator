package com.yourname.expensetracker.ui.components.currency

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.currency.CurrencyUnsetReason
import com.yourname.expensetracker.domain.currency.RateSourceLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * manual-entry-currency (ux-flows section 10): the state of the currency/rate region under the
 * amount, shared by add-expense and receipt review. The model is enums, codes and [LocalDate] with no
 * copy strings; the composables that render it are at the end of this file.
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

// ---------------------------------------------------------------------------------------------
// MEC B2b: composables for the region (ux-flows sections 3, 4, 13). Stateless: every value arrives
// as a parameter and every action leaves as a plain callback; nothing is persisted, logged or announced
// with the amount.
// ---------------------------------------------------------------------------------------------

/**
 * Renders [region] under the amount row: the preview line (Evaluating, Eligible) or the blocked card.
 * Every other state renders nothing. [suppressHomeUnresolvedCard] must be true while the B1
 * `SaveResult.Error` card is shown, so a home-unresolved refusal is never rendered twice.
 *
 * The preview line holding the converted figure is a polite live region only for the first figure shown
 * after a currency, date or home change ([announceKey] changed, ux-flows section 13); it carries no live
 * region while the figure changes per keystroke (the figure is read on focus). "Checking exchange rate"
 * and the blocked card are announced, the card assertively when it comes from Save
 * ([CurrencyBlockOrigin.SAVE]) and politely otherwise.
 *
 * [announceKey] is the host's monotonically increasing counter, bumped only by currency, date and home
 * changes. Its initial value is never announced (opening the screen is not a change).
 */
@Composable
fun CurrencyRateRegionContent(
    region: CurrencyRegionUi,
    ratesSetting: CurrencyRatesSetting,
    rateRefresh: CurrencyRefreshStatus,
    ratesToday: LocalDate?,
    suppressHomeUnresolvedCard: Boolean,
    onRefresh: () -> Unit,
    onOpenSetting: () -> Unit,
    onChangeCurrency: () -> Unit,
    modifier: Modifier = Modifier,
    announceKey: Int = 0
) {
    val latch = remember { PreviewAnnounceLatch(announceKey) }
    val previewText = if (region is CurrencyRegionUi.Eligible) previewLineText(region, ratesToday) else null
    val announcePreview = latch.observe(announceKey, previewText)
    when (region) {
        is CurrencyRegionUi.Evaluating -> Text(
            text = stringResource(R.string.mec_preview_checking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        is CurrencyRegionUi.Eligible -> RatePreviewLine(previewText, announcePreview, modifier)
        is CurrencyRegionUi.Blocked -> {
            val duplicate = region.variant == CurrencyBlockVariant.HOME_UNRESOLVED && suppressHomeUnresolvedCard
            if (!duplicate) {
                BlockedRateCard(
                    region = region,
                    ratesSetting = ratesSetting,
                    rateRefresh = rateRefresh,
                    ratesToday = ratesToday,
                    onRefresh = onRefresh,
                    onOpenSetting = onOpenSetting,
                    onChangeCurrency = onChangeCurrency,
                    modifier = modifier
                )
            }
        }
        else -> Unit
    }
}

/** The preview line's text, or null when there is nothing to show. Unavailable is never zero. */
@Composable
private fun previewLineText(region: CurrencyRegionUi.Eligible, ratesToday: LocalDate?): String? {
    val dateText = formatRateDate(region.rateDate, ratesToday)
    val amount = region.formattedAmountInHome
    val isEcb = region.sourceLabel == RateSourceLabel.ECB
    // Unavailable is never zero: without a figure only the rate date is shown, and only for the ECB wording.
    return when {
        amount != null && !region.amountUnavailable ->
            stringResource(if (isEcb) R.string.mec_preview_ecb else R.string.mec_preview_other, amount, dateText)
        isEcb -> stringResource(R.string.mec_preview_rate_only, dateText)
        else -> null
    }
}

@Composable
private fun RatePreviewLine(text: String?, announce: Boolean, modifier: Modifier) {
    if (text != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .fillMaxWidth()
                .semantics { if (announce) liveRegion = LiveRegionMode.Polite }
        )
    }
}

/**
 * Decides whether the preview line is a polite live region. It is one only for the first figure shown
 * after the host's announce key changed (a currency, date or home change), and stops being one as soon
 * as the figure changes again under the same key (an amount keystroke). Plain fields on purpose: it is
 * read and updated idempotently during composition for the same inputs, is never saved or logged, and
 * is held per composition by `remember`.
 */
private class PreviewAnnounceLatch(initialKey: Int) {
    private var consumedKey = initialKey
    private var seenKey = initialKey
    private var lastText: String? = null
    private var baselineText: String? = null
    private var sawGapSinceKey = false
    private var armedText: String? = null

    fun observe(key: Int, text: String?): Boolean {
        if (key != seenKey) {
            seenKey = key
            // The text of the previous composition: a stale figure still on screen is not the new one.
            baselineText = lastText
            sawGapSinceKey = false
            armedText = null
        }
        if (key != consumedKey) {
            if (text == null) {
                sawGapSinceKey = true
            } else {
                val armed = armedText
                if (armed == null) {
                    if (text != baselineText) {
                        armedText = text
                    } else if (sawGapSinceKey) {
                        consumedKey = key // Same figure came back: nothing changed to announce.
                    }
                } else if (text != armed) {
                    consumedKey = key // The figure moved again (a keystroke): stop announcing.
                    armedText = null
                }
            }
        }
        lastText = text
        return text != null && key != consumedKey && armedText == text
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlockedRateCard(
    region: CurrencyRegionUi.Blocked,
    ratesSetting: CurrencyRatesSetting,
    rateRefresh: CurrencyRefreshStatus,
    ratesToday: LocalDate?,
    onRefresh: () -> Unit,
    onOpenSetting: () -> Unit,
    onChangeCurrency: () -> Unit,
    modifier: Modifier
) {
    val requester = remember { BringIntoViewRequester() }
    val assertive = region.origin == CurrencyBlockOrigin.SAVE
    LaunchedEffect(region) {
        if (assertive) requester.bringIntoView()
    }

    val rateVariant = region.variant == CurrencyBlockVariant.NONE || region.variant == CurrencyBlockVariant.STALE
    val settingOn = ratesSetting == CurrencyRatesSetting.ON // Unknown is treated as Off (fail closed).
    val latest = region.latestRateDate
    val message = when (region.variant) {
        CurrencyBlockVariant.STALE ->
            if (latest != null) stringResource(R.string.mec_block_stale, formatRateDate(latest, ratesToday))
            else stringResource(R.string.mec_block_none)
        CurrencyBlockVariant.UNSUPPORTED -> stringResource(R.string.mec_block_unsupported)
        CurrencyBlockVariant.HOME_UNRESOLVED -> stringResource(R.string.mec_block_home_unresolved)
        CurrencyBlockVariant.NONE -> stringResource(R.string.mec_block_none)
    }
    val refreshLineRes = if (!rateVariant) null else when (rateRefresh) {
        CurrencyRefreshStatus.DONE_STILL_BLOCKED -> R.string.mec_refresh_still_blocked
        CurrencyRefreshStatus.FAILED_OFFLINE -> R.string.mec_refresh_offline
        CurrencyRefreshStatus.FAILED_HOME_UNAVAILABLE -> R.string.mec_refresh_home_unavailable
        CurrencyRefreshStatus.FAILED -> R.string.mec_refresh_failed
        else -> null
    }
    val showRefresh = rateVariant && settingOn && rateRefresh != CurrencyRefreshStatus.FAILED_HOME_UNAVAILABLE
    val showOpenSetting = rateVariant && !settingOn
    val showChange = region.variant != CurrencyBlockVariant.HOME_UNRESOLVED
    val onCard = MaterialTheme.colorScheme.onErrorContainer
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = onCard)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The live region covers the message and hints only; the action buttons stay separate focus targets.
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
                }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = onCard, modifier = Modifier.size(20.dp))
                    Text(message, color = onCard, style = MaterialTheme.typography.bodyMedium)
                }
                if (rateVariant) {
                    if (!settingOn) {
                        Text(stringResource(R.string.mec_block_off_hint), color = onCard, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.mec_block_alt_hint), color = onCard, style = MaterialTheme.typography.bodySmall)
                }
                if (refreshLineRes != null) {
                    Text(stringResource(refreshLineRes), color = onCard, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (showRefresh) {
                val running = rateRefresh == CurrencyRefreshStatus.RUNNING
                TextButton(
                    onClick = onRefresh,
                    enabled = !running,
                    colors = buttonColors,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = onCard)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.mec_action_refreshing))
                    } else {
                        Text(stringResource(R.string.mec_action_refresh))
                    }
                }
            }
            if (showOpenSetting) {
                TextButton(onClick = onOpenSetting, colors = buttonColors, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.mec_action_open_setting))
                }
            }
            if (showChange) {
                TextButton(onClick = onChangeCurrency, colors = buttonColors, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.mec_action_change_currency))
                }
            }
        }
    }
}

/** A public ECB date, rendered without any time zone; the year is added when it differs from today's. */
private fun formatRateDate(date: LocalDate, today: LocalDate?): String {
    val pattern = if (today != null && today.year == date.year) "d MMM" else "d MMM yyyy"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(date)
}
