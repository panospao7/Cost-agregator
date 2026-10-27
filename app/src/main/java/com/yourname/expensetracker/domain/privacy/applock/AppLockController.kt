package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.di.AppLockMainDispatcher
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.diagnostics.DiagnosticThrowableSummary
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkHost
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkRequest
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
 * Bounded result of [AppLockController.beginInertDisable] (S18 ii-e, T19). It carries no text and no platform detail.
 */
sealed interface InertDisableBegin {
    /** The token for [id] is recorded; the caller may write once and must report it with `onInertDisableApplied(id, ...)`. */
    data class Begun(val id: Long) : InertDisableBegin

    /**
     * INV-44, INV-45: the state is `INERT(SETTINGS_UNREADABLE_NO_AUTH)` or the load state is unreadable (or unresolved).
     * Nothing was recorded; a disable never bypasses the review: the review-save is the way for a persistent unreadable
     * store, and a retry or re-read for a temporarily unreadable (IO_ERROR, TIMEOUT) or unresolved view.
     */
    data object ReviewRequired : InertDisableBegin

    /** The state is not `INERT`, or an attempt is open. Nothing was recorded. */
    data object NotInOrigin : InertDisableBegin
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
 * the non-success return to the origin with the timer hand-off (T34). S17 adds the held deep link (T20 to
 * T22, see "The held link" below). S18 part (i-a) adds the enable / disable apply (T17, T18, T26) driven by
 * [AppLockSettingsOperations]; S18 part (i-b1) adds the auto-lock timeout change (a longer timeout confirms through
 * CONFIRM_CHANGE, an equal or shorter one commits without a prompt). S18 part (ii-c) adds the [notice] flow (INV-51,
 * derived by [LockNoticeDerivation]) and the settings re-read (T28, T29, INV-47; [requestSettingsReread] and the
 * foreground re-read). S18 part (ii-d) adds the review-save entries (T27, INV-45: [startReviewSaveAttempt],
 * [onReviewSavedWithoutPrompt]); the controller owns `LOCK_SETTINGS_REVIEW_SAVED` and
 * `LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE)` (D7).
 *
 * ## The held link (S17: T20, T21, T22, INV-30, INV-31, OD-7a)
 *
 * One link is held in memory ([HeldDeepLink], newest wins, never persisted, never logged, dropped with the
 * process). [onLockQuery] with a link runs the LOCK_QUERY evaluation and decides APPLY_NOW or HOLD in the same
 * critical section, so a long background can never let a link apply before the lock engages (INV-31). The link
 * is held while `LOCKED`, `RESOLVING` or `AUTHENTICATING` of ANY purpose, while an attempt is open, and while an
 * open-origin success is still being applied (the T33 gate, until [onOpenOriginApplyCompleted]).
 *
 * The controller has no navigation port, so a held link is PULLED: [releaseHeldLink] (a LOCK_QUERY itself) hands
 * it back at most once, only from `DISABLED`, `UNLOCKED` or `INERT`, with no open attempt and no open T33 gate,
 * only while the app is in the foreground (F1: a backgrounded release is [HeldLinkRelease.None], evaluates
 * nothing and consumes nothing), and only after the pending timeout was evaluated. The close of a prompt never releases by itself: a T34
 * hand-off and its evaluation, or a T33 success AND the apply completion, come first. The link is dropped after
 * `T_held` (5 min, monotonic). The held link never starts a prompt.
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
     * code. For the deep-link codes (S17: `DL_HELD_LOCKED`, `DL_HELD_REPLACED`, `DL_HELD_EXPIRED`) [metadata] is
     * the ready-made bounded host metadata under the closed `HOST` key (the deep-link host enums only, never link
     * content) and [cause] is null; the two are mutually exclusive. `LOCK_SETTING_PERSIST_FAILED` (S18) uses
     * [metadata] too: the closed `FIELD` key with the [AppLockSettingField] enum name only.
     */
    private class PendingDiagnostic(
        val code: PrivacyDiagnosticCode,
        val cause: Enum<*>?,
        /** Ready-made bounded metadata (the deep-link host enums only); mutually exclusive with [cause]. */
        val metadata: PrivacyDiagnosticMetadata?
    ) {
        init {
            require(metadata == null || cause == null)
        }

        constructor(code: PrivacyDiagnosticCode, cause: Enum<*>?) : this(code, cause, null)
    }

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
        val successHandler: OpenOriginSuccessHandler?,
        /** For ENABLE and CONFIRM_CHANGE: the closed prompt selector the attempt was started with. Null for UNLOCK. */
        val prompt: OpenOriginPrompt? = null,
        /** For a CHANGE attempt of a timeout change (S18 i-b1): the requested timeout, copied into the one-shot token. */
        val requestedTimeout: AutoLockTimeout? = null,
        /** For a CHANGE attempt of a weakening review-save (S18 ii-d): the record to write, copied into the one-shot token. */
        val requestedRecord: AppLockPrivacySettings? = null
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

    // S17, guarded by stateLock. Memory only; never persisted, logged or put in a Bundle (T22).
    private var heldLink: HeldDeepLink? = null

    /** Counts UNLOCK successes (T8), so a release can say whether an unlock happened since the link was held. */
    private var unlockCount = 0L

    /**
     * The T33 gate: the id of the open-origin attempt whose success was handed to S18 and whose apply has not
     * completed yet. While it is open, a link is held and never released (INV-54): the close publishing the
     * origin state is not the end of the operation. Bounded by [HeldDeepLink.HELD_LINK_TTL_MILLIS] on the
     * monotonic clock so a forgotten completion cannot hold a link for the whole process.
     */
    private var applyGateAttemptId: Long? = null
    private var applyGateSinceMillis = 0L

    /**
     * The one-shot token of the open-origin success whose setting move ([onOpenOriginSettingApplied]) has not
     * happened yet (S18 i-a, privacy review F2): the attempt id and that attempt's prompt, recorded by the close
     * under [stateLock]. Separate from [applyGateAttemptId], which lapses after `T_held`. The move consumes it
     * only for the exact id; a call with no token, a wrong id or a reused id changes nothing and records nothing.
     * [pendingApplyForegroundSinceClose] is whether the app was in the foreground at the close or came to the
     * foreground at any time since, while the token is open (F6, arch review F3).
     */
    private var pendingApplyAttemptId: Long? = null
    private var pendingApplyPrompt: OpenOriginPrompt? = null

    /** S18 i-b1: the timeout of a timeout-change attempt, recorded with the token. The apply takes it from here. */
    private var pendingApplyTimeout: AutoLockTimeout? = null

    /** S18 ii-d: the record of a weakening review-save attempt, recorded with the token. The T27 move takes it from here. */
    private var pendingApplyRecord: AppLockPrivacySettings? = null

    /**
     * S18 ii-e (T19): the one-shot token of an `INERT` disable that has begun and not been applied yet. Its own id
     * sequence ([lastInertDisableId]), never an attempt id: T19 has no prompt and no attempt. Guarded by [stateLock].
     */
    private var pendingInertDisableId: Long? = null
    private var lastInertDisableId = 0L
    private var pendingApplyForegroundSinceClose = false
    private val claimedDiagnosticCodes: MutableSet<PrivacyDiagnosticCode> =
        EnumSet.noneOf(PrivacyDiagnosticCode::class.java)
    private val claimedAuthErrorBuckets: MutableSet<DeviceAuthErrorBucket> =
        EnumSet.noneOf(DeviceAuthErrorBucket::class.java)
    /**
     * Once-per-process claims keyed by (code, cause), so one code's claim never silences another code's cause.
     * The cause is a closed enum: a [DeviceAuthCapability], or an [AppLockSettingField] for
     * `LOCK_SETTING_PERSIST_FAILED(FIELD)`.
     */
    private val claimedCodeCauses: MutableSet<Pair<PrivacyDiagnosticCode, Enum<*>>> = HashSet()

    // S18-ii-c, guarded by stateLock. `currentNotice` is derived from (`current`, `loadState`) at the end of every
    // transition; `writeSequence` counts committed settings writes (D6); the re-read fields are described at
    // [requestSettingsReread].
    private val mutableNotice = MutableStateFlow<LockNotice?>(null)
    private var currentNotice: LockNotice? = null
    private var writeSequence = 0L
    private var rereadSequence = 0L
    private var rereadIssuedWriteSequence = 0L
    private var keptReread: KeptReread? = null
    private val foregroundRereadRunning = AtomicBoolean(false)

    // Written only under publishLock; read without it for the cheap staleness pre-check.
    @Volatile private var publishedVersion = 0L

    /**
     * The one process-level state. Starts at [AppLockState.Resolving]. Published after the controller's
     * monitor is released; a collector must never call a transition or the authenticator (R2).
     */
    val state: StateFlow<AppLockState> = mutableState.asStateFlow()

    /**
     * The one lock notice (INV-51), derived by [LockNoticeDerivation] from the state and the load state, or null for
     * none. It is stamped with the same version as [state] and published after the monitor is released, in the same
     * publication, so it is never older than the state it belongs to (R2). It is republished when only the load
     * state changed (a committed write, a re-read). An equal value is not re-emitted, so a notice that stays the
     * same across a prompt (AE-115) is never seen to disappear and return.
     *
     * Observation guarantee (F1): at any single instant (the values written under publishLock), a closed state
     * (`RESOLVING`, `LOCKED`, `AUTHENTICATING(UNLOCK)`) is never paired with a non-null notice. Two separate reads
     * (`state.value` then `notice.value`), or a `combine` on a multi-threaded dispatcher, can pair values from
     * different instants. Consumers must render the notice only together with an open state from the same sample,
     * or collect both flows on Main.
     */
    val notice: StateFlow<LockNotice?> = mutableNotice.asStateFlow()

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
            keptReread = null // S18-ii-c: a snapshot kept for T29 belongs to the unreadable view this read replaces
            resolvedByTimeout = false
            lastBackgroundedAtMillis = null
            setState(AppLockState.Disabled)
            // T6b "apply held link" (shared with T2): pulled through releaseHeldLink() (S17); the state is open now.
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
        synchronized(stateLock) {
            appInForeground = true // P6: before the early return for AUTHENTICATING
            // Arch review F3: a foreground after the close and before the move counts as "foreground since close", so a
            // success that closed in the background, then saw foreground and background during the write, still
            // records the pending background at the move (no fail-open window).
            if (pendingApplyAttemptId != null) pendingApplyForegroundSinceClose = true
        }
        nudgeAwaitingLaunch()
        evaluateForeground(ForegroundTrigger.APP_FOREGROUNDED)
        // INV-47: the evaluation above used the state held at that moment (F while unreadable) and never waited. The
        // re-read starts after it, on the controller scope.
        launchReread(foreground = true)
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
     * still handled here. While the T33 gate is open (the previous open-origin success was handed over and
     * [onOpenOriginApplyCompleted] was not called yet, for at most `T_held`) the start is refused with
     * [OpenOriginStartResult.NotInOrigin] as well (F5): the origin is not available until the apply completed.
     *
     * On a start the attempt is opened and `AUTHENTICATING(id, purpose)` is published before the prompt is
     * launched, exactly as for UNLOCK (P1). A matching success returns the state to the origin and calls
     * [onSuccess] once; the controller applies nothing (T33 is S18). Every other end returns to the origin
     * with nothing written (T34).
     */
    fun startOpenOriginAttempt(prompt: OpenOriginPrompt, onSuccess: OpenOriginSuccessHandler): OpenOriginStartResult =
        startOpenOriginAttemptWith(prompt, onSuccess, null, null)

    /**
     * S18 i-b1: starts the CONFIRM_CHANGE attempt (CHANGE copy) of a LONGER auto-lock timeout. Same legality as
     * [startOpenOriginAttempt] (only from exactly `UNLOCKED`, one prompt at a time, the T33 gate refuses). The
     * requested timeout is recorded with the attempt and, on a matching success, in the one-shot token; the
     * state-move entry [onOpenOriginSettingApplied] applies THAT value, never a caller argument. Whether the
     * timeout is longer than the committed one is the caller's check ([AppLockSettingsOperations]).
     */
    fun startTimeoutChangeAttempt(requestedTimeout: AutoLockTimeout, onSuccess: OpenOriginSuccessHandler): OpenOriginStartResult =
        startOpenOriginAttemptWith(OpenOriginPrompt.CHANGE, onSuccess, requestedTimeout, null)

    /**
     * S18 ii-d (T27, INV-45): starts the CONFIRM_CHANGE attempt (CHANGE copy) of a WEAKENING review-save. Same
     * legality as [startOpenOriginAttempt] (only from exactly `UNLOCKED`, one prompt at a time, the T33 gate refuses),
     * and in addition only while the controller's load state is persistent-unreadable (`CORRUPTED` or `INVALID_VALUE`),
     * checked under the monitor: otherwise [OpenOriginStartResult.NotInOrigin]. The [record] is kept in the attempt and,
     * on a matching success, in the one-shot token; [onOpenOriginSettingApplied] applies THAT record's state move
     * (lock off: `DISABLED`, lock on: `UNLOCKED`), never a caller argument. Whether the record is weakening is the
     * caller's check ([AppLockSettingsOperations], via [ReviewSaveAuthorisation]).
     */
    fun startReviewSaveAttempt(record: AppLockPrivacySettings, onSuccess: OpenOriginSuccessHandler): OpenOriginStartResult =
        startOpenOriginAttemptWith(OpenOriginPrompt.CHANGE, onSuccess, null, record)

    private fun startOpenOriginAttemptWith(
        prompt: OpenOriginPrompt,
        onSuccess: OpenOriginSuccessHandler,
        requestedTimeout: AutoLockTimeout?,
        requestedRecord: AppLockPrivacySettings?
    ): OpenOriginStartResult {
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
            // F5: no new attempt while the previous open-origin success is still being applied (the T33 gate).
            // A lapsed gate is not cleared here, so a link still held is dropped as expired by the next release (F2).
            if (applyGateWithinBoundLocked(clock.elapsedRealtimeMillis())) return@transition OpenOriginStartResult.NotInOrigin
            // S18 ii-d: a review-save attempt exists only while the settings need a review (INV-44, T27).
            if (requestedRecord != null && !persistentUnreadableLocked()) return@transition OpenOriginStartResult.NotInOrigin
            val attemptId = ++lastIssuedAttemptId
            val attempt = InFlightAttempt(
                attemptId, purpose, null, prompt.copy, onSuccess, prompt, requestedTimeout, requestedRecord
            )
            inFlight = attempt // open BEFORE any authenticate call (S15 binding)
            setState(AppLockState.Authenticating(attemptId, purpose))
            opened = attempt
            OpenOriginStartResult.Started(attemptId)
        }
        opened?.let { startAttemptJobs(it) }
        return result
    }

    // --- Enable and disable (S18 part (i-a): T17, T18, T26, T33) ----------------------------------------

    /**
     * T26: the request-time refusal of an ENABLE request (capability not `AVAILABLE`, or an API that cannot
     * authenticate). The caller ([AppLockSettingsOperations], the only production caller) has already read the
     * capability and starts no prompt. From exactly `DISABLED` with no open attempt it records the refusal code
     * for [capability], once per process per (code, cause) through [claimedCodeCauses]: `NO_CREDENTIAL` is
     * `LOCK_UNAVAILABLE_NOT_SECURE`, `UNSUPPORTED_API` is `LOCK_UNAVAILABLE_API`, `HW_UNAVAILABLE`, `UNKNOWN` and
     * `CHECK_FAILED` (as `UNKNOWN`) are `LOCK_UNAVAILABLE_CAPABILITY`. The state does not change. `AVAILABLE` and
     * any other state change nothing and record nothing.
     */
    fun onEnableRefusedAtRequest(capability: DeviceAuthCapability) {
        val diagnostic = transition {
            if (current != AppLockState.Disabled || inFlight != null || capability == DeviceAuthCapability.AVAILABLE) {
                null
            } else {
                claimEnableRefusal(capability)
            }
        }
        record(diagnostic)
    }

    /**
     * The state move of an ENABLE or DISABLE apply (T17, T18 by T33), called by the settings operation AFTER its
     * durable write returned and BEFORE it calls [onOpenOriginApplyCompleted]. [committed] is true only for a
     * write that returned a durable commit; a failure, a refusal or an exception is false. The caller is the only
     * production caller ([AppLockSettingsOperations]); the call carries no text and no platform detail.
     *
     * Runtime binding (S18 i-a privacy review F2): the direction (enable or disable) is NOT a caller argument. It
     * is derived from the prompt that the close of attempt [attemptId] recorded in a one-shot token under
     * [stateLock]. Unless [attemptId] matches the unconsumed token, the call is a no-op (no state change, no
     * code); a match consumes the token, so a repeat is a no-op too. The token does not lapse with the T33 gate.
     *
     * Everything is re-checked under [stateLock] (the state may have moved since the hand-off, AE-114):
     * - not committed: no state change, `LOCK_SETTING_PERSIST_FAILED(APP_LOCK_ENABLED)` (AE-114: `UNLOCKED` stays;
     *   the same for ENABLE, which stays `DISABLED`), once per process per (code, field) (spec 10, F8);
     * - committed: the controller's settings view takes the new lock flag, `LOCK_ENABLED` or `LOCK_DISABLED` is
     *   recorded (once per process per code, F8), and the state moves only from exactly the origin with no open
     *   attempt: `DISABLED` to `UNLOCKED`
     *   for [OpenOriginPrompt.ENABLE] (T17), `UNLOCKED` to `DISABLED` for [OpenOriginPrompt.DISABLE] (T18). On the
     *   ENABLE move, if the app was in the foreground when the prompt closed and is not now (it left during the
     *   write), or was in the background at the close, came to the foreground and left again during the write (arch
     *   review F3), the pending background is set to now (monotonic) so the next foreground measures it (F6). If the
     *   state is something else (for example a foreground evaluation locked it between the hand-off and the write,
     *   T14) it is NOT moved: a `LOCKED` state stays `LOCKED` until a real unlock (fail closed), and the next cold
     *   start resolves from the committed store.
     * - [OpenOriginPrompt.CHANGE] with a token timeout (S18 i-b1, a longer timeout): committed takes THAT timeout
     *   into the settings view, not committed records `LOCK_SETTING_PERSIST_FAILED(AUTO_LOCK_TIMEOUT)` (once per
     *   process per (code, field)); the state never moves. A CHANGE with a token RECORD (S18 ii-d, a weakening
     *   review-save) applies T27: committed takes the record as the settings view, drops any kept re-read snapshot,
     *   bumps `writeSequence`, moves lock off to `DISABLED` and lock on to `UNLOCKED` (only from `UNLOCKED`/`INERT` with
     *   no open attempt) and records `LOCK_SETTINGS_REVIEW_SAVED`; not committed moves nothing and records
     *   `LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE)`. A CHANGE with neither changes nothing.
     *
     * The unlock-equivalent discard (T33: `promptBackgroundAt` and `lastBackgroundedAt`) was done by the close
     * that handed the success over and is not repeated here. The held link is not released here either: it waits
     * for [onOpenOriginApplyCompleted].
     */
    fun onOpenOriginSettingApplied(attemptId: Long, committed: Boolean) {
        val diagnostic = transition {
            // F3 (fail closed): a committed write is counted even when no token matches, so D6 drops any older
            // re-read or kept snapshot.
            if (committed) writeSequence++ // D6: a settings write committed
            // F2: the prompt (and so ENABLE or DISABLE) is the one recorded by the close of THIS attempt. No token
            // (no prior success, or already consumed) or a different id: a no-op, nothing recorded.
            val prompt = pendingApplyPrompt
            if (prompt == null || pendingApplyAttemptId != attemptId) return@transition null
            val foregroundSinceClose = pendingApplyForegroundSinceClose
            val tokenTimeout = pendingApplyTimeout
            val tokenRecord = pendingApplyRecord
            pendingApplyAttemptId = null
            pendingApplyPrompt = null
            pendingApplyTimeout = null
            pendingApplyRecord = null
            pendingApplyForegroundSinceClose = false
            applyOpenOriginSettingLocked(prompt, committed, foregroundSinceClose, tokenTimeout, tokenRecord)
        }
        record(diagnostic)
    }

    /**
     * S18 ii-d (T27, INV-45): the view, the state move and the diagnostics of a review-save that needed NO prompt (a
     * non-weakening record, or a record saved while `INERT` where nothing can authenticate, D3). The caller
     * ([AppLockSettingsOperations], the only production caller) wrote [saved] durably first, inside the shared write
     * serializer; [committed] is true only for a durable commit.
     *
     * - Not committed: nothing changes (the sentinel is kept, the state stays) and
     *   `LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE)` is recorded, once per process per (code, field).
     * - Committed: `writeSequence` is bumped (F2, D6: a kept or in-flight re-read snapshot is dropped), a kept snapshot
     *   is discarded, the record becomes the controller's settings view, `LOCK_SETTINGS_REVIEW_SAVED` is recorded once
     *   per process, and the state moves per T27 only from `UNLOCKED` or `INERT` with no open attempt: lock off to
     *   `DISABLED`, lock on to `UNLOCKED`. Any other state is not moved (fail closed; a `LOCKED` state stays `LOCKED`).
     *   A weakening record (per [ReviewSaveAuthorisation]) does not move the state unless it is `INERT`: from `UNLOCKED`
     *   it needs the prompt, so a caller's word alone never relaxes an unlocked lock (the commit is still taken).
     *
     * The state never moves before a commit (INV-46). The inert notice is derived from the new state and view, so it is
     * gone with the move (AE-87).
     */
    fun onReviewSavedWithoutPrompt(saved: AppLockPrivacySettings, committed: Boolean) {
        val diagnostic = transition {
            if (!committed) return@transition claimPersistFailedLocked(AppLockSettingField.REVIEW_SAVE)
            applyReviewSaveCommittedLocked(saved, direct = true)
        }
        record(diagnostic)
    }

    /**
     * The committed half of a review-save, shared by the prompt path (via the token, [direct] false) and the no-prompt
     * path ([direct] true). Must be called with [stateLock] held. Returns the `LOCK_SETTINGS_REVIEW_SAVED` diagnostic
     * the first time.
     *
     * Reviewer-strict ISSUE-1 (fail closed): on the direct path the state moves on the caller's word, so a weakening
     * record (lock off, or a longer timeout) never moves the state unless it is `INERT` (D3, where nothing can
     * authenticate). A weakening record from `UNLOCKED` needs the prompt, whose token authorises the move. The commit
     * itself is still taken (the store did commit): the sequence bump, the view and the diagnostic are unchanged.
     */
    private fun applyReviewSaveCommittedLocked(record: AppLockPrivacySettings, direct: Boolean): PendingDiagnostic? {
        writeSequence++ // F2, D6: a settings write committed
        keptReread = null
        loadState = AppLockSettingsLoadState.Loaded(record)
        val cur = current
        val unauthorisedWeakening = direct && ReviewSaveAuthorisation.isWeakening(record) && cur !is AppLockState.Inert
        if (!unauthorisedWeakening && inFlight == null && (cur is AppLockState.Unlocked || cur is AppLockState.Inert)) {
            val next: AppLockState = if (record.appLockEnabled) AppLockState.Unlocked else AppLockState.Disabled
            if (next != cur) setState(next)
        }
        return claim(PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED)
    }

    /**
     * S18 ii-e (T19, INV-3, D3): begins the disable of the lock while `INERT`, where nothing can authenticate. Under the
     * monitor:
     * - the state must be exactly `INERT` with no open attempt, else [InertDisableBegin.NotInOrigin];
     * - `INERT(SETTINGS_UNREADABLE_NO_AUTH)`, or a load state that is unreadable (or unresolved), is
     *   [InertDisableBegin.ReviewRequired] (INV-44, INV-45: the review-save is the way; nothing is recorded);
     * - otherwise a one-shot token with a fresh id is recorded and returned as [InertDisableBegin.Begun].
     *
     * Whether authentication is really impossible (the FRESH capability is neither `AVAILABLE` nor `CHECK_FAILED`, D3)
     * is the caller's check ([AppLockSettingsOperations], the only production caller), made AFTER this call (so
     * [InertDisableBegin.ReviewRequired] applies whatever the capability) and again inside the shared write serializer
     * before the write. The write is the caller's too. A new begin replaces an older
     * unused token (an abandoned one can never be applied: only the exact id applies).
     */
    fun beginInertDisable(): InertDisableBegin = transition {
        val cur = current
        if (cur !is AppLockState.Inert || inFlight != null) return@transition InertDisableBegin.NotInOrigin
        val view = loadState
        val readable = view is AppLockSettingsLoadState.Loaded || view is AppLockSettingsLoadState.FirstRunDefault
        if (cur.reason == InertReason.SETTINGS_UNREADABLE_NO_AUTH || !readable) return@transition InertDisableBegin.ReviewRequired
        val id = ++lastInertDisableId
        pendingInertDisableId = id
        InertDisableBegin.Begun(id)
    }

    /**
     * S18 ii-e (T19): the view, the state move and the diagnostics of an `INERT` disable, called by the settings
     * operation AFTER its durable write returned. [committed] is true only for a durable commit. Id-bound: unless [id]
     * matches the unconsumed token of [beginInertDisable] the call is a no-op (no state change, no code); a match
     * consumes the token, so a repeat is a no-op too. Everything is re-checked under the monitor:
     * - not committed: no state change (`INERT` stays), `LOCK_SETTING_PERSIST_FAILED(APP_LOCK_ENABLED)` once per process
     *   per (code, field), the lock flag of the view is left as it is (INV-46);
     * - committed: `writeSequence` is bumped (F2, D6: a kept or in-flight re-read snapshot is dropped; counted even when
     *   no token matches, like [onOpenOriginSettingApplied]), the view takes lock off, `LOCK_DISABLED` is recorded once per
     *   process, and the state moves to `DISABLED` ONLY from exactly `INERT` with no open attempt. If the state moved
     *   meanwhile (for example the capability returned and a foreground made it `UNLOCKED` or `LOCKED`) it is NOT
     *   moved; the view still follows the commit, so an `UNLOCKED` session stays open with the lock off (handoff
     *   carry-forward 3) and a `LOCKED` state stays `LOCKED` until a real unlock; the next cold start resolves from the
     *   committed store. The pending background is
     *   discarded with the move (`DISABLED` records none).
     */
    fun onInertDisableApplied(id: Long, committed: Boolean) {
        val diagnostic = transition {
            if (committed) writeSequence++ // F2, D6: a settings write committed
            if (pendingInertDisableId != id) return@transition null
            pendingInertDisableId = null
            if (!committed) return@transition claimPersistFailedLocked(AppLockSettingField.APP_LOCK_ENABLED)
            when (val view = loadState) {
                is AppLockSettingsLoadState.Loaded ->
                    loadState = AppLockSettingsLoadState.Loaded(view.settings.copy(appLockEnabled = false))
                is AppLockSettingsLoadState.FirstRunDefault ->
                    loadState = AppLockSettingsLoadState.Loaded(view.settings.copy(appLockEnabled = false))
                is AppLockSettingsLoadState.Unreadable, null -> Unit
            }
            if (current is AppLockState.Inert && inFlight == null) {
                setState(AppLockState.Disabled)
                lastBackgroundedAtMillis = null
            }
            claim(PrivacyDiagnosticCode.LOCK_DISABLED)
        }
        record(diagnostic)
    }

    /** True while the load state is `Unreadable` with a persistent cause (`CORRUPTED`, `INVALID_VALUE`). Under [stateLock]. */
    private fun persistentUnreadableLocked(): Boolean {
        val view = loadState
        return view is AppLockSettingsLoadState.Unreadable && view.cause.isPersistent
    }

    /**
     * S18 ii-d: whether the settings need a review right now (the load state is persistent-unreadable). For
     * [AppLockSettingsOperations] only (a source-scan pin holds that); the state check is its own.
     */
    internal val isPersistentUnreadable: Boolean
        get() = synchronized(stateLock) { persistentUnreadableLocked() }

    /**
     * S18 i-b1: the settings view and failure record of a timeout write that needed NO prompt (an equal or shorter
     * timeout, or a longer one while the lock is off). The caller ([AppLockSettingsOperations], the only production
     * caller) wrote [timeout] durably first; [committed] is true only for a durable commit. A commit takes the new
     * timeout into the controller's settings view (the next foreground evaluation measures with it). A write that did
     * not commit changes nothing and records `LOCK_SETTING_PERSIST_FAILED(AUTO_LOCK_TIMEOUT)`, once per process per
     * (code, field). The state never changes. The prompt path does NOT use this entry: it takes its timeout from the
     * token ([onOpenOriginSettingApplied]).
     */
    fun onTimeoutWrittenWithoutPrompt(timeout: AutoLockTimeout, committed: Boolean) {
        val diagnostic = transition {
            if (committed) {
                writeSequence++ // D6: a settings write committed
                // Privacy review F2 (fail closed): a no-prompt commit never lengthens a view that has the lock on. A
                // longer timeout over a lock-on view means the caller's pre-read was stale; the view stays as it is
                // (more restrictive), so a foreground never measures with an unauthorised longer timeout.
                val view = (loadState as? AppLockSettingsLoadState.Loaded)?.settings
                    ?: (loadState as? AppLockSettingsLoadState.FirstRunDefault)?.settings
                val wouldLengthenLockOnView = view != null && view.appLockEnabled &&
                    timeout.millis > view.autoLockTimeout.millis
                if (!wouldLengthenLockOnView) applyTimeoutToViewLocked(timeout)
                null
            } else {
                claimTimeoutPersistFailedLocked()
            }
        }
        record(diagnostic)
    }

    /** Must be called with [stateLock] held. An unreadable or missing view is left as it is (fail-closed record F). */
    private fun applyTimeoutToViewLocked(timeout: AutoLockTimeout) {
        when (val view = loadState) {
            is AppLockSettingsLoadState.Loaded ->
                loadState = AppLockSettingsLoadState.Loaded(view.settings.copy(autoLockTimeout = timeout))
            is AppLockSettingsLoadState.FirstRunDefault ->
                loadState = AppLockSettingsLoadState.Loaded(view.settings.copy(autoLockTimeout = timeout))
            is AppLockSettingsLoadState.Unreadable, null -> Unit
        }
    }

    /**
     * S18 i-b2: the settings view and failure record of a forced-aware plain write ([AppLockSettingField.DISCREET] or
     * [AppLockSettingField.SECURE_SCREEN]; these never need a prompt: with the lock on they are forced and refused, with
     * the lock off they are not a weakening). The caller ([AppLockSettingsOperations], the only production caller)
     * wrote [value] durably first; [committed] is true only for a durable commit and then the value is taken into the
     * controller's settings view. A write that did not commit changes nothing and records
     * `LOCK_SETTING_PERSIST_FAILED(FIELD)` once per process per (code, field). Any other field is ignored. The state
     * never changes.
     *
     * The view's discreet and secure-screen fields are bookkeeping only (the stored plain values): the effective values
     * always OR in the lock flag (`EffectivePrivacyValues`), so this view never decides what is shown or posted by itself.
     */
    fun onPlainSettingWritten(field: AppLockSettingField, value: Boolean, committed: Boolean) {
        val diagnostic = transition {
            if (field != AppLockSettingField.DISCREET && field != AppLockSettingField.SECURE_SCREEN) return@transition null
            if (!committed) return@transition claimPersistFailedLocked(field)
            writeSequence++ // D6: a settings write committed
            when (val view = loadState) {
                is AppLockSettingsLoadState.Loaded, is AppLockSettingsLoadState.FirstRunDefault -> {
                    val settings = (view as? AppLockSettingsLoadState.Loaded)?.settings
                        ?: (view as AppLockSettingsLoadState.FirstRunDefault).settings
                    loadState = AppLockSettingsLoadState.Loaded(
                        if (field == AppLockSettingField.DISCREET) {
                            settings.copy(discreetNotifications = value)
                        } else {
                            settings.copy(secureScreen = value)
                        }
                    )
                }
                is AppLockSettingsLoadState.Unreadable, null -> Unit
            }
            null
        }
        record(diagnostic)
    }

    /** Once per process per (code, FIELD) (spec 10, F8). Must be called with [stateLock] held. */
    private fun claimTimeoutPersistFailedLocked(): PendingDiagnostic? =
        claimPersistFailedLocked(AppLockSettingField.AUTO_LOCK_TIMEOUT)

    /** Once per process per (code, FIELD) (spec 10, F8). Must be called with [stateLock] held. */
    private fun claimPersistFailedLocked(field: AppLockSettingField): PendingDiagnostic? =
        if (claimedCodeCauses.add(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED to field)) {
            PendingDiagnostic(
                PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED,
                null,
                PrivacyDiagnosticMetadata.builder().put(PrivacyDiagnosticKey.FIELD, field).build()
            )
        } else {
            null
        }

    /** Must be called with [stateLock] held. */
    private fun applyOpenOriginSettingLocked(
        prompt: OpenOriginPrompt,
        committed: Boolean,
        foregroundSinceClose: Boolean,
        tokenTimeout: AutoLockTimeout?,
        tokenRecord: AppLockPrivacySettings?
    ): PendingDiagnostic? {
        val enable = when (prompt) {
            OpenOriginPrompt.ENABLE -> true
            OpenOriginPrompt.DISABLE -> false
            OpenOriginPrompt.CHANGE -> {
                // S18 ii-d (T27): a weakening review-save carries its record in the token. Committed: the shared T27 move
                // (lock off to DISABLED, lock on to UNLOCKED) and LOCK_SETTINGS_REVIEW_SAVED; not committed: nothing moves
                // and LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE) is claimed (the sentinel stays).
                if (tokenRecord != null) {
                    return if (committed) {
                        applyReviewSaveCommittedLocked(tokenRecord, direct = false)
                    } else {
                        claimPersistFailedLocked(AppLockSettingField.REVIEW_SAVE)
                    }
                }
                // S18 i-b1: a timeout change carries its value in the token; the state never moves (UNLOCKED stays).
                // A CHANGE with neither a token timeout nor a token record carries no other weakening kind: nothing to apply.
                if (tokenTimeout == null) return null
                if (!committed) return claimTimeoutPersistFailedLocked()
                applyTimeoutToViewLocked(tokenTimeout)
                return null
            }
        }
        if (!committed) {
            // Once per process per (code, FIELD) (spec 10, F8).
            return if (claimedCodeCauses.add(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED to AppLockSettingField.APP_LOCK_ENABLED)) {
                PendingDiagnostic(
                    PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED,
                    null,
                    PrivacyDiagnosticMetadata.builder().put(PrivacyDiagnosticKey.FIELD, AppLockSettingField.APP_LOCK_ENABLED).build()
                )
            } else {
                null
            }
        }
        // The committed flag becomes the controller's view of the store. An unreadable or missing view is left as it
        // is (it stays fail-closed record F); a committed per-field write cannot start from one.
        when (val view = loadState) {
            is AppLockSettingsLoadState.Loaded ->
                loadState = AppLockSettingsLoadState.Loaded(view.settings.copy(appLockEnabled = enable))
            is AppLockSettingsLoadState.FirstRunDefault ->
                loadState = AppLockSettingsLoadState.Loaded(view.settings.copy(appLockEnabled = enable))
            is AppLockSettingsLoadState.Unreadable, null -> Unit
        }
        val origin = if (enable) AppLockState.Disabled else AppLockState.Unlocked
        if (current == origin && inFlight == null) {
            setState(if (enable) AppLockState.Unlocked else AppLockState.Disabled)
            // F6: the app left the foreground while the durable write ran (the prompt had closed in the
            // foreground). `DISABLED` records no background, so the time away is measured from now (monotonic):
            // the next foreground evaluates it. A background during the PROMPT is not this case: that one is
            // unlock-equivalent and discarded by the close (AE-117).
            if (enable && foregroundSinceClose && !appInForeground && lastBackgroundedAtMillis == null) {
                lastBackgroundedAtMillis = clock.elapsedRealtimeMillis()
            }
        }
        // Once per process per cause (spec 10, F8): neither code has a cause, so the code is the claim.
        return claim(if (enable) PrivacyDiagnosticCode.LOCK_ENABLED else PrivacyDiagnosticCode.LOCK_DISABLED)
    }

    // S18 i-b2 settled: the only weakening writes while the lock is on are the disable (T18) and a longer timeout (both
    // prompt-guarded); discreet and secure screen are FORCED while the lock is on (INV-48) and so refused, not prompted;
    // TECH-13 (INERT) is the Operations' refusal with no controller entry.
    // S18 ii-e settled: the disable while INERT, where nothing can authenticate (T19, D3), is the id-bound pair
    // [beginInertDisable] / [onInertDisableApplied]; every other weakening while INERT keeps the TECH-13 refusal.
    // S18 ii-d settled: a weakening review-save confirms through the CHANGE prompt ([startReviewSaveAttempt]); a
    // non-weakening one, or one saved while INERT with nothing to authenticate with (D3), needs no prompt
    // ([onReviewSavedWithoutPrompt]).

    // --- Settings re-read (S18-ii-c: T28, T29, INV-47) ----------------------------------------------------------

    /** A re-read result kept by T29 for use after an unlock; dropped by D6 if a settings write committed after it. */
    private class KeptReread(val read: AppLockSettingsLoadState, val writeSequenceAtKeep: Long)

    /**
     * `SETTINGS_REREAD` (INV-47): reads the settings store once on the controller scope and offers the result to
     * the private, sequence-checked [onSettingsReread]. The settings section's retry binds this later; it has NO
     * production caller yet (a source-scan pin holds it). It does nothing unless the load state is transient
     * unreadable (`IO_ERROR`, `TIMEOUT`): a readable view needs no re-read, and a persistent cause (`CORRUPTED`,
     * `INVALID_VALUE`) stays until a review-save (INV-33). A new request makes every older re-read stale. The
     * foreground does the same on its own at each `APP_FOREGROUNDED` ([onAppForegrounded]).
     */
    fun requestSettingsReread() {
        launchReread(foreground = false)
    }

    /**
     * Issues one re-read and runs it on [scope]; the caller never waits for it (INV-47). A foreground re-read is
     * skipped while a foreground one is still running, so a burst of foregrounds reads once. Issuing records the
     * sequence number and the write sequence at that moment: only the newest issue may apply (a stale one is dropped)
     * and none may apply after a settings write committed meanwhile (D6).
     */
    private fun launchReread(foreground: Boolean) {
        if (foreground && !foregroundRereadRunning.compareAndSet(false, true)) return
        val issued = synchronized(stateLock) {
            if (!transientUnreadableLocked()) {
                null
            } else {
                rereadIssuedWriteSequence = writeSequence
                ++rereadSequence
            }
        }
        if (issued == null) {
            if (foreground) foregroundRereadRunning.set(false)
            return
        }
        val job = scope.launch { onSettingsReread(issued, readLoadState()) }
        if (foreground) job.invokeOnCompletion { foregroundRereadRunning.set(false) }
    }

    /** True while the load state is `Unreadable` with a transient cause. Must be called with [stateLock] held. */
    private fun transientUnreadableLocked(): Boolean {
        val view = loadState
        return view is AppLockSettingsLoadState.Unreadable && !view.cause.isPersistent
    }

    /**
     * `SETTINGS_REREAD(loadState)` (T28, T29). Private and separate from [onLateSettingsResolved]. Dropped when:
     * it is not the newest issued re-read ([seq], a stale result); a settings write committed since it was issued
     * (D6); or the load state is no longer transient unreadable. An unreadable result passes those checks and then
     * changes neither the view nor the state; in `LOCKED` or `AUTHENTICATING` it also drops an older kept snapshot
     * (F5: the newer result supersedes it). A readable result is then:
     * - T28, from `UNLOCKED` or `INERT`: applied at once through [applyRereadLocked], without auth;
     * - T29, in `LOCKED` or `AUTHENTICATING` of any purpose: kept, nothing changes. It is applied by
     *   [applyKeptRereadAfterClose] after an unlock or an open-origin prompt that ends in an open state;
     * - any other state (`RESOLVING`, `DISABLED`): ignored.
     * It never locks and never opens `LOCKED` (T6b alone may).
     */
    private fun onSettingsReread(seq: Long, read: AppLockSettingsLoadState) {
        // Capability before the monitor (no binder call under it); only an open state with a readable result uses it.
        val before = current
        val usesCapability = read !is AppLockSettingsLoadState.Unreadable &&
            (before is AppLockState.Unlocked || before is AppLockState.Inert)
        val capability = if (usesCapability) readQueryCapability() else null
        val diagnostic = transition {
            if (seq != rereadSequence) return@transition null
            if (rereadIssuedWriteSequence != writeSequence) return@transition null
            if (!transientUnreadableLocked()) return@transition null
            val unreadable = read is AppLockSettingsLoadState.Unreadable
            when (current) {
                is AppLockState.Unlocked, is AppLockState.Inert ->
                    if (unreadable || capability == null) null else applyRereadLocked(read, capability)
                is AppLockState.Locked, is AppLockState.Authenticating -> {
                    // F5: a newer unreadable result supersedes an older kept snapshot (the view stays unreadable).
                    keptReread = if (unreadable) null else KeptReread(read, writeSequence)
                    null
                }
                else -> null
            }
        }
        record(diagnostic)
    }

    /**
     * T28 under the monitor, from `UNLOCKED` or `INERT` with a readable [read]: takes the read as the controller's
     * settings view and moves per the stored values. Lock off: `DISABLED`. Lock on and the lock can protect
     * (capability not genuinely unavailable, a failed check counts as able, R3): `UNLOCKED`. Lock on and the
     * capability genuinely unavailable: `INERT(reason)`. No auth, and it never locks mid-session. Returns the
     * diagnostic of a new `INERT` (an existing code, claimed once per process).
     */
    private fun applyRereadLocked(read: AppLockSettingsLoadState, capability: DeviceAuthCapability): PendingDiagnostic? {
        val settings = when (read) {
            is AppLockSettingsLoadState.Loaded -> read.settings
            is AppLockSettingsLoadState.FirstRunDefault -> read.settings
            is AppLockSettingsLoadState.Unreadable -> return null
        }
        // TODO(S18-ii-c D9): capture `(loadState as Unreadable).cause` here, before the assignment, and emit
        // LOCK_SETTINGS_RECOVERED(cause) once the owner approves the emitter entry. Recovery and a new INERT can
        // coincide, so both diagnostics must be recorded.
        loadState = read
        keptReread = null
        val next: AppLockState = if (!settings.appLockEnabled) {
            AppLockState.Disabled
        } else {
            capability.toInertReason()?.let { AppLockState.Inert(it) } ?: AppLockState.Unlocked
        }
        if (next == current) return null
        return if (next is AppLockState.Inert) {
            enterInert(next.reason)
        } else {
            setState(next)
            null
        }
    }

    /**
     * T29's second half: after an UNLOCK success or an open-origin prompt that ended, offers the kept snapshot to T28.
     * Nothing happens while the state is still `LOCKED`, `AUTHENTICATING` or `RESOLVING` (the snapshot stays kept, so
     * `LOCKED` is never relaxed). In an open state the snapshot is consumed: dropped if a settings write committed
     * after it was kept (D6) or the view is no longer transient unreadable, otherwise applied through T28 from
     * `UNLOCKED` or `INERT`.
     */
    private fun applyKeptRereadAfterClose() {
        if (synchronized(stateLock) { keptReread == null }) return
        val before = current
        val capability = if (before is AppLockState.Unlocked || before is AppLockState.Inert) readQueryCapability() else null
        val diagnostic = transition {
            val kept = keptReread ?: return@transition null
            val now = current
            if (now is AppLockState.Locked || now is AppLockState.Authenticating || now is AppLockState.Resolving) {
                return@transition null
            }
            keptReread = null
            if (kept.writeSequenceAtKeep != writeSequence) return@transition null
            if (!transientUnreadableLocked()) return@transition null
            if ((now is AppLockState.Unlocked || now is AppLockState.Inert) && capability != null) {
                applyRereadLocked(kept.read, capability)
            } else {
                null
            }
        }
        record(diagnostic)
    }

    // --- The held deep link (T20, T21, T22; S17) --------------------------------------------------

    /**
     * `DEEP_LINK_RECEIVED(link)` through `LOCK_QUERY` (T20, T21, INV-30, INV-31). Runs the same pending-timeout
     * evaluation as [onLockQuery] and then decides, in the same critical section, so the decision can never be
     * based on a state the evaluation would have changed:
     * - [LinkDecision.HOLD] (T20): the state is `LOCKED`, `RESOLVING` or `AUTHENTICATING` of any purpose, an
     *   attempt is open, or an open-origin success is still being applied (the T33 gate). The link is held
     *   (replacing any older one, newest wins) with `DL_HELD_LOCKED` or `DL_HELD_REPLACED`. Nothing is looked
     *   up, written or navigated.
     * - [LinkDecision.APPLY_NOW] (T21): the state is `DISABLED`, `UNLOCKED` or `INERT` with nothing pending.
     *   A link still held is older and is dropped, so it can never apply after this one.
     *
     * The caller applies a link only on APPLY_NOW. The decision is never made from `AUTHENTICATING` (the
     * evaluation does not run there, T32).
     */
    fun onLockQuery(link: DeepLinkRequest): LinkDecision {
        val capability = readQueryCapability()
        val pending = ArrayList<PendingDiagnostic>(2)
        val decision = transition {
            evaluateForegroundLocked(ForegroundTrigger.LOCK_QUERY, capability)?.let { pending += it }
            decideLinkLocked(link, pending)
        }
        pending.forEach { record(it) }
        return decision
    }

    /**
     * Hands the held link back at most once (T8, T2, T4, T6, T6b "apply held link", T33 and T34 "release").
     * It is a LOCK_QUERY itself: the pending timeout is evaluated first, in the same critical section, so a
     * link is never released into a lock that should have engaged (INV-31).
     *
     * [HeldLinkRelease.None] while the app is not in the foreground (F1: no evaluation, nothing consumed, the link
     * stays held), while `LOCKED`, `RESOLVING`, `AUTHENTICATING`, with an attempt open, or while the T33 gate is
     * open (an open-origin success whose apply has not completed): the link stays held. Otherwise the link is
     * removed under the monitor (so it can be released once only) and returned as [HeldLinkRelease.Apply], or as
     * [HeldLinkRelease.Expired] after `T_held` (the caller opens Home). A T33 gate that lapsed after `T_held`
     * with a link held also drops the link as [HeldLinkRelease.Expired] (F2): [HeldLinkRelease.Apply] is never
     * returned before the apply completed. The call is a cheap no-op when nothing is held. The caller (S20)
     * calls it from a separate main-thread call (never inline under the publish lock) after each publication of
     * `DISABLED`, `UNLOCKED` or `INERT`, at ON_START right after [onAppForegrounded], and after
     * [onOpenOriginApplyCompleted].
     */
    fun releaseHeldLink(): HeldLinkRelease {
        // Nothing held, or not in the foreground (F1): None without the capability read. Re-checked under the
        // monitor below, where the foreground guard decides.
        if (synchronized(stateLock) { heldLink == null || !appInForeground }) return HeldLinkRelease.None
        val capability = readQueryCapability()
        val pending = ArrayList<PendingDiagnostic>(2)
        val release = transition {
            // F1: while the app is not in the foreground nothing is evaluated, consumed or released: the link stays
            // held and the pending background stays pending for the next APP_FOREGROUNDED (INV-31). This guard is
            // deliberately NOT on onLockQuery(link): AE-58 needs that decision before onStart.
            if (!appInForeground) return@transition HeldLinkRelease.None
            evaluateForegroundLocked(ForegroundTrigger.LOCK_QUERY, capability)?.let { pending += it }
            releaseLocked(pending)
        }
        pending.forEach { record(it) }
        return release
    }

    /**
     * Closes the T33 gate (S18 calls this when the durable apply of an open-origin success completed OR failed).
     * Id-bound: an id other than the one whose success was handed over changes nothing, so a stale or repeated
     * call is harmless. It releases nothing itself; the caller then asks [releaseHeldLink]. Public rather than
     * internal for the same reason as the open-origin start API: its caller is another main file, and the id
     * binding means a wrong call cannot open the gate early for any other attempt.
     */
    fun onOpenOriginApplyCompleted(attemptId: Long) {
        synchronized(stateLock) {
            if (applyGateAttemptId == attemptId) applyGateAttemptId = null
            // The apply is over: its setting move can no longer be applied (the move runs before this call).
            clearPendingApplyLocked(attemptId)
        }
    }

    /** Drops the one-shot setting-move token when it belongs to [attemptId]. Must be called with [stateLock] held. */
    private fun clearPendingApplyLocked(attemptId: Long) {
        if (pendingApplyAttemptId == attemptId) {
            pendingApplyAttemptId = null
            pendingApplyPrompt = null
            pendingApplyTimeout = null
            pendingApplyRecord = null
            pendingApplyForegroundSinceClose = false
        }
    }

    /**
     * The capability for a link query, read before the monitor (no binder call under it). A failed read is
     * [DeviceAuthCapability.CHECK_FAILED]: transient, never INERT, so the timer still evaluates fail-closed.
     * `CancellationException` and `Error` propagate.
     */
    private fun readQueryCapability(): DeviceAuthCapability =
        try {
            capabilityProvider.current()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("App-lock link query capability read failed: class=%s", DiagnosticThrowableSummary.className(e))
            DeviceAuthCapability.CHECK_FAILED
        }

    /** True while the T33 gate is open. A gate older than `T_held` (or from a backwards clock) is lapsed and cleared. Under [stateLock]. */
    private fun applyGateOpenLocked(nowMillis: Long): Boolean {
        if (applyGateAttemptId == null) return false
        if (!applyGateWithinBoundLocked(nowMillis)) {
            applyGateAttemptId = null
            return false
        }
        return true
    }

    /** True while the gate is set and not older than `T_held` (a backwards clock counts as lapsed). Never clears. Under [stateLock]. */
    private fun applyGateWithinBoundLocked(nowMillis: Long): Boolean {
        if (applyGateAttemptId == null) return false
        val elapsed = nowMillis - applyGateSinceMillis
        return elapsed >= 0L && elapsed < HeldDeepLink.HELD_LINK_TTL_MILLIS
    }

    /** T20 / T21 under the monitor, after the evaluation. Appends the diagnostics claimed to [out]. */
    private fun decideLinkLocked(link: DeepLinkRequest, out: MutableList<PendingDiagnostic>): LinkDecision {
        val cur = current
        val now = clock.elapsedRealtimeMillis()
        // Evaluated BEFORE mustHold (no short-circuit): a T33 gate that lapsed before this link arrived is cleared
        // here, at hold time, even while LOCKED. A link held under a still-open gate is dropped at lapse (F2).
        val gateOpen = applyGateOpenLocked(now)
        val mustHold = cur is AppLockState.Locked || cur is AppLockState.Resolving ||
            cur is AppLockState.Authenticating || inFlight != null || gateOpen
        val previous = heldLink
        if (!mustHold) {
            // T21: the newest link applies now; an older held one must never apply after it.
            heldLink = null
            if (previous != null) out += linkReplacedDiagnostic(previous.host, link.host)
            return LinkDecision.APPLY_NOW
        }
        heldLink = HeldDeepLink(link, now, unlockCount)
        out += if (previous == null) linkHeldDiagnostic(link.host) else linkReplacedDiagnostic(previous.host, link.host)
        return LinkDecision.HOLD
    }

    /** The release decision under the monitor, after the evaluation. Appends the diagnostics claimed to [out]. */
    private fun releaseLocked(out: MutableList<PendingDiagnostic>): HeldLinkRelease {
        val held = heldLink ?: return HeldLinkRelease.None
        val cur = current
        val open = cur is AppLockState.Disabled || cur is AppLockState.Unlocked || cur is AppLockState.Inert
        val now = clock.elapsedRealtimeMillis()
        if (!open || inFlight != null) return HeldLinkRelease.None
        val gateWasSet = applyGateAttemptId != null
        if (gateWasSet && applyGateWithinBoundLocked(now)) return HeldLinkRelease.None
        applyGateAttemptId = null // lapsed: cleared; a link still held is dropped below, never applied (F2)
        heldLink = null // taken: a link is released at most once (AE-118)
        if (gateWasSet || held.isExpired(now)) {
            // F2/F6: a T33 gate that lapsed (T_held without an apply completion) with a link held drops the link as
            // expired; Apply is never returned before the apply completed.
            out += linkExpiredDiagnostic(held.host)
            return HeldLinkRelease.Expired
        }
        return HeldLinkRelease.Apply(held.request, afterUnlock = unlockCount > held.unlockCountAtHold)
    }

    private fun linkHeldDiagnostic(host: DeepLinkHost): PendingDiagnostic = PendingDiagnostic(
        PrivacyDiagnosticCode.DL_HELD_LOCKED,
        null,
        PrivacyDiagnosticMetadata.builder().put(PrivacyDiagnosticKey.HOST, host).build()
    )

    private fun linkReplacedDiagnostic(old: DeepLinkHost, new: DeepLinkHost): PendingDiagnostic = PendingDiagnostic(
        PrivacyDiagnosticCode.DL_HELD_REPLACED,
        null,
        PrivacyDiagnosticMetadata.builder()
            .put(PrivacyDiagnosticKey.OLD_HOST, old)
            .put(PrivacyDiagnosticKey.NEW_HOST, new)
            .build()
    )

    private fun linkExpiredDiagnostic(host: DeepLinkHost): PendingDiagnostic = PendingDiagnostic(
        PrivacyDiagnosticCode.DL_HELD_EXPIRED,
        null,
        PrivacyDiagnosticMetadata.builder().put(PrivacyDiagnosticKey.HOST, host).build()
    )

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
            // CANCEL-01: a second cancellation from the close is attached the same way; `e` is rethrown below.
            try {
                closeAttempt(attempt, if (attempt.launched) Closing.Abandoned else Closing.NotLaunched)
            } catch (closeFailure: CancellationException) {
                if (closeFailure !== e) e.addSuppressed(closeFailure)
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
     *   refuse the same way with `LOCK_UNAVAILABLE_NOT_SECURE` / `LOCK_UNAVAILABLE_API` (S18 i-a).
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
                // S18 (i-a): NO_CREDENTIAL and UNSUPPORTED_API are T26-style refusals with their own bounded codes
                // (LOCK_UNAVAILABLE_NOT_SECURE, LOCK_UNAVAILABLE_API), claimed per (code, cause) like the others.
                DeviceAuthCapability.NO_CREDENTIAL,
                DeviceAuthCapability.UNSUPPORTED_API -> Closing.EnableRefused(capability)
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
                    unlockCount++
                    setState(AppLockState.Unlocked)
                    // T8 "apply held link once": nothing is applied here. The held link is pulled through
                    // releaseHeldLink() (S17), which hands it back at most once.
                    claim(PrivacyDiagnosticCode.LOCK_UNLOCKED)
                }
                Closing.OpenOriginSuccess -> {
                    // T33 hand-off, nothing applied here. Back to the origin; the attempt's promptBackgroundAt
                    // is discarded with it and the pending background is cleared (spec 3.4: an auth success that
                    // ends in an open state clears it). Increment 1 has no prompt that is not unlock-equivalent
                    // (INV-54), so the "otherwise hand the background to the timer" clause has no case yet.
                    lastBackgroundedAtMillis = null
                    setState(originState(attempt))
                    // S17: the T33 gate. A held link is not released by this close publishing the origin state;
                    // it waits for onOpenOriginApplyCompleted(attemptId) (S18's apply completed or failed).
                    applyGateAttemptId = attempt.attemptId
                    applyGateSinceMillis = clock.elapsedRealtimeMillis()
                    // F2: the one-shot token of the setting move (id plus this attempt's prompt). The gate lapses;
                    // this does not. F6: whether the app was in the foreground when the prompt closed.
                    pendingApplyAttemptId = attempt.attemptId
                    pendingApplyPrompt = attempt.prompt
                    pendingApplyTimeout = attempt.requestedTimeout
                    pendingApplyRecord = attempt.requestedRecord
                    pendingApplyForegroundSinceClose = appInForeground
                    null
                }
                is Closing.EnableRefused -> {
                    setState(originState(attempt))
                    claimEnableRefusal(closing.cause)
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
        // T29 (S18-ii-c): an UNLOCK success, or any end of an open-origin prompt, may leave an open state; a kept
        // re-read snapshot is then offered to T28. Still LOCKED or AUTHENTICATING keeps it.
        if (closing == Closing.Success || attempt.purpose.isOpenOrigin) applyKeptRereadAfterClose()
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
            clearApplyGate(attempt.attemptId)
            throw e
        } catch (e: Exception) {
            Timber.w("App-lock success hand-off failed: class=%s", DiagnosticThrowableSummary.className(e))
            clearApplyGate(attempt.attemptId)
        }
    }

    /**
     * F8: a handler that threw did not start an apply, so nothing will call [onOpenOriginApplyCompleted] for this
     * attempt. Clears its gate (id-bound, under [stateLock]) so a held link and the next open-origin start are not
     * blocked for `T_held`. The `CancellationException` is rethrown by the caller after this.
     */
    private fun clearApplyGate(attemptId: Long) {
        synchronized(stateLock) {
            if (applyGateAttemptId == attemptId) applyGateAttemptId = null
            clearPendingApplyLocked(attemptId)
        }
    }

    // --- Internals ------------------------------------------------------------------------------

    /**
     * Runs [block] under [stateLock], then publishes the resulting state AFTER the lock is released (R2).
     * The state and its version are read inside the same critical section as the block, so the published
     * value is exactly what this block produced (or a newer one), never a value from a different moment.
     */
    private inline fun <R> transition(block: () -> R): R {
        val (result, view) = synchronized(stateLock) {
            val outcome = block()
            outcome to publicationLocked()
        }
        publish(view)
        return result
    }

    /** What one transition publishes: the state and the notice derived with it, under one version stamp. */
    private class Publication(val stamp: Long, val state: AppLockState, val notice: LockNotice?)

    /**
     * Must be called with [stateLock] held. Derives the notice from the state and load state the transition ended
     * with. A notice that changed without a state change (a load-state-only change) takes a new version so it is
     * published too; an unchanged notice leaves the version alone.
     */
    private fun publicationLocked(): Publication {
        val derived = LockNoticeDerivation.derive(current, loadState)
        if (derived != currentNotice) {
            currentNotice = derived
            version++
        }
        return Publication(version, current, currentNotice)
    }

    /** Must be called with [stateLock] held. Every state change goes through here. */
    private fun setState(next: AppLockState) {
        current = next
        version++
    }

    /** Publishes [view] unless a newer version is already out (a stale value is never published). */
    private fun publish(view: Publication) {
        if (view.stamp <= publishedVersion) return
        synchronized(publishLock) {
            if (view.stamp <= publishedVersion) return
            publishedVersion = view.stamp
            // F1: a closed state is never observable with a non-null notice. A null notice is written first (so a
            // state that closes never shows with the old notice); a non-null notice is written after the state.
            if (view.notice == null) {
                mutableNotice.value = null
                mutableState.value = view.state
            } else {
                mutableState.value = view.state
                mutableNotice.value = view.notice
            }
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
                // T2 "apply held link": pulled through releaseHeldLink() (S17); T4 and T6 are the same.
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
                // T14: a link held (or arriving later) stays held in LOCKED (S17, decideLinkLocked / releaseLocked).
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
        claimCodeCause(PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY, cause)

    /**
     * The T26 refusal code for [cause] (S18 i-a): `NO_CREDENTIAL` is `LOCK_UNAVAILABLE_NOT_SECURE`,
     * `UNSUPPORTED_API` is `LOCK_UNAVAILABLE_API`, every other cause (`HW_UNAVAILABLE`, `UNKNOWN`, and
     * `CHECK_FAILED` as `UNKNOWN`) is `LOCK_UNAVAILABLE_CAPABILITY`. Claimed once per process per (code, cause)
     * through [claimedCodeCauses]. Must be called with [stateLock] held.
     */
    private fun claimEnableRefusal(cause: DeviceAuthCapability): PendingDiagnostic? = when (cause) {
        DeviceAuthCapability.NO_CREDENTIAL -> claimCodeCause(PrivacyDiagnosticCode.LOCK_UNAVAILABLE_NOT_SECURE, cause)
        DeviceAuthCapability.UNSUPPORTED_API -> claimCodeCause(PrivacyDiagnosticCode.LOCK_UNAVAILABLE_API, cause)
        DeviceAuthCapability.CHECK_FAILED -> claimCapabilityCause(DeviceAuthCapability.UNKNOWN)
        else -> claimCapabilityCause(cause)
    }

    private fun claimCodeCause(code: PrivacyDiagnosticCode, cause: Enum<*>): PendingDiagnostic? =
        if (claimedCodeCauses.add(code to cause)) PendingDiagnostic(code, cause) else null

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
     * name only. The only metadata ever attached is either one bounded enum under the closed `CAUSE` key (the
     * `LOCK_AUTH_ERROR` bucket or the `LOCK_UNAVAILABLE_CAPABILITY` cause), or, for the deep-link codes
     * (`DL_HELD_LOCKED`, `DL_HELD_REPLACED`, `DL_HELD_EXPIRED`), the ready-made bounded host metadata under the
     * closed `HOST` key (the [DeepLinkHost] enums only, never link content). A diagnostic never carries both.
     *
     * The emitter's 60 s rate window is per code, not per bucket (F5): a second `LOCK_AUTH_ERROR` bucket
     * claimed here within 60 s of the first may be counted by the emitter but not written. This is accepted.
     */
    private fun record(diagnostic: PendingDiagnostic?) {
        if (diagnostic == null) return
        val metadata = diagnostic.metadata ?: diagnostic.cause?.let {
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
