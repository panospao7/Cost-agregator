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
    AUTO_LOCK_TIMEOUT,
    SECURE_SCREEN
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
    WOULD_WEAKEN,

    /**
     * S18 i-b2 (INV-48, AE-103): the stored lock flag is on, so `discreetNotifications` and `secureScreen` are forced and
     * their stored values are read-only. Nothing was written and no authentication is asked.
     */
    FORCED,

    /**
     * S18 ii-d (F4(a), D8): a review-save found, inside its store edit, that the stored state is NOT persistent-unreadable
     * (`CORRUPTED` or `INVALID_VALUE`): it is `Loaded`, a first run, or transient-unreadable. A review-save is only the
     * recovery of a persistently unreadable store (INV-44), so nothing was written. A stale review card (the store was
     * recovered or changed since the card was shown) can therefore never overwrite a committed record.
     */
    NOT_UNDER_REVIEW
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
     *
     * Conditional (S18 ii-d, F4(a), D8): the write happens ONLY if, inside the same store edit, the stored state is
     * persistent-unreadable (`CORRUPTED` or `INVALID_VALUE`). A readable in-edit state (`Loaded`, first run) gives
     * `Rejected(NOT_UNDER_REVIEW)` and nothing is written, so a lock commit that landed after the caller looked is
     * never overwritten. On the real store a transient read failure (`IO_ERROR`) is not visible inside the edit: it
     * gives `Rejected(UNREADABLE)` and nothing is written, not `NOT_UNDER_REVIEW`. An IO failure of the edit itself is
     * `Failed(REVIEW_SAVE)` (AE-89).
     */
    suspend fun reviewSave(record: AppLockPrivacySettings): AppLockSettingsWriteResult

    /**
     * Per-field write of `appLockEnabled` (T17, T18; INV-3, INV-44, INV-46). Same contract as
     * [setDiscreetNotifications]: `Rejected(UNREADABLE)` and nothing written for any `Unreadable` state;
     * `Failed(APP_LOCK_ENABLED)` if the commit fails; `Committed` only after the durable commit. Authorisation
     * (the fresh prompt) is the caller's concern.
     *
     * Abstract (S18 ii-f, privacy review finding 8): there is no default body, so every implementation and every fake must
     * state its behaviour. A fake that does not support the write returns `Failed(APP_LOCK_ENABLED)` (fail-closed).
     */
    suspend fun setAppLockEnabled(enabled: Boolean): AppLockSettingsWriteResult

    /**
     * Per-field write of `autoLockTimeout` (S18 i-b1; INV-3, INV-44, INV-46). Same contract as
     * [setAppLockEnabled]: `Rejected(UNREADABLE)` and nothing written for any `Unreadable` state;
     * `Failed(AUTO_LOCK_TIMEOUT)` if the commit fails; `Committed` only after the durable commit. Authorisation
     * (the fresh prompt for a longer timeout) is the caller's concern.
     *
     * Abstract (S18 ii-f, privacy review finding 8): no default body. A fake that does not support the write returns
     * `Failed(AUTO_LOCK_TIMEOUT)` (fail-closed).
     */
    suspend fun setAutoLockTimeout(timeout: AutoLockTimeout): AppLockSettingsWriteResult

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
     * Abstract (S18 ii-f, privacy review finding 8): no default body. A fake that does not support the write returns
     * `Failed(AUTO_LOCK_TIMEOUT)` (fail-closed).
     */
    suspend fun setAutoLockTimeoutIfNotWeakening(timeout: AutoLockTimeout): AppLockSettingsWriteResult

    /**
     * Forced-aware per-field write of `discreetNotifications` (S18 i-b2; INV-48, AE-103, INV-44, INV-46). The check and
     * the write are ONE store edit:
     * - `Unreadable` store: `Rejected(UNREADABLE)`, nothing written;
     * - the stored lock flag is on (discreet is forced, also while the lock state is `INERT`): `Rejected(FORCED)`,
     *   nothing written, no authentication is asked (a write of either value is refused);
     * - otherwise the write, `Committed` only after the durable commit; `Failed(DISCREET)` if it fails.
     *
     * Abstract (S18 ii-f, privacy review finding 8): no default body. A fake that does not support the write returns
     * `Failed(DISCREET)` (fail-closed).
     */
    suspend fun setDiscreetNotificationsIfNotForced(enabled: Boolean): AppLockSettingsWriteResult

    /**
     * Forced-aware per-field write of `secureScreen` (S18 i-b2). Same contract as [setDiscreetNotificationsIfNotForced]
     * with `Failed(SECURE_SCREEN)`: a stored lock flag that is on gives `Rejected(FORCED)` and nothing is written.
     * Abstract (S18 ii-f, privacy review finding 8): no default body. A fake that does not support the write returns
     * `Failed(SECURE_SCREEN)` (fail-closed).
     */
    suspend fun setSecureScreenIfNotForced(enabled: Boolean): AppLockSettingsWriteResult
}
