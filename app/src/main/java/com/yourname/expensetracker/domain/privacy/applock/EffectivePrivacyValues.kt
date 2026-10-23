package com.yourname.expensetracker.domain.privacy.applock

/**
 * Derived effective values (domain-spec 2.3). Computed, never persisted, and the only place that
 * turns an unreadable or missing read into fail-closed values (INV-2).
 *
 * 1a covers `effectiveDiscreet` and the fail-closed record `F`. The 1b terms (lock enabled, secure
 * screen, timeout) are added by S14.
 */
object EffectivePrivacyValues {

    /**
     * The settings consumers must act on. For `Unreadable` this is the fail-closed record `F`
     * (never the normal defaults); a null [loadState] means no usable read arrived in time
     * (T_read exceeded) and is also fail-closed.
     */
    fun effectiveSettings(loadState: AppLockSettingsLoadState?): AppLockPrivacySettings =
        when (loadState) {
            is AppLockSettingsLoadState.Loaded -> loadState.settings
            is AppLockSettingsLoadState.FirstRunDefault -> loadState.settings
            is AppLockSettingsLoadState.Unreadable, null -> AppLockPrivacySettings.FAIL_CLOSED
        }

    /**
     * 1a: `discreetNotifications OR load state is Unreadable OR the read exceeded T_read`
     * (a null [loadState]).
     */
    fun effectiveDiscreet(loadState: AppLockSettingsLoadState?): Boolean =
        when (loadState) {
            is AppLockSettingsLoadState.Loaded -> loadState.settings.discreetNotifications
            is AppLockSettingsLoadState.FirstRunDefault -> loadState.settings.discreetNotifications
            is AppLockSettingsLoadState.Unreadable, null -> true
        }
}
