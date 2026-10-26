package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.di.AppLockMainDispatcher
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.diagnostics.DiagnosticThrowableSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded result of [AppLockController.onUnlockTapped]. It carries no text and no platform detail.
 */
sealed interface UnlockTapResult {
    /**
     * T7: the state moved to `AUTHENTICATING(attemptId, UNLOCK)` and the attempt is open. The system
     * prompt is launched afterwards, on the main thread, outside the controller's lock; its outcome
     * (or the watchdog, or host loss) always closes the attempt.
     */
    data class Started(val attemptId: Long) : UnlockTapResult

    /** A prompt is already in flight (INV-34). Nothing changed and no second attempt exists (AE-46). */
    data object AlreadyInFlight : UnlockTapResult

    /** The state is not `LOCKED`, so no prompt may start (spec 3.3: not from INERT, RESOLVING or an open state). */
    data object NotLocked : UnlockTapResult

    /**
     * T7b: the capability re-check says it is genuinely not available, so the state became `INERT([reason])`
     * and no prompt starts. A failed check ([DeviceAuthCapability.CHECK_FAILED]) never produces this result.
     */
    data class BecameInert(val reason: InertReason) : UnlockTapResult
}

/**
 * Bounded result of [AppLockController.startOpenOriginAttempt]. It carries no text and no platform detail.
 */
sealed interface OpenOriginStartResult {
    /** `AUTHENTICATING(attemptId, purpose)` is published and the attempt is open; the prompt launches next. */
    data class Started(val attemptId: Long) : OpenOriginStartResult

    /** A prompt is already in flight, of any purpose (INV-34, INV-54). Nothing changed. */
    data object AlreadyInFlight : OpenOriginStartResult

    /** The state is not the origin of the requested purpose (spec 2.4, 3.3). Nothing changed and nothing launched. */
    data object NotInOrigin : OpenOriginStartResult
}

/**
 * Receives the success of an open-origin attempt (the T33 hand-off to the owning settings operation, S18).
 *
 * The controller invokes it at most once per attempt, only for a success that matches the open attempt by
 * identity and id, after the attempt has closed and the state has returned to its origin. It is invoked
 * outside the controller's monitor, on the thread that delivered the outcome (the main thread). The
 * controller applies nothing itself: no setting, no unlock. A handler must not block, and a throwing
 * handler is logged by class name and otherwise ignored (the attempt is already closed). A
 * `CancellationException` is the exception to that: it is rethrown unchanged, never swallowed, so S18 must
 * never throw it from the handler (it would surface into the adapter's callback). Because the handler runs
 * outside the monitor, it may call back into the controller (start an attempt, `onLockQuery`, background);
 * the state it sees is already the closed origin. A non-success outcome never reaches it.
 */
fun interface OpenOriginSuccessHandler {
    fun onSucceeded(attemptId: Long, purpose: AuthPurpose)
}

/**
 * The process-level app-lock controller and the ONLY writer of [AppLockState] (INV-24, spec 2.4).
 *
 * S16 covers: resolution from the settings store (T1 to T6, T6b), the inert escapes (T4, T6, T7b, T11,
 * T15, T16), the foreground lock evaluation (T13 to T15) with pending-background consumption (spec 3.4,
 * AMD-22), and the whole UNLOCK attempt: start (T7), launch, success (T8), cancel (T9), error and
 * lockout (T10), no credential (T11), stale outcomes (T12) and host loss (T35); and the open-origin
 * attempts (`ENABLE` from `DISABLED`, `CONFIRM_CHANGE` from `UNLOCKED`): their start API, the prompt
 * background (T32), the success hand-off (T33, delivered to the S18 handler without applying anything) and
 * the non-success return to the origin with the timer hand-off (T34). `TODO(S17)` and `TODO(S18)` mark the
 * held link and the settings operations.
 *
 * ## Ports only
 *
 * The controller reads the settings store (a DataStore, never Room, INV-28), the device capability, the
 * monotonic clock, the system prompt port and the diagnostics front door. It has no Android type, no
 * barrier and no operational lock input: workers, receivers and services cannot reach it (INV-36, AE-68)
 * and locking never touches backup or restore (INV-28, AE-63).
 *
 * ## Fail-closed rules
 *
 * - Every unreadable or timed-out read resolves through the fail-closed record `F` (INV-2): lock on,
 *   `IMMEDIATE`, so the foreground timer after an unlock re-locks on any background (AE-90).
 * - A negative elapsed time (the clock went backwards) counts as timed out.
 * - The first background of a session is kept until a foreground evaluation consumes it: over-counting
 *   time away can only lock sooner (spec INV-54).
 * - Only the controller's own [RESOLVE_TIMEOUT_MILLIS] fallback may be superseded by a late read (T6b);
 *   a late read is never retained for later (it is dropped unless T6b applies at once).
 * - Only [DeviceAuthOutcome.Succeeded] for the open UNLOCK attempt opens the app. Every other outcome,
 *   an unanswered attempt, a lost host and a prompt that never launched all return to the origin (the
 *   origin lock for UNLOCK, `DISABLED` for ENABLE, `UNLOCKED` for CONFIRM_CHANGE) with nothing written.
 * - A success is bound to its purpose (INV-54): an ENABLE or CONFIRM_CHANGE success never unlocks `LOCKED` and
 *   an UNLOCK success never reaches the settings handler. A closing type that does not match the open
 *   `AUTHENTICATING(id, purpose)` state is dropped as stale.
 * - An ENABLE or CONFIRM_CHANGE prompt left open longer than [PROMPT_WATCHDOG_MILLIS] is abandoned like any
 *   other (the change is not applied, F5). The lock timer accrues nothing while it is shown (monotonic
 *   clock only), so a long foreground prompt cannot lock the app by itself.
 * - [DeviceAuthCapability.CHECK_FAILED] (the check threw) is transient: it never opens the app and never
 *   turns the lock `INERT` (R3). Only a genuine non-available capability does. An Unlock tap with a failed
 *   check still starts a normal UNLOCK attempt and launches the prompt (owner-approvals item 32): the
 *   platform prompt is the real authority and only a matching `Succeeded` opens the app. A prompt that then
 *   reports `NotAvailable` while the re-check still fails returns to the origin lock.
 *
 * ## Concurrency and publication (R2)
 *
 * Every transition computes its next state under one monitor ([stateLock]) and never suspends. The
 * [StateFlow] value is published AFTER the monitor is released, stamped with a monotonically increasing
 * version, and a value older than the one already published is never published. So a collector (even an
 * unconfined one that calls back into the controller) never runs inside the monitor, and the last
 * published value is always the newest state. Collectors must still never call a transition or the
 * authenticator (S19/S20 rule): publication holds a separate publish monitor while collectors resume inline.
 *
 * Capability is read before the monitor is taken, so no binder call runs under it. Diagnostics are fire
 * and forget on the injected scope (INV-37): they never block or delay a transition, and
 * `CancellationException` is never swallowed. Each code is emitted at most once per process per cause
 * (spec 10): the first transition that produces a code claims it under the monitor and later ones stay
 * silent. The emitter's own rate limit remains as a backstop.
 *
 * ## The prompt (P1, P2, P3, P4, P5)
 *
 * T7 records the attempt as open and publishes `AUTHENTICATING(id, UNLOCK)`. Only then, outside the
 * monitor, an orchestrating coroutine on the injected scope calls [DeviceAuthenticator.authenticate]
 * (hopping to the main dispatcher for the call). The outcome arrives only through the attempt's own
 * callback, which checks that the attempt is still the open one and that the id matches; anything else is
 * stale (T12). The adapter may deliver an outcome synchronously inside `authenticate` or `cancel`, which is
 * safe because the attempt is already open and every transition takes the monitor only briefly.
 *
 * Every attempt also has a watchdog on the same scope that closes it as cancelled if nothing answered
 * within [PROMPT_WATCHDOG_MILLIS]. The watchdog never waits for an outcome, is checked by attempt identity
 * and is cancelled when the attempt closes. Host loss ([onPromptHostLost]) closes the attempt first and
 * only then asks the adapter to cancel, so the adapter's cancel echo finds the attempt closed and is ignored.
 *
 * ## Time
 *
 * Only [MonotonicClock] (`elapsedRealtime`) is read for the lock timer. There is no wall-clock input, so a
 * clock change cannot move the timer (AE-43, FG-17). The prompt retry and watchdog bounds are coroutine
 * delays, not timer inputs.
 */
@Singleton
class AppLockController @Inject constructor(
    private val settingsRepository: AppLockSettingsRepository,
    private val capabilityProvider: DeviceAuthCapabilityProvider,
    private val clock: MonotonicClock,
    private val diagnostics: PrivacyDiagnosticsEmitter,
    @ApplicationScope private val scope: CoroutineScope,
    private val authenticator: DeviceAuthenticator,
    @AppLockMainDispatcher private val mainDispatcher: CoroutineDispatcher
) {

    private enum class ForegroundTrigger { APP_FOREGROUNDED, LOCK_QUERY }

    /** How an open attempt ends (private; the callback, the watchdog and host loss map onto these). */
    private sealed interface Closing {
        /** T8: the UNLOCK success. Checked against `Authenticating(id, UNLOCK)` only. */
        data object Success : Closing

        /**
         * T33: the success of an ENABLE or CONFIRM_CHANGE attempt. A separate type with its own check against
         * `Authenticating(id, purpose)` of an open-origin purpose, so the UNLOCK-only check above is untouched.
         * It returns to the origin and hands the success to the S18 handler; it applies nothing.
         */
        data object OpenOriginSuccess : Closing

        /**
         * ENABLE only: the prompt reported no credential or hardware and the capability re-check agrees
         * (T26-style refusal). Returns to `DISABLED` and records `LOCK_UNAVAILABLE_CAPABILITY(cause)`.
         */
        data class EnableRefused(val cause: DeviceAuthCapability) : Closing

        /** T9: the person dismissed the prompt. */
        data object Cancelled : Closing

        /** T10: the platform locked authentication out. */
        data object LockedOut : Closing

        /** T10: a bounded prompt error. */
        data class Error(val bucket: DeviceAuthErrorBucket) : Closing

        /** T11: no credential and the capability re-check agrees. */
        data class BecameInert(val reason: InertReason) : Closing

        /** T35 (host loss) and the watchdog: closes as cancelled, then asks the adapter to cancel. */
        data object Abandoned : Closing

        /** P2: nothing was launched. Back to the origin, no `LOCK_AUTH_*` code. */
        data object NotLaunched : Closing
    }

    /**
     * A diagnostic claimed under the monitor and recorded after it (spec 10). [cause] is the one bounded enum
     * attached under the closed `CAUSE` key: the [DeviceAuthErrorBucket] of `LOCK_AUTH_ERROR`, or
     * [DeviceAuthCapability] of `LOCK_UNAVAILABLE_CAPABILITY` (`HW_UNAVAILABLE` or `UNKNOWN`). Null for every other
     * code.
     */
    private class PendingDiagnostic(val code: PrivacyDiagnosticCode, val cause: Enum<*>?)

    /**
     * The attempt that is open. It is also the callback handed to the authenticator, so an outcome is bound
     * to this exact attempt by identity (P1).
     */
    private inner class InFlightAttempt(
        val attemptId: Long,
        val purpose: AuthPurpose,
        /** For [AuthPurpose.UNLOCK]: the cause of the `LOCKED` state it returns to (T9). Null otherwise. */
        val originLockCause: LockCause?,
        val copy: AuthPromptCopy,
        /** For ENABLE and CONFIRM_CHANGE: where a matching success is handed (T33). Null for UNLOCK. */
        val successHandler: OpenOriginSuccessHandler?
    ) : DeviceAuthCallback {
        /**
         * T32: the first `APP_BACKGROUNDED` during an open-origin attempt (monotonic). Bound to this attempt, so
         * it is gone when the attempt closes. Read and written only under [stateLock].
         */
        var promptBackgroundAt: Long? = null

        @Volatile var orchestrator: Job? = null
        @Volatile var watchdog: Job? = null

        /** True while the orchestrator waits to retry a launch that answered AWAITING_PRIOR_TERMINAL. */
        @Volatile var awaitingPriorTerminal: Boolean = false

        /**
         * Set (on the main dispatcher, right after `authenticate` returns) once the adapter answered LAUNCHED.
         * Set before any cancellation can be observed by the orchestrator, so a cancellation that races with the
         * return of the launch still knows a prompt may be showing.
         */
        @Volatile var launched: Boolean = false

        /** Wakes the retry wait early (the next foreground, the adapter's ON_START). */
        val nudge = Channel<Unit>(Channel.CONFLATED)

        override fun onOutcome(attemptId: Long, outcome: DeviceAuthOutcome) {
            routeOutcome(this, attemptId, outcome)
        }

        fun cancelJobs() {
            orchestrator?.cancel()
            watchdog?.cancel()
            nudge.close()
        }
    }

    private val stateLock = Any()
    private val publishLock = Any()
    private val mutableState = MutableStateFlow<AppLockState>(AppLockState.Resolving) // T1
    private val resolveStarted = AtomicBoolean(false)

    // Guarded by stateLock. `current` is the source of truth; `mutableState` only mirrors it (R2).
    @Volatile private var current: AppLockState = AppLockState.Resolving
    private var version = 0L
    private var loadState: AppLockSettingsLoadState? = null
    private var resolvedByTimeout = false
    private var lastBackgroundedAtMillis: Long? = null

    /**
     * Whether the app is in the foreground, for T34's immediate evaluation (P6). Set by `APP_FOREGROUNDED` and
     * cleared by `APP_BACKGROUNDED`, each before any early return. It starts false, which is the safe default:
     * with no foreground seen yet, a T34 evaluation waits for the next `APP_FOREGROUNDED` or `LOCK_QUERY` and is
     * never lost (the hand-off stays pending); a wrongly true value could consume time away. S20 must therefore
     * deliver `APP_FOREGROUNDED` at every real foreground, including the first one.
     */
    private var appInForeground = false
    private var inFlight: InFlightAttempt? = null
    private var lastIssuedAttemptId = 0L
    private val claimedDiagnosticCodes: MutableSet<PrivacyDiagnosticCode> =
        EnumSet.noneOf(PrivacyDiagnosticCode::class.java)
    private val claimedAuthErrorBuckets: MutableSet<DeviceAuthErrorBucket> =
        EnumSet.noneOf(DeviceAuthErrorBucket::class.java)
    /** Once-per-process claims keyed by (code, cause), so one code's claim never silences another code's cause. */
    private val claimedCodeCauses: MutableSet<Pair<PrivacyDiagnosticCode, DeviceAuthCapability>> = HashSet()

    // Written only under publishLock; read without it for the cheap staleness pre-check.
    @Volatile private var publishedVersion = 0L

    /**
     * The one process-level state. Starts at [AppLockState.Resolving]. Published after the controller's
     * monitor is released; a collector must never call a transition or the authenticator (R2).
     */
    val state: StateFlow<AppLockState> = mutableState.asStateFlow()

    /** The pending background (spec 2.4), for inspection by tests. Null when none is pending. */
    internal val pendingBackgroundAtMillis: Long?
        get() = synchronized(stateLock) { lastBackgroundedAtMillis }

    /** The id of the open attempt, for inspection by tests. Null when none is open. */
    internal val inFlightAttemptId: Long?
        get() = synchronized(stateLock) { inFlight?.attemptId }

    /** The open attempt's `promptBackgroundAt` (T32), for inspection by tests. Null when none is recorded. */
    internal val promptBackgroundAtMillis: Long?
        get() = synchronized(stateLock) { inFlight?.promptBackgroundAt }

    // --- Resolution (T1 to T6, T6b) -------------------------------------------------------------

    /**
     * Reads the settings store once and applies the result, with the controller's own
     * [RESOLVE_TIMEOUT_MILLIS] bound (T_resolve). Idempotent: a second call while a resolve is running
     * or done returns at once. A read that outlives the bound resolves as `RESOLVE_TIMEOUT` and its
     * late result is then offered as `LATE_SETTINGS_RESOLVED` (T6b).
     *
     * `CancellationException` propagates; if this call is cancelled before a result, a later call may
     * try again and the state stays `RESOLVING` (neutral surface, secure screen on). An `Error` from the
     * read propagates the same way and also re-arms the call.
     *
     * S20 must call this from the application scope, or again on each ON_START while the state is
     * `RESOLVING`: a caller scope that is cancelled leaves `RESOLVING` until a later call resolves.
     */
    suspend fun resolve() {
        if (!resolveStarted.compareAndSet(false, true)) return
        val read = scope.async { readLoadState() }
        val firstRead = try {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MILLIS) { read.await() }
        } catch (t: Throwable) {
            // Cancellation or an Error (for example ExceptionInInitializerError) from the read: reset the
            // one-shot flag so a later call can retry; the state stays RESOLVING, never stuck for the process.
            read.cancel()
            resolveStarted.set(false)
            throw t
        }
        if (firstRead != null) {
            onSettingsResolved(firstRead)
            return
        }
        onResolveTimeout()
        scope.launch { onLateSettingsResolved(read.await()) }
    }

    /** `SETTINGS_RESOLVED(loadState)`: T2 to T6. Ignored unless the state is `RESOLVING`. Only [resolve] calls it. */
    private fun onSettingsResolved(loadState: AppLockSettingsLoadState) {
        resolveFrom(loadState)
    }

    /** `RESOLVE_TIMEOUT`: T5 or T6 with the fail-closed record. Ignored unless the state is `RESOLVING`. Only [resolve] calls it. */
    private fun onResolveTimeout() {
        resolveFrom(null)
    }

    /**
     * `LATE_SETTINGS_RESOLVED(loadState)` (T6b). Moves `LOCKED(SETTINGS_UNREADABLE)` to `DISABLED` only when:
     * the fallback was the controller's own `RESOLVE_TIMEOUT` (never a `CORRUPTED`, `IO_ERROR` or
     * `INVALID_VALUE` read), the state is exactly that `LOCKED` (so not while `AUTHENTICATING(UNLOCK)`,
     * AMD-22), and the late read is a readable state with the lock off. Anything else is dropped.
     * Only [resolve] calls it.
     */
    private fun onLateSettingsResolved(loadState: AppLockSettingsLoadState) {
        transition {
            if (!resolvedByTimeout) return@transition
            if (current != AppLockState.Locked(LockCause.SETTINGS_UNREADABLE)) return@transition
            val readableAndOff = when (loadState) {
                is AppLockSettingsLoadState.Loaded -> !loadState.settings.appLockEnabled
                is AppLockSettingsLoadState.FirstRunDefault -> !loadState.settings.appLockEnabled
                is AppLockSettingsLoadState.Unreadable -> false
            }
            if (!readableAndOff) return@transition
            this.loadState = loadState
            resolvedByTimeout = false
            lastBackgroundedAtMillis = null
            setState(AppLockState.Disabled)
            // TODO(S17): apply the held link (T6b side effect "apply held link" is shared with T2).
        }
    }

    // --- Background and foreground (T13 to T16, spec 3.4) ---------------------------------------

    /**
     * `APP_BACKGROUNDED` (T13, T32): in `UNLOCKED` and `INERT` the first background is recorded and kept
     * until a foreground evaluation consumes it. During `AUTHENTICATING(ENABLE | CONFIRM_CHANGE)` the first
     * background of the attempt is recorded as that attempt's `promptBackgroundAt` (T32); no lock evaluation
     * happens while the prompt is in flight, it happens at the outcome (T33, T34). Other states record
     * nothing. A background during `AUTHENTICATING(UNLOCK)` records nothing: the UNLOCK success clears the
     * pending background anyway (AE-42) and every non-success outcome stays `LOCKED`.
     *
     * The foreground flag (P6) is cleared first, before any early return.
     */
    fun onAppBackgrounded() {
        transition {
            appInForeground = false
            val cur = current
            if (cur is AppLockState.Authenticating) {
                val attempt = inFlight
                if (cur.purpose.isOpenOrigin && attempt != null && attempt.attemptId == cur.attemptId &&
                    attempt.promptBackgroundAt == null
                ) {
                    attempt.promptBackgroundAt = clock.elapsedRealtimeMillis() // later backgrounds do not move it
                }
                return@transition
            }
            if (cur !is AppLockState.Unlocked && cur !is AppLockState.Inert) return@transition
            if (lastBackgroundedAtMillis == null) {
                lastBackgroundedAtMillis = clock.elapsedRealtimeMillis()
            }
        }
    }

    /**
     * `APP_FOREGROUNDED` (T14, T15, T16, T32). Consumes the pending background. During an open-origin prompt
     * nothing is evaluated (T32): the foreground only sets the flag that lets T34 evaluate at the outcome. It
     * also wakes a launch that is waiting for the adapter's previous terminal callback
     * (AWAITING_PRIOR_TERMINAL, the adapter's ON_START).
     */
    fun onAppForegrounded() {
        synchronized(stateLock) { appInForeground = true } // P6: before the early return for AUTHENTICATING
        nudgeAwaitingLaunch()
        evaluateForeground(ForegroundTrigger.APP_FOREGROUNDED)
    }

    /**
     * `LOCK_QUERY` (spec 3.4, INV-31): the same pending-timeout evaluation as a foreground (T14, T15,
     * but not T16), so link handling cannot apply a link when the lock should engage whichever of
     * `onNewIntent` and `onStart` runs first. Returns the state after the evaluation.
     */
    fun onLockQuery(): AppLockState = evaluateForeground(ForegroundTrigger.LOCK_QUERY)

    // --- Unlock (T7, T7b, T8 to T12, T35) -------------------------------------------------------

    /**
     * `UNLOCK_TAPPED` (T7, T7b), `DEVICE_AUTH` only. From `LOCKED`:
     * - capability genuinely not `AVAILABLE` on re-check: `INERT(reason)` and no prompt (T7b);
     * - capability check failed ([DeviceAuthCapability.CHECK_FAILED]): transient (R3), so this is NOT inert
     *   and not a refusal. A bounded `LOCK_UNAVAILABLE_CAPABILITY(UNKNOWN)` is recorded once per process and
     *   the tap continues as below (owner-approvals item 32);
     * - otherwise a new, strictly increasing attempt id and `AUTHENTICATING(id, UNLOCK)` (T7), after which
     *   the prompt is launched outside the monitor (P1).
     *
     * From `AUTHENTICATING` it reports [UnlockTapResult.AlreadyInFlight] and changes nothing; from any
     * other state it reports [UnlockTapResult.NotLocked] (no prompt from `INERT`, `RESOLVING` or an open
     * state, spec 3.3).
     */
    fun onUnlockTapped(): UnlockTapResult {
        val before = current
        if (before is AppLockState.Authenticating) return UnlockTapResult.AlreadyInFlight
        if (before !is AppLockState.Locked) return UnlockTapResult.NotLocked
        val capability = capabilityProvider.current()
        val inertReason = capability.toInertReason()
        var diagnostic: PendingDiagnostic? = null
        var opened: InFlightAttempt? = null
        val result: UnlockTapResult = transition {
            val cur = current
            if (cur is AppLockState.Authenticating) return@transition UnlockTapResult.AlreadyInFlight
            if (cur !is AppLockState.Locked) return@transition UnlockTapResult.NotLocked
            // TODO(S25): PIN mode ([2]) has no capability re-check; effective mode is DEVICE_AUTH only here.
            if (inertReason != null) {
                diagnostic = enterInert(inertReason)
                return@transition UnlockTapResult.BecameInert(inertReason)
            }
            if (capability == DeviceAuthCapability.CHECK_FAILED) {
                // R3 refined (item 32): launch the prompt anyway; only a matching Succeeded can open the app.
                diagnostic = claimCapabilityCause(DeviceAuthCapability.UNKNOWN)
            }
            val attemptId = ++lastIssuedAttemptId
            val attempt = InFlightAttempt(attemptId, AuthPurpose.UNLOCK, cur.cause, AuthPromptCopy.UNLOCK, null)
            inFlight = attempt // open BEFORE any authenticate call (S15 binding)
            setState(AppLockState.Authenticating(attemptId, AuthPurpose.UNLOCK))
            opened = attempt
            UnlockTapResult.Started(attemptId)
        }
        record(diagnostic)
        opened?.let { startAttemptJobs(it) }
        return result
    }

    /**
     * `PROMPT_HOST_LOST(attemptId)` (T35, P4): the Activity hosting the prompt was destroyed while not
     * changing configurations and no outcome has arrived. Id-checked: an unknown or stale id changes nothing
     * (T12). The attempt closes first (back to the origin, `LOCK_AUTH_CANCELLED`), and only then is the
     * adapter asked to cancel, so its cancel echo finds the attempt closed and is ignored. A configuration
     * change such as rotation is not host loss and must not call this (spec 3.5).
     */
    fun onPromptHostLost(attemptId: Long) {
        val attempt = synchronized(stateLock) { inFlight?.takeIf { it.attemptId == attemptId } } ?: return
        closeAttempt(attempt, Closing.Abandoned)
    }

    // --- Open-origin attempts (ENABLE, CONFIRM_CHANGE; T17/T18 prompt part, T32 to T34) ------------------

    /**
     * Starts an ENABLE or CONFIRM_CHANGE attempt (the S18 settings operations bind this; S16 tests drive it
     * with a no-op change). [prompt] fixes the purpose and the constant prompt text together.
     *
     * Legal only from the purpose's origin (spec 2.4, 3.3): ENABLE from exactly `DISABLED`, CONFIRM_CHANGE
     * from exactly `UNLOCKED`. Anything else is refused with no state change and no launch: from
     * `AUTHENTICATING` of any purpose it is [OpenOriginStartResult.AlreadyInFlight] (at most one prompt across
     * all purposes, INV-34, INV-54), from `RESOLVING`, `LOCKED` or `INERT` (and the wrong open state)
     * [OpenOriginStartResult.NotInOrigin]. The request-time refusal for an unavailable capability or API
     * (T26) is the caller's check and happens before this call; the prompt's own `NotAvailable` outcome is
     * still handled here.
     *
     * On a start the attempt is opened and `AUTHENTICATING(id, purpose)` is published before the prompt is
     * launched, exactly as for UNLOCK (P1). A matching success returns the state to the origin and calls
     * [onSuccess] once; the controller applies nothing (T33 is S18). Every other end returns to the origin
     * with nothing written (T34).
     */
    fun startOpenOriginAttempt(prompt: OpenOriginPrompt, onSuccess: OpenOriginSuccessHandler): OpenOriginStartResult {
        val purpose = prompt.purpose
        var opened: InFlightAttempt? = null
        val result: OpenOriginStartResult = transition {
            val cur = current
            if (cur is AppLockState.Authenticating || inFlight != null) return@transition OpenOriginStartResult.AlreadyInFlight
            val inOrigin = when (purpose.origin) {
                AppLockOrigin.LOCKED -> cur is AppLockState.Locked
                AppLockOrigin.DISABLED -> cur == AppLockState.Disabled
                AppLockOrigin.UNLOCKED -> cur == AppLockState.Unlocked
            }
            // The Unlock tap is the only way to start AUTHENTICATING(UNLOCK); this API never does.
            if (!purpose.isOpenOrigin || !inOrigin) return@transition OpenOriginStartResult.NotInOrigin
            val attemptId = ++lastIssuedAttemptId
            val attempt = InFlightAttempt(attemptId, purpose, null, prompt.copy, onSuccess)
            inFlight = attempt // open BEFORE any authenticate call (S15 binding)
            setState(AppLockState.Authenticating(attemptId, purpose))
            opened = attempt
            OpenOriginStartResult.Started(attemptId)
        }
        opened?.let { startAttemptJobs(it) }
        return result
    }

    // TODO(S18): SETTINGS_REREAD (T28, T29), ENABLE / DISABLE / WEAKENING requests (T17 to T19, T26, T27),
    //  and LockNotice derivation (INV-51).
    // TODO(S17): DEEP_LINK_RECEIVED (T20, T21), the held link and its TTL.

    // --- The attempt: launch, outcome, close ------------------------------------------------------

    /**
     * Starts the orchestrator (the launch and its retries) and the watchdog for the freshly opened
     * [attempt]. Both run on the injected scope and are created lazily so they can be recorded on the
     * attempt under the monitor first: an attempt that already closed cancels them instead of starting them.
     */
    private fun startAttemptJobs(attempt: InFlightAttempt) {
        val orchestrator = scope.launch(start = CoroutineStart.LAZY) { launchPrompt(attempt) }
        val watchdog = scope.launch(start = CoroutineStart.LAZY) {
            delay(PROMPT_WATCHDOG_MILLIS)
            // Never waits for an outcome. If the attempt is still the open one it is unanswered: close it
            // as T35/T9 and dismiss whatever the adapter still holds.
            closeAttempt(attempt, Closing.Abandoned)
        }
        val stillOpen = synchronized(stateLock) {
            if (inFlight === attempt) {
                attempt.orchestrator = orchestrator
                attempt.watchdog = watchdog
                true
            } else {
                false
            }
        }
        if (stillOpen) {
            orchestrator.start()
            watchdog.start()
        } else {
            orchestrator.cancel()
            watchdog.cancel()
        }
    }

    /**
     * Calls [DeviceAuthenticator.authenticate] for [attempt] and maps the bounded [AuthLaunch] (P2).
     * LAUNCHED: done, the callback or the watchdog closes the attempt (the watchdog covers only a LAUNCHED
     * prompt that never answers). AWAITING_PRIOR_TERMINAL: the attempt stays open and the launch is retried
     * for up to [AWAITING_PRIOR_TERMINAL_BOUND_MILLIS] or until the next foreground; when the budget is
     * exhausted nothing was launched, so the attempt closes as not launched (F2). Any other value: nothing
     * was launched, roll back to the origin with no `LOCK_AUTH_*` code.
     *
     * A `CancellationException` that ends the launch (a foreign cancellation, for example a cancelled scope)
     * also closes the attempt, with the non-suspending [closeAttempt], and is then rethrown unchanged:
     * cancellation is never swallowed. If `authenticate` had already answered LAUNCHED (the cancellation raced
     * with the return of the launch) a prompt may be showing, so the attempt closes as [Closing.Abandoned] and
     * the adapter is asked to cancel; otherwise nothing was launched and it closes as [Closing.NotLaunched].
     * When the attempt was already closed (its own jobs were cancelled by the close) the close is a no-op.
     */
    private suspend fun launchPrompt(attempt: InFlightAttempt) {
        try {
            launchWithRetries(attempt)
        } catch (e: CancellationException) {
            // ISSUE-3: the original CancellationException is what propagates. A throwable from the close itself
            // (a failing capability read, rethrown by closeAttempt after the attempt is closed) is attached to it.
            try {
                closeAttempt(attempt, if (attempt.launched) Closing.Abandoned else Closing.NotLaunched)
            } catch (closeFailure: Throwable) {
                if (closeFailure !== e) e.addSuppressed(closeFailure)
            }
            throw e
        }
    }

    private suspend fun launchWithRetries(attempt: InFlightAttempt) {
        var waitedMillis = 0L
        while (true) {
            if (!isOpen(attempt)) return
            when (callAuthenticate(attempt)) {
                AuthLaunch.LAUNCHED -> return
                AuthLaunch.AWAITING_PRIOR_TERMINAL -> {
                    if (waitedMillis >= AWAITING_PRIOR_TERMINAL_BOUND_MILLIS) {
                        // Budget exhausted with nothing launched: close now, do not leave it to the watchdog.
                        closeAttempt(attempt, Closing.NotLaunched)
                        return
                    }
                    attempt.awaitingPriorTerminal = true
                    withTimeoutOrNull(AWAITING_PRIOR_TERMINAL_STEP_MILLIS) { attempt.nudge.receiveCatching() }
                    attempt.awaitingPriorTerminal = false
                    waitedMillis += AWAITING_PRIOR_TERMINAL_STEP_MILLIS
                }
                AuthLaunch.ALREADY_IN_FLIGHT,
                AuthLaunch.HOST_UNAVAILABLE,
                AuthLaunch.PROMPT_UNAVAILABLE -> {
                    closeAttempt(attempt, Closing.NotLaunched)
                    return
                }
            }
        }
    }

    /**
     * The one `authenticate` call, on the main dispatcher (P5), never under [stateLock]. The adapter may
     * deliver an outcome synchronously inside it; the attempt is already open, so that is safe. A throw
     * (other than cancellation) counts as PROMPT_UNAVAILABLE.
     */
    private suspend fun callAuthenticate(attempt: InFlightAttempt): AuthLaunch =
        withContext(mainDispatcher) {
            if (!isOpen(attempt)) return@withContext AuthLaunch.HOST_UNAVAILABLE // closed while queued: no launch
            try {
                authenticator.authenticate(attempt.attemptId, attempt.copy, attempt).also {
                    if (it == AuthLaunch.LAUNCHED) attempt.launched = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("App-lock prompt launch failed: class=%s", DiagnosticThrowableSummary.className(e))
                AuthLaunch.PROMPT_UNAVAILABLE
            }
        }

    /** Dismisses the adapter's prompt for [attemptId] on the main dispatcher (P5), best effort. */
    private fun postCancel(attemptId: Long) {
        scope.launch(mainDispatcher) {
            try {
                authenticator.cancel(attemptId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("App-lock prompt cancel failed: class=%s", DiagnosticThrowableSummary.className(e))
            }
        }
    }

    private fun nudgeAwaitingLaunch() {
        val waiting = synchronized(stateLock) { inFlight?.takeIf { it.awaitingPriorTerminal } } ?: return
        waiting.nudge.trySend(Unit)
    }

    private fun isOpen(attempt: InFlightAttempt): Boolean = synchronized(stateLock) { inFlight === attempt }

    /**
     * Routes one outcome of [attempt] (P1). Bound by identity and by id: an outcome for a closed attempt, an
     * older attempt or a different id is stale (T12) and changes nothing, even if it is a success.
     */
    private fun routeOutcome(attempt: InFlightAttempt, attemptId: Long, outcome: DeviceAuthOutcome) {
        if (attemptId != attempt.attemptId || !isOpen(attempt)) return
        when (outcome) {
            DeviceAuthOutcome.Succeeded -> {
                // Purpose-bound (INV-54, spec 3.3 cross-purpose rule): an UNLOCK success only unlocks, an
                // ENABLE or CONFIRM_CHANGE success only reaches its own handler (T33) and never unlocks.
                val closing: Closing = if (attempt.purpose == AuthPurpose.UNLOCK) Closing.Success else Closing.OpenOriginSuccess
                closeAttempt(attempt, closing)
            }
            DeviceAuthOutcome.Cancelled -> closeAttempt(attempt, Closing.Cancelled)
            is DeviceAuthOutcome.LockedOut -> closeAttempt(attempt, Closing.LockedOut)
            DeviceAuthOutcome.NotAvailable -> routeNotAvailable(attempt)
            is DeviceAuthOutcome.Failed -> closeAttempt(attempt, Closing.Error(outcome.bucket))
        }
    }

    /**
     * T11: the prompt reports no credential or hardware. The capability is re-checked (outside the monitor).
     * - UNLOCK: only a genuine non-available capability makes the lock `INERT`.
     * - ENABLE: `HW_UNAVAILABLE` and `UNKNOWN` are a T26-style refusal. The attempt returns to `DISABLED` and
     *   records `LOCK_UNAVAILABLE_CAPABILITY` with the re-check's bounded cause, once per process per
     *   (code, cause). A failed check ([DeviceAuthCapability.CHECK_FAILED]) refuses too, as cause `UNKNOWN`:
     *   enabling never proceeds on an unknown capability (fail closed). `NO_CREDENTIAL` and `UNSUPPORTED_API`
     *   are a bounded error (`OTHER`) that also stays `DISABLED` with nothing written (`TODO(S18)` below).
     * - CONFIRM_CHANGE: T34 has no INERT row. The attempt returns to `UNLOCKED` as a bounded error, and T16
     *   applies at the next foreground.
     * - Otherwise (the re-check says AVAILABLE, or the UNLOCK check failed, R3: transient) the attempt returns to
     *   its origin as a bounded error and the next tap or request tries again.
     */
    private fun routeNotAvailable(attempt: InFlightAttempt) {
        if (!isOpen(attempt)) return
        // Finding 4: a failing re-check must never leave the attempt open. Whatever it throws (CancellationException
        // included) the attempt closes as a bounded error back on its origin and the throwable is rethrown unchanged.
        val capability = try {
            capabilityProvider.current()
        } catch (e: Throwable) {
            closeWith(attempt, Closing.Error(DeviceAuthErrorBucket.OTHER), capability = null)
            throw e
        }
        val reason = capability.toInertReason()
        val closing: Closing = when {
            attempt.purpose == AuthPurpose.UNLOCK && reason != null -> Closing.BecameInert(reason)
            attempt.purpose == AuthPurpose.ENABLE -> when (capability) {
                DeviceAuthCapability.HW_UNAVAILABLE, DeviceAuthCapability.UNKNOWN -> Closing.EnableRefused(capability)
                DeviceAuthCapability.CHECK_FAILED -> Closing.EnableRefused(DeviceAuthCapability.UNKNOWN)
                // TODO(S18): NO_CREDENTIAL and UNSUPPORTED_API get their own bounded codes
                //  (LOCK_UNAVAILABLE_NOT_SECURE, LOCK_UNAVAILABLE_API). Until S18 adds them they are a bounded
                //  error (OTHER) and never LOCK_UNAVAILABLE_CAPABILITY, which is for HW_UNAVAILABLE and UNKNOWN only.
                DeviceAuthCapability.NO_CREDENTIAL,
                DeviceAuthCapability.UNSUPPORTED_API,
                DeviceAuthCapability.AVAILABLE -> Closing.Error(DeviceAuthErrorBucket.OTHER)
            }
            else -> Closing.Error(DeviceAuthErrorBucket.OTHER)
        }
        closeAttempt(attempt, closing)
    }

    /**
     * Closes [attempt] if it is still the open one (identity check) and moves to the matching state. Returns
     * false, changing nothing, when it is not. After the monitor is released: the attempt's watchdog and
     * orchestrator are cancelled, the claimed diagnostics are recorded, for [Closing.Abandoned] the adapter
     * is asked to cancel (its synchronous echo then finds the attempt closed), and for
     * [Closing.OpenOriginSuccess] the success is handed to the attempt's handler.
     *
     * Every non-success close of a CONFIRM_CHANGE attempt also runs the T34 timer hand-off in the same
     * critical section, so the state is never published as `UNLOCKED` first when the hand-off locks it.
     */
    private fun closeAttempt(attempt: InFlightAttempt, closing: Closing): Boolean {
        // T34 may evaluate the timer at once (T14/T15/T16), which needs the capability; a binder call must not
        // run under the monitor, so it is read here, before it, only for the attempt that can use it.
        // ISSUE-3: an attempt that is no longer the open one never reads the capability (a binder call for nothing).
        if (!isOpen(attempt)) return false
        // F4: a failing read must never leave the attempt open. Whatever it throws (CancellationException
        // included) the attempt is closed with CHECK_FAILED (ISSUE-2: transient, never INERT, but T34 still
        // evaluates fail-closed in the foreground) and the throwable is rethrown unchanged AFTER the close, so
        // nothing is swallowed.
        if (attempt.purpose != AuthPurpose.CONFIRM_CHANGE || closing == Closing.OpenOriginSuccess) {
            return closeWith(attempt, closing, capability = null)
        }
        val capability = try {
            capabilityProvider.current()
        } catch (e: Throwable) {
            closeWith(attempt, closing, DeviceAuthCapability.CHECK_FAILED)
            throw e
        }
        return closeWith(attempt, closing, capability)
    }

    /**
     * The close of [closeAttempt] with the capability already read ([capability] is null when none was read, and
     * [DeviceAuthCapability.CHECK_FAILED] when the read failed). Never throws for a read failure: that is
     * [closeAttempt]'s to rethrow.
     */
    private fun closeWith(attempt: InFlightAttempt, closing: Closing, capability: DeviceAuthCapability?): Boolean {
        var diagnostic: PendingDiagnostic? = null
        var timerDiagnostic: PendingDiagnostic? = null
        var staleClose = false
        val closed = transition {
            if (inFlight !== attempt) return@transition false
            // F4 / ISSUE-1: EVERY close (success or not) may only close the exact AUTHENTICATING(thisId,
            // thisPurpose) state it belongs to; an UNLOCK success only an UNLOCK attempt, and an open-origin
            // success only an ENABLE or CONFIRM_CHANGE one (it unlocks nothing). Every writer keeps the open
            // attempt and that state in step, so no public sequence reaches this guard; it is the last
            // fail-closed check. A mismatch is stale: the attempt is dropped, no handler is called, no
            // diagnostic code is claimed for the outcome, and nothing unlocks. If the state is still an
            // AUTHENTICATING one it would be stranded on a prompt state with no open attempt and no way out:
            // - purpose UNLOCK, or a purpose other than this attempt's: LOCKED(COLD_START) (fail-closed; the
            //   origin of an open-origin attempt would turn an AUTHENTICATING(UNLOCK) state into an open one
            //   without an unlock);
            // - the same purpose (a different id): back to this attempt's origin.
            // Any other state is left as it is.
            val purposeMismatch = when (closing) {
                Closing.Success -> attempt.purpose != AuthPurpose.UNLOCK
                Closing.OpenOriginSuccess -> !attempt.purpose.isOpenOrigin
                else -> false
            }
            if (purposeMismatch || current != AppLockState.Authenticating(attempt.attemptId, attempt.purpose)) {
                inFlight = null
                val stranded = current
                if (stranded is AppLockState.Authenticating) {
                    if (stranded.purpose == AuthPurpose.UNLOCK || stranded.purpose != attempt.purpose) {
                        setState(AppLockState.Locked(LockCause.COLD_START))
                    } else {
                        setState(originState(attempt))
                        // F5 / ISSUE-2: a CONFIRM_CHANGE return still hands off the prompt background (T34). A
                        // capability that was not read counts as CHECK_FAILED (transient, never INERT), so a
                        // foreground past the timeout still locks.
                        timerDiagnostic = handOffPromptBackground(attempt, capability ?: DeviceAuthCapability.CHECK_FAILED)
                    }
                }
                staleClose = true
                return@transition false
            }
            inFlight = null
            diagnostic = when (closing) {
                Closing.Success -> {
                    lastBackgroundedAtMillis = null // an auth success that ends in an open state clears it (AE-42)
                    setState(AppLockState.Unlocked)
                    // TODO(S17): apply the held link once after an unlock (T8 side effect).
                    claim(PrivacyDiagnosticCode.LOCK_UNLOCKED)
                }
                Closing.OpenOriginSuccess -> {
                    // T33 hand-off, nothing applied here. Back to the origin; the attempt's promptBackgroundAt
                    // is discarded with it and the pending background is cleared (spec 3.4: an auth success that
                    // ends in an open state clears it). Increment 1 has no prompt that is not unlock-equivalent
                    // (INV-54), so the "otherwise hand the background to the timer" clause has no case yet.
                    lastBackgroundedAtMillis = null
                    setState(originState(attempt))
                    null
                }
                is Closing.EnableRefused -> {
                    setState(originState(attempt))
                    claimCapabilityCause(closing.cause)
                }
                Closing.Cancelled, Closing.Abandoned -> {
                    setState(originState(attempt))
                    claim(PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED)
                }
                Closing.LockedOut -> {
                    setState(originState(attempt))
                    claim(PrivacyDiagnosticCode.LOCK_AUTH_LOCKOUT)
                }
                is Closing.Error -> {
                    setState(originState(attempt))
                    claimAuthError(closing.bucket)
                }
                is Closing.BecameInert -> enterInert(closing.reason)
                Closing.NotLaunched -> {
                    setState(originState(attempt))
                    null
                }
            }
            if (closing != Closing.Success && closing != Closing.OpenOriginSuccess) {
                timerDiagnostic = handOffPromptBackground(attempt, capability) // T34
            }
            true
        }
        if (!closed) {
            if (staleClose) {
                attempt.cancelJobs()
                record(timerDiagnostic) // only a T34 lock that really happened; the stale outcome claims no code
            }
            return false
        }
        attempt.cancelJobs()
        record(diagnostic)
        record(timerDiagnostic)
        if (closing == Closing.Abandoned) postCancel(attempt.attemptId)
        if (closing == Closing.OpenOriginSuccess) deliverOpenOriginSuccess(attempt)
        return true
    }

    /**
     * The state every non-success outcome of [attempt] returns to (spec 2.4, T9/T10/T34/T35): `LOCKED` with the
     * cause it came from for UNLOCK, `DISABLED` for ENABLE, `UNLOCKED` for CONFIRM_CHANGE. The T34 timer
     * hand-off for the open origins is [handOffPromptBackground].
     */
    private fun originState(attempt: InFlightAttempt): AppLockState = when (attempt.purpose.origin) {
        AppLockOrigin.LOCKED -> AppLockState.Locked(attempt.originLockCause ?: LockCause.COLD_START)
        AppLockOrigin.DISABLED -> AppLockState.Disabled
        AppLockOrigin.UNLOCKED -> AppLockState.Unlocked
    }

    /**
     * T34 timer hand-off for a CONFIRM_CHANGE attempt that ended without success (cancel, error, lockout, host
     * loss, the watchdog or a launch that never happened). Must be called with [stateLock] held, after the state
     * returned to `UNLOCKED`.
     *
     * - No background was recorded during the prompt and none was pending: nothing to hand over, and since no
     *   time accrues while a prompt is shown in the foreground, nothing locks (AE-116, first half).
     * - ENABLE (origin `DISABLED`): `promptBackgroundAt` is dropped with the attempt.
     * - CONFIRM_CHANGE: `lastBackgroundedAt := promptBackgroundAt`. If a background was already pending, the
     *   earlier of the two is kept, so the hand-off can only lock sooner, never later (fail-closed reading; the
     *   spec assigns the value, and the two cannot differ in practice because the prompt starts in the
     *   foreground). If the app is in the foreground and the capability could be read, T14/T15/T16 are
     *   evaluated at once, in this same critical section so `UNLOCKED` is not published first when it locks.
     *   Otherwise the pending background waits for the next `APP_FOREGROUNDED` or `LOCK_QUERY`.
     */
    private fun handOffPromptBackground(attempt: InFlightAttempt, capability: DeviceAuthCapability?): PendingDiagnostic? {
        if (attempt.purpose != AuthPurpose.CONFIRM_CHANGE) return null
        // F1: both sources count. A background that was already pending when the prompt started (the attempt
        // may start from UNLOCKED while the app is in the background) is handed over too, even when the prompt
        // itself recorded no background. The earlier of the two is kept (can only lock sooner, never later).
        val merged = listOfNotNull(attempt.promptBackgroundAt, lastBackgroundedAtMillis).minOrNull() ?: return null
        lastBackgroundedAtMillis = merged
        if (!appInForeground || capability == null) return null
        return evaluateForegroundLocked(ForegroundTrigger.APP_FOREGROUNDED, capability)
    }

    /**
     * Hands a matching ENABLE or CONFIRM_CHANGE success to the attempt's handler (T33, S18). The attempt is
     * closed and the state is already back on the origin; nothing is applied here. A failing handler is logged
     * by class name only and never affects the controller; `CancellationException` is not swallowed.
     */
    private fun deliverOpenOriginSuccess(attempt: InFlightAttempt) {
        val handler = attempt.successHandler ?: return
        try {
            handler.onSucceeded(attempt.attemptId, attempt.purpose)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("App-lock success hand-off failed: class=%s", DiagnosticThrowableSummary.className(e))
        }
    }

    // --- Internals ------------------------------------------------------------------------------

    /**
     * Runs [block] under [stateLock], then publishes the resulting state AFTER the lock is released (R2).
     * The state and its version are read inside the same critical section as the block, so the published
     * value is exactly what this block produced (or a newer one), never a value from a different moment.
     */
    private inline fun <R> transition(block: () -> R): R {
        val (result, stamp, next) = synchronized(stateLock) { Triple(block(), version, current) }
        publish(stamp, next)
        return result
    }

    /** Must be called with [stateLock] held. Every state change goes through here. */
    private fun setState(next: AppLockState) {
        current = next
        version++
    }

    /** Publishes [next] unless a newer version is already out (a stale value is never published). */
    private fun publish(stamp: Long, next: AppLockState) {
        if (stamp <= publishedVersion) return
        synchronized(publishLock) {
            if (stamp <= publishedVersion) return
            publishedVersion = stamp
            mutableState.value = next
        }
    }

    private fun resolveFrom(read: AppLockSettingsLoadState?) {
        if (current !is AppLockState.Resolving) return
        val capability = capabilityProvider.current()
        val diagnostic = transition {
            if (current !is AppLockState.Resolving) return@transition null
            applyResolution(read, capability)
        }
        record(diagnostic)
    }

    /** Applies T2 to T6 under the monitor. A null [read] is the controller's `RESOLVE_TIMEOUT`. */
    private fun applyResolution(read: AppLockSettingsLoadState?, capability: DeviceAuthCapability): PendingDiagnostic? {
        loadState = read
        resolvedByTimeout = read == null
        val settings = EffectivePrivacyValues.effectiveSettings(read)
        val unreadable = read == null || read is AppLockSettingsLoadState.Unreadable
        val inertReason = capability.toInertReason()
        return when {
            // T5 / T6. "Verifier present" ([2]) does not exist yet, so capability decides alone.
            unreadable ->
                if (inertReason == null) {
                    enterLocked(LockCause.SETTINGS_UNREADABLE)
                } else {
                    enterInert(InertReason.SETTINGS_UNREADABLE_NO_AUTH)
                }
            // T2
            !settings.appLockEnabled -> {
                setState(AppLockState.Disabled)
                // TODO(S17): apply the held link.
                null
            }
            // T3
            inertReason == null -> enterLocked(LockCause.COLD_START)
            // T4
            else -> enterInert(inertReason)
        }
    }

    private fun evaluateForeground(trigger: ForegroundTrigger): AppLockState {
        val before = current
        if (before !is AppLockState.Unlocked && before !is AppLockState.Inert) return before
        val capability = capabilityProvider.current()
        val diagnostic = transition { evaluateForegroundLocked(trigger, capability) }
        record(diagnostic)
        return current
    }

    /** T14, T15, T16 under the monitor, for `UNLOCKED` and `INERT` only. */
    private fun evaluateForegroundLocked(trigger: ForegroundTrigger, capability: DeviceAuthCapability): PendingDiagnostic? {
        val cur = current
        if (cur !is AppLockState.Unlocked && cur !is AppLockState.Inert) return null
        val settings = EffectivePrivacyValues.effectiveSettings(loadState)
        val inertReason = capability.toInertReason()
        val lockActive = settings.appLockEnabled && inertReason == null

        if (lockActive) {
            // No pending background means this is not a return to the app (a query while in use, a
            // rotation): nothing to measure, nothing consumed. It never measures from an old background.
            val pending = lastBackgroundedAtMillis ?: return null
            lastBackgroundedAtMillis = null // consumed (spec 3.4, AMD-22)
            val elapsed = clock.elapsedRealtimeMillis() - pending
            val timedOut = elapsed < 0L || elapsed >= settings.autoLockTimeout.millis
            if (timedOut) {
                // T14. TODO(S17): an in-flight link is held here.
                return enterLocked(LockCause.TIMEOUT)
            }
            // T15: back within the timeout; a shown inert banner clears.
            if (cur is AppLockState.Inert) setState(AppLockState.Unlocked)
            return null
        }

        // The lock cannot protect now (or is off). A foreground consumes the pending background; a
        // LOCK_QUERY does not, so a transient capability loss at query time cannot erase the time away.
        if (trigger == ForegroundTrigger.APP_FOREGROUNDED) {
            lastBackgroundedAtMillis = null
            // T16: enabled, DEVICE_AUTH, capability genuinely not AVAILABLE (CHECK_FAILED never gets here).
            if (cur is AppLockState.Unlocked && settings.appLockEnabled && inertReason != null) {
                return enterInert(inertReason)
            }
        }
        return null
    }

    /** Returns the diagnostic to record for this transition: the code the first time, null after (spec 10). */
    private fun enterLocked(cause: LockCause): PendingDiagnostic? {
        setState(AppLockState.Locked(cause))
        return claim(
            when (cause) {
                LockCause.COLD_START -> PrivacyDiagnosticCode.LOCK_SHOWN_COLD_START
                LockCause.TIMEOUT -> PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT
                LockCause.SETTINGS_UNREADABLE -> PrivacyDiagnosticCode.LOCK_SHOWN_SETTINGS_UNREADABLE
            }
        )
    }

    /** Returns the diagnostic to record for this transition: the code the first time, null after (spec 10). */
    private fun enterInert(reason: InertReason): PendingDiagnostic? {
        setState(AppLockState.Inert(reason))
        return claim(
            when (reason) {
                InertReason.NO_CREDENTIAL -> PrivacyDiagnosticCode.LOCK_INERT_NO_CREDENTIAL
                InertReason.HW_UNAVAILABLE -> PrivacyDiagnosticCode.LOCK_INERT_HW_UNAVAILABLE
                InertReason.UNKNOWN -> PrivacyDiagnosticCode.LOCK_INERT_UNKNOWN
                InertReason.API_UNSUPPORTED -> PrivacyDiagnosticCode.LOCK_INERT_API_UNSUPPORTED
                InertReason.SETTINGS_UNREADABLE_NO_AUTH -> PrivacyDiagnosticCode.LOCK_INERT_SETTINGS_UNREADABLE
            }
        )
    }

    /**
     * Once per process per cause (spec 10): returns [code] when it is claimed for the first time, null when
     * an earlier transition already claimed it. Must be called with [stateLock] held.
     */
    private fun claim(code: PrivacyDiagnosticCode): PendingDiagnostic? =
        if (claimedDiagnosticCodes.add(code)) PendingDiagnostic(code, null) else null

    /**
     * `LOCK_AUTH_ERROR` is claimed once per process per bucket (HW_UNAVAILABLE / TIMEOUT / SYSTEM_CANCELED /
     * OTHER), because the bucket is the cause. Must be called with [stateLock] held.
     */
    private fun claimAuthError(bucket: DeviceAuthErrorBucket): PendingDiagnostic? =
        if (claimedAuthErrorBuckets.add(bucket)) PendingDiagnostic(PrivacyDiagnosticCode.LOCK_AUTH_ERROR, bucket) else null

    /**
     * `LOCK_UNAVAILABLE_CAPABILITY` with the bounded capability [cause] (an enum name only). Claimed once per
     * process per (code, cause) pair, like [claimedAuthErrorBuckets]: a claim of one cause must not silence
     * the same code for a different cause, and a claim of one code never silences another code. Used by the
     * Unlock tap with a failed check (R3, item 32; cause `UNKNOWN`) and by an ENABLE prompt whose
     * `NotAvailable` re-check refuses (T26-style); S18's T26 claims through the same set, per (code, cause).
     * Must be called with [stateLock] held.
     */
    private fun claimCapabilityCause(cause: DeviceAuthCapability): PendingDiagnostic? =
        if (claimedCodeCauses.add(PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY to cause)) {
            PendingDiagnostic(PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY, cause)
        } else {
            null
        }

    /**
     * Maps the capability to the inert reason, or null when the lock may stay active: the device can
     * authenticate, or the check itself failed ([DeviceAuthCapability.CHECK_FAILED], R3: transient, so it
     * is treated as "lock still applies" and the prompt-time check decides).
     */
    private fun DeviceAuthCapability.toInertReason(): InertReason? = when (this) {
        DeviceAuthCapability.AVAILABLE -> null
        DeviceAuthCapability.CHECK_FAILED -> null
        DeviceAuthCapability.UNSUPPORTED_API -> InertReason.API_UNSUPPORTED
        DeviceAuthCapability.NO_CREDENTIAL -> InertReason.NO_CREDENTIAL
        DeviceAuthCapability.HW_UNAVAILABLE -> InertReason.HW_UNAVAILABLE
        DeviceAuthCapability.UNKNOWN -> InertReason.UNKNOWN
    }

    /** One read of the store. A non-IO failure is treated as an unreadable store, never as defaults (INV-2). */
    private suspend fun readLoadState(): AppLockSettingsLoadState =
        try {
            settingsRepository.getLoadState()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Bounded: only the class name is logged; the text is dropped and the store counts as
            // unreadable (fail closed).
            Timber.w("App-lock settings read failed: class=%s", DiagnosticThrowableSummary.className(e))
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR)
        }

    /**
     * Fire and forget (INV-37): never blocks or delays a transition. [diagnostic] is already claimed under
     * the monitor by the transition, so this only launches (null means a repeat: nothing to emit). The
     * emitter is best effort; `CancellationException` is rethrown and any other failure is logged by class
     * name only. The only metadata ever attached is one bounded enum under the closed `CAUSE` key: the
     * `LOCK_AUTH_ERROR` bucket or the `LOCK_UNAVAILABLE_CAPABILITY` cause.
     *
     * The emitter's 60 s rate window is per code, not per bucket (F5): a second `LOCK_AUTH_ERROR` bucket
     * claimed here within 60 s of the first may be counted by the emitter but not written. This is accepted.
     */
    private fun record(diagnostic: PendingDiagnostic?) {
        if (diagnostic == null) return
        val metadata = diagnostic.cause?.let {
            PrivacyDiagnosticMetadata.builder().put(PrivacyDiagnosticKey.CAUSE, it).build()
        } ?: PrivacyDiagnosticMetadata.EMPTY
        scope.launch {
            try {
                diagnostics.emit(diagnostic.code, metadata)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Lock diagnostic emit failed: class=%s", DiagnosticThrowableSummary.className(e))
            }
        }
    }

    companion object {
        /** T_resolve (tech-decisions TECH-11): the bound on the cold-start settings read. */
        const val RESOLVE_TIMEOUT_MILLIS: Long = 2_000L

        /**
         * The watchdog bound (P3). The spec names no timeout for an unanswered prompt, so this is a controller
         * constant. An attempt that nothing answered within it (the library silently skipped the launch, a
         * recreated host never reports) closes as cancelled and returns to the origin lock, so Unlock can never
         * stay disabled for good (INV-24, INV-54). An exhausted launch-retry budget does not wait for it: that
         * closes as not launched at once (F2).
         *
         * Five minutes: long enough that a person who is genuinely entering a credential is not cut off (the
         * system gives a credential prompt no timeout of its own, and the lock timer accrues nothing while a
         * prompt is shown, spec 3.4), short enough that a prompt that was never shown frees the lock surface.
         * Closing is always fail-closed (the app stays `LOCKED`), so a too-short bound costs convenience and
         * a too-long one costs only a stuck button, never exposure. Real delays, not the lock timer: this uses
         * coroutine time only. G1b measures real-device behaviour and may tune it.
         */
        const val PROMPT_WATCHDOG_MILLIS: Long = 5 * 60_000L

        /**
         * AWAITING_PRIOR_TERMINAL (S15): the adapter refuses a launch until the terminal callback of a
         * client-cancelled prompt arrives, which it waits for at most about 1500 ms or until its host's next
         * ON_START. The controller retries the launch for that long (or until the next foreground), then
         * closes the attempt as not launched (F2).
         */
        const val AWAITING_PRIOR_TERMINAL_BOUND_MILLIS: Long = 1_500L

        /** The pause between launch retries while the adapter answers AWAITING_PRIOR_TERMINAL. */
        const val AWAITING_PRIOR_TERMINAL_STEP_MILLIS: Long = 250L
    }
}
