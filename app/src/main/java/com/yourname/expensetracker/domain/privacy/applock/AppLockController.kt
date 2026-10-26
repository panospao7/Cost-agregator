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
 * The process-level app-lock controller and the ONLY writer of [AppLockState] (INV-24, spec 2.4).
 *
 * S16 covers: resolution from the settings store (T1 to T6, T6b), the inert escapes (T4, T6, T7b, T11,
 * T15, T16), the foreground lock evaluation (T13 to T15) with pending-background consumption (spec 3.4,
 * AMD-22), and the whole UNLOCK attempt: start (T7), launch, success (T8), cancel (T9), error and
 * lockout (T10), no credential (T11), stale outcomes (T12) and host loss (T35). Everything marked
 * `TODO(S16-ii-b)` (ENABLE and CONFIRM_CHANGE attempts, T32 to T34) belongs to part (ii-b); `TODO(S17)`
 * and `TODO(S18)` mark the held link and the settings operations.
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
 *   an unanswered attempt, a lost host and a prompt that never launched all return to the origin lock.
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
        /** T8. */
        data object Success : Closing

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
     * [DeviceAuthCapability.UNKNOWN] of `LOCK_UNAVAILABLE_CAPABILITY`. Null for every other code.
     */
    private class PendingDiagnostic(val code: PrivacyDiagnosticCode, val cause: Enum<*>?)

    /**
     * The attempt that is open. It is also the callback handed to the authenticator, so an outcome is bound
     * to this exact attempt by identity (P1). Part (ii-b) extends it with `promptBackgroundAt`.
     */
    private inner class InFlightAttempt(
        val attemptId: Long,
        val purpose: AuthPurpose,
        /** For [AuthPurpose.UNLOCK]: the cause of the `LOCKED` state it returns to (T9). Null otherwise. */
        val originLockCause: LockCause?,
        val copy: AuthPromptCopy
    ) : DeviceAuthCallback {
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
    private var inFlight: InFlightAttempt? = null
    private var lastIssuedAttemptId = 0L
    private val claimedDiagnosticCodes: MutableSet<PrivacyDiagnosticCode> =
        EnumSet.noneOf(PrivacyDiagnosticCode::class.java)
    private val claimedAuthErrorBuckets: MutableSet<DeviceAuthErrorBucket> =
        EnumSet.noneOf(DeviceAuthErrorBucket::class.java)
    private val claimedCapabilityCauses: MutableSet<DeviceAuthCapability> =
        EnumSet.noneOf(DeviceAuthCapability::class.java)

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
     * `APP_BACKGROUNDED` (T13): in `UNLOCKED` and `INERT` the first background is recorded and kept
     * until a foreground evaluation consumes it. Other states record nothing.
     */
    fun onAppBackgrounded() {
        transition {
            val cur = current
            if (cur !is AppLockState.Unlocked && cur !is AppLockState.Inert) {
                // TODO(S16-ii-b): T32 for AUTHENTICATING(ENABLE | CONFIRM_CHANGE): record promptBackgroundAt.
                //  A background during AUTHENTICATING(UNLOCK) records nothing: the UNLOCK success clears the
                //  pending background anyway (AE-42) and every non-success outcome stays LOCKED.
                return@transition
            }
            if (lastBackgroundedAtMillis == null) {
                lastBackgroundedAtMillis = clock.elapsedRealtimeMillis()
            }
        }
    }

    /**
     * `APP_FOREGROUNDED` (T14, T15, T16). Consumes the pending background. It also wakes a launch that is
     * waiting for the adapter's previous terminal callback (AWAITING_PRIOR_TERMINAL, the adapter's ON_START).
     */
    fun onAppForegrounded() {
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
            // TODO(S16-ii-b): PIN mode ([2]) has no capability re-check; effective mode is DEVICE_AUTH only here.
            if (inertReason != null) {
                diagnostic = enterInert(inertReason)
                return@transition UnlockTapResult.BecameInert(inertReason)
            }
            if (capability == DeviceAuthCapability.CHECK_FAILED) {
                // R3 refined (item 32): launch the prompt anyway; only a matching Succeeded can open the app.
                diagnostic = claimCapabilityCheckFailed()
            }
            val attemptId = ++lastIssuedAttemptId
            val attempt = InFlightAttempt(attemptId, AuthPurpose.UNLOCK, cur.cause, AuthPromptCopy.UNLOCK)
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

    // TODO(S16-ii-b): the ENABLE / CONFIRM_CHANGE start API (S18 binds it), T32 / T33 / T34 and their
    //  promptBackgroundAt handoff, the foreground flag for T34 (P6), and AuthPromptCopy selection beyond UNLOCK.
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
            closeAttempt(attempt, if (attempt.launched) Closing.Abandoned else Closing.NotLaunched)
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
                // Only an UNLOCK success unlocks here (spec 3.3 cross-purpose rule).
                // TODO(S16-ii-b / S18): ENABLE and CONFIRM_CHANGE successes apply their owning row (T33).
                if (attempt.purpose == AuthPurpose.UNLOCK) closeAttempt(attempt, Closing.Success)
            }
            DeviceAuthOutcome.Cancelled -> closeAttempt(attempt, Closing.Cancelled)
            is DeviceAuthOutcome.LockedOut -> closeAttempt(attempt, Closing.LockedOut)
            DeviceAuthOutcome.NotAvailable -> routeNotAvailable(attempt)
            is DeviceAuthOutcome.Failed -> closeAttempt(attempt, Closing.Error(outcome.bucket))
        }
    }

    /**
     * T11: the prompt reports no credential or hardware. The capability is re-checked (outside the monitor);
     * only a genuine non-available capability makes the lock `INERT`. If the re-check says AVAILABLE, or the
     * check itself failed (R3, transient), the attempt returns to the origin lock as a bounded error and the
     * next tap tries again.
     */
    private fun routeNotAvailable(attempt: InFlightAttempt) {
        if (!isOpen(attempt)) return
        val reason = capabilityProvider.current().toInertReason()
        if (attempt.purpose == AuthPurpose.UNLOCK && reason != null) {
            closeAttempt(attempt, Closing.BecameInert(reason))
        } else {
            closeAttempt(attempt, Closing.Error(DeviceAuthErrorBucket.OTHER))
        }
    }

    /**
     * Closes [attempt] if it is still the open one (identity check) and moves to the matching state. Returns
     * false, changing nothing, when it is not. After the monitor is released: the attempt's watchdog and
     * orchestrator are cancelled, the claimed diagnostic is recorded, and, for [Closing.Abandoned], the adapter
     * is asked to cancel (its synchronous echo then finds the attempt closed).
     */
    private fun closeAttempt(attempt: InFlightAttempt, closing: Closing): Boolean {
        var diagnostic: PendingDiagnostic? = null
        var staleSuccess = false
        val closed = transition {
            if (inFlight !== attempt) return@transition false
            // F4: only AUTHENTICATING(thisId, UNLOCK) may become UNLOCKED. Every writer keeps the open attempt
            // and that state in step, so no public sequence reaches this guard; it is the last fail-closed
            // check before the one transition that opens the app. A mismatch is stale: the attempt is dropped
            // and nothing unlocks. If the state is still an AUTHENTICATING one (a different id or purpose than
            // this attempt), leaving it would strand the app on a prompt state with no open attempt and no way
            // out, so it returns to the origin lock (fail-closed, never an unlock). Any other state is left as it is.
            if (closing == Closing.Success &&
                current != AppLockState.Authenticating(attempt.attemptId, AuthPurpose.UNLOCK)
            ) {
                inFlight = null
                if (current is AppLockState.Authenticating) setState(originState(attempt))
                staleSuccess = true
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
            true
        }
        if (!closed) {
            if (staleSuccess) attempt.cancelJobs()
            return false
        }
        attempt.cancelJobs()
        record(diagnostic)
        if (closing == Closing.Abandoned) postCancel(attempt.attemptId)
        return true
    }

    /**
     * The state every non-success outcome of [attempt] returns to (spec 2.4, T9/T10/T35). For UNLOCK it is
     * `LOCKED` with the cause it came from.
     * TODO(S16-ii-b): for the open origins the T34 timer handoff (`promptBackgroundAt`) also runs here.
     */
    private fun originState(attempt: InFlightAttempt): AppLockState = when (attempt.purpose.origin) {
        AppLockOrigin.LOCKED -> AppLockState.Locked(attempt.originLockCause ?: LockCause.COLD_START)
        AppLockOrigin.DISABLED -> AppLockState.Disabled
        AppLockOrigin.UNLOCKED -> AppLockState.Unlocked
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
     * A failed capability check at an Unlock tap (R3, item 32): `LOCK_UNAVAILABLE_CAPABILITY` with the bounded
     * cause [DeviceAuthCapability.UNKNOWN]. Claimed once per process per CAUSE (not per code), like
     * [claimedAuthErrorBuckets]: this tap-time claim of `UNKNOWN` must not silence a later S18 T26 emission of
     * the same code with a different cause. Must be called with [stateLock] held.
     */
    private fun claimCapabilityCheckFailed(): PendingDiagnostic? =
        if (claimedCapabilityCauses.add(DeviceAuthCapability.UNKNOWN)) {
            PendingDiagnostic(PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY, DeviceAuthCapability.UNKNOWN)
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
