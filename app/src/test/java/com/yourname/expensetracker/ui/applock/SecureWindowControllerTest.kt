package com.yourname.expensetracker.ui.applock

import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.AuthPurpose
import com.yourname.expensetracker.domain.privacy.applock.InertReason
import com.yourname.expensetracker.domain.privacy.applock.LockCause
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * SecureWindowController (S20-b, INV-29, INV-53, AE-113, AE-114). Plain JVM: the window is a recording sink, the
 * committed settings are a scripted flow and the controller state is a scripted StateFlow. `follow` runs on the
 * test's standard dispatcher and every step is settled with `runCurrent()`, so each emission is observed on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecureWindowControllerTest {

    private class RecordingSink : WindowFlagSink {
        val calls = mutableListOf<Boolean>()

        /** The flag as the window would hold it. */
        var current: Boolean? = null
            private set

        override fun setSecure(secure: Boolean) {
            calls += secure
            current = secure
        }
    }

    private companion object {
        const val BACKOFF_MS = 1_000L
    }

    private val lockOff =AppLockSettingsLoadState.Loaded(AppLockPrivacySettings())
    private val lockOn = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = true))
    private val secureOnly = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(secureScreen = true))

    private fun controllerOver(
        committed: Flow<AppLockSettingsLoadState>,
        state: MutableStateFlow<AppLockState>
    ): SecureWindowController {
        val repository = mockk<AppLockSettingsRepository>()
        every { repository.observeLoadState() } returns committed
        return controllerWith(repository, state)
    }

    /** The re-subscription backoff is a constant [BACKOFF_MS] unless [backoff] says otherwise (virtual time). */
    private fun controllerWith(
        repository: AppLockSettingsRepository,
        state: MutableStateFlow<AppLockState>,
        backoff: (Int) -> Long = { BACKOFF_MS }
    ): SecureWindowController {
        val controller = mockk<AppLockController>()
        every { controller.state } returns state
        return SecureWindowController(repository, controller, backoff)
    }

    /** Moves the virtual clock to the absolute time [millis] and settles everything due. */
    private fun TestScope.advanceTo(millis: Long) {
        advanceTimeBy(millis - currentTime)
        runCurrent()
    }

    /** Applies the initial flag, starts following, settles, and returns the follower job (cancelled with the test). */
    private fun TestScope.start(subject: SecureWindowController, sink: RecordingSink): Job {
        subject.applyInitial(sink)
        val job = backgroundScope.launch { subject.follow(sink) }
        runCurrent()
        return job
    }

    /** One committed emission, settled. */
    private fun TestScope.commit(committed: MutableSharedFlow<AppLockSettingsLoadState>, value: AppLockSettingsLoadState) {
        committed.tryEmit(value)
        runCurrent()
    }

    /** One controller state change, settled. */
    private fun TestScope.moveTo(state: MutableStateFlow<AppLockState>, value: AppLockState) {
        state.value = value
        runCurrent()
    }

    private fun committedFlow(initial: AppLockSettingsLoadState): MutableSharedFlow<AppLockSettingsLoadState> =
        MutableSharedFlow<AppLockSettingsLoadState>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            .also { it.tryEmit(initial) }

    // --- applyInitial

    @Test
    fun applyInitial_setsTheFlagSynchronously_beforeAnyStateIsRead() {
        val sink = RecordingSink()
        val neverRead = mockk<AppLockSettingsRepository>() // no stubbing: any read would throw
        val controller = mockk<AppLockController>() // no stubbing: any read would throw
        SecureWindowController(neverRead, controller).applyInitial(sink)

        assertEquals(listOf(true), sink.calls)
        assertEquals(true, sink.current)
    }

    // --- the pure decision

    @Test
    fun decide_isTheEffectiveSecureScreenRule_forEveryLoadStateAndResolvingValue() {
        val unreadable = SettingsUnreadableCause.entries.map { AppLockSettingsLoadState.Unreadable(it) }
        for (resolving in listOf(true, false)) {
            assertTrue(SecureWindowController.decide(null, resolving))
            unreadable.forEach { assertTrue(SecureWindowController.decide(it, resolving)) }
            assertTrue(SecureWindowController.decide(lockOn, resolving))
            assertTrue(SecureWindowController.decide(secureOnly, resolving))
        }
        assertTrue(SecureWindowController.decide(lockOff, resolving = true))
        assertFalse(SecureWindowController.decide(lockOff, resolving = false))
        assertFalse(SecureWindowController.decide(AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings()), false))
    }

    // --- RESOLVING, Unreadable, null and error all give "set"

    @Test
    fun resolving_keepsTheFlagSet_evenWithACommittedLockOffState_andClearsWhenResolutionEnds() = runTest {
        val state = MutableStateFlow<AppLockState>(AppLockState.Resolving)
        val sink = RecordingSink()
        start(controllerOver(committedFlow(lockOff), state), sink)

        assertEquals(true, sink.current)
        assertFalse(sink.calls.contains(false))

        moveTo(state, AppLockState.Disabled)
        assertEquals(false, sink.current)
    }

    @Test
    fun unreadable_everyCause_givesSet() = runTest {
        for (cause in SettingsUnreadableCause.entries) {
            val sink = RecordingSink()
            start(
                controllerOver(committedFlow(AppLockSettingsLoadState.Unreadable(cause)), MutableStateFlow(AppLockState.Disabled)),
                sink
            )
            assertEquals("cause $cause", true, sink.current)
            assertFalse("cause $cause", sink.calls.contains(false))
        }
    }

    @Test
    fun unreadable_afterAReadableState_setsTheFlagAgain() = runTest {
        val committed = committedFlow(lockOff)
        val sink = RecordingSink()
        start(controllerOver(committed, MutableStateFlow(AppLockState.Disabled)), sink)
        assertEquals(false, sink.current)

        commit(committed, AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR))
        assertEquals(true, sink.current)
    }

    @Test
    fun absentState_neverClears_theFlagStaysAsApplyInitialSetIt() = runTest {
        val absent = flow<AppLockSettingsLoadState> { awaitCancellation() }
        val sink = RecordingSink()
        start(controllerOver(absent, MutableStateFlow(AppLockState.Disabled)), sink)

        assertEquals(listOf(true), sink.calls) // only applyInitial; the follower wrote nothing
        assertEquals(true, sink.current)
    }

    @Test
    fun nullState_isFailClosed_inTheDecision() {
        assertTrue(SecureWindowController.decide(null, resolving = false))
    }

    @Test
    fun flowError_beforeAnyEmission_givesSet() = runTest {
        val failing = flow<AppLockSettingsLoadState> { throw IOException("read failed") }
        val sink = RecordingSink()
        val job = start(controllerOver(failing, MutableStateFlow(AppLockState.Disabled)), sink)

        assertEquals(true, sink.current)
        assertFalse(sink.calls.contains(false))
        assertFalse("the error must not escape the collector", job.isCancelled)
    }

    @Test
    fun flowError_afterTheFlagWasCleared_setsItAgain() = runTest {
        val failNow = CompletableDeferred<Unit>()
        val failsLater = flow {
            emit(lockOff)
            failNow.await()
            throw IOException("read failed")
        }
        val sink = RecordingSink()
        start(controllerOver(failsLater, MutableStateFlow(AppLockState.Disabled)), sink)
        assertEquals(false, sink.current)

        failNow.complete(Unit)
        runCurrent()

        assertEquals(listOf(true, false, true), sink.calls)
    }

    @Test
    fun flowError_afterTheFlagWasCleared_controllerStateChangesWhileTheStreamIsDown_neverClearIt() = runTest {
        val failNow = CompletableDeferred<Unit>()
        val failsLater = flow {
            emit(lockOff)
            failNow.await()
            throw IOException("read failed")
        }
        val state = MutableStateFlow<AppLockState>(AppLockState.Disabled)
        val sink = RecordingSink()
        start(controllerOver(failsLater, state), sink)
        failNow.complete(Unit)
        runCurrent()
        assertEquals(listOf(true, false, true), sink.calls)

        moveTo(state, AppLockState.Resolving)
        moveTo(state, AppLockState.Disabled) // the stream is still down (inside the backoff): null still gives "set"

        assertEquals(true, sink.current)
        assertEquals(listOf(true, false, true), sink.calls) // no false followed
    }

    @Test
    fun flowError_foreignCancellationException_isASourceFailure_mapsToSet_andTheCollectorKeepsRunning() = runTest {
        val foreign = flow<AppLockSettingsLoadState> { throw CancellationException("upstream, not this collector") }
        val sink = RecordingSink()
        val job = start(controllerOver(foreign, MutableStateFlow(AppLockState.Disabled)), sink)

        assertEquals(true, sink.current)
        assertFalse(sink.calls.contains(false))
        assertTrue("the collector's own job is not cancelled by an upstream exception", job.isActive)
    }

    // --- re-subscription with a bounded backoff (INV-47, AE-97)

    @Test
    fun resubscribe_unreadableThenCompletion_keepsTheFlagSet_thenClearsOnceALockOffStateArrives() = runTest {
        val broken = flowOf<AppLockSettingsLoadState>(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR))
        val recovered = committedFlow(lockOff)
        var subscriptions = 0
        val repository = mockk<AppLockSettingsRepository>()
        every { repository.observeLoadState() } answers {
            subscriptions++
            if (subscriptions == 1) broken else recovered
        }
        val sink = RecordingSink()
        start(controllerWith(repository, MutableStateFlow(AppLockState.Disabled)), sink)

        assertEquals(true, sink.current) // Unreadable, then the stream completed
        assertEquals(1, subscriptions)

        advanceTo(BACKOFF_MS - 1)
        assertEquals("still inside the backoff: set", true, sink.current)
        assertEquals(1, subscriptions)
        assertFalse(sink.calls.contains(false))

        advanceTo(BACKOFF_MS)
        assertEquals(2, subscriptions)
        assertEquals(false, sink.current) // recovered: a committed lock-off state clears the flag
    }

    @Test
    fun resubscribe_aCleanCompletionAfterLockOff_setsTheFlagAgain_insteadOfFreezingAStaleClear() = runTest {
        val completes = flow<AppLockSettingsLoadState> { emit(lockOff) } // then completes cleanly
        val sink = RecordingSink()
        start(controllerOver(completes, MutableStateFlow(AppLockState.Disabled)), sink)

        assertEquals(listOf(true, false, true), sink.calls)
        assertEquals(true, sink.current)
    }

    @Test
    fun resubscribe_backoffGrowsWhileTheStreamKeepsEnding_andNeverSpins() = runTest {
        var subscriptions = 0
        val repository = mockk<AppLockSettingsRepository>()
        every { repository.observeLoadState() } answers {
            subscriptions++
            flow<AppLockSettingsLoadState> { throw IOException("read failed") }
        }
        val delays = mutableListOf<Int>()
        val sink = RecordingSink()
        start(
            controllerWith(repository, MutableStateFlow(AppLockState.Disabled)) { failures ->
                delays += failures
                100L * (failures + 1)
            },
            sink
        )
        assertEquals(1, subscriptions)

        advanceTo(99)
        assertEquals(1, subscriptions)
        advanceTo(100) // after delay(100)
        assertEquals(2, subscriptions)
        advanceTo(299)
        assertEquals(2, subscriptions)
        advanceTo(300) // + delay(200)
        assertEquals(3, subscriptions)
        advanceTo(599)
        assertEquals(3, subscriptions)
        advanceTo(600) // + delay(300)
        assertEquals(4, subscriptions)

        assertEquals(listOf(0, 1, 2, 3), delays)
        assertEquals(true, sink.current)
        assertFalse(sink.calls.contains(false))
    }

    @Test
    fun resubscribe_aRealEmissionRestartsTheBackoff_butAnUnreadableOneDoesNot() = runTest {
        var subscriptions = 0
        val repository = mockk<AppLockSettingsRepository>()
        every { repository.observeLoadState() } answers {
            subscriptions++
            when (subscriptions) {
                1, 2 -> flowOf<AppLockSettingsLoadState>(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR))
                3 -> flowOf<AppLockSettingsLoadState>(lockOn) // a readable emission, then the stream ends
                else -> flow<AppLockSettingsLoadState> { awaitCancellation() }
            }
        }
        val delays = mutableListOf<Int>()
        val sink = RecordingSink()
        start(
            controllerWith(repository, MutableStateFlow(AppLockState.Disabled)) { failures ->
                delays += failures
                100L
            },
            sink
        )
        advanceTo(100)
        advanceTo(200)
        advanceTo(300)

        assertEquals(4, subscriptions)
        assertEquals("Unreadable does not reset (0, 1); a Loaded emission does (the third ends with 0)", listOf(0, 1, 0), delays)
    }

    @Test
    fun resubscribe_cancellationDuringTheBackoffDelay_endsTheFollower_andNeverSubscribesAgain() = runTest {
        var subscriptions = 0
        val repository = mockk<AppLockSettingsRepository>()
        every { repository.observeLoadState() } answers {
            subscriptions++
            flow<AppLockSettingsLoadState> { throw IOException("read failed") }
        }
        val sink = RecordingSink()
        val job = start(controllerWith(repository, MutableStateFlow(AppLockState.Disabled)), sink)
        assertEquals(1, subscriptions)
        assertFalse(job.isCompleted) // parked in delay(BACKOFF_MS)

        job.cancel(CancellationException("activity destroyed"))
        runCurrent()

        assertTrue("the delay propagates the cancellation", job.isCompleted)
        advanceTo(10 * BACKOFF_MS)
        assertEquals(1, subscriptions)
        assertFalse(sink.calls.contains(false))
    }

    @Test
    fun defaultBackoffMillis_growsFromOneSecond_isCapped_andIsAlwaysPositive() {
        assertEquals(1_000L, SecureWindowController.defaultBackoffMillis(0))
        assertEquals(2_000L, SecureWindowController.defaultBackoffMillis(1))
        assertEquals(4_000L, SecureWindowController.defaultBackoffMillis(2))
        assertEquals(30_000L, SecureWindowController.defaultBackoffMillis(5)) // 32 s capped
        for (failures in listOf(-1, 0, 6, 100, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val millis = SecureWindowController.defaultBackoffMillis(failures)
            assertTrue("failures $failures -> $millis", millis in 1_000L..30_000L)
        }
    }

    @Test
    fun flowError_runtimeExceptionIsAlsoFailClosed() = runTest {
        val failing = flow<AppLockSettingsLoadState> { throw IllegalStateException("boom") }
        val sink = RecordingSink()
        start(controllerOver(failing, MutableStateFlow(AppLockState.Disabled)), sink)

        assertEquals(true, sink.current)
    }

    @Test
    fun cancellation_isNeverSwallowed_theFollowerEndsAndWritesNothingMore() = runTest {
        val committed = committedFlow(lockOff)
        val sink = RecordingSink()
        val job = start(controllerOver(committed, MutableStateFlow(AppLockState.Disabled)), sink)
        val callsBefore = sink.calls.size

        job.cancel(CancellationException("activity destroyed"))
        runCurrent()
        // The follower must actually finish (isCancelled alone is true the instant cancel() is called).
        assertTrue("the follower completes within runCurrent()", job.isCompleted)
        commit(committed, lockOn)

        assertEquals(callsBefore, sink.calls.size)
    }

    // --- committed-only: an AUTHENTICATING state alone never moves the flag

    @Test
    fun authenticating_neverMovesTheFlag_forAnyPurpose_withTheLockOff() = runTest {
        for (purpose in AuthPurpose.entries) {
            val state = MutableStateFlow<AppLockState>(AppLockState.Disabled)
            val sink = RecordingSink()
            start(controllerOver(committedFlow(lockOff), state), sink)
            val before = sink.calls.toList()

            moveTo(state, AppLockState.Authenticating(7L, purpose))
            assertEquals("open $purpose", before, sink.calls)
            moveTo(state, AppLockState.Disabled)
            assertEquals("close $purpose", before, sink.calls)
            assertEquals(false, sink.current)
        }
    }

    @Test
    fun authenticating_neverMovesTheFlag_forAnyPurpose_withTheLockOn() = runTest {
        for (purpose in AuthPurpose.entries) {
            val state = MutableStateFlow<AppLockState>(AppLockState.Unlocked)
            val sink = RecordingSink()
            start(controllerOver(committedFlow(lockOn), state), sink)
            val before = sink.calls.toList()

            moveTo(state, AppLockState.Authenticating(8L, purpose))
            assertEquals("open $purpose", before, sink.calls)
            moveTo(state, AppLockState.Unlocked)
            assertEquals("close $purpose", before, sink.calls)
            assertEquals(true, sink.current)
        }
    }

    @Test
    fun otherNonResolvingStates_areNotInputs_theFlagFollowsCommittedSettingsOnly() = runTest {
        val others = listOf(
            AppLockState.Disabled,
            AppLockState.Unlocked,
            AppLockState.Locked(LockCause.COLD_START),
            AppLockState.Inert(InertReason.NO_CREDENTIAL)
        )
        val state = MutableStateFlow<AppLockState>(AppLockState.Disabled)
        val sink = RecordingSink()
        start(controllerOver(committedFlow(lockOff), state), sink)
        val before = sink.calls.toList()

        others.forEach { moveTo(state, it) }

        assertEquals(before, sink.calls)
        assertEquals(false, sink.current)
    }

    // --- AE-113

    @Test
    fun ae113_enablePrompt_flagNotSetWhileCommittedLockIsOff_setAtTheFirstEmissionAfterTheCommit() = runTest {
        val committed = committedFlow(lockOff)
        val state = MutableStateFlow<AppLockState>(AppLockState.Disabled)
        val sink = RecordingSink()
        start(controllerOver(committed, state), sink)
        assertEquals(false, sink.current)

        moveTo(state, AppLockState.Authenticating(1L, AuthPurpose.ENABLE)) // prompt opens
        assertEquals(false, sink.current)
        val callsDuringPrompt = sink.calls.size

        moveTo(state, AppLockState.Unlocked) // prompt succeeded; the write has not committed yet
        assertEquals(false, sink.current)
        assertEquals(callsDuringPrompt, sink.calls.size)

        commit(committed, lockOn) // the commit: first emission after it
        assertEquals(true, sink.current)
        assertEquals(listOf(true, false, true), sink.calls)
    }

    @Test
    fun ae113_enablePromptCancelled_flagStaysOff_noWriteNoChange() = runTest {
        val state = MutableStateFlow<AppLockState>(AppLockState.Disabled)
        val sink = RecordingSink()
        start(controllerOver(committedFlow(lockOff), state), sink)

        moveTo(state, AppLockState.Authenticating(1L, AuthPurpose.ENABLE))
        moveTo(state, AppLockState.Disabled) // failed or cancelled

        assertEquals(listOf(true, false), sink.calls)
    }

    // --- AE-114

    @Test
    fun ae114_confirmChange_flagSetForTheWholePrompt_clearedOnlyAfterTheCommit() = runTest {
        val committed = committedFlow(lockOn)
        val state = MutableStateFlow<AppLockState>(AppLockState.Unlocked)
        val sink = RecordingSink()
        start(controllerOver(committed, state), sink)
        assertEquals(true, sink.current)

        moveTo(state, AppLockState.Authenticating(2L, AuthPurpose.CONFIRM_CHANGE))
        assertEquals(true, sink.current)

        moveTo(state, AppLockState.Unlocked) // prompt succeeded; the weakening write is not committed yet
        assertEquals(true, sink.current)
        assertFalse("never cleared before the commit", sink.calls.contains(false))

        commit(committed, AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = false))) // committed disable
        moveTo(state, AppLockState.Disabled)
        assertEquals(false, sink.current)
    }

    @Test
    fun ae114_writeFailure_leavesTheFlagSet_becauseTheCommittedStateIsUnchanged() = runTest {
        val committed = committedFlow(lockOn)
        val state = MutableStateFlow<AppLockState>(AppLockState.Unlocked)
        val sink = RecordingSink()
        start(controllerOver(committed, state), sink)

        moveTo(state, AppLockState.Authenticating(3L, AuthPurpose.CONFIRM_CHANGE))
        moveTo(state, AppLockState.Unlocked) // prompt succeeded, then the persist failed: nothing new is committed
        commit(committed, lockOn) // a re-read of the unchanged committed value

        assertEquals(true, sink.current)
        assertFalse("the flag must never be cleared", sink.calls.contains(false))
    }

    @Test
    fun ae114_weakeningOfSecureScreenAlone_withTheLockOn_keepsTheFlag_untilTheLockIsAlsoOff() = runTest {
        val committed = committedFlow(AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = true, secureScreen = true)))
        val sink = RecordingSink()
        start(controllerOver(committed, MutableStateFlow(AppLockState.Unlocked)), sink)

        commit(committed, AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = true, secureScreen = false)))
        assertEquals(true, sink.current)
        assertFalse(sink.calls.contains(false))

        commit(committed, AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = false, secureScreen = false)))
        assertEquals(false, sink.current)
    }

    // --- distinctUntilChanged

    @Test
    fun distinctUntilChanged_repeatedIdenticalEmissions_causeNoRedundantSinkCalls() = runTest {
        val committed = committedFlow(lockOn)
        val state = MutableStateFlow<AppLockState>(AppLockState.Unlocked)
        val sink = RecordingSink()
        start(controllerOver(committed, state), sink)

        repeat(3) { commit(committed, lockOn) }
        commit(committed, secureOnly) // different settings, same computed value
        moveTo(state, AppLockState.Locked(LockCause.TIMEOUT))
        moveTo(state, AppLockState.Authenticating(4L, AuthPurpose.UNLOCK))

        assertEquals(listOf(true, true), sink.calls) // applyInitial, then one first emission
    }

    @Test
    fun distinctUntilChanged_aRealChangeIsStillApplied() = runTest {
        val committed = committedFlow(lockOn)
        val sink = RecordingSink()
        start(controllerOver(committed, MutableStateFlow(AppLockState.Unlocked)), sink)

        commit(committed, lockOff)
        commit(committed, lockOff)
        commit(committed, lockOn)

        assertEquals(listOf(true, true, false, true), sink.calls)
    }
}
