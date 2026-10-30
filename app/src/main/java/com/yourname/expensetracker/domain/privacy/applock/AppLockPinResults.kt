package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayReason
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayTier
import com.yourname.expensetracker.domain.privacy.applock.pin.PinFormatResult
import com.yourname.expensetracker.domain.privacy.applock.pin.VerifierUnreadableCause

/**
 * Why a PIN entry did not pass (S25). Bounded and PIN-free: no failure count is carried, and `toString` of a delay
 * never prints a duration (INV-38).
 */
sealed interface PinRejection {
    /** The PIN is wrong; the failure is durable and [delay] is the tier that now applies. */
    data class Wrong(val delay: PinDelayTier) : PinRejection {
        // G25-8: the tier reveals the failure count (INV-38), so toString never prints it.
        override fun toString(): String = "Wrong"
    }

    /** A delay is active: the entry was rejected without being evaluated (T25). */
    data class Delayed(val reason: PinDelayReason, val remainingMillis: Long) : PinRejection {
        override fun toString(): String = "Delayed(reason=$reason)"
    }

    /** A verifier exists but cannot be checked. Stays locked; the recovery state applies (INV-41, OD-15). */
    data class VerifierUnreadable(val cause: VerifierUnreadableCause) : PinRejection

    /** The attempt state could not be written durably, so nothing was evaluated. */
    data object Unavailable : PinRejection
}

/** Bounded result of [AppLockController.onPinSubmitted] (T23 to T25). Only [Unlocked] opens the app. */
sealed interface PinUnlockResult {
    /** T23: the PIN was correct and the state is `UNLOCKED`. */
    data object Unlocked : PinUnlockResult

    /** T24, T25 and the unreadable / unavailable outcomes: the app stays `LOCKED`. */
    data class Rejected(val reason: PinRejection) : PinUnlockResult

    /** The state is not `LOCKED` in effective `APP_PIN` mode, or no verifier exists. Nothing was evaluated. */
    data object NotInPinMode : PinUnlockResult

    /** Another PIN operation is running. Nothing was evaluated. */
    data object AlreadyInFlight : PinUnlockResult
}

/** Bounded result of [AppLockController.requestPinSetup] (T31 request, INV-50). */
sealed interface PinSetupRequestResult {
    /** The device prompt (CONFIRM_CHANGE) started; on its success the step becomes [PinStep.SETUP_ENTRY]. */
    data class Started(val attemptId: Long) : PinSetupRequestResult

    /** INV-50 preconditions do not hold (state, lock off, already PIN mode, unreadable settings, capability). */
    data object NotAllowed : PinSetupRequestResult

    /** A prompt is already in flight (INV-34), or another PIN operation is running. */
    data object AlreadyInFlight : PinSetupRequestResult

    /** [AppLockController.enablePin] completed: `lockMode = APP_PIN` is written and the verifier exists (INV-50). */
    data object Enabled : PinSetupRequestResult

    /** The new PIN fails the format rules. Nothing was written. */
    data class InvalidFormat(val result: PinFormatResult) : PinSetupRequestResult

    /** A write failed; the mode write was reverted best effort and no verifier exists (INV-50). */
    data object Failed : PinSetupRequestResult
}

/** Bounded result of a PIN step operation (setup, change, remove, disable). It carries no PIN and no platform text. */
sealed interface PinOperationResult {
    /** The operation completed and the step is closed. */
    data object Done : PinOperationResult

    /** No matching step is open (the state left `UNLOCKED`, T13/T14, or the mode changed). Nothing was applied. */
    data object NotInStep : PinOperationResult

    /** Another PIN operation is running. Nothing was applied. */
    data object AlreadyInFlight : PinOperationResult

    /** The two entries of the new PIN differ. The step stays open. */
    data object Mismatch : PinOperationResult

    /** The new PIN fails the format rules. The step stays open. */
    data class InvalidFormat(val result: PinFormatResult) : PinOperationResult

    /** The current PIN was not accepted (shared counter and delay, INV-39). The step stays open. */
    data class NotVerified(val reason: PinRejection) : PinOperationResult

    /** Nothing was changed: a write before the point of no return failed (INV-49 step 1, INV-50). */
    data object Failed : PinOperationResult

    /**
     * INV-49: the PIN data is deleted but the settings write failed. The effective mode is now `DEVICE_AUTH` with the
     * lock still on (`UNLOCKED`, or `INERT` when the device cannot authenticate).
     */
    data object ModeWriteFailed : PinOperationResult

    /**
     * INV-49 (AMD-4, T30): the PIN data is deleted and the step is closed, but the device cannot authenticate, so the
     * lock must turn off. [AppLockController.disablePin] never writes the lock flag; [AppLockSettingsOperations.removePin]
     * makes that write and maps the final result to [Done] or [ModeWriteFailed].
     */
    data object RemovedLockOffRequired : PinOperationResult
}

/** Bounded result of the frozen fingerprint hook [AppLockController.onFingerprintUnlockSucceeded] (D-I2-7). */
enum class FingerprintUnlockResult {
    /** Fingerprint unlock is not wired (S26) or the state does not allow it. Nothing changed. */
    NOT_AVAILABLE,

    /** The counter was reset (INV-39) and the state is `UNLOCKED`. */
    UNLOCKED
}
