package com.yourname.expensetracker.domain.privacy.applock

/**
 * Device-local app-lock / privacy-mode settings (domain-spec 2.1).
 *
 * Increment 1a carries the discreet-notification flag only. The 1b fields (lock enabled,
 * auto-lock timeout, secure screen) and the Inc 2 fields (lock mode, biometric-in-PIN-mode) are added
 * by later slices; an absent key always takes that field's default, so adding a field never
 * resets the store (spec 2.1).
 *
 * These values are never persisted in Room and never part of a `.costbackup` (INV-1).
 */
data class AppLockPrivacySettings(
    /** Default false. An invalid persisted value maps to true (most restrictive, spec 2.1). */
    val discreetNotifications: Boolean = DEFAULT_DISCREET_NOTIFICATIONS
) {
    companion object {
        const val DEFAULT_DISCREET_NOTIFICATIONS: Boolean = false

        /** Normal first-run defaults. Never used for an `Unreadable` load state (INV-2). */
        val DEFAULTS: AppLockPrivacySettings = AppLockPrivacySettings()

        /**
         * The fail-closed effective record `F` for the 1a fields (spec 2.3, AMD-1): applies whenever
         * the load state is unreadable. It is derived, never written to the store.
         */
        val FAIL_CLOSED: AppLockPrivacySettings = AppLockPrivacySettings(discreetNotifications = true)
    }
}
