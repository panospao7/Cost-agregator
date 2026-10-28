package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S16: the foreground lock evaluation (T13 to T15) with pending-background consumption (spec 3.4, AMD-22).
 * Moved out of `AppLockControllerResolutionTest` in part (ii-b) with every assertion unchanged. The timer of
 * the open-origin prompts (T32, T34, AE-116) is in `AppLockControllerPromptOutcomeTest`.
 *
 * Acceptance examples covered here: AE-40, AE-41, AE-43, AE-58 (controller part), AE-90.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockControllerTimerTest {

    private fun everyTimeout() = AutoLockTimeout.entries

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
