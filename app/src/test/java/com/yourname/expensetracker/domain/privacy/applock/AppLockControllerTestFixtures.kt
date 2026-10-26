package com.yourname.expensetracker.domain.privacy.applock

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

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

    override fun current(): DeviceAuthCapability {
        evaluations++
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

    override suspend fun getLoadState(): AppLockSettingsLoadState {
        readCount++
        gate?.await()
        failure?.let { throw it }
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
}

internal object AppLockTestSettings {
    val DISABLED: AppLockSettingsLoadState = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings())

    fun enabled(timeout: AutoLockTimeout = AutoLockTimeout.M1): AppLockSettingsLoadState =
        AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = true, autoLockTimeout = timeout))

    fun unreadable(cause: SettingsUnreadableCause): AppLockSettingsLoadState =
        AppLockSettingsLoadState.Unreadable(cause)
}

/**
 * Wires one [AppLockController] to fakes. The scope runs on an unconfined test dispatcher so
 * fire-and-forget diagnostics are recorded in [emitted] as they are launched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AppLockControllerHarness(
    loadState: AppLockSettingsLoadState = AppLockTestSettings.DISABLED,
    capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE,
    scheduler: TestCoroutineScheduler = TestCoroutineScheduler()
) {
    val clock = FakeMonotonicClock()
    val capabilityProvider = FakeCapabilityProvider(capability)
    val repository = FakeAppLockSettingsRepository(loadState)
    val emitted = mutableListOf<PrivacyDiagnosticCode>()

    /** The metadata argument of each emit, in step with [emitted]. The controller must pass none (spec 10). */
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
    private val scope = CoroutineScope(
        SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> uncaught += t }
    )

    /** A new controller over the same fakes: a new process start (state `RESOLVING`). */
    fun newController(): AppLockController =
        AppLockController(repository, capabilityProvider, clock, emitter, scope)

    /** A new controller already resolved from the harness store (the usual start of a test). */
    fun resolvedController(): AppLockController =
        newController().also { it.onSettingsResolved(repository.loadState) }

    /** Taps Unlock and succeeds the attempt: the in-process auth success that is the only way to unlock. */
    fun unlock(controller: AppLockController) {
        val tap = controller.onUnlockTapped()
        assertTrue("expected an attempt, got $tap", tap is UnlockTapResult.Started)
        assertTrue(controller.onAuthSucceeded((tap as UnlockTapResult.Started).attemptId))
        assertEquals(AppLockState.Unlocked, controller.state.value)
    }
}
