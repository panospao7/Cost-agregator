package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * S16 part (ii-a): the prompt machinery around an UNLOCK attempt, so that every `AUTHENTICATING(UNLOCK)`
 * exits. The launch (P1, P5), the bounded result of each launch (P2), the watchdog (P3), host loss (P4,
 * T35, AE-118) and the publication of the state flow after the lock is released (R2).
 *
 * The prompt is a fake [DeviceAuthenticator]; "main" is a queued dispatcher that runs on
 * [AppLockControllerHarness.settle]; time is the test scheduler.
 *
 * Part (ii-b) (the tests from "S16 part (ii-b)" down): the ENABLE and CONFIRM_CHANGE attempts, started through
 * `startOpenOriginAttempt` with a recording success handler as the no-op change (S18 binds the real one). They
 * cover purpose binding, the return to the origin, T32 (`promptBackgroundAt`), the T33 hand-off, T34 in both
 * callback orders with the AE-116 59.999 s / 60.000 s boundary (monotonic clock only), T35 and AE-118 for every
 * purpose, and the F5 watchdog (coroutine time, separate from the lock timer).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockControllerPromptOutcomeTest {

    private val authCodes = setOf(
        PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED,
        PrivacyDiagnosticCode.LOCK_AUTH_LOCKOUT,
        PrivacyDiagnosticCode.LOCK_AUTH_ERROR
    )

    // --- Launch: open before authenticate, outside the monitor, on main (P1, P5) ---------------------------

    @Test
    fun `T7 the attempt is open and published before authenticate runs, and the launch hops to main`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        var stateAtCall: AppLockState? = null
        var openAtCall: Long? = null
        h.authenticator.onAuthenticate = {
            stateAtCall = controller.state.value
            openAtCall = controller.inFlightAttemptId
        }

        val started = controller.onUnlockTapped() as UnlockTapResult.Started

        // The tap returned without calling the authenticator: the prompt call is queued on main.
        assertTrue(h.authenticator.calls.isEmpty())
        assertEquals(AppLockState.Authenticating(started.attemptId, AuthPurpose.UNLOCK), controller.state.value)
        h.settle()

        val call = h.authenticator.calls.single()
        assertEquals(started.attemptId, call.attemptId)
        assertEquals(AuthPromptCopy.UNLOCK, call.copy)
        assertTrue("authenticate must run on the main dispatcher", call.onMain)
        assertEquals(AppLockState.Authenticating(started.attemptId, AuthPurpose.UNLOCK), stateAtCall)
        assertEquals(started.attemptId, openAtCall)
    }

    @Test
    fun `P1 authenticate and cancel are never called under the controller's monitor`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        val probesInTime = AtomicInteger(0)
        // Another thread takes the monitor (a no-op host-loss for an unknown id). If the call to the
        // authenticator ran under the monitor, the probe would block and the join would time out. The count
        // is made on the joining side: a probe thread that is still alive after the join did not get the
        // monitor in time, even if it finishes later once the monitor is released.
        val probe: (Long) -> Unit = {
            val t = thread(isDaemon = true) { controller.onPromptHostLost(-1L) }
            t.join(5_000)
            if (!t.isAlive) probesInTime.incrementAndGet()
        }
        h.authenticator.onAuthenticate = probe
        h.authenticator.onCancel = probe

        val id = h.tap(controller)
        controller.onPromptHostLost(id)
        h.settle()

        assertEquals("authenticate and cancel each ran with the monitor free", 2, probesInTime.get())
        assertEquals(listOf(id), h.authenticator.cancelCalls)
    }

    @Test
    fun `P1 an outcome delivered synchronously inside authenticate is handled, the attempt was already open`() {
        val outcomes: List<Pair<DeviceAuthOutcome, AppLockState>> = listOf(
            DeviceAuthOutcome.Succeeded to AppLockState.Unlocked,
            DeviceAuthOutcome.Cancelled to AppLockState.Locked(LockCause.COLD_START),
            DeviceAuthOutcome.LockedOut(permanent = true) to AppLockState.Locked(LockCause.COLD_START),
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED) to AppLockState.Locked(LockCause.COLD_START)
        )
        for ((outcome, expected) in outcomes) {
            val (h, controller) = lockedControllerFor(LockCause.COLD_START)
            var openAtCall: Long? = null
            h.authenticator.onAuthenticate = { openAtCall = controller.inFlightAttemptId }
            h.authenticator.reentrantOutcome = outcome

            val started = controller.onUnlockTapped() as UnlockTapResult.Started
            h.settle()

            assertEquals("$outcome", expected, controller.state.value)
            assertEquals("$outcome: open before authenticate", started.attemptId, openAtCall)
            assertNull("$outcome", controller.inFlightAttemptId)
            assertEquals("$outcome", 1, h.authenticator.calls.size)
            assertEquals("$outcome", 0, h.activeJobs)
        }
    }

    @Test
    fun `P1 a synchronous NotAvailable inside authenticate re-checks the capability and becomes INERT`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        h.authenticator.onAuthenticate = { h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL }
        h.authenticator.reentrantOutcome = DeviceAuthOutcome.NotAvailable

        controller.onUnlockTapped()
        h.settle()

        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        assertNull(controller.inFlightAttemptId)
        assertEquals(0, h.activeJobs)
    }

    // --- P2: a launch that did not happen -----------------------------------------------------------------------

    @Test
    fun `P2 every non-LAUNCHED result rolls back to the origin with no LOCK_AUTH code and enables Unlock again`() {
        val notLaunched = listOf(
            AuthLaunch.ALREADY_IN_FLIGHT,
            AuthLaunch.HOST_UNAVAILABLE,
            AuthLaunch.PROMPT_UNAVAILABLE
        )
        for (result in notLaunched) {
            for (cause in LockCause.entries) {
                val (h, controller) = lockedControllerFor(cause)
                h.emitted.clear()
                h.authenticator.defaultResult = result
                // lockedControllerFor(cause) may already have performed an authenticate call (h.unlock for TIMEOUT).
                val callsBefore = h.authenticator.calls.size

                val started = controller.onUnlockTapped() as UnlockTapResult.Started
                assertEquals(
                    "$result / $cause",
                    AppLockState.Authenticating(started.attemptId, AuthPurpose.UNLOCK),
                    controller.state.value
                )
                h.settle()

                assertEquals("$result / $cause", AppLockState.Locked(cause), controller.state.value)
                assertNull("$result / $cause", controller.inFlightAttemptId)
                assertEquals("$result / $cause", callsBefore + 1, h.authenticator.calls.size) // exactly one launch
                assertTrue("$result / $cause", h.emitted.none { it in authCodes })
                assertTrue("$result / $cause", h.authenticator.cancelCalls.isEmpty())
                assertEquals("$result / $cause", 0, h.activeJobs)

                h.authenticator.defaultResult = AuthLaunch.LAUNCHED
                val retry = h.tap(controller)
                assertTrue("$result / $cause", retry > started.attemptId)
            }
        }
    }

    @Test
    fun `P2 a launch that throws counts as PROMPT_UNAVAILABLE, rolls back and leaks nothing`() {
        val (h, controller) = lockedControllerFor(LockCause.TIMEOUT)
        h.emitted.clear()
        h.authenticator.throwOnAuthenticate = IllegalStateException("secret-path /data/user/0/x")

        controller.onUnlockTapped()
        h.settle()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertNull(controller.inFlightAttemptId)
        assertTrue(h.emitted.isEmpty())
        assertEquals(emptyList<Throwable>(), h.uncaught)
    }

    @Test
    fun `F2 a foreign CancellationException from the launch propagates and closes the attempt as not launched at once`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        h.emitted.clear()
        h.authenticator.throwOnAuthenticate = CancellationException("cancelled")

        val started = controller.onUnlockTapped() as UnlockTapResult.Started
        assertEquals(AppLockState.Authenticating(started.attemptId, AuthPurpose.UNLOCK), controller.state.value)
        // The orchestrator and the watchdog are running, queued behind the "main" dispatcher.
        val attemptJobs = h.activeControllerJobs()
        assertTrue("expected the orchestrator and the watchdog, got ${attemptJobs.size}", attemptJobs.size >= 2)
        h.settle()

        // Not swallowed (no uncaught throwable, the launch coroutine ends cancelled) and not left for the
        // watchdog: the attempt is already closed, back on the origin, with no LOCK_AUTH_* code.
        // Every job of the attempt, including the orchestrator that ran launchPrompt, is finished and ended
        // cancelled, not completed normally and not still running. Limit of this check: closeAttempt also
        // cancels the orchestrator itself before the rethrow, so a launchPrompt that swallowed the exception
        // would end cancelled too; the check pins that no job of the attempt is left running or completed
        // normally, and the propagation itself is pinned by the rethrow in launchPrompt (see the handoff).
        assertTrue(
            "every job of the attempt ended cancelled",
            attemptJobs.all { it.isCompleted && it.isCancelled }
        )
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertNull(controller.inFlightAttemptId)
        assertEquals(emptyList<Throwable>(), h.uncaught)
        assertTrue(h.emitted.none { it in authCodes })
        assertTrue(h.authenticator.cancelCalls.isEmpty())
        assertEquals(0, h.activeJobs)
        // Unlock is enabled again.
        h.authenticator.throwOnAuthenticate = null
        assertTrue(h.tap(controller) > started.attemptId)
    }

    @Test
    fun `F7 a cancellation that races with a LAUNCHED answer abandons the attempt so the adapter is asked to cancel`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        h.emitted.clear()
        // The scope's work is cancelled while authenticate runs; it still answers LAUNCHED, so a prompt may show.
        h.authenticator.onAuthenticate = { h.cancelControllerJobs() }

        val started = controller.onUnlockTapped() as UnlockTapResult.Started
        h.settle()

        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertNull(controller.inFlightAttemptId)
        assertEquals(listOf(started.attemptId), h.authenticator.cancelCalls) // the adapter was asked to cancel
        assertEquals(emptyList<Throwable>(), h.uncaught) // cancellation is not swallowed into an error
        // Closed as cancelled; the adapter's SYSTEM_CANCELED echo found the attempt closed and was ignored.
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED), h.emitted.filter { it in authCodes })
        h.authenticator.onAuthenticate = null
        assertTrue(h.tap(controller) > started.attemptId)
    }

    @Test
    fun `P2 AWAITING_PRIOR_TERMINAL keeps the attempt open and retries until the launch succeeds`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        h.authenticator.queue(AuthLaunch.AWAITING_PRIOR_TERMINAL, AuthLaunch.AWAITING_PRIOR_TERMINAL, AuthLaunch.LAUNCHED)

        val id = h.tap(controller)
        assertEquals(1, h.authenticator.calls.size)
        assertEquals(AppLockState.Authenticating(id, AuthPurpose.UNLOCK), controller.state.value)
        h.advance(AppLockController.AWAITING_PRIOR_TERMINAL_STEP_MILLIS)
        assertEquals(2, h.authenticator.calls.size)
        assertEquals(AppLockState.Authenticating(id, AuthPurpose.UNLOCK), controller.state.value)
        h.advance(AppLockController.AWAITING_PRIOR_TERMINAL_STEP_MILLIS)
        assertEquals(3, h.authenticator.calls.size)

        // Every retry was for the same attempt, with the same callback.
        assertEquals(setOf(id), h.authenticator.calls.map { it.attemptId }.toSet())
        assertEquals(1, h.authenticator.calls.map { it.callback }.toSet().size)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertTrue(h.emitted.none { it in authCodes })
        assertEquals(0, h.activeJobs)
    }

    @Test
    fun `P2 the next foreground wakes a launch that is waiting for the prior terminal`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        h.authenticator.queue(AuthLaunch.AWAITING_PRIOR_TERMINAL, AuthLaunch.LAUNCHED)
        val id = h.tap(controller)
        assertEquals(1, h.authenticator.calls.size)
        h.settle()
        assertEquals("no busy loop while waiting", 1, h.authenticator.calls.size)

        controller.onAppForegrounded() // the adapter's ON_START
        h.settle()

        assertEquals(2, h.authenticator.calls.size)
        assertEquals(AppLockState.Authenticating(id, AuthPurpose.UNLOCK), controller.state.value)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    @Test
    fun `P2 F2 a retry budget that runs out closes the attempt as not launched at once, without the watchdog`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        h.emitted.clear()
        h.authenticator.defaultResult = AuthLaunch.AWAITING_PRIOR_TERMINAL
        val bound = AppLockController.AWAITING_PRIOR_TERMINAL_BOUND_MILLIS
        val step = AppLockController.AWAITING_PRIOR_TERMINAL_STEP_MILLIS
        val steps = (bound / step).toInt()

        val id = h.tap(controller)
        repeat(steps - 1) { h.advance(step) }

        // Still waiting before the last step: the attempt is open and the retries continue.
        assertEquals(AppLockState.Authenticating(id, AuthPurpose.UNLOCK), controller.state.value)
        assertEquals(steps, h.authenticator.calls.size)

        h.advance(step) // the last retry answers AWAITING_PRIOR_TERMINAL again and the budget is spent

        // One call now and one after each step up to the bound, then no more; closed with no LOCK_AUTH_* code.
        val expectedCalls = steps + 1
        assertEquals(expectedCalls, h.authenticator.calls.size)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertNull(controller.inFlightAttemptId)
        assertTrue(h.emitted.none { it in authCodes })
        assertTrue(h.authenticator.cancelCalls.isEmpty())
        assertEquals(0, h.activeJobs)

        h.advance(AppLockController.PROMPT_WATCHDOG_MILLIS)
        assertEquals("the retries are bounded", expectedCalls, h.authenticator.calls.size)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertTrue(h.emitted.none { it in authCodes })
        assertTrue(h.tap(controller) > id) // Unlock is enabled again
    }

    // --- P3: the watchdog ------------------------------------------------------------------------------------------

    @Test
    fun `P3 an unanswered attempt is closed by the watchdog as cancelled, and a late success is stale`() {
        val (h, controller) = lockedControllerFor(LockCause.TIMEOUT)
        h.emitted.clear()
        val id = h.tap(controller)
        val call = h.authenticator.calls.last()

        h.advance(AppLockController.PROMPT_WATCHDOG_MILLIS - 1)
        assertEquals(AppLockState.Authenticating(id, AuthPurpose.UNLOCK), controller.state.value)
        assertTrue(h.authenticator.cancelCalls.isEmpty())
        h.advance(1)

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertNull(controller.inFlightAttemptId)
        // Closed first, then the adapter was asked to cancel on main; its synchronous echo is ignored.
        assertEquals(listOf(id), h.authenticator.cancelCalls)
        assertEquals(listOf(true), h.authenticator.cancelOnMain)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED), h.emitted)
        assertEquals(0, h.activeJobs)

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded, call)
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        // Unlock is enabled again.
        assertTrue(h.tap(controller) > id)
    }

    @Test
    fun `P3 the watchdog is cancelled when the attempt closes and never closes a later attempt`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        val first = h.tap(controller)
        h.advance(AppLockController.PROMPT_WATCHDOG_MILLIS - 1_000)
        h.authenticator.deliver(DeviceAuthOutcome.Cancelled) // closes the first attempt
        assertEquals(0, h.activeJobs) // its watchdog is gone

        val second = h.tap(controller)
        assertEquals("only the second attempt's own watchdog is alive", 1, h.activeJobs)
        h.advance(2_000) // the first watchdog's old deadline passes
        assertEquals(AppLockState.Authenticating(second, AuthPurpose.UNLOCK), controller.state.value)
        assertTrue(h.authenticator.cancelCalls.isEmpty())
        assertTrue(second > first)

        h.advance(AppLockController.PROMPT_WATCHDOG_MILLIS) // the second watchdog's own deadline
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertEquals(listOf(second), h.authenticator.cancelCalls)
    }

    @Test
    fun `P3 a success that arrives in time cancels the watchdog and nothing happens at its old deadline`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        h.tap(controller)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        h.emitted.clear()

        h.advance(AppLockController.PROMPT_WATCHDOG_MILLIS * 2)

        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertTrue(h.authenticator.cancelCalls.isEmpty())
        assertTrue(h.emitted.isEmpty())
        assertEquals(0, h.activeJobs)
    }

    // --- T35 / AE-118: host loss -----------------------------------------------------------------------------------

    @Test
    fun `AE-118 T35 host loss closes an UNLOCK attempt before the adapter cancel echo and the echo is ignored`() {
        for (cause in LockCause.entries) {
            val (h, controller) = lockedControllerFor(cause)
            h.emitted.clear()
            val id = h.tap(controller)
            var stateAtCancel: AppLockState? = null
            h.authenticator.onCancel = { stateAtCancel = controller.state.value }

            controller.onPromptHostLost(id)

            // Closed at once, back on the origin, Unlock enabled; the adapter has not been asked yet.
            assertEquals("cause $cause", AppLockState.Locked(cause), controller.state.value)
            assertNull("cause $cause", controller.inFlightAttemptId)
            assertEquals("cause $cause", listOf(PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED), h.emitted)
            assertTrue("cause $cause", h.authenticator.cancelCalls.isEmpty())

            h.settle()

            assertEquals("cause $cause", listOf(id), h.authenticator.cancelCalls)
            assertEquals("cause $cause", listOf(true), h.authenticator.cancelOnMain)
            // The attempt was already closed when the adapter was asked to cancel (T35 before the echo).
            assertEquals("cause $cause", AppLockState.Locked(cause), stateAtCancel)
            // The adapter's synchronous Failed(SYSTEM_CANCELED) echo found the attempt closed: no second code.
            assertEquals("cause $cause", listOf(PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED), h.emitted)
            assertEquals("cause $cause", AppLockState.Locked(cause), controller.state.value)
            assertEquals("cause $cause", 0, h.activeJobs)
            assertTrue("cause $cause", h.tap(controller) > id)
        }
    }

    @Test
    fun `T35 host loss before the queued launch ran means the prompt is never launched`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        val started = controller.onUnlockTapped() as UnlockTapResult.Started

        controller.onPromptHostLost(started.attemptId)
        h.settle()

        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertTrue("the launch must not run for a closed attempt", h.authenticator.calls.isEmpty())
        assertEquals(0, h.activeJobs)
    }

    @Test
    fun `T35 host loss while the launch waits for the prior terminal stops the retries`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        h.authenticator.defaultResult = AuthLaunch.AWAITING_PRIOR_TERMINAL
        val id = h.tap(controller)
        assertEquals(1, h.authenticator.calls.size)

        controller.onPromptHostLost(id)
        h.advance(AppLockController.AWAITING_PRIOR_TERMINAL_BOUND_MILLIS * 2)

        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertEquals(1, h.authenticator.calls.size)
        assertEquals(0, h.activeJobs)
    }

    @Test
    fun `T12 a host loss for an unknown or stale id changes nothing`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        val id = h.tap(controller)
        h.emitted.clear()

        for (bad in listOf(0L, -1L, id + 1, id - 1, Long.MAX_VALUE)) {
            controller.onPromptHostLost(bad)
        }
        h.settle()

        assertEquals(AppLockState.Authenticating(id, AuthPurpose.UNLOCK), controller.state.value)
        assertEquals(id, controller.inFlightAttemptId)
        assertTrue(h.emitted.isEmpty())
        assertTrue(h.authenticator.cancelCalls.isEmpty())
    }

    @Test
    fun `both callback orders - the system cancel echo first, then host loss, is one cancel and a stale second event`() {
        val (h, controller) = lockedControllerFor(LockCause.TIMEOUT)
        h.emitted.clear()
        val id = h.tap(controller)

        h.authenticator.deliver(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED)) // the echo first
        controller.onPromptHostLost(id) // host loss arrives after the attempt is already closed
        h.settle()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_AUTH_ERROR), h.emitted)
        assertTrue(h.authenticator.cancelCalls.isEmpty())
    }

    @Test
    fun `AE-118 a late success for the abandoned id is stale, even after the app re-locked`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.resolvedController()
        val abandoned = h.tap(controller)
        val abandonedCall = h.authenticator.calls.last()
        controller.onPromptHostLost(abandoned)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)

        // The person unlocks with a new attempt, then the app re-locks.
        val second = h.tap(controller)
        assertTrue(second > abandoned)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded, abandonedCall)
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)

        // And while a third attempt is open, the abandoned id still cannot answer it.
        val third = h.tap(controller)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded, abandonedCall)
        assertEquals(AppLockState.Authenticating(third, AuthPurpose.UNLOCK), controller.state.value)
        h.authenticator.deliverWithId(abandoned, DeviceAuthOutcome.Succeeded) // right callback, wrong id
        assertEquals(AppLockState.Authenticating(third, AuthPurpose.UNLOCK), controller.state.value)
    }

    @Test
    fun `AE-118 a rotation keeps the attempt and a matching success on the recreated host is accepted`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        val id = h.tap(controller)

        // A rotation is not APP_BACKGROUNDED and not host loss: the controller hears nothing, the attempt stays.
        assertEquals(AppLockState.Authenticating(id, AuthPurpose.UNLOCK), controller.state.value)
        assertEquals(id, controller.inFlightAttemptId)
        h.authenticator.deliverWithId(id + 1, DeviceAuthOutcome.Succeeded) // a wrong id is still rejected
        assertEquals(AppLockState.Authenticating(id, AuthPurpose.UNLOCK), controller.state.value)

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertFalse(h.emitted.any { it in authCodes })
    }

    // --- R2: publication after the monitor is released ----------------------------------------------------------

    @Test
    fun `R2 an unconfined collector that calls back into the controller never runs inside the monitor`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.newController()
        val seen = CopyOnWriteArrayList<AppLockState>()
        val queried = CopyOnWriteArrayList<AppLockState>()
        val probesInTime = AtomicInteger(0)
        val collectorScope = CoroutineScope(Dispatchers.Unconfined)
        val collector = collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            controller.state.collect { state ->
                seen += state
                queried += controller.onLockQuery() // re-enters the controller on the publishing thread
                // Another thread must be able to take the monitor while this collector runs. If publication
                // happened inside the monitor, this probe would block and the join would time out. Counted on
                // the joining side: a probe still alive after the join did not get the monitor in time.
                val probe = thread(isDaemon = true) { controller.onPromptHostLost(-1L) }
                probe.join(5_000)
                if (!probe.isAlive) probesInTime.incrementAndGet()
            }
        }
        try {
            runBlocking { controller.resolve() }
            h.tap(controller)
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            controller.onAppBackgrounded()
            controller.onAppForegrounded()

            assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
            assertEquals("every publication let another thread take the monitor", seen.size, probesInTime.get())
            assertEquals(seen.toList(), queried.toList())
            assertEquals(controller.state.value, controller.onLockQuery())
            assertEquals(controller.state.value, seen.last())
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `R2 a transition started by a collector during publication leaves the newest state published`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.resolvedController()
        val seen = CopyOnWriteArrayList<AppLockState>()
        var reacted = false
        val collectorScope = CoroutineScope(Dispatchers.Unconfined)
        val collector = collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            controller.state.collect { state ->
                seen += state
                if (state == AppLockState.Unlocked && !reacted) {
                    reacted = true
                    // A full background and foreground while UNLOCKED is being published: IMMEDIATE re-locks.
                    controller.onAppBackgrounded()
                    controller.onAppForegrounded()
                }
            }
        }
        try {
            val id = h.tap(controller)
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

            assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
            // The older Unlocked is never published after the newer Locked(TIMEOUT).
            assertEquals(
                listOf(
                    AppLockState.Locked(LockCause.COLD_START),
                    AppLockState.Authenticating(id, AuthPurpose.UNLOCK),
                    AppLockState.Unlocked,
                    AppLockState.Locked(LockCause.TIMEOUT)
                ),
                seen.toList()
            )
            assertTrue(PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT in h.emitted)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `R2 a stale value is never published - publish drops a stamp no newer than the published version`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.resolvedController()
        h.tap(controller)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // a normal transition: UNLOCKED is published
        assertEquals(AppLockState.Unlocked, controller.state.value)
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        val published = AppLockState.Locked(LockCause.TIMEOUT)
        assertEquals(published, controller.state.value)

        // Deterministic pin on the private `publish(stamp, next)` and the version it guards. Only stamps and
        // states are fed in; the expected outcome is "nothing changes" and then "the newer one is published".
        val publish = AppLockController::class.java
            .getDeclaredMethod("publish", Long::class.javaPrimitiveType, AppLockState::class.java)
            .apply { isAccessible = true }
        val publishedVersionField = AppLockController::class.java
            .getDeclaredField("publishedVersion")
            .apply { isAccessible = true }
        val lastVersion = publishedVersionField.getLong(controller)
        assertTrue("a normal transition published at least one version", lastVersion > 0L)

        // A different state with a stamp equal to, older than, or far older than the published version is dropped.
        for (stale in listOf(lastVersion, lastVersion - 1, 1L, 0L)) {
            publish.invoke(controller, stale, AppLockState.Unlocked)
            assertEquals("stale stamp $stale", published, controller.state.value)
            assertEquals("stale stamp $stale", lastVersion, publishedVersionField.getLong(controller))
        }

        // The same call with a newer stamp is published, so the pin above cannot pass vacuously
        // (a wrong method, a wrong argument order or a method that never publishes would fail here).
        publish.invoke(controller, lastVersion + 1, AppLockState.Disabled)
        assertEquals(AppLockState.Disabled, controller.state.value)
        assertEquals(lastVersion + 1, publishedVersionField.getLong(controller))
    }

    // =====================================================================================================
    // S16 part (ii-b): ENABLE and CONFIRM_CHANGE attempts. The start API (driven with a no-op change: a
    // recording success handler), T32 (promptBackgroundAt), the T33 hand-off, T34 and T35 for every purpose.
    // =====================================================================================================

    private val confirmPrompts = listOf(OpenOriginPrompt.DISABLE, OpenOriginPrompt.CHANGE)

    /** One way an in-flight attempt can end: a named action on the harness, the controller and the attempt id. */
    private data class Ending(
        val name: String,
        val end: (AppLockControllerHarness, AppLockController, Long) -> Unit
    )

    private fun openOriginController(
        prompt: OpenOriginPrompt,
        timeout: AutoLockTimeout = AutoLockTimeout.M1
    ): Pair<AppLockControllerHarness, AppLockController> =
        if (prompt.purpose == AuthPurpose.ENABLE) disabledControllerFor() else unlockedControllerFor(timeout)

    private fun originOf(prompt: OpenOriginPrompt): AppLockState =
        if (prompt.purpose == AuthPurpose.ENABLE) AppLockState.Disabled else AppLockState.Unlocked

    // --- The start API: copy selector, open before authenticate, on main (P1, P5) -------------------------------

    @Test
    fun `ii-b the start API opens the attempt before authenticate, maps the copy and purpose, and launches on main`() {
        val expectedCopy = mapOf(
            OpenOriginPrompt.ENABLE to AuthPromptCopy.ENABLE,
            OpenOriginPrompt.DISABLE to AuthPromptCopy.DISABLE,
            OpenOriginPrompt.CHANGE to AuthPromptCopy.CHANGE
        )
        val expectedPurpose = mapOf(
            OpenOriginPrompt.ENABLE to AuthPurpose.ENABLE,
            OpenOriginPrompt.DISABLE to AuthPurpose.CONFIRM_CHANGE,
            OpenOriginPrompt.CHANGE to AuthPurpose.CONFIRM_CHANGE
        )
        assertEquals(OpenOriginPrompt.entries.toSet(), expectedCopy.keys)
        for (prompt in OpenOriginPrompt.entries) {
            assertEquals("$prompt", expectedCopy.getValue(prompt), prompt.copy)
            assertEquals("$prompt", expectedPurpose.getValue(prompt), prompt.purpose)

            val (h, controller) = openOriginController(prompt)
            var stateAtCall: AppLockState? = null
            var openAtCall: Long? = null
            h.authenticator.onAuthenticate = {
                stateAtCall = controller.state.value
                openAtCall = controller.inFlightAttemptId
            }
            val callsBefore = h.authenticator.calls.size
            val lastIdBefore = h.authenticator.calls.lastOrNull()?.attemptId ?: 0L

            val started = controller.startOpenOriginAttempt(prompt, RecordingSuccessHandler()) as OpenOriginStartResult.Started

            // The start returned without calling the authenticator: the prompt call is queued on main.
            assertEquals("$prompt", callsBefore, h.authenticator.calls.size)
            val open = AppLockState.Authenticating(started.attemptId, prompt.purpose)
            assertEquals("$prompt", open, controller.state.value)
            assertEquals("$prompt", started.attemptId, controller.inFlightAttemptId)
            assertTrue("$prompt: ids are strictly increasing across purposes", started.attemptId > lastIdBefore)
            h.settle()

            val call = h.authenticator.calls.last()
            assertEquals("$prompt", callsBefore + 1, h.authenticator.calls.size)
            assertEquals("$prompt", started.attemptId, call.attemptId)
            assertEquals("$prompt", expectedCopy.getValue(prompt), call.copy)
            assertTrue("$prompt: authenticate must run on the main dispatcher", call.onMain)
            assertEquals("$prompt", open, stateAtCall)
            assertEquals("$prompt", started.attemptId, openAtCall)
        }
    }

    @Test
    fun `ii-b authenticate and cancel of an open-origin attempt are never called under the controller's monitor`() {
        for (prompt in OpenOriginPrompt.entries) {
            val (h, controller) = openOriginController(prompt)
            val probesInTime = AtomicInteger(0)
            val probe: (Long) -> Unit = {
                val t = thread(isDaemon = true) { controller.onPromptHostLost(-1L) }
                t.join(5_000)
                if (!t.isAlive) probesInTime.incrementAndGet()
            }
            h.authenticator.onAuthenticate = probe
            h.authenticator.onCancel = probe

            val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
            controller.onPromptHostLost(id)
            h.settle()

            assertEquals("$prompt", 2, probesInTime.get())
            assertEquals("$prompt", listOf(id), h.authenticator.cancelCalls)
        }
    }

    // --- T33: a matching success is handed over, nothing is applied -------------------------------------------

    @Test
    fun `T33 a matching success returns to the origin, hands the success over once and applies nothing`() {
        for (prompt in OpenOriginPrompt.entries) {
            val (h, controller) = openOriginController(prompt)
            h.emitted.clear()
            val handler = RecordingSuccessHandler()
            val id = h.startOpenOrigin(controller, prompt, handler)
            val call = h.authenticator.calls.last()

            // A success with another id never reaches the handler.
            h.authenticator.deliverWithId(id + 1, DeviceAuthOutcome.Succeeded)
            assertEquals("$prompt", AppLockState.Authenticating(id, prompt.purpose), controller.state.value)
            assertTrue("$prompt", handler.delivered.isEmpty())

            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

            // ENABLE stays DISABLED and CONFIRM_CHANGE stays UNLOCKED: S18 applies the change and moves on.
            assertEquals("$prompt", originOf(prompt), controller.state.value)
            assertEquals("$prompt", listOf(id to prompt.purpose), handler.delivered)
            assertNull("$prompt", controller.inFlightAttemptId)
            assertEquals("$prompt", 0, h.activeJobs)
            assertEquals("$prompt", 0, h.repository.writeAttempts)
            assertTrue("$prompt: no unlock code and no LOCK_AUTH code", h.emitted.none {
                it == PrivacyDiagnosticCode.LOCK_UNLOCKED || it in authCodes
            })

            // A repeated or late callback of the same attempt is stale; so is the watchdog's old deadline.
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded, call)
            h.authenticator.deliver(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER), call)
            h.advance(AppLockController.PROMPT_WATCHDOG_MILLIS * 2)
            assertEquals("$prompt", originOf(prompt), controller.state.value)
            assertEquals("$prompt", 1, handler.delivered.size)
            assertTrue("$prompt", h.authenticator.cancelCalls.isEmpty())
        }
    }

    @Test
    fun `T33 an outcome delivered synchronously inside authenticate is handed over, the attempt was already open`() {
        for (prompt in OpenOriginPrompt.entries) {
            val (h, controller) = openOriginController(prompt)
            h.authenticator.reentrantOutcome = DeviceAuthOutcome.Succeeded
            val handler = RecordingSuccessHandler()

            val id = h.startOpenOrigin(controller, prompt, handler)

            assertEquals("$prompt", originOf(prompt), controller.state.value)
            assertEquals("$prompt", listOf(id to prompt.purpose), handler.delivered)
            assertNull("$prompt", controller.inFlightAttemptId)
            assertEquals("$prompt", 0, h.activeJobs)
        }
    }

    @Test
    fun `T33 a failing success handler never affects the controller and is not swallowed into an error state`() {
        for (prompt in OpenOriginPrompt.entries) {
            val (h, controller) = openOriginController(prompt)
            val handler = RecordingSuccessHandler().apply { failure = IllegalStateException("secret-path /data/x") }
            val id = h.startOpenOrigin(controller, prompt, handler)

            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

            assertEquals("$prompt", originOf(prompt), controller.state.value)
            assertEquals("$prompt", listOf(id to prompt.purpose), handler.delivered)
            assertEquals("$prompt", emptyList<Throwable>(), h.uncaught)
            // The control is enabled again.
            assertTrue("$prompt", controller.startOpenOriginAttempt(prompt, handler) is OpenOriginStartResult.Started)
        }
    }

    // --- T34: every non-success end returns to the origin, nothing written --------------------------------------

    @Test
    fun `T34 every non-success outcome of an open-origin attempt returns to the origin, records a bounded code and writes nothing`() {
        val noMetadata = emptyMap<PrivacyDiagnosticKey, Any>()
        val cases: List<Triple<DeviceAuthOutcome, PrivacyDiagnosticCode, Map<PrivacyDiagnosticKey, Any>>> = listOf(
            Triple(DeviceAuthOutcome.Cancelled, PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED, noMetadata),
            Triple(DeviceAuthOutcome.LockedOut(permanent = false), PrivacyDiagnosticCode.LOCK_AUTH_LOCKOUT, noMetadata),
            Triple(DeviceAuthOutcome.LockedOut(permanent = true), PrivacyDiagnosticCode.LOCK_AUTH_LOCKOUT, noMetadata)
        ) + DeviceAuthErrorBucket.entries.map { bucket ->
            Triple(
                DeviceAuthOutcome.Failed(bucket),
                PrivacyDiagnosticCode.LOCK_AUTH_ERROR,
                mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.CAUSE to bucket)
            )
        }
        for (prompt in OpenOriginPrompt.entries) {
            for ((outcome, code, metadata) in cases) {
                val (h, controller) = openOriginController(prompt)
                h.emitted.clear()
                h.emittedMetadata.clear()
                val handler = RecordingSuccessHandler()
                val id = h.startOpenOrigin(controller, prompt, handler)

                h.authenticator.deliver(outcome)

                val label = "$prompt / $outcome"
                assertEquals(label, originOf(prompt), controller.state.value)
                assertNull(label, controller.inFlightAttemptId)
                assertEquals(label, listOf(code), h.emitted)
                assertEquals(label, metadata, h.emittedMetadata.single().entries)
                assertTrue(label, handler.delivered.isEmpty())
                assertEquals(label, 0, h.repository.writeAttempts)
                assertEquals(label, 0, h.activeJobs)
                assertTrue(label, h.authenticator.cancelCalls.isEmpty())
                // The settings switch is enabled again: a new request opens a new, larger attempt.
                val again = controller.startOpenOriginAttempt(prompt, handler)
                assertTrue(label, again is OpenOriginStartResult.Started && again.attemptId > id)
            }
        }
    }

    @Test
    fun `spec 10 the LOCK_AUTH codes of open-origin attempts are emitted once per process per cause`() {
        val (h, controller) = unlockedControllerFor()
        h.emitted.clear()
        fun attempt(outcome: DeviceAuthOutcome) {
            h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
            h.authenticator.deliver(outcome)
            assertEquals(AppLockState.Unlocked, controller.state.value)
        }

        attempt(DeviceAuthOutcome.Cancelled)
        attempt(DeviceAuthOutcome.Cancelled)
        attempt(DeviceAuthOutcome.LockedOut(permanent = false))
        attempt(DeviceAuthOutcome.LockedOut(permanent = true))
        attempt(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.TIMEOUT))
        attempt(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.TIMEOUT))

        assertEquals(
            listOf(
                PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED,
                PrivacyDiagnosticCode.LOCK_AUTH_LOCKOUT,
                PrivacyDiagnosticCode.LOCK_AUTH_ERROR
            ),
            h.emitted.filter { it in authCodes }
        )
    }

    @Test
    fun `P2 a launch that did not happen returns an open-origin attempt to its origin with no LOCK_AUTH code`() {
        for (result in listOf(AuthLaunch.ALREADY_IN_FLIGHT, AuthLaunch.HOST_UNAVAILABLE, AuthLaunch.PROMPT_UNAVAILABLE)) {
            for (prompt in OpenOriginPrompt.entries) {
                val (h, controller) = openOriginController(prompt)
                h.emitted.clear()
                h.authenticator.defaultResult = result
                val handler = RecordingSuccessHandler()

                val id = h.startOpenOrigin(controller, prompt, handler)

                val label = "$result / $prompt"
                assertEquals(label, originOf(prompt), controller.state.value)
                assertNull(label, controller.inFlightAttemptId)
                assertTrue(label, h.emitted.none { it in authCodes })
                assertTrue(label, h.authenticator.cancelCalls.isEmpty())
                assertTrue(label, handler.delivered.isEmpty())
                assertEquals(label, 0, h.activeJobs)
                h.authenticator.defaultResult = AuthLaunch.LAUNCHED
                val again = controller.startOpenOriginAttempt(prompt, handler)
                assertTrue(label, again is OpenOriginStartResult.Started && again.attemptId > id)
            }
        }
    }

    // --- NotAvailable: ENABLE is a T26-style refusal, CONFIRM_CHANGE has no INERT row (T34) --------------------

    @Test
    fun `ENABLE NotAvailable with HW_UNAVAILABLE or UNKNOWN is a refusal with the cause of the re-check, once per process per cause`() {
        val (h, controller) = disabledControllerFor()
        h.emitted.clear()
        h.emittedMetadata.clear()
        val handler = RecordingSuccessHandler()

        fun refusedBy(capability: DeviceAuthCapability) {
            h.startOpenOrigin(controller, OpenOriginPrompt.ENABLE, handler)
            h.capabilityProvider.capability = capability
            val evaluationsBefore = h.capabilityProvider.evaluations
            h.authenticator.deliver(DeviceAuthOutcome.NotAvailable)
            assertEquals("$capability", AppLockState.Disabled, controller.state.value)
            assertNull("$capability", controller.inFlightAttemptId)
            // The capability was re-checked once, at the outcome (never cached from the request).
            assertEquals("$capability", evaluationsBefore + 1, h.capabilityProvider.evaluations)
        }

        refusedBy(DeviceAuthCapability.HW_UNAVAILABLE)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY), h.emitted)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.CAUSE to DeviceAuthCapability.HW_UNAVAILABLE),
            h.emittedMetadata.single().entries
        )

        refusedBy(DeviceAuthCapability.HW_UNAVAILABLE) // the same cause again: silent
        assertEquals(1, h.emitted.count { it == PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY })

        refusedBy(DeviceAuthCapability.UNKNOWN) // another cause: emitted once
        val capabilityEntries = h.emitted.indices.filter { h.emitted[it] == PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY }
        assertEquals(
            listOf(DeviceAuthCapability.HW_UNAVAILABLE, DeviceAuthCapability.UNKNOWN),
            capabilityEntries.map { h.emittedMetadata[it].entries.getValue(PrivacyDiagnosticKey.CAUSE) }
        )
        assertTrue(handler.delivered.isEmpty())
        assertEquals(0, h.repository.writeAttempts)
    }

    @Test
    fun `ENABLE NotAvailable with NO_CREDENTIAL or UNSUPPORTED_API is a bounded OTHER error, stays DISABLED, writes nothing and is never LOCK_UNAVAILABLE_CAPABILITY`() {
        // TODO(S18) owns LOCK_UNAVAILABLE_NOT_SECURE / LOCK_UNAVAILABLE_API for these two causes.
        for (capability in listOf(DeviceAuthCapability.NO_CREDENTIAL, DeviceAuthCapability.UNSUPPORTED_API)) {
            val (h, controller) = disabledControllerFor()
            h.emitted.clear()
            h.emittedMetadata.clear()
            val handler = RecordingSuccessHandler()
            h.startOpenOrigin(controller, OpenOriginPrompt.ENABLE, handler)
            h.capabilityProvider.capability = capability

            h.authenticator.deliver(DeviceAuthOutcome.NotAvailable)

            assertEquals("$capability", AppLockState.Disabled, controller.state.value)
            assertNull("$capability", controller.inFlightAttemptId)
            assertEquals("$capability", listOf(PrivacyDiagnosticCode.LOCK_AUTH_ERROR), h.emitted)
            assertEquals(
                "$capability",
                mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.CAUSE to DeviceAuthErrorBucket.OTHER),
                h.emittedMetadata.single().entries
            )
            assertFalse("$capability", h.emitted.contains(PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY))
            assertTrue("$capability", handler.delivered.isEmpty())
            assertEquals("$capability", 0, h.repository.writeAttempts)
        }
    }

    @Test
    fun `ENABLE NotAvailable with a failed capability check refuses as UNKNOWN, and with AVAILABLE it is a bounded error`() {
        val (h, controller) = disabledControllerFor()
        h.emitted.clear()
        h.emittedMetadata.clear()
        h.startOpenOrigin(controller, OpenOriginPrompt.ENABLE, RecordingSuccessHandler())
        h.capabilityProvider.capability = DeviceAuthCapability.CHECK_FAILED

        h.authenticator.deliver(DeviceAuthOutcome.NotAvailable)

        assertEquals(AppLockState.Disabled, controller.state.value) // never proceeds on an unknown capability
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY), h.emitted)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.CAUSE to DeviceAuthCapability.UNKNOWN),
            h.emittedMetadata.single().entries
        )

        // The platform and the capability disagree: not a refusal, a bounded prompt error.
        h.emitted.clear()
        h.emittedMetadata.clear()
        h.capabilityProvider.capability = DeviceAuthCapability.AVAILABLE
        h.startOpenOrigin(controller, OpenOriginPrompt.ENABLE, RecordingSuccessHandler())
        h.authenticator.deliver(DeviceAuthOutcome.NotAvailable)

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_AUTH_ERROR), h.emitted)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.CAUSE to DeviceAuthErrorBucket.OTHER),
            h.emittedMetadata.single().entries
        )
    }

    @Test
    fun `ENABLE refusals under a failed capability check claim UNKNOWN once per process, keyed by code and cause`() {
        val (h, controller) = disabledControllerFor(DeviceAuthCapability.CHECK_FAILED)
        h.emitted.clear()
        h.startOpenOrigin(controller, OpenOriginPrompt.ENABLE, RecordingSuccessHandler())
        h.authenticator.deliver(DeviceAuthOutcome.NotAvailable) // CHECK_FAILED re-check: cause UNKNOWN

        h.startOpenOrigin(controller, OpenOriginPrompt.ENABLE, RecordingSuccessHandler())
        h.authenticator.deliver(DeviceAuthOutcome.NotAvailable)

        assertEquals(1, h.emitted.count { it == PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY })
        val claimedField = AppLockController::class.java.getDeclaredField("claimedCodeCauses")
        claimedField.isAccessible = true
        assertEquals(
            setOf<Any?>(PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY to DeviceAuthCapability.UNKNOWN),
            (claimedField.get(controller) as Set<*>).toSet()
        )
    }

    @Test
    fun `CONFIRM_CHANGE NotAvailable returns to UNLOCKED as a bounded error, never INERT, and T16 applies at the next foreground`() {
        for (prompt in confirmPrompts) {
            val (h, controller) = unlockedControllerFor()
            h.emitted.clear()
            h.emittedMetadata.clear()
            h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
            h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL

            h.authenticator.deliver(DeviceAuthOutcome.NotAvailable)

            assertEquals("$prompt", AppLockState.Unlocked, controller.state.value)
            assertEquals("$prompt", listOf(PrivacyDiagnosticCode.LOCK_AUTH_ERROR), h.emitted)
            assertEquals(
                "$prompt",
                mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.CAUSE to DeviceAuthErrorBucket.OTHER),
                h.emittedMetadata.single().entries
            )

            controller.onAppForegrounded() // T16
            assertEquals("$prompt", AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        }
    }

    // --- AE-116: no time accrues while the prompt is shown; a real background goes to the timer (T32, T34) -----

    @Test
    fun `AE-116 a 10 minute foreground prompt accrues nothing, whatever ends it, and the monotonic clock is the only input`() {
        val endings = listOf(
            Ending("cancel") { h, _, _ -> h.authenticator.deliver(DeviceAuthOutcome.Cancelled) },
            Ending("lockout") { h, _, _ -> h.authenticator.deliver(DeviceAuthOutcome.LockedOut(permanent = true)) },
            Ending("error") { h, _, _ -> h.authenticator.deliver(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER)) },
            Ending("host loss") { _, controller, id -> controller.onPromptHostLost(id) }
        )
        for (prompt in confirmPrompts) {
            for ((name, end) in endings) {
                val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
                val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
                val open = AppLockState.Authenticating(id, AuthPurpose.CONFIRM_CHANGE)

                // Ten minutes pass on the monotonic clock only (no coroutine time, so the watchdog is not due).
                h.clock.advance(10 * 60_000L)
                controller.onAppForegrounded()
                assertEquals("$prompt / $name", open, controller.onLockQuery()) // T32: nothing is evaluated
                assertEquals("$prompt / $name", open, controller.state.value)
                assertNull("$prompt / $name", controller.promptBackgroundAtMillis)
                assertNull("$prompt / $name", controller.pendingBackgroundAtMillis)

                end(h, controller, id)

                assertEquals("$prompt / $name", AppLockState.Unlocked, controller.state.value)
                assertNull("$prompt / $name", controller.pendingBackgroundAtMillis)
                assertFalse("$prompt / $name", PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT in h.emitted)
                controller.onAppForegrounded()
                assertEquals("$prompt / $name", AppLockState.Unlocked, controller.state.value)
            }
        }
    }

    private enum class CallbackOrder {
        /** The system cancel is delivered before APP_BACKGROUNDED. */
        CANCEL_BEFORE_BACKGROUND,

        /** APP_BACKGROUNDED, then the cancel while still in the background, then APP_FOREGROUNDED. */
        CANCEL_WHILE_BACKGROUNDED,

        /** APP_BACKGROUNDED, APP_FOREGROUNDED during the prompt, then the cancel in the foreground. */
        CANCEL_AFTER_FOREGROUND
    }

    /** Home at t0, the system cancels the prompt, the app returns after [awayMillis]. Returns the final harness state. */
    private fun homeAndReturn(
        prompt: OpenOriginPrompt,
        order: CallbackOrder,
        awayMillis: Long
    ): Pair<AppLockControllerHarness, AppLockController> {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
        val open = AppLockState.Authenticating(id, AuthPurpose.CONFIRM_CHANGE)
        val systemCancel = DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED)
        when (order) {
            CallbackOrder.CANCEL_BEFORE_BACKGROUND -> {
                h.authenticator.deliver(systemCancel)
                assertEquals(AppLockState.Unlocked, controller.state.value)
                controller.onAppBackgrounded() // an ordinary T13 now
                h.clock.advance(awayMillis)
                controller.onAppForegrounded()
            }
            CallbackOrder.CANCEL_WHILE_BACKGROUNDED -> {
                controller.onAppBackgrounded() // Home at t0
                val t0 = h.clock.nowMillis
                assertEquals(t0, controller.promptBackgroundAtMillis) // T32
                assertNull(controller.pendingBackgroundAtMillis) // not a T13: nothing evaluated while in flight
                h.authenticator.deliver(systemCancel)
                // AE-116: UNLOCKED, lastBackgroundedAt = t0, and nothing is evaluated while still backgrounded.
                assertEquals(AppLockState.Unlocked, controller.state.value)
                assertEquals(t0, controller.pendingBackgroundAtMillis)
                assertNull(controller.promptBackgroundAtMillis)
                h.clock.advance(awayMillis)
                controller.onAppForegrounded()
            }
            CallbackOrder.CANCEL_AFTER_FOREGROUND -> {
                controller.onAppBackgrounded()
                h.clock.advance(awayMillis)
                controller.onAppForegrounded()
                // T32: the foreground evaluates nothing while the prompt is in flight.
                assertEquals(open, controller.state.value)
                h.authenticator.deliver(systemCancel) // T34 evaluates at once: the app is in the foreground
            }
        }
        return h to controller
    }

    @Test
    fun `AE-116 Home then the system cancel - 59_999 ms is UNLOCKED and 60_000 ms is LOCKED TIMEOUT, in every callback order`() {
        for (prompt in confirmPrompts) {
            for (order in CallbackOrder.entries) {
                val (hBefore, before) = homeAndReturn(prompt, order, 59_999L)
                assertEquals("$prompt / $order at 59.999 s", AppLockState.Unlocked, before.state.value)
                assertFalse("$prompt / $order", PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT in hBefore.emitted)
                assertNull("$prompt / $order", before.pendingBackgroundAtMillis)

                val (hAt, at) = homeAndReturn(prompt, order, 60_000L)
                assertEquals("$prompt / $order at 60.000 s", AppLockState.Locked(LockCause.TIMEOUT), at.state.value)
                assertEquals("$prompt / $order", 1, hAt.emitted.count { it == PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT })
                assertNull("$prompt / $order", at.inFlightAttemptId)
            }
        }
    }

    @Test
    fun `T34 an immediate lock is published as the lock, never as UNLOCKED first`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        val seen = CopyOnWriteArrayList<AppLockState>()
        val collectorScope = CoroutineScope(Dispatchers.Unconfined)
        val collector = collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            controller.state.collect { seen += it }
        }
        try {
            val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
            controller.onAppBackgrounded()
            h.clock.advance(60_000L)
            controller.onAppForegrounded()

            h.authenticator.deliver(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED))

            assertEquals(
                listOf(
                    AppLockState.Unlocked,
                    AppLockState.Authenticating(id, AuthPurpose.CONFIRM_CHANGE),
                    AppLockState.Locked(LockCause.TIMEOUT)
                ),
                seen.toList()
            )
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `T34 every non-success end of a confirm attempt in the foreground evaluates the timer at once, at the boundary`() {
        val endings = listOf(
            Ending("cancel") { h, _, _ -> h.authenticator.deliver(DeviceAuthOutcome.Cancelled) },
            Ending("lockout") { h, _, _ -> h.authenticator.deliver(DeviceAuthOutcome.LockedOut(permanent = false)) },
            Ending("error") { h, _, _ -> h.authenticator.deliver(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER)) },
            Ending("host loss (T35)") { _, controller, id -> controller.onPromptHostLost(id) },
            Ending("watchdog (F5)") { h, _, _ -> h.advance(AppLockController.PROMPT_WATCHDOG_MILLIS) }
        )
        for (prompt in confirmPrompts) {
            for ((name, end) in endings) {
                for ((away, expected) in listOf(
                    59_999L to AppLockState.Unlocked,
                    60_000L to AppLockState.Locked(LockCause.TIMEOUT)
                )) {
                    val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
                    val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
                    controller.onAppBackgrounded()
                    h.clock.advance(away) // the monotonic clock; the watchdog above uses coroutine time only
                    controller.onAppForegrounded()
                    assertEquals("$prompt / $name", AppLockState.Authenticating(id, AuthPurpose.CONFIRM_CHANGE), controller.state.value)

                    end(h, controller, id)

                    assertEquals("$prompt / $name at $away ms", expected, controller.state.value)
                    assertNull("$prompt / $name", controller.pendingBackgroundAtMillis) // consumed by T14/T15
                    assertNull("$prompt / $name", controller.inFlightAttemptId)
                }
            }
        }
    }

    @Test
    fun `T34 a prompt that never launched still hands its background to the timer`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        h.authenticator.defaultResult = AuthLaunch.HOST_UNAVAILABLE
        controller.startOpenOriginAttempt(OpenOriginPrompt.DISABLE, RecordingSuccessHandler())
        controller.onAppBackgrounded() // before the queued launch ran
        val t0 = h.clock.nowMillis

        h.settle() // the launch answers HOST_UNAVAILABLE: closed as not launched, in the background

        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(t0, controller.pendingBackgroundAtMillis)
        h.clock.advance(60_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `T32 the first background of the attempt is recorded, later ones foregrounds and queries do not move it`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        val open = AppLockState.Authenticating(id, AuthPurpose.CONFIRM_CHANGE)
        assertNull(controller.promptBackgroundAtMillis)

        controller.onAppBackgrounded()
        val first = h.clock.nowMillis
        assertEquals(first, controller.promptBackgroundAtMillis)
        assertNull(controller.pendingBackgroundAtMillis)
        h.clock.advance(30_000L)
        controller.onAppForegrounded()
        assertEquals(open, controller.onLockQuery())
        controller.onAppBackgrounded() // a second background of the same attempt
        h.clock.advance(10 * 60_000L)
        assertEquals(open, controller.onLockQuery()) // holds its link, evaluates nothing
        assertEquals(first, controller.promptBackgroundAtMillis)
        assertNull(controller.pendingBackgroundAtMillis)
        assertFalse(PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT in h.emitted)

        h.authenticator.deliver(DeviceAuthOutcome.Cancelled) // still in the background

        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(first, controller.pendingBackgroundAtMillis) // the first background wins
        assertNull(controller.promptBackgroundAtMillis) // cleared when the attempt ended
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `T32 promptBackgroundAt belongs to its attempt, a new attempt starts with none`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M15)
        h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        controller.onAppBackgrounded()
        assertTrue(controller.promptBackgroundAtMillis != null)
        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
        controller.onAppForegrounded() // consumed: 0 ms away, back within the timeout

        h.startOpenOrigin(controller, OpenOriginPrompt.DISABLE, RecordingSuccessHandler())

        assertNull(controller.promptBackgroundAtMillis)
    }

    @Test
    fun `T34 with origin DISABLED the prompt background is dropped, and ENABLE never starts a timer`() {
        val (h, controller) = disabledControllerFor()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.ENABLE, RecordingSuccessHandler())
        controller.onAppBackgrounded()
        assertTrue(controller.promptBackgroundAtMillis != null)
        assertEquals(AppLockState.Authenticating(id, AuthPurpose.ENABLE), controller.state.value)
        h.clock.advance(60 * 60_000L)

        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertNull(controller.promptBackgroundAtMillis)
        assertNull(controller.pendingBackgroundAtMillis)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Disabled, controller.state.value)
        assertFalse(PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT in h.emitted)
    }

    @Test
    fun `T32 a background during an UNLOCK prompt records no promptBackgroundAt`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        h.tap(controller)

        controller.onAppBackgrounded()

        assertNull(controller.promptBackgroundAtMillis)
        assertNull(controller.pendingBackgroundAtMillis)
    }

    @Test
    fun `P6 the foreground flag is cleared by the background before the early return, so T34 never evaluates while backgrounded`() {
        // Foreground seen (the fixture reports it), then background during the prompt: the cancel that arrives
        // while still backgrounded must not evaluate the timer, and the hand-off waits for the next foreground.
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        controller.onAppBackgrounded()
        val t0 = h.clock.nowMillis
        h.clock.advance(5 * 60_000L)

        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)

        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(t0, controller.pendingBackgroundAtMillis)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    // --- F1: a background that was pending when the prompt started is handed to the timer (T34) ---------------

    @Test
    fun `F1 a background pending before the prompt started is handed to the timer even when the prompt recorded none, at the boundary`() {
        for ((away, expected) in listOf(
            59_999L to AppLockState.Unlocked,
            60_000L to AppLockState.Locked(LockCause.TIMEOUT)
        )) {
            val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
            controller.onAppBackgrounded() // the app goes to the background while UNLOCKED: pending is recorded
            val t0 = h.clock.nowMillis
            assertEquals(t0, controller.pendingBackgroundAtMillis)
            h.clock.advance(away)

            val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
            controller.onAppForegrounded() // nothing is evaluated while the prompt is open (T32)
            assertEquals("at $away ms", AppLockState.Authenticating(id, AuthPurpose.CONFIRM_CHANGE), controller.state.value)
            assertNull("at $away ms", controller.promptBackgroundAtMillis) // the prompt itself recorded no background

            h.authenticator.deliver(DeviceAuthOutcome.Cancelled)

            assertEquals("at $away ms", expected, controller.state.value)
            assertNull("at $away ms", controller.pendingBackgroundAtMillis) // consumed by T14/T15
            assertNull("at $away ms", controller.inFlightAttemptId)
        }
    }

    // --- F4: a failing capability read during the close never leaves the attempt open ---------------------------

    @Test
    fun `F4 a capability read that throws during the close still closes the attempt and then rethrows what it threw`() {
        class CapabilityBoom : Error("capability boom")
        val failures = listOf<Throwable>(CapabilityBoom(), CancellationException("cancelled read"))
        for (failure in failures) {
            val label = failure.javaClass.simpleName
            val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
            val handler = RecordingSuccessHandler()
            val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, handler)
            h.capabilityProvider.failure = failure
            val activeBefore = h.activeJobs

            var thrown: Throwable? = null
            try {
                h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
            } catch (t: Throwable) {
                thrown = t
            }

            assertTrue(label, thrown === failure) // surfaced unchanged, after the close
            assertEquals(label, AppLockState.Unlocked, controller.state.value)
            assertNull(label, controller.inFlightAttemptId)
            assertTrue(label, handler.delivered.isEmpty())
            assertTrue(label, h.activeJobs < activeBefore) // the attempt's watchdog and orchestrator were cancelled
            assertEquals(label, listOf(PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED), h.emitted.takeLast(1))

            // The attempt is closed for good: a late success for it is stale, and a new attempt can start.
            h.capabilityProvider.failure = null
            h.authenticator.deliverWithId(id, DeviceAuthOutcome.Succeeded)
            assertEquals(label, AppLockState.Unlocked, controller.state.value)
            assertTrue(label, handler.delivered.isEmpty())
            val again = controller.startOpenOriginAttempt(OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
            assertTrue(label, again is OpenOriginStartResult.Started && again.attemptId > id)
        }
    }

    @Test
    fun `F4 a NotAvailable whose capability re-check throws still closes the attempt back on its origin and rethrows what it threw`() {
        class RecheckBoom : Error("recheck boom")
        for (prompt in OpenOriginPrompt.entries) {
            val label = "$prompt"
            val (h, controller) = openOriginController(prompt)
            val handler = RecordingSuccessHandler()
            val id = h.startOpenOrigin(controller, prompt, handler)
            val failure = RecheckBoom()
            h.capabilityProvider.failure = failure
            val activeBefore = h.activeJobs

            var thrown: Throwable? = null
            try {
                h.authenticator.deliver(DeviceAuthOutcome.NotAvailable)
            } catch (t: Throwable) {
                thrown = t
            }

            assertTrue(label, thrown === failure) // surfaced unchanged, after the close
            assertEquals(label, originOf(prompt), controller.state.value) // never INERT, never left on the prompt
            assertNull(label, controller.inFlightAttemptId)
            assertTrue(label, handler.delivered.isEmpty())
            assertTrue(label, h.activeJobs < activeBefore)

            // The attempt is closed for good: a late success is stale, and a new attempt can start.
            h.capabilityProvider.failure = null
            h.authenticator.deliverWithId(id, DeviceAuthOutcome.Succeeded)
            assertEquals(label, originOf(prompt), controller.state.value)
            assertTrue(label, handler.delivered.isEmpty())
            val again = controller.startOpenOriginAttempt(prompt, RecordingSuccessHandler())
            assertTrue(label, again is OpenOriginStartResult.Started && again.attemptId > id)
        }
    }

    // --- F5: the defensive stale-success branch still hands off the prompt background ---------------------------

    @Test
    fun `F5 a stale success of a CONFIRM_CHANGE attempt returns to UNLOCKED, applies nothing and still hands off the prompt background`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        val handler = RecordingSuccessHandler()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, handler)
        controller.onAppBackgrounded()
        val t0 = h.clock.nowMillis
        assertEquals(t0, controller.promptBackgroundAtMillis)
        // No public sequence breaks the state/attempt pairing; force the mismatch the guard defends against.
        val currentField = AppLockController::class.java.getDeclaredField("current")
        currentField.isAccessible = true
        currentField.set(controller, AppLockState.Authenticating(id + 1, AuthPurpose.CONFIRM_CHANGE))

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertTrue(handler.delivered.isEmpty()) // stale: nothing is handed to the settings operation
        assertNull(controller.inFlightAttemptId)
        assertEquals(t0, controller.pendingBackgroundAtMillis) // handed to the timer, not lost
        h.clock.advance(60_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `F5 a stale open-origin success while the state is AUTHENTICATING(UNLOCK) locks the app instead of returning to the origin`() {
        for (prompt in OpenOriginPrompt.entries) {
            val label = "$prompt"
            val (h, controller) = openOriginController(prompt)
            val handler = RecordingSuccessHandler()
            val id = h.startOpenOrigin(controller, prompt, handler)
            // No public sequence breaks the state/attempt pairing; force the mismatch the guard defends against.
            val currentField = AppLockController::class.java.getDeclaredField("current")
            currentField.isAccessible = true
            currentField.set(controller, AppLockState.Authenticating(id + 1, AuthPurpose.UNLOCK))

            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

            // Never an unlock: the origin (UNLOCKED or DISABLED) would open an AUTHENTICATING(UNLOCK) state.
            assertEquals(label, AppLockState.Locked(LockCause.COLD_START), controller.state.value)
            assertTrue(label, handler.delivered.isEmpty()) // stale: nothing is handed to the settings operation
            assertNull(label, controller.inFlightAttemptId)
            assertEquals(label, 0, h.repository.writeAttempts)
        }
    }

    @Test
    fun `ISSUE-1 a cancel of an attempt whose state is AUTHENTICATING(UNLOCK) of another id locks the app, calls no handler and records no code`() {
        for (prompt in OpenOriginPrompt.entries) {
            val label = "$prompt"
            val (h, controller) = openOriginController(prompt)
            val handler = RecordingSuccessHandler()
            val id = h.startOpenOrigin(controller, prompt, handler)
            val emittedBefore = h.emitted.size
            // No public sequence breaks the state/attempt pairing; force the mismatch the guard defends against.
            val currentField = AppLockController::class.java.getDeclaredField("current")
            currentField.isAccessible = true
            currentField.set(controller, AppLockState.Authenticating(id + 1, AuthPurpose.UNLOCK))

            h.authenticator.deliver(DeviceAuthOutcome.Cancelled)

            // Never an unlock and never an open origin: the stranded UNLOCK prompt state becomes a lock.
            assertEquals(label, AppLockState.Locked(LockCause.COLD_START), controller.state.value)
            assertTrue(label, handler.delivered.isEmpty())
            assertNull(label, controller.inFlightAttemptId)
            assertEquals(label, emittedBefore, h.emitted.size) // no LOCK_AUTH_CANCELLED, no other code
            assertEquals(label, 0, h.repository.writeAttempts)
        }
    }

    // --- ISSUE-2: a failed capability read during a CONFIRM_CHANGE close still evaluates T34 fail-closed ---------

    @Test
    fun `ISSUE-2 a capability read that throws during a CHANGE cancel in the foreground past the timeout still locks, then rethrows`() {
        class CapabilityBoom : Error("capability boom")
        val failure = CapabilityBoom()
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        controller.onAppBackgrounded() // pending background recorded while UNLOCKED
        h.clock.advance(60_000L)
        val handler = RecordingSuccessHandler()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, handler)
        controller.onAppForegrounded() // nothing is evaluated while the prompt is open (T32); the app is in the foreground
        assertEquals(AppLockState.Authenticating(id, AuthPurpose.CONFIRM_CHANGE), controller.state.value)
        h.capabilityProvider.failure = failure

        var thrown: Throwable? = null
        try {
            h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(thrown === failure) // surfaced unchanged, after the close
        // CHECK_FAILED is transient (R3): it never makes the lock INERT, and the elapsed timeout still locks.
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertNull(controller.inFlightAttemptId)
        assertNull(controller.pendingBackgroundAtMillis)
        assertTrue(handler.delivered.isEmpty())
    }

    // --- F7: the success handler may call back into the controller ----------------------------------------------

    @Test
    fun `F7 a success handler that calls back into the controller neither deadlocks nor sees the attempt open`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        var stateInHandler: AppLockState? = null
        var inFlightInHandler: Long? = -1L
        var queriedFromOtherThread: AppLockState? = null
        var otherThreadFinished = false
        var started: OpenOriginStartResult? = null
        var stateAfterStart: AppLockState? = null
        val delivered = mutableListOf<Pair<Long, AuthPurpose>>()
        val handler = OpenOriginSuccessHandler { attemptId, purpose ->
            delivered += attemptId to purpose
            // F5: the next open-origin start is refused while the T33 gate is open, so the handler (standing in
            // for S18's finished apply) completes it first.
            controller.onOpenOriginApplyCompleted(attemptId)
            stateInHandler = controller.state.value
            inFlightInHandler = controller.inFlightAttemptId
            // Another thread needs the controller's monitor: if the handler ran under it, this would time out.
            val other = thread { queriedFromOtherThread = controller.onLockQuery() }
            other.join(5_000L)
            otherThreadFinished = !other.isAlive
            started = controller.startOpenOriginAttempt(OpenOriginPrompt.DISABLE, RecordingSuccessHandler())
            controller.onAppBackgrounded()
            stateAfterStart = controller.state.value
        }
        val callbackId = (controller.startOpenOriginAttempt(OpenOriginPrompt.CHANGE, handler) as OpenOriginStartResult.Started)
            .attemptId
        h.settle()

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(listOf(callbackId to AuthPurpose.CONFIRM_CHANGE), delivered)
        assertEquals(AppLockState.Unlocked, stateInHandler) // already back on the origin
        assertNull(inFlightInHandler) // already closed
        assertTrue(otherThreadFinished)
        assertEquals(AppLockState.Unlocked, queriedFromOtherThread)
        val next = started as OpenOriginStartResult.Started
        assertTrue(next.attemptId > callbackId)
        assertEquals(AppLockState.Authenticating(next.attemptId, AuthPurpose.CONFIRM_CHANGE), stateAfterStart)
        assertEquals(AppLockState.Authenticating(next.attemptId, AuthPurpose.CONFIRM_CHANGE), controller.state.value)
        assertEquals(next.attemptId, controller.inFlightAttemptId)
        assertTrue(controller.promptBackgroundAtMillis != null) // the background belongs to the new attempt
    }

    @Test
    fun `a success handler that throws CancellationException is rethrown unchanged after the attempt closed on its origin`() {
        for (prompt in OpenOriginPrompt.entries) {
            val label = "$prompt"
            val (h, controller) = openOriginController(prompt)
            val cancellation = CancellationException("handler cancelled")
            val handler = RecordingSuccessHandler()
            handler.failure = cancellation
            val id = h.startOpenOrigin(controller, prompt, handler)

            var thrown: Throwable? = null
            try {
                h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            } catch (t: Throwable) {
                thrown = t
            }

            assertTrue(label, thrown === cancellation) // the same instance, never swallowed
            assertEquals(label, listOf(id to prompt.purpose), handler.delivered)
            assertEquals(label, originOf(prompt), controller.state.value)
            assertNull(label, controller.inFlightAttemptId)

            val again = controller.startOpenOriginAttempt(prompt, RecordingSuccessHandler())
            assertTrue(label, again is OpenOriginStartResult.Started && again.attemptId > id)
        }
    }

    // --- AE-117 timer part: a success discards the prompt background (T33) -------------------------------------

    @Test
    fun `AE-117 a success discards the prompt background, whether it arrives before or after the foreground`() {
        for (prompt in OpenOriginPrompt.entries) {
            for (afterForeground in listOf(false, true)) {
                val label = "$prompt / afterForeground=$afterForeground"
                val (h, controller) = openOriginController(prompt, AutoLockTimeout.IMMEDIATE)
                val handler = RecordingSuccessHandler()
                val id = h.startOpenOrigin(controller, prompt, handler)
                val callsBefore = h.authenticator.calls.size

                controller.onAppBackgrounded() // an OEM credential UI stops the host at t=0
                h.clock.advance(20_000L)
                if (afterForeground) controller.onAppForegrounded()
                h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // t=20 s
                if (!afterForeground) controller.onAppForegrounded()

                assertEquals(label, originOf(prompt), controller.state.value) // no LOCKED(TIMEOUT), no loop
                assertEquals(label, listOf(id to prompt.purpose), handler.delivered)
                assertNull(label, controller.pendingBackgroundAtMillis)
                controller.onAppForegrounded()
                assertEquals(label, originOf(prompt), controller.state.value)
                h.settle()
                assertEquals(label, callsBefore, h.authenticator.calls.size)
                assertFalse(label, PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT in h.emitted)
            }
        }
    }

    // --- T35 / AE-118: host loss for every purpose ---------------------------------------------------------------

    @Test
    fun `AE-118 T35 host loss closes an open-origin attempt before the adapter cancel echo, and a late success is stale`() {
        for (prompt in OpenOriginPrompt.entries) {
            val (h, controller) = openOriginController(prompt)
            h.emitted.clear()
            val handler = RecordingSuccessHandler()
            val id = h.startOpenOrigin(controller, prompt, handler)
            val call = h.authenticator.calls.last()
            var stateAtCancel: AppLockState? = null
            h.authenticator.onCancel = { stateAtCancel = controller.state.value }

            controller.onPromptHostLost(id)

            // Closed at once, back on the origin; the adapter has not been asked yet.
            assertEquals("$prompt", originOf(prompt), controller.state.value)
            assertNull("$prompt", controller.inFlightAttemptId)
            assertEquals("$prompt", listOf(PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED), h.emitted)
            assertTrue("$prompt", h.authenticator.cancelCalls.isEmpty())

            h.settle()

            assertEquals("$prompt", listOf(id), h.authenticator.cancelCalls)
            assertEquals("$prompt", listOf(true), h.authenticator.cancelOnMain)
            assertEquals("$prompt", originOf(prompt), stateAtCancel)
            // The adapter's synchronous Failed(SYSTEM_CANCELED) echo found the attempt closed: no second code.
            assertEquals("$prompt", listOf(PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED), h.emitted)
            assertEquals("$prompt", 0, h.activeJobs)

            // The change is not applied: a late success for the abandoned id is stale.
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded, call)
            h.authenticator.deliverWithId(id, DeviceAuthOutcome.Succeeded, call)
            assertEquals("$prompt", originOf(prompt), controller.state.value)
            assertTrue("$prompt", handler.delivered.isEmpty())
            assertEquals("$prompt", 0, h.repository.writeAttempts)

            // The settings switch is enabled again.
            val again = controller.startOpenOriginAttempt(prompt, handler)
            assertTrue("$prompt", again is OpenOriginStartResult.Started && again.attemptId > id)
        }
    }

    @Test
    fun `AE-118 a late success for the abandoned CONFIRM_CHANGE id is stale even after the app re-locked`() {
        for (prompt in confirmPrompts) {
            val (h, controller) = unlockedControllerFor(AutoLockTimeout.IMMEDIATE)
            val handler = RecordingSuccessHandler()
            val id = h.startOpenOrigin(controller, prompt, handler)
            val call = h.authenticator.calls.last()
            controller.onPromptHostLost(id)
            h.settle()
            assertEquals(AppLockState.Unlocked, controller.state.value)

            controller.onAppBackgrounded()
            controller.onAppForegrounded()
            assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)

            h.authenticator.deliver(DeviceAuthOutcome.Succeeded, call)
            h.authenticator.deliverWithId(id, DeviceAuthOutcome.Succeeded, call)

            assertEquals("$prompt", AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
            assertTrue("$prompt", handler.delivered.isEmpty())
            assertNull("$prompt", controller.inFlightAttemptId)
        }
    }

    @Test
    fun `AE-118 T12 a host loss for an unknown or stale id changes nothing for an open-origin attempt`() {
        for (prompt in OpenOriginPrompt.entries) {
            val (h, controller) = openOriginController(prompt)
            val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
            h.emitted.clear()

            for (bad in listOf(0L, -1L, id + 1, id - 1, Long.MAX_VALUE)) {
                controller.onPromptHostLost(bad)
            }
            h.settle()

            assertEquals("$prompt", AppLockState.Authenticating(id, prompt.purpose), controller.state.value)
            assertEquals("$prompt", id, controller.inFlightAttemptId)
            assertTrue("$prompt", h.emitted.isEmpty())
            assertTrue("$prompt", h.authenticator.cancelCalls.isEmpty())
        }
    }

    @Test
    fun `AE-118 a rotation keeps an open-origin attempt and a matching success on the recreated host is accepted`() {
        for (prompt in OpenOriginPrompt.entries) {
            val (h, controller) = openOriginController(prompt)
            val handler = RecordingSuccessHandler()
            val id = h.startOpenOrigin(controller, prompt, handler)
            val open = AppLockState.Authenticating(id, prompt.purpose)

            // A rotation is not APP_BACKGROUNDED and not host loss: the controller hears nothing, the attempt stays.
            assertEquals("$prompt", open, controller.state.value)
            assertEquals("$prompt", id, controller.inFlightAttemptId)
            h.authenticator.deliverWithId(id + 1, DeviceAuthOutcome.Succeeded) // a wrong id is still rejected
            assertEquals("$prompt", open, controller.state.value)

            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

            assertEquals("$prompt", originOf(prompt), controller.state.value)
            assertEquals("$prompt", listOf(id to prompt.purpose), handler.delivered)
        }
    }

    @Test
    fun `AE-118 T35 host loss while the launch waits for the prior terminal stops the retries for an open-origin attempt`() {
        for (prompt in OpenOriginPrompt.entries) {
            val (h, controller) = openOriginController(prompt)
            h.authenticator.defaultResult = AuthLaunch.AWAITING_PRIOR_TERMINAL
            val callsBefore = h.authenticator.calls.size
            val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
            assertEquals("$prompt", callsBefore + 1, h.authenticator.calls.size)

            controller.onPromptHostLost(id)
            h.advance(AppLockController.AWAITING_PRIOR_TERMINAL_BOUND_MILLIS * 2)

            assertEquals("$prompt", originOf(prompt), controller.state.value)
            assertEquals("$prompt", callsBefore + 1, h.authenticator.calls.size)
            assertEquals("$prompt", 0, h.activeJobs)
        }
    }

    // --- F5: the watchdog applies to every purpose ----------------------------------------------------------------

    @Test
    fun `F5 an open-origin prompt left open past the watchdog bound is abandoned and the change is not applied`() {
        for (prompt in OpenOriginPrompt.entries) {
            val (h, controller) = openOriginController(prompt)
            h.emitted.clear()
            val handler = RecordingSuccessHandler()
            val id = h.startOpenOrigin(controller, prompt, handler)
            val call = h.authenticator.calls.last()

            h.advance(AppLockController.PROMPT_WATCHDOG_MILLIS - 1)
            assertEquals("$prompt", AppLockState.Authenticating(id, prompt.purpose), controller.state.value)
            assertTrue("$prompt", h.authenticator.cancelCalls.isEmpty())
            h.advance(1)

            assertEquals("$prompt", originOf(prompt), controller.state.value)
            assertNull("$prompt", controller.inFlightAttemptId)
            // Closed first, then the adapter was asked to cancel on main; its synchronous echo is ignored.
            assertEquals("$prompt", listOf(id), h.authenticator.cancelCalls)
            assertEquals("$prompt", listOf(true), h.authenticator.cancelOnMain)
            assertEquals("$prompt", listOf(PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED), h.emitted)
            assertEquals("$prompt", 0, h.activeJobs)

            // Fail closed: a late success after the abandonment applies nothing.
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded, call)
            assertEquals("$prompt", originOf(prompt), controller.state.value)
            assertTrue("$prompt", handler.delivered.isEmpty())
            assertEquals("$prompt", 0, h.repository.writeAttempts)
            assertTrue("$prompt", controller.startOpenOriginAttempt(prompt, handler) is OpenOriginStartResult.Started)
        }
    }
}
