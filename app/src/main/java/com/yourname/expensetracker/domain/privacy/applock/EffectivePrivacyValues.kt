package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState

/**
 * Derived effective values (domain-spec 2.3). Computed, never persisted, and the only place that
 * turns an unreadable or missing read into fail-closed values (INV-2).
 *
 * Every value is computed from the committed load state only (INV-46); there is no `AppLockState`
 * input except the single `RESOLVING` flag of [effectiveSecureScreen] (INV-53).
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
     * `discreetNotifications OR appLockEnabled OR load state is Unreadable OR the read exceeded T_read`
     * (a null [loadState]). The lock term (1b, OD-2a) holds in every lock state, including inert and
     * API-unsupported, because it reads the stored flag, not the lock state.
     */
    fun effectiveDiscreet(loadState: AppLockSettingsLoadState?): Boolean {
        val settings = effectiveSettings(loadState)
        return settings.discreetNotifications || settings.appLockEnabled
    }

    /**
     * The stored lock flag, or true for an unreadable or timed-out read (fail-closed record `F`).
     * Used for the API 30 and below action rule (INV-10).
     */
    fun effectiveLockEnabled(loadState: AppLockSettingsLoadState?): Boolean =
        effectiveSettings(loadState).appLockEnabled

    /**
     * `effectiveLockMode` (spec 2.3, AMD-4, INV-41): `APP_PIN` if a PIN verifier record exists, readable or not
     * ([PinVerifierState.PRESENT] or [PinVerifierState.UNREADABLE]); `DEVICE_AUTH` if none exists. The stored
     * [storedMode] never overrides a known verifier state.
     *
     * A null [verifier] means the PIN store has not been read yet (or the read timed out). Only then the advisory
     * stored mode decides, so a stored `APP_PIN` (or the fail-closed `APP_PIN` of an invalid token) stays PIN mode
     * until the real verifier state arrives. This is fail-closed for INV-40, and with nothing stored it is the
     * device-auth behaviour.
     */
    fun effectiveLockMode(verifier: PinVerifierState?, storedMode: LockMode): EffectiveLockMode =
        when (verifier) {
            PinVerifierState.PRESENT, PinVerifierState.UNREADABLE -> EffectiveLockMode.APP_PIN
            PinVerifierState.ABSENT -> EffectiveLockMode.DEVICE_AUTH
            null -> if (storedMode == LockMode.APP_PIN) EffectiveLockMode.APP_PIN else EffectiveLockMode.DEVICE_AUTH
        }

    /**
     * The settings part of `effectiveSecureScreen` (spec 2.3, INV-29):
     * `secureScreen OR appLockEnabled OR load state is Unreadable OR state = RESOLVING`.
     *
     * [resolving] is the one non-settings input: whether `AppLockState` is `RESOLVING`. There is no
     * `AUTHENTICATING` term: during a prompt the flag follows committed settings only (INV-53, INV-46).
     * A null [loadState] (no usable read) is fail-closed.
     */
    fun effectiveSecureScreen(loadState: AppLockSettingsLoadState?, resolving: Boolean): Boolean {
        if (resolving) return true
        val settings = effectiveSettings(loadState)
        return settings.secureScreen || settings.appLockEnabled
    }
}
