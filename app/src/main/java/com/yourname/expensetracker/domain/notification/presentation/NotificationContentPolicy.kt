package com.yourname.expensetracker.domain.notification.presentation

import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.EffectivePrivacyValues
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause

/**
 * The single owner of notification presentation decisions (INV-4, domain-spec 4.2 to 4.4). Pure and
 * total: no I/O, no clock, no Android types, never throws.
 *
 * Mode (INV-6, 1a terms): DISCREET exactly when `EffectivePrivacyValues.effectiveDiscreet` holds, that
 * is the setting is on, or the load state is `Unreadable(any)`, or no usable read arrived in time
 * (a null load state). The 1b lock term is added with the lock slices.
 *
 * Actions (INV-10, 1a): BILL on API 31+ is [NotificationActionPolicy.AUTH_REQUIRED] unconditionally
 * (OD-5). BILL on API 30 and below is [NotificationActionPolicy.UNCHANGED] in 1a; the 1b rows that drop
 * the actions on a locked or unreadable device are added by the 1b slices. Kinds other than BILL have no
 * actions today, so their policy is [NotificationActionPolicy.UNCHANGED] (nothing to change).
 *
 * Expanded style (spec 4.3): only ANOMALY and BRIEFING have BigText today, and only in FULL. DISCREET
 * never has an expanded style.
 *
 * The decision identifies the kind and never carries copy; the data layer maps the kind to string
 * resources (INV-5, TECH-2).
 */
object NotificationContentPolicy {

    /** First API level where notification actions can require device authentication (OD-5). */
    private const val AUTH_REQUIRED_MIN_SDK = 31

    /**
     * Decides from a settings read. A null [loadState] means the bounded read timed out. For every
     * unreadable or timed-out read the mode is DISCREET and [NotificationPresentationDecision.fallbackReason]
     * is set.
     */
    fun decide(
        kind: NotificationKind,
        loadState: AppLockSettingsLoadState?,
        sdkInt: Int
    ): NotificationPresentationDecision {
        val discreet = EffectivePrivacyValues.effectiveDiscreet(loadState)
        return build(kind, discreet, sdkInt, fallbackReasonFor(loadState))
    }

    /** DISCREET decision for a failure that produced no usable load state (spec 4.2, INV-9). */
    fun decideFailClosed(
        kind: NotificationKind,
        sdkInt: Int,
        reason: NotificationFallbackReason
    ): NotificationPresentationDecision = build(kind, discreet = true, sdkInt = sdkInt, fallbackReason = reason)

    /**
     * DISCREET decision for the in-place re-post sweep (spec 4.6, INV-13): the sweep runs only after a
     * committed write made discreet effective, so it does not read the settings again. No fallback reason.
     */
    fun decideDiscreet(kind: NotificationKind, sdkInt: Int): NotificationPresentationDecision =
        build(kind, discreet = true, sdkInt = sdkInt, fallbackReason = null)

    private fun fallbackReasonFor(loadState: AppLockSettingsLoadState?): NotificationFallbackReason? =
        when (loadState) {
            null -> NotificationFallbackReason.READ_TIMEOUT
            is AppLockSettingsLoadState.Unreadable ->
                if (loadState.cause == SettingsUnreadableCause.TIMEOUT) {
                    NotificationFallbackReason.READ_TIMEOUT
                } else {
                    NotificationFallbackReason.SETTINGS_UNREADABLE
                }
            is AppLockSettingsLoadState.Loaded, is AppLockSettingsLoadState.FirstRunDefault -> null
        }

    private fun build(
        kind: NotificationKind,
        discreet: Boolean,
        sdkInt: Int,
        fallbackReason: NotificationFallbackReason?
    ): NotificationPresentationDecision {
        val mode = if (discreet) NotificationPresentationMode.DISCREET else NotificationPresentationMode.FULL
        return NotificationPresentationDecision(
            kind = kind,
            mode = mode,
            expandedStyleAllowed = mode == NotificationPresentationMode.FULL && hasExpandedStyle(kind),
            actionPolicy = actionPolicy(kind, sdkInt),
            fallbackReason = fallbackReason
        )
    }

    private fun hasExpandedStyle(kind: NotificationKind): Boolean =
        when (kind) {
            NotificationKind.ANOMALY, NotificationKind.BRIEFING -> true
            NotificationKind.BILL,
            NotificationKind.BUDGET,
            NotificationKind.WARRANTY,
            NotificationKind.RECEIPT_MATCH -> false
        }

    private fun actionPolicy(kind: NotificationKind, sdkInt: Int): NotificationActionPolicy =
        when (kind) {
            NotificationKind.BILL ->
                if (sdkInt >= AUTH_REQUIRED_MIN_SDK) {
                    NotificationActionPolicy.AUTH_REQUIRED
                } else {
                    NotificationActionPolicy.UNCHANGED
                }
            NotificationKind.ANOMALY,
            NotificationKind.BUDGET,
            NotificationKind.WARRANTY,
            NotificationKind.RECEIPT_MATCH,
            NotificationKind.BRIEFING -> NotificationActionPolicy.UNCHANGED
        }
}
