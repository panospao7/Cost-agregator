package com.yourname.expensetracker.domain.privacy.applock

/**
 * The pure derivation of the one [LockNotice] (domain-spec INV-51, AMD-6, AMD-22). It has no state, no clock and
 * no port: the controller calls it under its monitor with the state it is about to publish and the load state it
 * holds, so the notice always follows the state it was derived with.
 *
 * - Only open states carry a notice: `DISABLED`, `UNLOCKED`, `INERT`. `RESOLVING`, `LOCKED` and
 *   `AUTHENTICATING(UNLOCK)` carry none.
 * - (AMD-22) During `AUTHENTICATING(ENABLE | CONFIRM_CHANGE)` the origin state's notice is derived, unchanged, so the
 *   slot neither disappears nor jumps while the prompt is open (INV-53, AE-115). The origin of ENABLE is `DISABLED`,
 *   the origin of CONFIRM_CHANGE is `UNLOCKED`.
 * - Priority: [LockNotice.Inert] over [LockNotice.ReviewSettings] over [LockNotice.BiometricInvalidated]. A lower
 *   notice waits, it is not dropped.
 * - [LockNotice.ReviewSettings] comes from the LOAD STATE (R4), and only for a persistent unreadable cause
 *   (`CORRUPTED`, `INVALID_VALUE`). Transient causes (`IO_ERROR`, `TIMEOUT`) and a missing load state (the
 *   controller's own resolve fallback) produce no review notice (INV-47).
 * - [LockNotice.BiometricInvalidated] is `false` in Increment 1 (no biometric pending flag exists yet).
 *
 * Notices carry no amounts, counts or link hints (INV-35).
 */
object LockNoticeDerivation {

    fun derive(
        state: AppLockState,
        loadState: AppLockSettingsLoadState?,
        biometricPending: Boolean = false
    ): LockNotice? {
        val openState: AppLockState = when (state) {
            AppLockState.Resolving, is AppLockState.Locked -> return null
            is AppLockState.Authenticating -> when (state.purpose.origin) {
                AppLockOrigin.LOCKED -> return null // AUTHENTICATING(UNLOCK): the lock surface, no notice
                AppLockOrigin.DISABLED -> AppLockState.Disabled
                AppLockOrigin.UNLOCKED -> AppLockState.Unlocked
            }
            AppLockState.Disabled, AppLockState.Unlocked, is AppLockState.Inert -> state
        }
        if (openState is AppLockState.Inert) return LockNotice.Inert(openState.reason)
        if (loadState is AppLockSettingsLoadState.Unreadable && loadState.cause.isPersistent) {
            return LockNotice.ReviewSettings
        }
        if (biometricPending) return LockNotice.BiometricInvalidated
        return null
    }
}
