package com.yourname.expensetracker.ui.applock

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.AuthLaunch
import com.yourname.expensetracker.domain.privacy.applock.AuthPromptCopy
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCallback
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthErrorBucket
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthOutcome
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthenticator
import com.yourname.expensetracker.domain.privacy.applock.pin.BiometricKeyResult
import com.yourname.expensetracker.domain.privacy.applock.pin.BiometricPinKeyManager
import kotlin.coroutines.cancellation.CancellationException

/**
 * The only authenticator combination the app lock ever uses (INV-23). `BIOMETRIC_WEAK` is never
 * requested. The capability provider in `data/privacy/applock` repeats this value and a test pins both.
 */
internal const val PROMPT_ALLOWED_AUTHENTICATORS: Int = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

/**
 * The only authenticator of the PIN-mode fingerprint prompt (INV-40, AE-72): `BIOMETRIC_STRONG` alone. Never
 * `BIOMETRIC_WEAK`, never `DEVICE_CREDENTIAL`; the CryptoObject needs a strong authentication.
 */
internal const val PIN_MODE_FINGERPRINT_AUTHENTICATORS: Int = BIOMETRIC_STRONG

/**
 * Longest time, in milliseconds, the adapter waits for the library terminal callback that follows a
 * client cancel before it stops refusing new launches. A library that never echoes cannot block the adapter.
 */
@VisibleForTesting
internal const val AWAITING_TERMINAL_TIMEOUT_MS: Long = 1500L

/** Constant title resource for [copy] (INV-53). */
@StringRes
@VisibleForTesting
internal fun promptTitleRes(copy: AuthPromptCopy): Int = when (copy) {
    AuthPromptCopy.UNLOCK -> R.string.applock_prompt_title_unlock
    AuthPromptCopy.ENABLE -> R.string.applock_prompt_title_enable
    AuthPromptCopy.DISABLE -> R.string.applock_prompt_title_disable
    AuthPromptCopy.CHANGE -> R.string.applock_prompt_title_change
    AuthPromptCopy.EXPORT -> R.string.applock_prompt_title_export
    AuthPromptCopy.PIN_FINGERPRINT -> R.string.applock_pin_prompt_title
}

/** Constant subtitle resource for [copy]. Only ENABLE has one (INV-53). */
@StringRes
@VisibleForTesting
internal fun promptSubtitleRes(copy: AuthPromptCopy): Int? = when (copy) {
    AuthPromptCopy.ENABLE -> R.string.applock_prompt_subtitle_enable
    AuthPromptCopy.UNLOCK,
    AuthPromptCopy.DISABLE,
    AuthPromptCopy.CHANGE,
    AuthPromptCopy.EXPORT,
    AuthPromptCopy.PIN_FINGERPRINT -> null
}

/**
 * Builds the prompt description from string resources only (INV-53).
 *
 * No description and no negative button are set: the device credential is allowed, and the library
 * rejects a negative button in that case. No user data reaches the prompt.
 */
@VisibleForTesting
internal fun buildPromptInfo(context: Context, copy: AuthPromptCopy): BiometricPrompt.PromptInfo {
    if (copy == AuthPromptCopy.PIN_FINGERPRINT) {
        // INV-40: strong biometric only, so the library requires (and we set) the "Use PIN" negative button.
        return BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(promptTitleRes(copy)))
            .setAllowedAuthenticators(PIN_MODE_FINGERPRINT_AUTHENTICATORS)
            .setNegativeButtonText(context.getString(R.string.applock_pin_prompt_negative))
            .build()
    }
    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(context.getString(promptTitleRes(copy)))
        .setAllowedAuthenticators(PROMPT_ALLOWED_AUTHENTICATORS)
    promptSubtitleRes(copy)?.let { builder.setSubtitle(context.getString(it)) }
    return builder.build()
}

/**
 * Maps a library error code to a bounded outcome (INV-34, INV-37, ux-flows 4.3).
 *
 * The library error text is never read. Every code not listed maps to [DeviceAuthErrorBucket.OTHER],
 * so an unknown result can never authenticate.
 */
@VisibleForTesting
internal fun mapAuthenticationError(errorCode: Int): DeviceAuthOutcome = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> DeviceAuthOutcome.Cancelled

    BiometricPrompt.ERROR_LOCKOUT -> DeviceAuthOutcome.LockedOut(permanent = false)
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> DeviceAuthOutcome.LockedOut(permanent = true)

    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
    BiometricPrompt.ERROR_NO_BIOMETRICS,
    BiometricPrompt.ERROR_HW_NOT_PRESENT -> DeviceAuthOutcome.NotAvailable

    BiometricPrompt.ERROR_HW_UNAVAILABLE,
    BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
    BiometricPrompt.ERROR_NO_SPACE,
    BiometricPrompt.ERROR_VENDOR -> DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.HW_UNAVAILABLE)

    BiometricPrompt.ERROR_TIMEOUT -> DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.TIMEOUT)
    BiometricPrompt.ERROR_CANCELED -> DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED)

    else -> DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER)
}

/**
 * Whether a prompt may be launched now: API 30 or newer (the strong-or-credential combination is
 * unsupported below it, so the library is never called there) and a host that can still take a
 * fragment transaction (state not saved, not finishing, not destroyed).
 */
@VisibleForTesting
internal fun canLaunchPrompt(sdkInt: Int, stateSaved: Boolean, finishing: Boolean, destroyed: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.R && !stateSaved && !finishing && !destroyed

/**
 * The two library operations the adapter needs. It exists so the adapter state machine can be driven
 * by a test double; production uses the handle backed by the real `BiometricPrompt`.
 */
@VisibleForTesting
internal interface PromptHandle {
    fun authenticate(info: BiometricPrompt.PromptInfo)

    /** S26: the PIN-mode fingerprint prompt with its CryptoObject. A handle without support throws, which maps to PROMPT_UNAVAILABLE. */
    fun authenticate(info: BiometricPrompt.PromptInfo, crypto: BiometricPrompt.CryptoObject) {
        throw UnsupportedOperationException("crypto prompt not supported")
    }

    fun cancelAuthentication()
}

private class LibraryPromptHandle(private val prompt: BiometricPrompt) : PromptHandle {
    override fun authenticate(info: BiometricPrompt.PromptInfo) = prompt.authenticate(info)
    override fun authenticate(info: BiometricPrompt.PromptInfo, crypto: BiometricPrompt.CryptoObject) =
        prompt.authenticate(info, crypto)
    override fun cancelAuthentication() = prompt.cancelAuthentication()
}

private fun createLibraryHandle(activity: FragmentActivity, callback: BiometricPrompt.AuthenticationCallback): PromptHandle =
    LibraryPromptHandle(BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback))

/**
 * [DeviceAuthenticator] backed by the AndroidX `BiometricPrompt`, bound to one [FragmentActivity]
 * (TECH-1 A). It is created by the UI shell per Activity instance and is not a singleton.
 *
 * Lifecycle contract for S20 (binding): create this adapter in `MainActivity.onCreate`, after
 * `super.onCreate`, every time the Activity is created. The library prompt is constructed once, in the
 * constructor, which is the androidx guidance for reattaching the callback after a configuration
 * change. The adapter itself holds only the current in-flight sink. Attempt identity is owned by the
 * process-level controller (S16, INV-24), not by this class: a new adapter instance starts with nothing
 * in flight and the old adapter never reports. The open attempt is carried across an Activity
 * recreation by the process-level relay (S20-a), which hands it to the new instance through [adopt]
 * (see the rotation rule below); a host that is really lost is reported to the controller
 * (PROMPT_HOST_LOST, INV-54).
 *
 * - At most one prompt is in flight; a second request returns [AuthLaunch.ALREADY_IN_FLIGHT] without
 *   launching (INV-34).
 * - Below API 30, or when the host cannot take a fragment transaction (state saved, finishing,
 *   destroyed), the request returns [AuthLaunch.HOST_UNAVAILABLE] without launching and no callback follows.
 * - After a [LAUNCHED][AuthLaunch.LAUNCHED] request, this adapter instance delivers at most one outcome
 *   per attempt. A repeated or late platform callback is dropped, because each callback is checked
 *   against the in-flight instance by reference. The outcome is not guaranteed: it is missing after an
 *   Activity recreation, and the platform may silently skip a launch; the controller closes such an
 *   attempt with [cancel] or its own timeout (S16).
 * - [cancel] on the in-flight attempt delivers one bounded outcome itself (Failed with
 *   SYSTEM_CANCELED), synchronously inside the call, after the library cancel has been requested.
 * - After such a client cancel of a launched prompt, the adapter expects one library terminal callback
 *   (the echo). That next success or error callback is dropped and ends the wait, so it can never be
 *   credited to a newer attempt. While waiting, a launch request returns
 *   [AuthLaunch.AWAITING_PRIOR_TERMINAL]. The wait also ends after [AWAITING_TERMINAL_TIMEOUT_MS] on the
 *   main thread and at the next ON_START or ON_DESTROY of the host, so a library that never echoes
 *   cannot block the adapter.
 * - After a rotation the prompt host (S20-a) hands the open attempt to the new instance with the internal
 *   [adopt] seam, which sets the in-flight sink only when idle and not awaiting a terminal. Every rule above
 *   (one outcome, stale terminals dropped by reference, client cancel) then applies to the adopted attempt.
 * - Wrong-fingerprint feedback stays inside the system prompt and is not an outcome.
 * - Prompt text comes from string resources only (INV-53). The library error text is never read.
 * - Must be used on the main thread.
 */
class BiometricPromptDeviceAuthenticator @VisibleForTesting internal constructor(
    private val activity: FragmentActivity,
    // S26 (G26-4): the PIN-mode fingerprint key port, injected by the prompt host. Null (hosts that never show the
    // PIN fingerprint prompt, for example the export gate) makes a PIN_FINGERPRINT request PROMPT_UNAVAILABLE.
    private val keyManager: BiometricPinKeyManager? = null,
    promptFactory: (BiometricPrompt.AuthenticationCallback) -> PromptHandle?
) : DeviceAuthenticator {

    constructor(activity: FragmentActivity) : this(activity, null)

    constructor(activity: FragmentActivity, keyManager: BiometricPinKeyManager?) :
        this(activity, keyManager, { callback -> createLibraryHandle(activity, callback) })

    private class InFlight(val attemptId: Long, val callback: DeviceAuthCallback, val pinFingerprint: Boolean)

    private var inFlight: InFlight? = null

    private var awaitingLibraryTerminal: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val awaitingTimeout = Runnable { awaitingLibraryTerminal = false }

    private val libraryCallback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            // INV-40: a PIN-mode fingerprint success counts only when the key-bound crypto verifies (fail closed).
            if (inFlight?.pinFingerprint == true) {
                val verified = try {
                    keyManager?.verifyAuthenticatedCrypto(result.cryptoObject?.cipher) == true
                } catch (e: CancellationException) {
                    throw e
                } catch (ignored: RuntimeException) {
                    false
                }
                onLibraryTerminal(
                    if (verified) DeviceAuthOutcome.Succeeded else DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER)
                )
                return
            }
            onLibraryTerminal(DeviceAuthOutcome.Succeeded)
        }

        override fun onAuthenticationError(errorCode: Int, errString: kotlin.CharSequence) {
            onLibraryTerminal(mapAuthenticationError(errorCode))
        }
    }

    /** Null only when the platform refused to build the prompt; every request then returns PROMPT_UNAVAILABLE. */
    private val prompt: PromptHandle? = try {
        promptFactory(libraryCallback)
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: RuntimeException) {
        // Bounded: no prompt exists, authenticate() reports PROMPT_UNAVAILABLE and the caller maps it to a failure.
        null
    }

    init {
        activity.lifecycle.addObserver(
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_START -> clearAwaiting()
                        // A finishing host (not a configuration change) must not leave the system prompt
                        // orphaned (AE-118). A stop that is not finishing (OEM credential screen, Home) and a
                        // rotation never cancel (AE-36/117/120).
                        Lifecycle.Event.ON_PAUSE,
                        Lifecycle.Event.ON_STOP -> {
                            if (activity.isFinishing && !activity.isChangingConfigurations) abandonForHostLoss()
                        }
                        Lifecycle.Event.ON_DESTROY -> {
                            // Clear the wait first, then abandon, so the wait started by the abandon survives
                            // this handler and the echo of the silent cancel is still dropped.
                            clearAwaiting()
                            if (!activity.isChangingConfigurations) abandonForHostLoss()
                            source.lifecycle.removeObserver(this)
                        }
                        else -> Unit
                    }
                }
            }
        )
    }

    /** True when the library prompt exists. Test hook only. */
    @get:VisibleForTesting
    internal val isPromptBuilt: Boolean
        get() = prompt != null

    /** True while a library terminal callback from a client cancel is still expected. Test hook only. */
    @get:VisibleForTesting
    internal val isAwaitingLibraryTerminal: Boolean
        get() = awaitingLibraryTerminal

    override fun authenticate(attemptId: Long, copy: AuthPromptCopy, callback: DeviceAuthCallback): AuthLaunch {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return AuthLaunch.HOST_UNAVAILABLE
        if (inFlight != null) return AuthLaunch.ALREADY_IN_FLIGHT
        if (awaitingLibraryTerminal) return AuthLaunch.AWAITING_PRIOR_TERMINAL
        val current = prompt ?: return AuthLaunch.PROMPT_UNAVAILABLE
        return try {
            val launchable = canLaunchPrompt(
                sdkInt = Build.VERSION.SDK_INT,
                stateSaved = activity.supportFragmentManager.isStateSaved,
                finishing = activity.isFinishing,
                destroyed = activity.isDestroyed
            )
            if (!launchable) return AuthLaunch.HOST_UNAVAILABLE
            val info = buildPromptInfo(activity, copy)
            if (copy == AuthPromptCopy.PIN_FINGERPRINT) {
                // A key that is not ready never reaches the library: no credential fallback exists in this mode.
                val ready = keyManager?.cipherForAuthentication() as? BiometricKeyResult.Ready
                    ?: return AuthLaunch.PROMPT_UNAVAILABLE
                inFlight = InFlight(attemptId, callback, pinFingerprint = true)
                current.authenticate(info, BiometricPrompt.CryptoObject(ready.cipher))
            } else {
                inFlight = InFlight(attemptId, callback, pinFingerprint = false)
                current.authenticate(info)
            }
            AuthLaunch.LAUNCHED
        } catch (e: CancellationException) {
            inFlight = null
            throw e
        } catch (ignored: RuntimeException) {
            // Bounded: nothing was launched and no callback follows; the caller maps the result to a failure.
            inFlight = null
            AuthLaunch.PROMPT_UNAVAILABLE
        }
    }

    /**
     * Takes over the open attempt [attemptId] after an Activity recreation (S20-a, handoff S16 binding F3/F7).
     *
     * The platform prompt survives a rotation and the library re-attaches the client callback of the
     * prompt this instance built in its constructor, so its terminal callback now reaches this adapter.
     * This seam only records which attempt that terminal belongs to. It never launches anything.
     *
     * Adopts only when the adapter is idle (nothing in flight) and is not waiting for the library
     * terminal of an earlier client cancel: otherwise it returns false and changes nothing, so an
     * adoption can never replace a live attempt or let a stale echo be credited to the adopted one.
     * It also returns false when the library prompt was never built or the API is below 30, because no
     * terminal can arrive then. A true result means at most one outcome is delivered for [attemptId],
     * through [callback], exactly like an attempt launched by [authenticate]: the terminal is still
     * checked against the in-flight instance by reference, so a repeated or late terminal is dropped.
     */
    internal fun adopt(attemptId: Long, copy: AuthPromptCopy, callback: DeviceAuthCallback): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (prompt == null) return false
        if (inFlight != null || awaitingLibraryTerminal) return false
        // G26-3: the crypto-verified path is derived from the attempt's copy, so a rotation cannot bypass it.
        inFlight = InFlight(attemptId, callback, pinFingerprint = copy == AuthPromptCopy.PIN_FINGERPRINT)
        return true
    }

    override fun cancel(attemptId: Long) {
        val flight = inFlight ?: return
        if (flight.attemptId != attemptId) return
        // Clear first so any echo from the library, synchronous or late, is dropped. The wait flag is set
        // before the library cancel so a synchronous echo clears it. The outcome is delivered after the
        // library cancel so a new attempt started from the callback is not cancelled by our own cancel.
        inFlight = null
        beginAwaiting()
        try {
            prompt?.cancelAuthentication()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: RuntimeException) {
            // Bounded: the attempt is already closed on our side.
        } finally {
            flight.callback.onOutcome(flight.attemptId, DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED))
        }
    }

    /**
     * Closes the in-flight attempt silently because the host is finishing (AE-118). The library prompt is
     * cancelled so its system window is not orphaned, but NO outcome is delivered: the end of a lost host is
     * reported to the controller by the prompt host (PROMPT_HOST_LOST, INV-54) and is Abandoned, not a failure.
     * The wait flag is set before the library cancel so a synchronous or late echo is dropped.
     *
     * Idempotent: with nothing in flight (already abandoned, cancelled, completed or never launched) it does
     * nothing, so a repeated lifecycle signal never cancels twice. Main thread only.
     */
    internal fun abandonForHostLoss() {
        if (inFlight == null) return
        inFlight = null
        beginAwaiting()
        try {
            prompt?.cancelAuthentication()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: RuntimeException) {
            // Bounded: the attempt is already closed on our side; no library text is read or logged.
        }
    }

    private fun onLibraryTerminal(outcome: DeviceAuthOutcome) {
        if (awaitingLibraryTerminal) {
            // The echo of a client cancel: dropped, never credited to a newer attempt.
            clearAwaiting()
            return
        }
        val flight = inFlight ?: return
        complete(flight, outcome)
    }

    private fun complete(flight: InFlight, outcome: DeviceAuthOutcome) {
        if (inFlight !== flight) return
        inFlight = null
        flight.callback.onOutcome(flight.attemptId, outcome)
    }

    private fun beginAwaiting() {
        mainHandler.removeCallbacks(awaitingTimeout)
        awaitingLibraryTerminal = true
        mainHandler.postDelayed(awaitingTimeout, AWAITING_TERMINAL_TIMEOUT_MS)
    }

    private fun clearAwaiting() {
        mainHandler.removeCallbacks(awaitingTimeout)
        awaitingLibraryTerminal = false
    }
}
