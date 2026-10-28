package com.yourname.expensetracker.domain.privacy.applock

/**
 * Auto-lock timeout (domain-spec 2.1). Stored as the enum token ([token]), never as milliseconds, so
 * an unknown persisted value can be detected. [millis] is derived and never persisted.
 *
 * Order is from the most to the least restrictive; [IMMEDIATE] is the fail-closed value.
 */
enum class AutoLockTimeout(val millis: Long) {
    IMMEDIATE(0L),
    S30(30_000L),
    M1(60_000L),
    M5(300_000L),
    M15(900_000L);

    /** The persisted token. It is the enum name and is part of the store format: never rename an entry. */
    val token: String get() = name

    companion object {
        /** Default for a missing field (spec 2.1). */
        val DEFAULT: AutoLockTimeout = M1

        /** The value an invalid persisted token maps to: the most restrictive (spec 2.1). */
        val FAIL_CLOSED: AutoLockTimeout = IMMEDIATE

        /** The entry for a persisted [token], or null when the token is not a known one. */
        fun fromToken(token: String): AutoLockTimeout? = entries.firstOrNull { it.token == token }
    }
}

/**
 * Device-local app-lock / privacy-mode settings (domain-spec 2.1).
 *
 * Increment 1b adds lock enabled, auto-lock timeout and secure screen. The Inc 2 fields (lock mode,
 * biometric-in-PIN-mode) are added by later slices; an absent key always takes that field's default,
 * so adding a field never resets the store (spec 2.1).
 *
 * These values are never persisted in Room and never part of a `.costbackup` (INV-1).
 */
data class AppLockPrivacySettings(
    /** Default false. An invalid persisted value maps to true (most restrictive, spec 2.1). */
    val discreetNotifications: Boolean = DEFAULT_DISCREET_NOTIFICATIONS,
    /**
     * Default false. An invalid persisted value makes the whole load state
     * `Unreadable(INVALID_VALUE)` (spec 2.1); it is never mapped to a value here.
     */
    val appLockEnabled: Boolean = DEFAULT_APP_LOCK_ENABLED,
    /** Default [AutoLockTimeout.M1]. An invalid persisted token maps to [AutoLockTimeout.IMMEDIATE]. */
    val autoLockTimeout: AutoLockTimeout = AutoLockTimeout.DEFAULT,
    /** Default false. An invalid persisted value maps to true (most restrictive, spec 2.1). */
    val secureScreen: Boolean = DEFAULT_SECURE_SCREEN
) {
    companion object {
        const val DEFAULT_DISCREET_NOTIFICATIONS: Boolean = false
        const val DEFAULT_APP_LOCK_ENABLED: Boolean = false
        const val DEFAULT_SECURE_SCREEN: Boolean = false

        /** Normal first-run defaults. Never used for an `Unreadable` load state (INV-2). */
        val DEFAULTS: AppLockPrivacySettings = AppLockPrivacySettings()

        /**
         * The fail-closed effective record `F` for the 1a and 1b fields (spec 2.3, AMD-1): applies
         * whenever the load state is unreadable. It is derived, never written to the store.
         */
        val FAIL_CLOSED: AppLockPrivacySettings = AppLockPrivacySettings(
            discreetNotifications = true,
            appLockEnabled = true,
            autoLockTimeout = AutoLockTimeout.IMMEDIATE,
            secureScreen = true
        )
    }
}
