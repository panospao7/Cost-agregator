package com.yourname.expensetracker.domain.privacy.applock

/**
 * Why the settings store could not be read (domain-spec 2.2).
 *
 * The repository itself produces [IO_ERROR], [CORRUPTED] and [INVALID_VALUE]. [TIMEOUT] is produced
 * only by a bounded reader that gave up waiting (the notification resolver, T_read); it is part of
 * the closed cause set so every consumer handles it identically.
 */
enum class SettingsUnreadableCause {
    IO_ERROR,
    CORRUPTED,
    TIMEOUT,
    INVALID_VALUE;

    /**
     * Persistent causes stay until the user explicitly saves the settings again (INV-33, INV-44).
     * Transient causes recover by re-read and accept no write (INV-44, INV-47).
     */
    val isPersistent: Boolean
        get() = this == CORRUPTED || this == INVALID_VALUE
}

/**
 * Typed result of every settings read (INV-2). [Unreadable] is never collapsed into defaults;
 * callers apply the fail-closed rules in `EffectivePrivacyValues`.
 */
sealed interface AppLockSettingsLoadState {
    /** The store holds committed settings (marker `NORMAL`, or at least one real key present). */
    data class Loaded(val settings: AppLockPrivacySettings) : AppLockSettingsLoadState

    /** No marker and no real key: a genuine first run with the normal defaults. */
    data class FirstRunDefault(val settings: AppLockPrivacySettings) : AppLockSettingsLoadState

    /** The store cannot be trusted. No settings are exposed, so no caller can fall back to defaults. */
    data class Unreadable(val cause: SettingsUnreadableCause) : AppLockSettingsLoadState
}
