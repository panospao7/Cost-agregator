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
}
