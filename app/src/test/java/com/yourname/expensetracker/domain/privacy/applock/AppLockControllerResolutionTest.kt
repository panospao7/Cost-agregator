package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S16 part (i): resolution (T1 to T6, T6b), the UNLOCK start and success (T7, T8) and the foreground
 * lock evaluation (T13 to T15) with pending-background consumption (spec 3.4, AMD-22).
 *
 * Acceptance examples covered here: AE-38 (controller part), AE-40, AE-41, AE-43, AE-51 (controller
 * part), AE-53, AE-58 (controller part), AE-61, AE-63, AE-90.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockControllerResolutionTest {

    private fun everyTimeout() = AutoLockTimeout.entries

    // --- T1: process start -----------------------------------------------------------------------

    @Test
    fun `T1 a new controller is RESOLVING and the secure screen is forced while it is`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        val controller = h.newController()

        assertEquals(AppLockState.Resolving, controller.state.value)
        // The settings part is false here, so RESOLVING alone must force FLAG_SECURE (INV-29).
        assertTrue(EffectivePrivacyValues.effectiveSecureScreen(AppLockTestSettings.DISABLED, resolving = true))
        assertEquals(0, h.repository.readCount)
        assertTrue(h.emitted.isEmpty())
    }

    // --- T2 / T3 ---------------------------------------------------------------------------------

    @Test
    fun `T2 lock off resolves to DISABLED for loaded and first-run settings with no diagnostic`() {
        for (read in listOf(
            AppLockTestSettings.DISABLED,
            AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS)
        )) {
            val h = AppLockControllerHarness(read)
            val controller = h.resolvedController()

            assertEquals(AppLockState.Disabled, controller.state.value)
            assertTrue(h.emitted.isEmpty())
        }
    }

    @Test
    fun `T2 lock off resolves to DISABLED even when the device cannot authenticate`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED, DeviceAuthCapability.NO_CREDENTIAL)

        assertEquals(AppLockState.Disabled, h.resolvedController().state.value)
    }

    @Test
    fun `AE-38 T3 lock on with capability AVAILABLE resolves to LOCKED COLD_START and records the code, no Room`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        val controller = h.resolvedController()

        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SHOWN_COLD_START), h.emitted)
        // No metadata at all: the cause is the code itself, so nothing else can reach a stored row.
        assertEquals(1, h.emittedMetadata.size)
        assertSame(PrivacyDiagnosticMetadata.EMPTY, h.emittedMetadata.single())
        // Exactly one settings read (the DataStore), no Room; nothing is written and the controller has
        // no Room or barrier dependency at all (INV-28).
        assertEquals(1, h.repository.readCount)
        assertEquals(0, h.repository.writeAttempts)
    }

    // --- T5: unreadable --------------------------------------------------------------------------

    @Test
    fun `AE-51 T5 every unreadable cause with capability AVAILABLE resolves to LOCKED SETTINGS_UNREADABLE`() {
        for (cause in SettingsUnreadableCause.entries) {
            val h = AppLockControllerHarness(AppLockTestSettings.unreadable(cause))
            val controller = h.resolvedController()

            assertEquals("cause $cause", AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
            assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SHOWN_SETTINGS_UNREADABLE), h.emitted)
        }
    }

    @Test
    fun `AE-51 the next cold start before any re-save is LOCKED again and nothing was written`() {
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED))
        val first = h.resolvedController()
        h.unlock(first)

        val secondProcess = h.resolvedController()

        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), secondProcess.state.value)
        assertEquals(0, h.repository.writeAttempts)
    }

    @Test
    fun `T5 RESOLVE_TIMEOUT with capability AVAILABLE resolves to LOCKED SETTINGS_UNREADABLE`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED, scheduler = testScheduler)
        val controller = h.newController()

        resolveTimedOut(h, controller)

        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SHOWN_SETTINGS_UNREADABLE), h.emitted)
    }

    // --- Resolution is one-shot --------------------------------------------------------------------

    @Test
    fun `resolution applies once so a later resolve cannot move DISABLED to LOCKED or LOCKED to DISABLED`() {
        val disabledHarness = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val disabled = disabledHarness.resolvedController()
        disabledHarness.repository.loadState = AppLockTestSettings.enabled()
        runBlocking { disabled.resolve() } // idempotent: no second read, no second resolution
        assertEquals(AppLockState.Disabled, disabled.state.value)
        assertEquals(1, disabledHarness.repository.readCount)

        val lockedHarness = AppLockControllerHarness(AppLockTestSettings.enabled())
        val locked = lockedHarness.resolvedController()
        lockedHarness.repository.loadState = AppLockTestSettings.DISABLED
        runBlocking { locked.resolve() }
        assertEquals(AppLockState.Locked(LockCause.COLD_START), locked.state.value)
        assertEquals(1, lockedHarness.repository.readCount)
    }

    // --- resolve(): the T_resolve bound and T6b ----------------------------------------------------

    @Test
    fun `resolve reads the store once and applies it, and a second call is a no-op`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), scheduler = testScheduler)
        val controller = h.newController()

        controller.resolve()
        controller.resolve()

        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertEquals(1, h.repository.readCount)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SHOWN_COLD_START), h.emitted)
    }

    @Test
    fun `AE-53 T6b a read that outlives T_resolve locks, and the late disabled read lifts it with no auth`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED, scheduler = testScheduler)
        h.repository.holdReads()
        val controller = h.newController()

        val resolving = launch { controller.resolve() }
        runCurrent()
        assertEquals(AppLockState.Resolving, controller.state.value)

        advanceTimeBy(AppLockController.RESOLVE_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SHOWN_SETTINGS_UNREADABLE), h.emitted)

        h.repository.releaseReads()
        runCurrent()
        resolving.join()

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertEquals(1, h.repository.readCount)
    }

    @Test
    fun `T6b a late read with the lock on keeps the controller LOCKED`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), scheduler = testScheduler)
        h.repository.holdReads()
        val controller = h.newController()
        val resolving = launch { controller.resolve() }
        runCurrent()
        advanceTimeBy(AppLockController.RESOLVE_TIMEOUT_MILLIS + 1)
        runCurrent()

        h.repository.releaseReads()
        runCurrent()
        resolving.join()

        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
    }

    @Test
    fun `a read that throws a non-cancellation exception is an unreadable store, never defaults`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED, scheduler = testScheduler)
        h.repository.failure = IllegalStateException("secret-path /data/x")
        val controller = h.newController()

        controller.resolve()

        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        // Bounded diagnostics only: the failure text is nowhere in what was recorded.
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SHOWN_SETTINGS_UNREADABLE), h.emitted)
    }

    @Test
    fun `cancelling resolve leaves RESOLVING and a later resolve can complete`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), scheduler = testScheduler)
        h.repository.holdReads()
        val controller = h.newController()

        val resolving = launch { controller.resolve() }
        runCurrent()
        resolving.cancelAndJoin()
        assertEquals(AppLockState.Resolving, controller.state.value)

        h.repository.releaseReads()
        controller.resolve()

        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertEquals(2, h.repository.readCount)
    }

    @Test
    fun `an Error from the read propagates and a later resolve can still complete`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), scheduler = testScheduler)
        h.repository.failure = ExceptionInInitializerError()
        val controller = h.newController()

        val thrown = try {
            controller.resolve()
            null
        } catch (t: Throwable) {
            t
        }

        assertTrue("expected the Error, got $thrown", thrown is ExceptionInInitializerError)
        assertEquals(AppLockState.Resolving, controller.state.value)

        // The one-shot flag was re-armed: a healthy read now resolves normally.
        h.repository.failure = null
        controller.resolve()

        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertEquals(2, h.repository.readCount)
        assertEquals(emptyList<Throwable>(), h.uncaught)
    }

    // --- Diagnostics isolation (INV-37) ---------------------------------------------------------------

    @Test
    fun `a failing diagnostic emit never changes or blocks the transition`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        h.emitFailure = IllegalStateException("sink down")

        val controller = h.resolvedController()

        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        // The emit was attempted (the sink was reached and threw), and nothing escaped the scope.
        assertTrue(PrivacyDiagnosticCode.LOCK_SHOWN_COLD_START in h.emitted)
        assertEquals(emptyList<Throwable>(), h.uncaught)
        // A later transition is just as unaffected.
        h.unlock(controller)
        assertEquals(emptyList<Throwable>(), h.uncaught)
    }

    @Test
    fun `a diagnostic emit that never returns does not block this transition or the next one`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        h.emitSuspendsForever = true

        val controller = h.resolvedController()

        // The emitter was reached and is stuck, yet the call returned with the new state.
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SHOWN_COLD_START), h.emitted)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)

        // The next transition (also with a stuck emit) still works.
        h.unlock(controller)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertEquals(
            listOf(
                PrivacyDiagnosticCode.LOCK_SHOWN_COLD_START,
                PrivacyDiagnosticCode.LOCK_UNLOCKED,
                PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT
            ),
            h.emitted
        )
        assertEquals(emptyList<Throwable>(), h.uncaught)
    }

    // --- AE-61 / AE-63: precedence with restore -----------------------------------------------------

    @Test
    fun `AE-61 a post-restore restart is a cold start LOCKED, and after unlock the stored lock flag is untouched`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        h.unlock(h.resolvedController()) // the session before the restore

        val restarted = h.resolvedController() // Runtime.exit(0) then a new process: T1 then T3

        assertEquals(AppLockState.Locked(LockCause.COLD_START), restarted.state.value)
        h.unlock(restarted)
        assertEquals(AppLockState.Unlocked, restarted.state.value)
        assertEquals(AppLockTestSettings.enabled(), h.repository.loadState)
        assertEquals(0, h.repository.writeAttempts)
    }

    @Test
    fun `AE-63 returning past the timeout re-locks and a restore never reaches the controller`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1))
        val controller = h.resolvedController()
        h.unlock(controller)

        // The restore runs outside the controller (no operational input, INV-28). Only time passes.
        controller.onAppBackgrounded()
        h.clock.advance(5 * 60_000L)
        controller.onAppForegrounded()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        h.unlock(controller)
        assertEquals(0, h.repository.writeAttempts)
    }

    // --- T7 / T8: unlock ----------------------------------------------------------------------------

    @Test
    fun `T7 and T8 a tap starts one attempt and only its matching success unlocks`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        val controller = h.resolvedController()

        val started = controller.onUnlockTapped() as UnlockTapResult.Started
        h.settle()

        assertEquals(AppLockState.Authenticating(started.attemptId, AuthPurpose.UNLOCK), controller.state.value)
        assertEquals(started.attemptId, controller.inFlightAttemptId)
        assertEquals(listOf(started.attemptId), h.authenticator.calls.map { it.attemptId })
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertNull(controller.inFlightAttemptId)
        assertEquals(
            listOf(PrivacyDiagnosticCode.LOCK_SHOWN_COLD_START, PrivacyDiagnosticCode.LOCK_UNLOCKED),
            h.emitted
        )
    }

    // --- Spec 10: each code at most once per process ---------------------------------------------------

    @Test
    fun `spec 10 a second re-lock or unlock with the same code emits nothing new`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        val firstRound = listOf(
            PrivacyDiagnosticCode.LOCK_SHOWN_COLD_START,
            PrivacyDiagnosticCode.LOCK_UNLOCKED,
            PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT
        )
        assertEquals(firstRound, h.emitted)

        // A second round: same states, same codes. The transitions still happen, the diagnostics do not.
        h.unlock(controller)
        controller.onAppBackgrounded()
        controller.onAppForegrounded()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertEquals(firstRound, h.emitted)
        assertEquals(firstRound.size, h.emittedMetadata.size)
    }

    @Test
    fun `spec 10 a different code still emits once after another was already claimed`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppBackgrounded()
        controller.onAppForegrounded() // LOCK_SHOWN_TIMEOUT, first time
        h.unlock(controller)
        h.emitted.clear()

        // The device loses its screen lock: INERT is a code this process has not emitted yet.
        h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_INERT_NO_CREDENTIAL), h.emitted)

        // Re-locking (TIMEOUT, claimed earlier) and re-entering the same inert reason are both silent.
        h.capabilityProvider.capability = DeviceAuthCapability.AVAILABLE
        controller.onAppBackgrounded()
        controller.onAppForegrounded() // T14 from INERT: LOCK_SHOWN_TIMEOUT already claimed
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        assertEquals(UnlockTapResult.BecameInert(InertReason.NO_CREDENTIAL), controller.onUnlockTapped())
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_INERT_NO_CREDENTIAL), h.emitted)
    }

    @Test
    fun `spec 10 the once-per-process set belongs to one controller, a new process emits again`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        h.resolvedController()
        h.resolvedController()

        assertEquals(
            listOf(PrivacyDiagnosticCode.LOCK_SHOWN_COLD_START, PrivacyDiagnosticCode.LOCK_SHOWN_COLD_START),
            h.emitted
        )
    }

    @Test
    fun `attempt ids are strictly increasing across unlocks in one process`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.resolvedController()
        val ids = mutableListOf<Long>()
        repeat(3) {
            val started = controller.onUnlockTapped() as UnlockTapResult.Started
            ids += started.attemptId
            h.settle()
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            assertEquals(AppLockState.Unlocked, controller.state.value)
            controller.onAppBackgrounded()
            controller.onAppForegrounded()
            assertTrue(controller.state.value is AppLockState.Locked)
        }

        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `no background is pending after a re-lock and unlock`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppBackgrounded()
        controller.onAppForegrounded() // T14: consumed, LOCKED

        h.unlock(controller)

        assertNull(controller.pendingBackgroundAtMillis)
    }

    // --- T13 to T15: the timer (spec 3.4) --------------------------------------------------------------

    @Test
    fun `AE-40 the boundary is inclusive for every timeout`() {
        for (timeout in everyTimeout().filter { it.millis > 0L }) {
            val justBefore = AppLockControllerHarness(AppLockTestSettings.enabled(timeout))
            val early = justBefore.resolvedController()
            justBefore.unlock(early)
            early.onAppBackgrounded()
            justBefore.clock.advance(timeout.millis - 1)
            early.onAppForegrounded()
            assertEquals("$timeout - 1 ms", AppLockState.Unlocked, early.state.value)

            val atBoundary = AppLockControllerHarness(AppLockTestSettings.enabled(timeout))
            val late = atBoundary.resolvedController()
            atBoundary.unlock(late)
            late.onAppBackgrounded()
            atBoundary.clock.advance(timeout.millis)
            late.onAppForegrounded()
            assertEquals("$timeout exactly", AppLockState.Locked(LockCause.TIMEOUT), late.state.value)
        }
    }

    @Test
    fun `AE-40 M1 59_999 ms is UNLOCKED and 60_000 ms is LOCKED TIMEOUT with its diagnostic`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1))
        val controller = h.resolvedController()
        h.unlock(controller)

        controller.onAppBackgrounded()
        h.clock.advance(59_999L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, controller.state.value)

        controller.onAppBackgrounded()
        h.clock.advance(60_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertEquals(PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT, h.emitted.last())
    }

    @Test
    fun `AE-41 IMMEDIATE ignores rotation, shade and focus loss, and locks on Home plus 1 s`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.resolvedController()
        h.unlock(controller)

        // Rotation, the notification shade and split-screen focus loss are not an APP_BACKGROUNDED, so a
        // foreground (or a query) with nothing pending never locks, however much time passes.
        h.clock.advance(10 * 60_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(AppLockState.Unlocked, controller.onLockQuery())

        controller.onAppBackgrounded()
        h.clock.advance(1_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `AE-41 IMMEDIATE locks even when the foreground is in the same millisecond as the background`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.resolvedController()
        h.unlock(controller)

        controller.onAppBackgrounded()
        controller.onAppForegrounded()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `AE-43 only the monotonic clock moves the timer, so a wall-clock change cannot`() {
        // The controller has no wall-clock input. A wall clock moved +2 h during a 10 s background is
        // invisible: only the monotonic 10 s elapses. Moved -1 h during 2 min: only the 2 min elapses.
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1))
        val controller = h.resolvedController()
        h.unlock(controller)

        controller.onAppBackgrounded()
        h.clock.advance(10_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, controller.state.value)

        controller.onAppBackgrounded()
        h.clock.advance(120_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `a clock that appears to go backwards counts as timed out (fail closed)`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M15))
        val controller = h.resolvedController()
        h.unlock(controller)

        controller.onAppBackgrounded()
        h.clock.advance(-1L)
        controller.onAppForegrounded()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `AE-90 while unreadable the effective timeout is IMMEDIATE, so 1 s away locks`() {
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED))
        val controller = h.resolvedController()
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        h.unlock(controller)

        controller.onAppBackgrounded()
        h.clock.advance(1_000L)
        controller.onAppForegrounded()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `AE-90 a RESOLVE_TIMEOUT fallback is also IMMEDIATE after an unlock`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M15), scheduler = testScheduler)
        val controller = h.newController()
        resolveTimedOut(h, controller)
        h.unlock(controller)

        controller.onAppBackgrounded()
        h.clock.advance(1_000L)
        controller.onAppForegrounded()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    // --- Pending-background consumption (spec 3.4, AMD-22) -----------------------------------------------

    @Test
    fun `a foreground consumes the pending background so a later query never measures from it`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1))
        val controller = h.resolvedController()
        h.unlock(controller)

        controller.onAppBackgrounded()
        assertEquals(h.clock.nowMillis, controller.pendingBackgroundAtMillis)
        h.clock.advance(10_000L)
        controller.onAppForegrounded()
        assertNull(controller.pendingBackgroundAtMillis)

        h.clock.advance(10 * 60_000L) // in use for ten minutes, no new background
        assertEquals(AppLockState.Unlocked, controller.onLockQuery())
        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    @Test
    fun `AE-58 a query after a long background locks, and the foreground that follows changes nothing`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1))
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppBackgrounded()
        h.clock.advance(10 * 60_000L)

        // onNewIntent before onStart.
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.onLockQuery())
        controller.onAppForegrounded()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertEquals(1, h.emitted.count { it == PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT })
    }

    @Test
    fun `AE-58 the foreground before the query gives the same result`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1))
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppBackgrounded()
        h.clock.advance(10 * 60_000L)

        controller.onAppForegrounded() // onStart before onNewIntent
        val afterQuery = controller.onLockQuery()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), afterQuery)
        assertEquals(1, h.emitted.count { it == PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT })
    }

    @Test
    fun `a query while the lock cannot protect does not erase the time away`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1))
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppBackgrounded()
        h.clock.advance(10 * 60_000L)

        h.capabilityProvider.capability = DeviceAuthCapability.UNKNOWN // a transient failure of the check
        assertEquals(AppLockState.Unlocked, controller.onLockQuery())
        assertFalse(controller.pendingBackgroundAtMillis == null)

        h.capabilityProvider.capability = DeviceAuthCapability.AVAILABLE
        controller.onAppForegrounded()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `the first background wins until a foreground consumes it (a repeated stop over-counts)`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1))
        val controller = h.resolvedController()
        h.unlock(controller)

        controller.onAppBackgrounded()
        val first = controller.pendingBackgroundAtMillis
        h.clock.advance(40_000L)
        controller.onAppBackgrounded()
        assertEquals(first, controller.pendingBackgroundAtMillis)
        h.clock.advance(20_000L)
        controller.onAppForegrounded()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `backgrounding while RESOLVING, LOCKED or DISABLED records nothing`() {
        val resolving = AppLockControllerHarness(AppLockTestSettings.enabled()).newController()
        resolving.onAppBackgrounded()
        assertNull(resolving.pendingBackgroundAtMillis)

        val locked = AppLockControllerHarness(AppLockTestSettings.enabled()).resolvedController()
        locked.onAppBackgrounded()
        assertNull(locked.pendingBackgroundAtMillis)

        val disabled = AppLockControllerHarness(AppLockTestSettings.DISABLED).resolvedController()
        disabled.onAppBackgrounded()
        assertNull(disabled.pendingBackgroundAtMillis)
    }
}
