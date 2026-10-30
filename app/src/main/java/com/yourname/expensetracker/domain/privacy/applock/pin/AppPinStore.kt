package com.yourname.expensetracker.domain.privacy.applock.pin

import kotlinx.coroutines.flow.Flow

/** Why a verifier is present but cannot be checked (S24 design decision 2, OD-15). */
enum class VerifierUnreadableCause {
    /** The Keystore HMAC key is missing. The attempt counter is untouched. */
    KEY_MISSING,

    /** Any other Keystore failure. Retry is allowed. The attempt counter is untouched. */
    KEYSTORE_ERROR,

    /**
     * The record or store cannot be read: unknown version or algId, iterations out of range, wrong salt or tag
     * length, a malformed field, or a corrupt or unreadable store. A tag mismatch is indistinguishable from a
     * wrong PIN.
     */
    RECORD_UNREADABLE
}

/**
 * Result of [AppPinStore.verify]. No variant carries a raw failure count or a PIN; counts and timings are never
 * printed by `toString` (INV-38).
 */
sealed interface PinVerifyResult {
    /** The PIN is correct. The shared counter has been reset (a failed reset write still gives Correct). */
    data object Correct : PinVerifyResult

    /** The PIN is wrong. The failure is durable. [delay] is the bounded tier that now applies. */
    data class Wrong(val delay: PinDelayTier) : PinVerifyResult

    /** A delay is active: the entry was rejected without being evaluated. */
    data class Delayed(val reason: PinDelayReason, val remainingMillis: Long) : PinVerifyResult {
        override fun toString(): String = "Delayed(reason=$reason)"
    }

    /** A verifier exists but cannot be checked. The counter is untouched; nothing is wiped (OD-15). */
    data class VerifierUnreadable(val cause: VerifierUnreadableCause) : PinVerifyResult

    /** The attempt state could not be written durably, so nothing was evaluated (no unlock, no wipe). */
    data object Unavailable : PinVerifyResult

    /** No verifier exists. */
    data object Absent : PinVerifyResult
}

/** Whether a verifier record exists. [UNREADABLE] still counts as "a verifier exists" (effective APP_PIN, AMD-4). */
enum class PinVerifierState {
    ABSENT,
    PRESENT,
    UNREADABLE
}

/** The PIN-store facts the controller needs (domain-spec 2.7, INV-51). */
data class AppPinStoreState(
    val verifier: PinVerifierState,
    val biometricEnabled: Boolean,
    val biometricInvalidatedNoticePending: Boolean
) {
    companion object {
        /** No verifier and no flags. */
        val ABSENT: AppPinStoreState = AppPinStoreState(PinVerifierState.ABSENT, false, false)
    }
}

enum class PinSetupResult { DONE, INVALID_FORMAT, FAILED }

/** Result of [AppPinStore.changePin]. */
sealed interface PinChangeResult {
    data object Changed : PinChangeResult
    data object InvalidFormat : PinChangeResult

    /** The current PIN was not accepted: [result] is the (non-Correct) verify outcome. */
    data class NotVerified(val result: PinVerifyResult) : PinChangeResult

    /** The current PIN was correct but the new verifier could not be written; the old PIN stays in force. */
    data object WriteFailed : PinChangeResult
}

enum class PinStoreWriteResult { DONE, FAILED }

enum class PinDeleteResult { DONE, FAILED }

/**
 * The only owner of the app PIN verifier, the shared attempt state and the PIN-mode flags (INV-42, Segment 28).
 *
 * Every PIN verification surface goes through [verify] and shares one counter and delay (AMD-2, INV-39). All
 * [CharArray] parameters are owned by the store from the call: it zeroes them in `finally`, including on
 * cancellation. Callers must not reuse them. A PIN is never turned into a String, logged or saved (INV-38).
 * Implementations never swallow `CancellationException`, and never wipe data on failed attempts.
 */
interface AppPinStore {

    /** One fresh read of the store facts. A store that cannot be read gives [PinVerifierState.UNREADABLE]. */
    suspend fun readState(): AppPinStoreState

    /** Committed state only. A read failure emits an [PinVerifierState.UNREADABLE] state, never ABSENT. */
    fun observeState(): Flow<AppPinStoreState>

    /**
     * Creates the Keystore key and writes the verifier, a zero attempt state and the flags in ONE edit (INV-50: the
     * caller has already committed `lockMode = APP_PIN`). Rejects a PIN that fails [PinFormatPolicy].
     */
    suspend fun setup(pin: CharArray): PinSetupResult

    /** Verifies [currentPin] through [verify] (shared counter), then replaces the verifier reusing the key. */
    suspend fun changePin(currentPin: CharArray, newPin: CharArray): PinChangeResult

    /**
     * The single verification path (AMD-2). Write-ahead: an active delay rejects without evaluating; otherwise the
     * counter is incremented durably BEFORE the PIN is evaluated, and a correct PIN resets it in a second write.
     */
    suspend fun verify(pin: CharArray): PinVerifyResult

    /**
     * Resets the shared attempt counter (a successful fingerprint unlock, INV-39).
     *
     * CONTROLLER-ONLY (S24 condition E): no screen, ViewModel or other caller may invoke it. A correct PIN already
     * resets the counter inside [verify].
     */
    suspend fun resetAttempts(): PinStoreWriteResult

    /** Sets the fingerprint-unlock flag (INV-51 clearing path is [dismissBiometricInvalidatedNotice]). */
    suspend fun setBiometricEnabled(enabled: Boolean): PinStoreWriteResult

    /** ONE write: fingerprint unlock off and `biometricInvalidatedNoticePending = true` (AMD-6, INV-51). */
    suspend fun recordBiometricInvalidated(): PinStoreWriteResult

    /** Clears `biometricInvalidatedNoticePending` (the user dismissed the notice). */
    suspend fun dismissBiometricInvalidatedNotice(): PinStoreWriteResult

    /**
     * Removes the PIN data (INV-49): one awaited clear of the store (the only decisive step), then the Keystore key,
     * best effort. A step-1 failure gives [PinDeleteResult.FAILED] and the caller must NOT write the settings.
     * Idempotent.
     */
    suspend fun deleteAll(): PinDeleteResult
}
