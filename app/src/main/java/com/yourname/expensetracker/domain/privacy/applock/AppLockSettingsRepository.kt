package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.flow.Flow

/**
 * Closed field enum for a failed settings write (spec 10: `LOCK_SETTING_PERSIST_FAILED(field)`).
 * [APP_LOCK_ENABLED] is the lock flag written by the T17 / T18 apply (S18). Later slices add the rest.
 */
enum class AppLockSettingField {
    DISCREET,
    REVIEW_SAVE,
    APP_LOCK_ENABLED,
    AUTO_LOCK_TIMEOUT
}

/** Why a settings write was refused before touching the store. */
enum class AppLockWriteRejection {
    /** INV-44: the load state is `Unreadable`, so a per-field write is refused and nothing is written. */
    UNREADABLE,

    /**
     * S18 i-b1 privacy review F1: a conditional timeout write found, inside the store edit, that the lock is on and the
     * requested timeout is longer than the stored one. That is a weakening that needs the fresh prompt, so nothing was
     * written.
     */
    WOULD_WEAKEN
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

    /**
     * Per-field write of `appLockEnabled` (T17, T18; INV-3, INV-44, INV-46). Same contract as
     * [setDiscreetNotifications]: `Rejected(UNREADABLE)` and nothing written for any `Unreadable` state;
     * `Failed(APP_LOCK_ENABLED)` if the commit fails; `Committed` only after the durable commit. Authorisation
     * (the fresh prompt) is the caller's concern.
     *
     * The default fails closed (`Failed(APP_LOCK_ENABLED)`, nothing written), so an implementation that does not
     * support the write can never report a commit. The real store overrides it.
     */
    suspend fun setAppLockEnabled(enabled: Boolean): AppLockSettingsWriteResult =
        AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED)

    /**
     * Per-field write of `autoLockTimeout` (S18 i-b1; INV-3, INV-44, INV-46). Same contract as
     * [setAppLockEnabled]: `Rejected(UNREADABLE)` and nothing written for any `Unreadable` state;
     * `Failed(AUTO_LOCK_TIMEOUT)` if the commit fails; `Committed` only after the durable commit. Authorisation
     * (the fresh prompt for a longer timeout) is the caller's concern.
     *
     * The default fails closed (`Failed(AUTO_LOCK_TIMEOUT)`, nothing written), so an implementation that does not
     * support the write can never report a commit. The real store overrides it.
     */
    suspend fun setAutoLockTimeout(timeout: AutoLockTimeout): AppLockSettingsWriteResult =
        AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT)

    /**
     * Conditional per-field write of `autoLockTimeout` for the NO-PROMPT path (S18 i-b1 privacy review F1; INV-3,
     * INV-44, INV-46). The check and the write are ONE store edit, so a stale pre-read can never turn a direct write
     * into an unauthorised weakening:
     * - `Unreadable` store: `Rejected(UNREADABLE)`, nothing written (as [setAutoLockTimeout]);
     * - the stored lock is on and [timeout] is longer than the stored timeout (an invalid stored timeout reads as
     *   IMMEDIATE, the most restrictive): `Rejected(WOULD_WEAKEN)`, nothing written;
     * - otherwise the write, `Committed` only after the durable commit; `Failed(AUTO_LOCK_TIMEOUT)` if it fails.
     *
     * The prompt path keeps the unconditional [setAutoLockTimeout]: the user authenticated for that exact value.
     * The default fails closed (`Failed(AUTO_LOCK_TIMEOUT)`, nothing written). The real store overrides it.
     * TODO(S18 privacy review finding 8): the defaults of this method and of [setAutoLockTimeout] become abstract
     * before any wiring.
     */
    suspend fun setAutoLockTimeoutIfNotWeakening(timeout: AutoLockTimeout): AppLockSettingsWriteResult =
        AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT)
}
