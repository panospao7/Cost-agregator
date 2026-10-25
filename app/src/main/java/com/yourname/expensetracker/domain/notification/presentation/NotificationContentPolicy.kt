package com.yourname.expensetracker.domain.notification.presentation

import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.EffectivePrivacyValues
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause

/**
 * The single owner of notification presentation decisions (INV-4, domain-spec 4.2 to 4.4). Pure and
 * total: no I/O, no clock, no Android types, never throws.
 *
 * Mode (INV-6): DISCREET exactly when `EffectivePrivacyValues.effectiveDiscreet` holds, that is the
 * setting is on, or the app lock is enabled (OD-2a, including inert and API-unsupported), or the load
 * state is `Unreadable(any)`, or no usable read arrived in time (a null load state).
 *
 * Actions (INV-10): BILL on API 31+ is [NotificationActionPolicy.AUTH_REQUIRED] unconditionally
 * (OD-5). BILL on API 30 and below is [NotificationActionPolicy.NONE] when the lock is enabled or the
 * settings are unreadable or timed out, otherwise [NotificationActionPolicy.UNCHANGED]. Kinds other than
 * BILL have no actions today, so their policy is [NotificationActionPolicy.UNCHANGED] (nothing to change).
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
        val lockOrUnreadable = EffectivePrivacyValues.effectiveLockEnabled(loadState)
        return build(kind, discreet, sdkInt, fallbackReasonFor(loadState), lockOrUnreadable)
    }

    /**
     * DISCREET decision for a failure that produced no usable load state (spec 4.2, INV-9). With no
     * usable state the settings count as unreadable, so BILL actions are dropped on API 30 and below.
     */
    fun decideFailClosed(
        kind: NotificationKind,
        sdkInt: Int,
        reason: NotificationFallbackReason
    ): NotificationPresentationDecision =
        build(kind, discreet = true, sdkInt = sdkInt, fallbackReason = reason, lockOrUnreadable = true)

    /**
     * DISCREET decision for the in-place re-post sweep (spec 4.6, INV-13): the sweep runs only after a
     * committed write made discreet effective, so it does not read the settings again. No fallback reason.
     *
     * [actionsSuppressed] is true when the caller knows the lock is enabled or the settings are
     * unreadable; on API 30 and below that drops the BILL actions (INV-10). The default keeps the
     * actions as the sweep rebuilds them today, and the sweep caller must pass the committed lock term
     * once it has one (see the S14 handoff note).
     */
    fun decideDiscreet(
        kind: NotificationKind,
        sdkInt: Int,
        actionsSuppressed: Boolean = false
    ): NotificationPresentationDecision =
        build(kind, discreet = true, sdkInt = sdkInt, fallbackReason = null, lockOrUnreadable = actionsSuppressed)

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
        fallbackReason: NotificationFallbackReason?,
        lockOrUnreadable: Boolean
    ): NotificationPresentationDecision {
        val mode = if (discreet) NotificationPresentationMode.DISCREET else NotificationPresentationMode.FULL
        return NotificationPresentationDecision(
            kind = kind,
            mode = mode,
            expandedStyleAllowed = mode == NotificationPresentationMode.FULL && hasExpandedStyle(kind),
            actionPolicy = actionPolicy(kind, sdkInt, lockOrUnreadable),
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

    private fun actionPolicy(kind: NotificationKind, sdkInt: Int, lockOrUnreadable: Boolean): NotificationActionPolicy =
        when (kind) {
            NotificationKind.BILL ->
                when {
                    sdkInt >= AUTH_REQUIRED_MIN_SDK -> NotificationActionPolicy.AUTH_REQUIRED
                    // API 30 and below, lock on or settings unreadable: no actions (INV-10, spec 4.4).
                    // API 26 to 29 can only hold a lock through transferred settings (inert) and follow
                    // the same row.
                    lockOrUnreadable -> NotificationActionPolicy.NONE
                    else -> NotificationActionPolicy.UNCHANGED
                }
            NotificationKind.ANOMALY,
            NotificationKind.BUDGET,
            NotificationKind.WARRANTY,
            NotificationKind.RECEIPT_MATCH,
            NotificationKind.BRIEFING -> NotificationActionPolicy.UNCHANGED
        }
}
