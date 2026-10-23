package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.flow.Flow

/**
 * Closed field enum for a failed settings write (spec 10: `LOCK_SETTING_PERSIST_FAILED(field)`).
 * Later slices add the 1b/2 fields (`APP_LOCK_ENABLED`, ...).
 */
enum class AppLockSettingField {
    DISCREET,
    REVIEW_SAVE
}

/** Why a settings write was refused before touching the store. */
enum class AppLockWriteRejection {
    /** INV-44: the load state is `Unreadable`, so a per-field write is refused and nothing is written. */
    UNREADABLE
}

/**
 * Typed outcome of a settings write (INV-46). A write reports [Committed] only after the durable
 * commit; anything else leaves the previous committed value in force.
 */
sealed interface AppLockSettingsWriteResult {
    /** The write is durable. [settings] is the committed record. */
    data class Committed(val settings: AppLockPrivacySettings) : AppLockSettingsWriteResult

    /** Refused without writing (the sentinel, if any, is untouched). */
    data class Rejected(val reason: AppLockWriteRejection) : AppLockSettingsWriteResult

    /** The commit failed (IO). The store, the sentinel and the previous committed value are unchanged. */
    data class Failed(val field: AppLockSettingField) : AppLockSettingsWriteResult
}

/**
 * The only reader and writer of the device-local app-lock / privacy settings (INV-1, OD-11a).
 *
 * Never backed by Room and never part of `.costbackup`. Consumers observe committed state only
 * (INV-46): there is no optimistic value. Implementations never swallow `CancellationException`.
 */
interface AppLockSettingsRepository {

    /** One fresh read. IO failure maps to `Unreadable(IO_ERROR)`; it never throws for IO. */
    suspend fun getLoadState(): AppLockSettingsLoadState

    /**
     * Committed states only. After an IO read failure the flow emits `Unreadable(IO_ERROR)` once and
     * completes; collectors re-subscribe to recover (INV-47).
     */
    fun observeLoadState(): Flow<AppLockSettingsLoadState>

    /**
     * Per-field write of `discreetNotifications` (INV-44, INV-46). Returns `Rejected(UNREADABLE)` and
     * writes nothing for any `Unreadable` state; `Failed(DISCREET)` if the commit fails.
     */
    suspend fun setDiscreetNotifications(enabled: Boolean): AppLockSettingsWriteResult

    /**
     * Review-save (INV-44): one atomic write that replaces the whole store with the complete [record]
     * and marker `NORMAL`. This is the only write that clears a `CORRUPTED` / `INVALID_VALUE` state, and
     * only on success. Authorisation (INV-45) is the caller's concern, not the store's.
     */
    suspend fun reviewSave(record: AppLockPrivacySettings): AppLockSettingsWriteResult
}
