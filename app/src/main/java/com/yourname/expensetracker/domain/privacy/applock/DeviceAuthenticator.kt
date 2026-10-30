package com.yourname.expensetracker.domain.privacy.applock

/**
 * Which constant, resource-backed prompt text the system prompt shows (INV-53).
 *
 * The controller selects one value per attempt. The set is closed, so no caller can pass free text,
 * and no value carries user data. Only [ENABLE] has a subtitle, and no value has a description.
 *
 * [EXPORT] (S22-b, D-S22-6) is NOT selected by the controller: it is the constant title of the device
 * authentication that gates the user-started database export on the unsupported-database screen ("Export backup").
 */
enum class AuthPromptCopy {
    UNLOCK,
    ENABLE,
    DISABLE,
    CHANGE,
    EXPORT,

    /**
     * S26 (INV-40): the fingerprint prompt of PIN mode. BIOMETRIC_STRONG only, never the device credential, with a
     * "Use PIN" negative button and a key-bound CryptoObject. Not selected by the existing controller attempts.
     */
    PIN_FINGERPRINT
}

/** Bounded readiness of the PIN-mode fingerprint key (S26). Never carries Keystore text. */
enum class PinFingerprintKeyState {
    /** The key exists and is usable for a prompt. */
    READY,

    /** The key was invalidated (new enrolment, OD-16a). The fingerprint flag must be turned off (INV-51). */
    INVALIDATED,

    /** No key, or a Keystore failure. Fingerprint is not offered; the flag is left untouched. */
    UNAVAILABLE
}

/** Domain view of the PIN-mode fingerprint key, so the controller never touches the data layer (S26). */
fun interface PinFingerprintKeyGate {
    /** May block on the Keystore: call off the main thread. */
    fun state(): PinFingerprintKeyState
}

/** Bounded bucket for a prompt error that is neither a cancel, a lockout nor an availability loss (INV-37). */
enum class DeviceAuthErrorBucket {
    HW_UNAVAILABLE,
    TIMEOUT,
    SYSTEM_CANCELED,
    OTHER
}

/**
 * Bounded result of one prompt (INV-34). It never carries platform error codes, error text or
 * exceptions, so nothing platform-supplied can reach the UI or diagnostics. Only [Succeeded]
 * authenticates; every other value, and any unknown platform result, leaves the state unchanged.
 */
sealed interface DeviceAuthOutcome {
    data object Succeeded : DeviceAuthOutcome

    /** The user dismissed the prompt or used the negative button. */
    data object Cancelled : DeviceAuthOutcome

    /** The platform locked authentication out; [permanent] is true when the biometric lockout needs the credential. */
    data class LockedOut(val permanent: Boolean) : DeviceAuthOutcome

    /** No device credential, no enrolled biometric, or no hardware (AE-50). The caller re-checks capability. */
    data object NotAvailable : DeviceAuthOutcome

    data class Failed(val bucket: DeviceAuthErrorBucket) : DeviceAuthOutcome
}

/**
 * Bounded result of one launch request. It carries no code and no text, so the caller can map each
 * value to a bounded failure or retry decision without any platform detail. Only [LAUNCHED] is
 * followed by an outcome callback.
 */
enum class AuthLaunch {
    /** The prompt was launched; the callback receives the outcome for the attempt. */
    LAUNCHED,

    /** Another prompt of this adapter is in flight (INV-34). Nothing was launched. */
    ALREADY_IN_FLIGHT,

    /** API below 30, or the host Activity cannot take a fragment transaction (state saved, finishing, destroyed). */
    HOST_UNAVAILABLE,

    /** A prompt cancelled by the client has not yet reported its terminal callback; retry shortly. */
    AWAITING_PRIOR_TERMINAL,

    /** The prompt could not be built, or the launch threw. */
    PROMPT_UNAVAILABLE
}

/** Receives the outcome of a launched attempt (INV-34). Invoked on the main thread. */
fun interface DeviceAuthCallback {
    fun onOutcome(attemptId: Long, outcome: DeviceAuthOutcome)
}

/**
 * Port to the system authentication prompt. The platform adapter is bound to an Activity, so it is
 * created by the UI shell and is not a singleton.
 */
interface DeviceAuthenticator {
    /**
     * Launches one prompt for [attemptId] with the constant text for [copy].
     *
     * Returns a value other than [AuthLaunch.LAUNCHED] when nothing was launched; no callback follows
     * in that case. After [AuthLaunch.LAUNCHED], [callback] is expected to receive at most one outcome for
     * [attemptId] while this adapter instance lives, but an outcome is NOT guaranteed: the platform may
     * silently skip a launch. The controller must close such an attempt itself, with [cancel] (which always
     * delivers Failed(SYSTEM_CANCELED) synchronously) or its own timeout. An outcome may also arrive
     * re-entrantly before [authenticate] returns, so record the attempt as open first. At most one prompt is
     * in flight at a time (INV-34).
     *
     * The guarantee is per adapter instance. After an Activity recreation the new adapter has nothing
     * in flight and the old adapter never reports; the process-level controller owns the open attempt
     * across recreation (S16, INV-24, PROMPT_HOST_LOST), and the process-level relay carries it to the
     * new adapter (S20-a).
     */
    fun authenticate(attemptId: Long, copy: AuthPromptCopy, callback: DeviceAuthCallback): AuthLaunch

    /**
     * Dismisses the prompt for [attemptId] if it is still in flight. A stale id is ignored.
     *
     * For the in-flight attempt, the adapter delivers [DeviceAuthOutcome.Failed] with
     * [DeviceAuthErrorBucket.SYSTEM_CANCELED] synchronously inside this call, so the callback runs
     * before [cancel] returns.
     */
    fun cancel(attemptId: Long)
}
