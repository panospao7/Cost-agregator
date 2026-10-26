package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S16: resolution (T1 to T6, T6b), its diagnostics (spec 10, once per process per cause) and the
 * precedence with restore. The timer and pending-background tests moved to `AppLockControllerTimerTest`,
 * and the T7/T8 unlock tests to `AppLockControllerAuthOutcomeTest` (part (ii-b), assertions unchanged).
 *
 * Acceptance examples covered here: AE-38 (controller part), AE-51 (controller part), AE-53, AE-61, AE-63.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockControllerResolutionTest {

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
}
