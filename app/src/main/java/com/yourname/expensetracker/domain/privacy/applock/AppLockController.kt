package com.yourname.expensetracker.domain.privacy.applock

import androidx.annotation.VisibleForTesting
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.diagnostics.DiagnosticThrowableSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
     * T7: the state moved to `AUTHENTICATING(attemptId, UNLOCK)`. The system prompt for [attemptId] is
     * NOT launched by the controller in part (i); the launch, its bounded result mapping and the
     * outcome events other than success belong to part (ii).
     */
    data class Started(val attemptId: Long) : UnlockTapResult

    /** A prompt is already in flight (INV-34). Nothing changed and no second attempt exists (AE-46). */
    data object AlreadyInFlight : UnlockTapResult

    /** The state is not `LOCKED`, so no prompt may start (spec 3.3: not from INERT, RESOLVING or an open state). */
    data object NotLocked : UnlockTapResult

    /** T7b: the capability re-check failed, so the state became `INERT([reason])` and no prompt starts. */
    data class BecameInert(val reason: InertReason) : UnlockTapResult
}

/**
 * The process-level app-lock controller and the ONLY writer of [AppLockState] (INV-24, spec 2.4).
 *
 * S16 part (i) covers: resolution from the settings store (T1 to T6, T6b), the inert escapes (T4, T6,
 * T7b, T15, T16), the foreground lock evaluation (T13 to T15) with pending-background consumption (spec
 * 3.4, AMD-22), and the UNLOCK start and success (T7, T8). Everything else, marked `TODO(S16-ii)` below,
 * belongs to part (ii) and to later slices (S17 held link, S18 settings operations).
 *
 * ## Ports only
 *
 * The controller reads the settings store (a DataStore, never Room, INV-28), the device capability, the
 * monotonic clock and the diagnostics front door. It has no Android type, no barrier and no operational
 * lock input: workers, receivers and services cannot reach it (INV-36, AE-68) and locking never touches
 * backup or restore (INV-28, AE-63).
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
 *
 * ## Concurrency
 *
 * Every transition runs under one monitor and never suspends. Capability is read before the monitor is
 * taken, so no binder call runs under it. Diagnostics are fire and forget on the injected scope (INV-37):
 * they never block or delay a transition, and `CancellationException` is never swallowed. Each code is
 * emitted at most once per process (spec 10, once per process per cause): the first transition that
 * produces a code claims it under the monitor and later ones stay silent. The emitter's own rate limit
 * remains as a backstop.
 *
 * ## Time
 *
 * Only [MonotonicClock] (`elapsedRealtime`) is read. There is no wall-clock input, so a clock change
 * cannot move the timer (AE-43, FG-17).
 */
@Singleton
class AppLockController @Inject constructor(
    private val settingsRepository: AppLockSettingsRepository,
    private val capabilityProvider: DeviceAuthCapabilityProvider,
    private val clock: MonotonicClock,
    private val diagnostics: PrivacyDiagnosticsEmitter,
    @ApplicationScope private val scope: CoroutineScope
) {

    private enum class ForegroundTrigger { APP_FOREGROUNDED, LOCK_QUERY }

    /** The attempt that is open. Part (ii) extends this with the prompt copy and `promptBackgroundAt`. */
    private class InFlightAttempt(
        val attemptId: Long,
        val purpose: AuthPurpose,
        /** For [AuthPurpose.UNLOCK]: the cause of the `LOCKED` state it returns to (T9). Null otherwise. */
        val originLockCause: LockCause?
    )

    private val stateLock = Any()
    private val mutableState = MutableStateFlow<AppLockState>(AppLockState.Resolving) // T1
    private val resolveStarted = AtomicBoolean(false)

    // Guarded by stateLock.
    private var loadState: AppLockSettingsLoadState? = null
    private var resolvedByTimeout = false
    private var lastBackgroundedAtMillis: Long? = null
    private var inFlight: InFlightAttempt? = null
    private var lastIssuedAttemptId = 0L
    private val claimedDiagnosticCodes: MutableSet<PrivacyDiagnosticCode> =
        EnumSet.noneOf(PrivacyDiagnosticCode::class.java)

    /** The one process-level state. Starts at [AppLockState.Resolving]. */
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

    /**
     * `SETTINGS_RESOLVED(loadState)`: T2 to T6. Ignored unless the state is `RESOLVING`. Test seam only:
     * [resolve] is the one production entry.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun onSettingsResolved(loadState: AppLockSettingsLoadState) {
        resolveFrom(loadState)
    }

    /**
     * `RESOLVE_TIMEOUT`: T5 or T6 with the fail-closed record. Ignored unless the state is `RESOLVING`.
     * Test seam only: [resolve] is the one production entry.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun onResolveTimeout() {
        resolveFrom(null)
    }

    /**
     * `LATE_SETTINGS_RESOLVED(loadState)` (T6b). Moves `LOCKED(SETTINGS_UNREADABLE)` to `DISABLED` only when:
     * the fallback was the controller's own `RESOLVE_TIMEOUT` (never a `CORRUPTED`, `IO_ERROR` or
     * `INVALID_VALUE` read), the state is exactly that `LOCKED` (so not while `AUTHENTICATING(UNLOCK)`,
     * AMD-22), and the late read is a readable state with the lock off. Anything else is dropped.
     * Test seam only: [resolve] is the one production entry.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun onLateSettingsResolved(loadState: AppLockSettingsLoadState) {
        synchronized(stateLock) {
            if (!resolvedByTimeout) return
            val current = mutableState.value
            if (current != AppLockState.Locked(LockCause.SETTINGS_UNREADABLE)) return
            val readableAndOff = when (loadState) {
                is AppLockSettingsLoadState.Loaded -> !loadState.settings.appLockEnabled
                is AppLockSettingsLoadState.FirstRunDefault -> !loadState.settings.appLockEnabled
                is AppLockSettingsLoadState.Unreadable -> false
            }
            if (!readableAndOff) return
            this.loadState = loadState
            resolvedByTimeout = false
            lastBackgroundedAtMillis = null
            mutableState.value = AppLockState.Disabled
            // TODO(S17): apply the held link (T6b side effect "apply held link" is shared with T2).
        }
    }

    // --- Background and foreground (T13 to T16, spec 3.4) ---------------------------------------

    /**
     * `APP_BACKGROUNDED` (T13): in `UNLOCKED` and `INERT` the first background is recorded and kept
     * until a foreground evaluation consumes it. Other states record nothing.
     */
    fun onAppBackgrounded() {
        synchronized(stateLock) {
            val current = mutableState.value
            if (current !is AppLockState.Unlocked && current !is AppLockState.Inert) {
                // TODO(S16-ii): T32 for AUTHENTICATING(ENABLE | CONFIRM_CHANGE): record promptBackgroundAt.
                return
            }
            if (lastBackgroundedAtMillis == null) {
                lastBackgroundedAtMillis = clock.elapsedRealtimeMillis()
            }
        }
    }

    /** `APP_FOREGROUNDED` (T14, T15, T16). Consumes the pending background. */
    fun onAppForegrounded() {
        evaluateForeground(ForegroundTrigger.APP_FOREGROUNDED)
    }

    /**
     * `LOCK_QUERY` (spec 3.4, INV-31): the same pending-timeout evaluation as a foreground (T14, T15,
     * but not T16), so link handling cannot apply a link when the lock should engage whichever of
     * `onNewIntent` and `onStart` runs first. Returns the state after the evaluation.
     */
    fun onLockQuery(): AppLockState = evaluateForeground(ForegroundTrigger.LOCK_QUERY)

    // --- Unlock (T7, T7b, T8) -------------------------------------------------------------------

    /**
     * `UNLOCK_TAPPED` (T7, T7b), `DEVICE_AUTH` only. From `LOCKED`:
     * - capability not `AVAILABLE` on re-check: `INERT(reason)` and no prompt (T7b);
     * - otherwise a new, strictly increasing attempt id and `AUTHENTICATING(id, UNLOCK)` (T7).
     *
     * From `AUTHENTICATING` it reports [UnlockTapResult.AlreadyInFlight] and changes nothing; from any
     * other state it reports [UnlockTapResult.NotLocked] (no prompt from `INERT`, `RESOLVING` or an open
     * state, spec 3.3).
     */
    fun onUnlockTapped(): UnlockTapResult {
        val before = mutableState.value
        if (before is AppLockState.Authenticating) return UnlockTapResult.AlreadyInFlight
        if (before !is AppLockState.Locked) return UnlockTapResult.NotLocked
        val capability = capabilityProvider.current()
        val inertReason = capability.toInertReason()
        var code: PrivacyDiagnosticCode? = null
        val result: UnlockTapResult = synchronized(stateLock) {
            val current = mutableState.value
            if (current is AppLockState.Authenticating) return@synchronized UnlockTapResult.AlreadyInFlight
            if (current !is AppLockState.Locked) return@synchronized UnlockTapResult.NotLocked
            // TODO(S16-ii): PIN mode ([2]) has no capability re-check; effective mode is DEVICE_AUTH only here.
            if (inertReason != null) {
                code = enterInert(inertReason)
                return@synchronized UnlockTapResult.BecameInert(inertReason)
            }
            val attemptId = ++lastIssuedAttemptId
            inFlight = InFlightAttempt(attemptId, AuthPurpose.UNLOCK, current.cause)
            mutableState.value = AppLockState.Authenticating(attemptId, AuthPurpose.UNLOCK)
            UnlockTapResult.Started(attemptId)
        }
        // TODO(S16-ii): launch the prompt here, after the monitor is released: record the attempt as open
        //  BEFORE calling DeviceAuthenticator.authenticate, map each non-LAUNCHED AuthLaunch to a bounded
        //  result, retry AWAITING_PRIOR_TERMINAL, own the watchdog, and tolerate a re-entrant outcome
        //  (S15 carry-forwards). Never call authenticate under stateLock; the outcome callback re-enters
        //  the public transitions (cancel delivers Failed(SYSTEM_CANCELED) synchronously).
        record(code)
        return result
    }

    /**
     * `AUTH_SUCCEEDED(attemptId)` for an UNLOCK attempt (T8). Only a success whose id matches the open
     * attempt AND whose purpose is UNLOCK unlocks (INV-34, spec 3.3). A stale, unknown, repeated or
     * cross-purpose id is ignored (T12) and changes nothing. Returns true when the success was applied.
     *
     * A success that ends in an open state clears the pending background (spec 3.4, AE-42).
     *
     * Not a production entry on its own: part (ii) routes the prompt outcome through a controller-owned
     * callback, so no other class calls this.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun onAuthSucceeded(attemptId: Long): Boolean {
        var code: PrivacyDiagnosticCode? = null
        val applied = synchronized(stateLock) {
            val attempt = inFlight ?: return@synchronized false
            val current = mutableState.value
            if (current != AppLockState.Authenticating(attemptId, AuthPurpose.UNLOCK)) return@synchronized false
            if (attempt.attemptId != attemptId || attempt.purpose != AuthPurpose.UNLOCK) return@synchronized false
            // TODO(S16-ii / S18): ENABLE and CONFIRM_CHANGE successes apply their owning row (T33), never here.
            inFlight = null
            lastBackgroundedAtMillis = null
            mutableState.value = AppLockState.Unlocked
            code = claim(PrivacyDiagnosticCode.LOCK_UNLOCKED)
            // TODO(S17): apply the held link once after an unlock (T8 side effect).
            true
        }
        record(code)
        return applied
    }

    // TODO(S16-ii): onAuthCancelled / onAuthError (T9, T10, T11, T12 for non-success outcomes), the
    //  PROMPT_HOST_LOST event (T35), the ENABLE / CONFIRM_CHANGE start API (S18 binds it), T32 / T33 / T34
    //  and their promptBackgroundAt handoff, and the LOCK_AUTH_* diagnostics. Until they exist an UNLOCK
    //  attempt can end only by success, so the controller must not be wired to a consumer before part (ii).
    // TODO(S18): SETTINGS_REREAD (T28, T29), ENABLE / DISABLE / WEAKENING requests (T17 to T19, T26, T27),
    //  and LockNotice derivation (INV-51).
    // TODO(S17): DEEP_LINK_RECEIVED (T20, T21), the held link and its TTL.

    // --- Internals ------------------------------------------------------------------------------

    private fun resolveFrom(read: AppLockSettingsLoadState?) {
        if (mutableState.value !is AppLockState.Resolving) return
        val capability = capabilityProvider.current()
        val code = synchronized(stateLock) {
            if (mutableState.value !is AppLockState.Resolving) return
            applyResolution(read, capability)
        }
        record(code)
    }

    /** Applies T2 to T6 under the monitor. A null [read] is the controller's `RESOLVE_TIMEOUT`. */
    private fun applyResolution(read: AppLockSettingsLoadState?, capability: DeviceAuthCapability): PrivacyDiagnosticCode? {
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
                mutableState.value = AppLockState.Disabled
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
        val before = mutableState.value
        if (before !is AppLockState.Unlocked && before !is AppLockState.Inert) return before
        val capability = capabilityProvider.current()
        val code = synchronized(stateLock) { evaluateForegroundLocked(trigger, capability) }
        record(code)
        return mutableState.value
    }

    /** T14, T15, T16 under the monitor, for `UNLOCKED` and `INERT` only. */
    private fun evaluateForegroundLocked(trigger: ForegroundTrigger, capability: DeviceAuthCapability): PrivacyDiagnosticCode? {
        val current = mutableState.value
        if (current !is AppLockState.Unlocked && current !is AppLockState.Inert) return null
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
            if (current is AppLockState.Inert) mutableState.value = AppLockState.Unlocked
            return null
        }

        // The lock cannot protect now (or is off). A foreground consumes the pending background; a
        // LOCK_QUERY does not, so a transient capability loss at query time cannot erase the time away.
        if (trigger == ForegroundTrigger.APP_FOREGROUNDED) {
            lastBackgroundedAtMillis = null
            // T16: enabled, DEVICE_AUTH, capability not AVAILABLE.
            if (current is AppLockState.Unlocked && settings.appLockEnabled && inertReason != null) {
                return enterInert(inertReason)
            }
        }
        return null
    }

    /** Returns the diagnostic to record for this transition: the code the first time, null after (spec 10). */
    private fun enterLocked(cause: LockCause): PrivacyDiagnosticCode? {
        mutableState.value = AppLockState.Locked(cause)
        return claim(
            when (cause) {
                LockCause.COLD_START -> PrivacyDiagnosticCode.LOCK_SHOWN_COLD_START
                LockCause.TIMEOUT -> PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT
                LockCause.SETTINGS_UNREADABLE -> PrivacyDiagnosticCode.LOCK_SHOWN_SETTINGS_UNREADABLE
            }
        )
    }

    /** Returns the diagnostic to record for this transition: the code the first time, null after (spec 10). */
    private fun enterInert(reason: InertReason): PrivacyDiagnosticCode? {
        mutableState.value = AppLockState.Inert(reason)
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
    private fun claim(code: PrivacyDiagnosticCode): PrivacyDiagnosticCode? =
        if (claimedDiagnosticCodes.add(code)) code else null

    /** Maps the capability to the inert reason, or null when the device can authenticate. */
    private fun DeviceAuthCapability.toInertReason(): InertReason? = when (this) {
        DeviceAuthCapability.AVAILABLE -> null
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
     * Fire and forget (INV-37): never blocks or delays a transition. [code] is already claimed under the
     * monitor by the transition, so this only launches (null means a repeat: nothing to emit). The emitter is
     * best effort; `CancellationException` is rethrown and any other failure is logged by class name only.
     */
    private fun record(code: PrivacyDiagnosticCode?) {
        if (code == null) return
        scope.launch {
            try {
                diagnostics.emit(code)
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
    }
}
