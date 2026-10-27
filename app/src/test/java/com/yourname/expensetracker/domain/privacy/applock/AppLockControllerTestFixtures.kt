package com.yourname.expensetracker.domain.privacy.applock

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.coroutines.CoroutineContext

/** Controllable [MonotonicClock]: only the monotonic clock exists for the controller (AE-43). */
internal class FakeMonotonicClock(var nowMillis: Long = 1_000L) : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = nowMillis

    fun advance(millis: Long) {
        nowMillis += millis
    }
}

/** Controllable capability. Counts evaluations so tests can prove it is re-evaluated, not cached. */
internal class FakeCapabilityProvider(var capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE) :
    DeviceAuthCapabilityProvider {
    var evaluations = 0
        private set

    /** When set, `current` throws it after counting the evaluation (a failing binder read, S16 F4). */
    var failure: Throwable? = null

    override fun current(): DeviceAuthCapability {
        evaluations++
        failure?.let { throw it }
        return capability
    }
}

/**
 * In-memory settings store. The controller must only read: every write attempt is counted and refused,
 * so a test can prove that no controller path writes settings (INV-1, INV-46, AE-61).
 */
internal class FakeAppLockSettingsRepository(var loadState: AppLockSettingsLoadState) : AppLockSettingsRepository {
    var readCount = 0
        private set
    var writeAttempts = 0
        private set
    var failure: Throwable? = null
    private var gate: CompletableDeferred<Unit>? = null

    /** The next reads suspend until [releaseReads]. */
    fun holdReads() {
        gate = CompletableDeferred()
    }

    fun releaseReads() {
        gate?.complete(Unit)
    }

    /** One scripted read (S18 ii-c): the [result] it returns, after it waited for [gate] when one is set. */
    private class ScriptedRead(val result: AppLockSettingsLoadState, val gate: CompletableDeferred<Unit>?)

    private val scriptedReads = ArrayDeque<ScriptedRead>()

    /**
     * Queues the result of a LATER read, in call order (a scripted re-read, S18 ii-c). A read with a [gate] suspends on
     * it after it started, so a test can overlap two reads and finish them out of order. When the queue is empty a
     * read returns [loadState]. A test that sets a gate completes it in a `finally`.
     */
    fun scriptRead(result: AppLockSettingsLoadState, gate: CompletableDeferred<Unit>? = null) {
        scriptedReads.addLast(ScriptedRead(result, gate))
    }

    override suspend fun getLoadState(): AppLockSettingsLoadState {
        readCount++
        val scripted = scriptedReads.removeFirstOrNull()
        gate?.await()
        failure?.let { throw it }
        if (scripted != null) {
            scripted.gate?.await()
            return scripted.result
        }
        return loadState
    }

    override fun observeLoadState(): Flow<AppLockSettingsLoadState> = flowOf(loadState)

    override suspend fun setDiscreetNotifications(enabled: Boolean): AppLockSettingsWriteResult {
        writeAttempts++
        return AppLockSettingsWriteResult.Failed(AppLockSettingField.DISCREET)
    }

    override suspend fun reviewSave(record: AppLockPrivacySettings): AppLockSettingsWriteResult {
        writeAttempts++
        return AppLockSettingsWriteResult.Failed(AppLockSettingField.REVIEW_SAVE)
    }

    // --- S18 (i-a): the lock-flag write of the enable / disable apply. Counted apart from [writeAttempts], which
    // only the controller-must-not-write pins read (the controller itself still never writes).

    /** Every `setAppLockEnabled` call, in order. */
    val lockWrites = mutableListOf<Boolean>()

    /** When set, decides the result of a lock write; the default commits and updates [loadState]. */
    var lockWriteResult: ((Boolean) -> AppLockSettingsWriteResult)? = null

    /** When set, a lock write throws it (a non-IO failure of the store). */
    var lockWriteFailure: Throwable? = null

    /** When set, a lock write suspends on it before committing (a slow durable write). */
    var lockWriteGate: CompletableDeferred<Unit>? = null

    override suspend fun setAppLockEnabled(enabled: Boolean): AppLockSettingsWriteResult {
        lockWrites += enabled
        lockWriteGate?.await()
        lockWriteFailure?.let { throw it }
        lockWriteResult?.let { return it(enabled) }
        val base = when (val state = loadState) {
            is AppLockSettingsLoadState.Loaded -> state.settings
            is AppLockSettingsLoadState.FirstRunDefault -> state.settings
            is AppLockSettingsLoadState.Unreadable -> return AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE)
        }
        val next = base.copy(appLockEnabled = enabled)
        loadState = AppLockSettingsLoadState.Loaded(next)
        return AppLockSettingsWriteResult.Committed(next)
    }

    // --- S18 (i-b1): the timeout write. Counted apart from [writeAttempts] like the lock write.

    /** Every `setAutoLockTimeout` call, in order. */
    val timeoutWrites = mutableListOf<AutoLockTimeout>()

    /** When set, decides the result of a timeout write; the default commits and updates [loadState]. */
    var timeoutWriteResult: ((AutoLockTimeout) -> AppLockSettingsWriteResult)? = null

    /** When set, a timeout write throws it (a non-IO failure of the store). */
    var timeoutWriteFailure: Throwable? = null

    override suspend fun setAutoLockTimeout(timeout: AutoLockTimeout): AppLockSettingsWriteResult {
        timeoutWrites += timeout
        timeoutWriteFailure?.let { throw it }
        timeoutWriteResult?.let { return it(timeout) }
        val base = when (val state = loadState) {
            is AppLockSettingsLoadState.Loaded -> state.settings
            is AppLockSettingsLoadState.FirstRunDefault -> state.settings
            is AppLockSettingsLoadState.Unreadable -> return AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE)
        }
        val next = base.copy(autoLockTimeout = timeout)
        loadState = AppLockSettingsLoadState.Loaded(next)
        return AppLockSettingsWriteResult.Committed(next)
    }

    // --- S18 (i-b1) privacy review F1: the conditional (no-prompt) timeout write. It shares the hooks and the
    // [timeoutWrites] list of the unconditional write, and the default applies the same check as the real store.

    /** Every `setAutoLockTimeoutIfNotWeakening` call, in order (also recorded in [timeoutWrites]). */
    val conditionalTimeoutWrites = mutableListOf<AutoLockTimeout>()

    /** When set, a conditional write suspends on it after being recorded and before it decides (an overlap probe). */
    var conditionalWriteGate: CompletableDeferred<Unit>? = null

    override suspend fun setAutoLockTimeoutIfNotWeakening(timeout: AutoLockTimeout): AppLockSettingsWriteResult {
        conditionalTimeoutWrites += timeout
        timeoutWrites += timeout
        conditionalWriteGate?.await()
        timeoutWriteFailure?.let { throw it }
        timeoutWriteResult?.let { return it(timeout) }
        val base = when (val state = loadState) {
            is AppLockSettingsLoadState.Loaded -> state.settings
            is AppLockSettingsLoadState.FirstRunDefault -> state.settings
            is AppLockSettingsLoadState.Unreadable -> return AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE)
        }
        if (base.appLockEnabled && timeout.millis > base.autoLockTimeout.millis) {
            return AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.WOULD_WEAKEN)
        }
        val next = base.copy(autoLockTimeout = timeout)
        loadState = AppLockSettingsLoadState.Loaded(next)
        return AppLockSettingsWriteResult.Committed(next)
    }

    // --- S18 (i-b2): the forced-aware discreet and secure-screen writes. Counted apart from [writeAttempts]; the
    // default applies the same check as the real store (Unreadable, then FORCED while the stored lock is on).

    /** Every forced-aware plain write, in order: the field and the requested value. */
    val plainWrites = mutableListOf<Pair<AppLockSettingField, Boolean>>()

    /** When set, decides the result of a plain write; the default applies the store's rules and updates [loadState]. */
    var plainWriteResult: ((AppLockSettingField, Boolean) -> AppLockSettingsWriteResult)? = null

    /** When set, a plain write throws it (a non-IO failure of the store). */
    var plainWriteFailure: Throwable? = null

    /** When set, a plain write suspends on it after being recorded and before it decides (an overlap probe). */
    var plainWriteGate: CompletableDeferred<Unit>? = null

    override suspend fun setDiscreetNotificationsIfNotForced(enabled: Boolean): AppLockSettingsWriteResult =
        plainWrite(AppLockSettingField.DISCREET, enabled)

    override suspend fun setSecureScreenIfNotForced(enabled: Boolean): AppLockSettingsWriteResult =
        plainWrite(AppLockSettingField.SECURE_SCREEN, enabled)

    private suspend fun plainWrite(field: AppLockSettingField, enabled: Boolean): AppLockSettingsWriteResult {
        plainWrites += field to enabled
        plainWriteGate?.await()
        plainWriteFailure?.let { throw it }
        plainWriteResult?.let { return it(field, enabled) }
        val base = when (val state = loadState) {
            is AppLockSettingsLoadState.Loaded -> state.settings
            is AppLockSettingsLoadState.FirstRunDefault -> state.settings
            is AppLockSettingsLoadState.Unreadable -> return AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE)
        }
        if (base.appLockEnabled) return AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.FORCED)
        val next = if (field == AppLockSettingField.DISCREET) {
            base.copy(discreetNotifications = enabled)
        } else {
            base.copy(secureScreen = enabled)
        }
        loadState = AppLockSettingsLoadState.Loaded(next)
        return AppLockSettingsWriteResult.Committed(next)
    }
}

internal object AppLockTestSettings {
    val DISABLED: AppLockSettingsLoadState = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings())

    fun enabled(timeout: AutoLockTimeout = AutoLockTimeout.M1): AppLockSettingsLoadState =
        AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = timeout))

    fun unreadable(cause: SettingsUnreadableCause): AppLockSettingsLoadState =
        AppLockSettingsLoadState.Unreadable(cause)
}

/**
 * Stands in for the main dispatcher. Work dispatched to it is queued on the test scheduler and runs on
 * [TestCoroutineScheduler.runCurrent] (see [AppLockControllerHarness.settle]), and [isOnMain] is true only
 * while such a task runs, so a test can prove which calls hop to "main" (S16 P5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MarkingMainDispatcher(scheduler: TestCoroutineScheduler) : CoroutineDispatcher() {
    private val delegate = StandardTestDispatcher(scheduler)
    private var depth = 0

    /** How many tasks were dispatched here in total. */
    var dispatched = 0
        private set

    val isOnMain: Boolean get() = depth > 0

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatched++
        delegate.dispatch(
            context,
            Runnable {
                depth++
                try {
                    block.run()
                } finally {
                    depth--
                }
            }
        )
    }
}

/**
 * The prompt adapter stand-in (S15 contract). [authenticate] answers with the queued [AuthLaunch] values
 * (default LAUNCHED), keeps the controller-owned callback of every call, and can deliver an outcome
 * re-entrantly inside the call. [cancel] of the in-flight attempt delivers `Failed(SYSTEM_CANCELED)`
 * synchronously inside the call, as the real adapter does. Stale ids are ignored by [cancel].
 */
internal class FakeDeviceAuthenticator(private val isOnMain: () -> Boolean = { true }) : DeviceAuthenticator {

    class Call(
        val attemptId: Long,
        val copy: AuthPromptCopy,
        val callback: DeviceAuthCallback,
        val onMain: Boolean
    )

    val calls = mutableListOf<Call>()
    val cancelCalls = mutableListOf<Long>()
    val cancelOnMain = mutableListOf<Boolean>()

    private val queuedResults = ArrayDeque<AuthLaunch>()

    /** The answer when nothing is queued. */
    var defaultResult: AuthLaunch = AuthLaunch.LAUNCHED

    /** When set, delivered synchronously inside `authenticate` after a LAUNCHED answer was decided. */
    var reentrantOutcome: DeviceAuthOutcome? = null

    /** When true (the real adapter), `cancel` of the in-flight attempt echoes `Failed(SYSTEM_CANCELED)`. */
    var echoOnCancel = true

    /** Hook run at the start of `authenticate` (for example to probe the controller's monitor). */
    var onAuthenticate: ((Long) -> Unit)? = null

    /** Hook run at the start of `cancel`, before any echo. */
    var onCancel: ((Long) -> Unit)? = null

    /** When set, `authenticate` throws it after recording the call. */
    var throwOnAuthenticate: Exception? = null

    private var inFlightCall: Call? = null

    val hasInFlight: Boolean get() = inFlightCall != null

    fun queue(vararg results: AuthLaunch) {
        queuedResults.addAll(results.toList())
    }

    override fun authenticate(attemptId: Long, copy: AuthPromptCopy, callback: DeviceAuthCallback): AuthLaunch {
        val call = Call(attemptId, copy, callback, isOnMain())
        calls += call
        onAuthenticate?.invoke(attemptId)
        throwOnAuthenticate?.let { throw it }
        val result = if (queuedResults.isEmpty()) defaultResult else queuedResults.removeFirst()
        if (result != AuthLaunch.LAUNCHED) return result
        inFlightCall = call
        reentrantOutcome?.let { outcome ->
            inFlightCall = null
            callback.onOutcome(attemptId, outcome)
        }
        return result
    }

    override fun cancel(attemptId: Long) {
        cancelCalls += attemptId
        cancelOnMain += isOnMain()
        onCancel?.invoke(attemptId)
        val call = inFlightCall
        if (call == null || call.attemptId != attemptId) return
        inFlightCall = null
        if (echoOnCancel) {
            call.callback.onOutcome(attemptId, DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED))
        }
    }

    /** Delivers [outcome] to the callback of [call] (default: the latest call) with that call's own id. */
    fun deliver(outcome: DeviceAuthOutcome, call: Call = calls.last()) {
        if (inFlightCall === call) inFlightCall = null
        call.callback.onOutcome(call.attemptId, outcome)
    }

    /** Delivers [outcome] to the callback of [call] (default: the latest call) but with a different id. */
    fun deliverWithId(id: Long, outcome: DeviceAuthOutcome, call: Call = calls.last()) {
        call.callback.onOutcome(id, outcome)
    }
}

/**
 * Wires one [AppLockController] to fakes. The scope runs on an unconfined test dispatcher so
 * fire-and-forget diagnostics are recorded in [emitted] as they are launched. The "main" dispatcher is
 * queued: the prompt calls of the controller run only after [settle] (or [advance]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AppLockControllerHarness(
    loadState: AppLockSettingsLoadState = AppLockTestSettings.DISABLED,
    capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE,
    val scheduler: TestCoroutineScheduler = TestCoroutineScheduler()
) {
    val clock = FakeMonotonicClock()
    val capabilityProvider = FakeCapabilityProvider(capability)
    val repository = FakeAppLockSettingsRepository(loadState)
    val emitted = mutableListOf<PrivacyDiagnosticCode>()
    val main = MarkingMainDispatcher(scheduler)
    val authenticator = FakeDeviceAuthenticator { main.isOnMain }

    /**
     * The metadata argument of each emit, in step with [emitted]. Only LOCK_AUTH_ERROR and
     * LOCK_UNAVAILABLE_CAPABILITY carry a `CAUSE` (the bounded bucket or capability enum name); the others are empty.
     */
    val emittedMetadata = mutableListOf<PrivacyDiagnosticMetadata>()

    /** When set, the emitter throws it. A diagnostic failure must never change a transition (INV-37). */
    var emitFailure: Throwable? = null

    /** When true, the emitter records the call and then suspends until cancelled (a stuck sink, INV-37). */
    var emitSuspendsForever = false

    /** Throwables that reached the scope's exception handler: the controller must let none escape. */
    val uncaught = mutableListOf<Throwable>()

    private val emitter = mockk<PrivacyDiagnosticsEmitter>().also { mock ->
        coEvery { mock.emit(any(), any()) } coAnswers {
            emitted += firstArg<PrivacyDiagnosticCode>()
            emittedMetadata += secondArg<PrivacyDiagnosticMetadata>()
            if (emitSuspendsForever) awaitCancellation()
            val failure = emitFailure
            if (failure != null) throw failure
        }
    }
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(
        scopeJob + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> uncaught += t }
    )

    /** Coroutines of the controller's scope that are still running (watchdog, launch, stuck emits). */
    val activeJobs: Int get() = scopeJob.children.count { it.isActive }

    /**
     * A snapshot of the children of the controller's scope that are running right now (right after a tap:
     * the prompt orchestrator and the watchdog). Keep the jobs and inspect them after [settle] to see how
     * each one ended; [activeJobs] only counts the ones still running.
     */
    fun activeControllerJobs(): List<Job> = scopeJob.children.filter { it.isActive }.toList()

    /**
     * Cancels every running child of the controller's scope (launch coroutine, watchdog, ...) without
     * cancelling the scope itself: a foreign cancellation of the controller's work (F7).
     */
    fun cancelControllerJobs() {
        scopeJob.children.toList().forEach { it.cancel() }
    }

    /** A new controller over the same fakes: a new process start (state `RESOLVING`). */
    fun newController(): AppLockController =
        AppLockController(repository, capabilityProvider, clock, emitter, scope, authenticator, main)

    /** A new controller already resolved from the harness store through the real [AppLockController.resolve]. */
    fun resolvedController(): AppLockController =
        newController().also { controller -> runBlocking { controller.resolve() } }

    /** Runs the work queued on the "main" dispatcher (the prompt calls) that is due now. */
    fun settle() {
        scheduler.runCurrent()
    }

    /** Advances virtual time (watchdog, launch retries) by [millis] and runs everything that became due. */
    fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    /** Taps Unlock, lets the prompt launch, and returns the attempt id. */
    fun tap(controller: AppLockController): Long {
        val result = controller.onUnlockTapped()
        assertTrue("expected an attempt, got $result", result is UnlockTapResult.Started)
        settle()
        return (result as UnlockTapResult.Started).attemptId
    }

    /** Taps Unlock and succeeds the attempt through the callback: the only way to unlock. */
    fun unlock(controller: AppLockController) {
        val attemptId = tap(controller)
        assertEquals(attemptId, authenticator.calls.last().attemptId)
        authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    /**
     * Starts an open-origin attempt through the S18-facing API, lets the prompt launch, and returns the attempt id.
     * The no-op change of S16 is the [handler]: it only records that a success was handed over.
     */
    fun startOpenOrigin(
        controller: AppLockController,
        prompt: OpenOriginPrompt,
        handler: RecordingSuccessHandler
    ): Long {
        val result = controller.startOpenOriginAttempt(prompt, handler)
        assertTrue("expected an attempt, got $result", result is OpenOriginStartResult.Started)
        settle()
        return (result as OpenOriginStartResult.Started).attemptId
    }
}

/**
 * [AppLockSettingsOperations] wired to one [AppLockController] over the shared fakes (S18 part (i-a)). The ONE place
 * the operations are constructed in tests (S18 ii-a): a test passes a shared [serializer] to prove the sharing with
 * another writer, otherwise each rig has its own.
 * With [deferred] false the apply runs inside the hand-off call (an unconfined scope); with true it is queued on
 * the test scheduler and runs on `h.settle()`, so a test can see that the handler itself does not wait for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SettingsOperationsRig(
    val h: AppLockControllerHarness,
    val controller: AppLockController,
    deferred: Boolean = false,
    val serializer: AppLockSettingsWriteSerializer = AppLockSettingsWriteSerializer()
) {
    /** Throwables that reached the operations scope's exception handler. */
    val uncaught = mutableListOf<Throwable>()

    /** Every `DiscreetRepostTrigger.onCommittedWrite(was, is)` call. */
    val triggerCalls = mutableListOf<Pair<Boolean, Boolean>>()

    /** When set, the trigger throws it. */
    var triggerFailure: Throwable? = null

    val job = SupervisorJob()
    private val dispatcher: CoroutineDispatcher =
        if (deferred) StandardTestDispatcher(h.scheduler) else UnconfinedTestDispatcher(h.scheduler)
    val scope = CoroutineScope(job + dispatcher + CoroutineExceptionHandler { _, t -> uncaught += t })

    private val trigger = mockk<DiscreetRepostTrigger>().also { mock ->
        coEvery { mock.onCommittedWrite(any(), any()) } coAnswers {
            triggerCalls += firstArg<Boolean>() to secondArg<Boolean>()
            triggerFailure?.let { throw it }
            DiscreetRepostOutcome.SWEPT
        }
    }

    val ops = AppLockSettingsOperations(controller, h.repository, h.capabilityProvider, trigger, serializer, scope)
}

/** The stand-in for the S18 apply: it records each success it was handed and applies nothing. */
internal class RecordingSuccessHandler : OpenOriginSuccessHandler {
    val delivered = mutableListOf<Pair<Long, AuthPurpose>>()

    /** When set, thrown from the hand-off (a failing S18 handler must not affect the controller). */
    var failure: Exception? = null

    override fun onSucceeded(attemptId: Long, purpose: AuthPurpose) {
        delivered += attemptId to purpose
        failure?.let { throw it }
    }
}

/**
 * A harness and a resolved controller in `UNLOCKED` (lock on, [timeout]), ready for a CONFIRM_CHANGE attempt.
 * The app is reported in the foreground, as S20 would after its first ON_START.
 */
internal fun unlockedControllerFor(
    timeout: AutoLockTimeout = AutoLockTimeout.M1
): Pair<AppLockControllerHarness, AppLockController> {
    val h = AppLockControllerHarness(AppLockTestSettings.enabled(timeout))
    val controller = h.resolvedController()
    h.unlock(controller)
    controller.onAppForegrounded()
    assertEquals(AppLockState.Unlocked, controller.state.value)
    return h to controller
}

/** A harness and a resolved controller in `DISABLED` (lock off), ready for an ENABLE attempt. */
internal fun disabledControllerFor(
    capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE
): Pair<AppLockControllerHarness, AppLockController> {
    val h = AppLockControllerHarness(AppLockTestSettings.DISABLED, capability)
    val controller = h.resolvedController()
    controller.onAppForegrounded()
    assertEquals(AppLockState.Disabled, controller.state.value)
    return h to controller
}

/**
 * A harness and a resolved controller that sits in `LOCKED([cause])`, ready for an Unlock tap:
 * COLD_START from an enabled store, SETTINGS_UNREADABLE from a corrupted store, TIMEOUT after an unlock,
 * a background and a foreground under the IMMEDIATE timeout.
 */
internal fun lockedControllerFor(
    cause: LockCause,
    capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE
): Pair<AppLockControllerHarness, AppLockController> {
    val settings = when (cause) {
        LockCause.COLD_START, LockCause.TIMEOUT -> AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE)
        LockCause.SETTINGS_UNREADABLE -> AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED)
    }
    val h = AppLockControllerHarness(settings, capability)
    val controller = h.resolvedController()
    if (cause == LockCause.TIMEOUT) {
        h.unlock(controller)
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
    }
    assertEquals(AppLockState.Locked(cause), controller.state.value)
    return h to controller
}

/**
 * Drives [AppLockController.resolve] to the controller's own RESOLVE_TIMEOUT fallback: the read is held and
 * T_resolve passes on the test scheduler. The read stays held; set `h.repository.loadState`, call
 * `h.repository.releaseReads()` and `runCurrent()` to deliver the late result (T6b). Returns the resolve job.
 * [h] must have been built with this test's `testScheduler`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.resolveTimedOut(h: AppLockControllerHarness, controller: AppLockController): Job {
    h.repository.holdReads()
    val resolving = launch { controller.resolve() }
    runCurrent()
    advanceTimeBy(AppLockController.RESOLVE_TIMEOUT_MILLIS + 1)
    runCurrent()
    return resolving
}
