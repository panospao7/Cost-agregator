package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S18 part (i-b1): the auto-lock timeout change of [AppLockSettingsOperations.requestTimeoutChange] over one
 * [AppLockController] and the shared fakes (INV-3, OD-8a, AE-116). A longer timeout confirms through
 * CONFIRM_CHANGE with the CHANGE copy; an equal or shorter one commits with no prompt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockSettingsOperationsTimeoutTest {

    private fun unlockedRig(
        timeout: AutoLockTimeout = AutoLockTimeout.M1,
        deferred: Boolean = false
    ): SettingsOperationsRig {
        // The controller's settings view is built from the same [timeout] as the store, so a test that asserts on the
        // view (a foreground evaluation) measures against the value it states, not against the M1 default.
        val (h, controller) = unlockedControllerFor(timeout)
        h.repository.loadState = AppLockTestSettings.enabled(timeout)
        h.emitted.clear()
        h.emittedMetadata.clear()
        // The rig's unlock prompt is setup, not part of the behaviour under test.
        h.authenticator.calls.clear()
        return SettingsOperationsRig(h, controller, deferred)
    }

    private fun persistFailedTimeoutMetadata(): Map<PrivacyDiagnosticKey, Any> =
        mapOf(PrivacyDiagnosticKey.FIELD to AppLockSettingField.AUTO_LOCK_TIMEOUT)

    /** Backgrounds for [awayMillis] and returns, then reports the state. */
    private fun awayAndBack(rig: SettingsOperationsRig, awayMillis: Long): AppLockState {
        rig.controller.onAppForegrounded()
        rig.controller.onAppBackgrounded()
        rig.h.clock.advance(awayMillis)
        rig.controller.onAppForegrounded()
        return rig.controller.state.value
    }

    // --- Longer: CONFIRM_CHANGE ------------------------------------------------------------------

    @Test
    fun `longer timeout prompts with the CHANGE copy, writes only after success, and the view takes the new timeout`() = runBlocking {
        val rig = unlockedRig()
        val h = rig.h

        assertEquals(TimeoutChangeResult.STARTED, rig.ops.requestTimeoutChange(AutoLockTimeout.M5))
        h.settle()
        assertEquals(AuthPromptCopy.CHANGE, h.authenticator.calls.last().copy)
        assertEquals(AuthPurpose.CONFIRM_CHANGE, (rig.controller.state.value as AppLockState.Authenticating).purpose)
        assertTrue("nothing is written before the prompt succeeds", h.repository.timeoutWrites.isEmpty())

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(listOf(AutoLockTimeout.M5), h.repository.timeoutWrites)
        assertEquals(AutoLockTimeout.M5, (h.repository.loadState as AppLockSettingsLoadState.Loaded).settings.autoLockTimeout)
        assertTrue(h.emitted.isEmpty())
        assertTrue(rig.uncaught.isEmpty())
        // The controller's view now measures with M5: 61 s away does not lock (it would with M1).
        assertEquals(AppLockState.Unlocked, awayAndBack(rig, AutoLockTimeout.M1.millis + 1))
        // The other settings writes never happened.
        assertTrue(h.repository.lockWrites.isEmpty())
        assertEquals(0, h.repository.writeAttempts)
    }

    @Test
    fun `AE-116 a cancelled request for M5 keeps M1 and writes nothing`() = runBlocking {
        val rig = unlockedRig()
        val h = rig.h
        rig.ops.requestTimeoutChange(AutoLockTimeout.M5)
        h.settle()

        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue(h.repository.timeoutWrites.isEmpty())
        assertEquals(AutoLockTimeout.M1, (h.repository.loadState as AppLockSettingsLoadState.Loaded).settings.autoLockTimeout)
        // The old timeout is still in force: M1 elapsed locks, as before the request.
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), awayAndBack(rig, AutoLockTimeout.M1.millis))
    }

    @Test
    fun `a longer timeout while the lock is off commits directly with no prompt`() = runBlocking {
        val (h, controller) = disabledControllerFor(DeviceAuthCapability.AVAILABLE)
        h.repository.loadState = AppLockTestSettings.DISABLED
        h.emitted.clear()
        val rig = SettingsOperationsRig(h, controller)

        assertEquals(TimeoutChangeResult.COMMITTED, rig.ops.requestTimeoutChange(AutoLockTimeout.M15))

        assertEquals(listOf(AutoLockTimeout.M15), h.repository.timeoutWrites)
        assertTrue(h.authenticator.calls.isEmpty())
        assertEquals(AppLockState.Disabled, controller.state.value)
    }

    // --- Equal or shorter: direct commit ---------------------------------------------------------

    @Test
    fun `equal and shorter timeouts commit directly with no prompt, and the view takes the shorter timeout`() = runBlocking {
        val rig = unlockedRig(AutoLockTimeout.M5)
        val h = rig.h

        assertEquals(TimeoutChangeResult.COMMITTED, rig.ops.requestTimeoutChange(AutoLockTimeout.M5))
        // The equal timeout applies to the view (the view was built with M5): 61 s away does not lock, M1 would.
        assertEquals(AppLockState.Unlocked, awayAndBack(rig, AutoLockTimeout.M1.millis + 1))
        assertEquals(TimeoutChangeResult.COMMITTED, rig.ops.requestTimeoutChange(AutoLockTimeout.S30))

        assertEquals(listOf(AutoLockTimeout.M5, AutoLockTimeout.S30), h.repository.timeoutWrites)
        assertTrue("no prompt for an equal or shorter timeout", h.authenticator.calls.isEmpty())
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue(h.emitted.isEmpty())
        // 31 s away locks with the shorter timeout now in the view.
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), awayAndBack(rig, AutoLockTimeout.S30.millis + 1))
    }

    @Test
    fun `a direct write that fails reports FAILED, keeps the view and records PERSIST_FAILED for AUTO_LOCK_TIMEOUT once`() = runBlocking {
        val failures = mapOf<String, (SettingsOperationsRig) -> Unit>(
            "failed" to { r -> r.h.repository.timeoutWriteResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT) } },
            "rejected" to { r -> r.h.repository.timeoutWriteResult = { AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE) } },
            "throws" to { r -> r.h.repository.timeoutWriteFailure = IllegalStateException("secret-path /data/user/0/x") }
        )
        for ((label, arrange) in failures) {
            val rig = unlockedRig(AutoLockTimeout.M5)
            arrange(rig)

            assertEquals(label, TimeoutChangeResult.FAILED, rig.ops.requestTimeoutChange(AutoLockTimeout.S30))
            assertEquals(label, TimeoutChangeResult.FAILED, rig.ops.requestTimeoutChange(AutoLockTimeout.S30))

            assertEquals(label, listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), rig.h.emitted)
            assertEquals(label, persistFailedTimeoutMetadata(), rig.h.emittedMetadata[0].entries)
            assertEquals(label, AppLockState.Unlocked, rig.controller.state.value)
            // The view still holds M5: 31 s away does not lock.
            assertEquals(label, AppLockState.Unlocked, awayAndBack(rig, AutoLockTimeout.S30.millis + 1))
        }
    }

    // --- Longer: write failure --------------------------------------------------------------------

    @Test
    fun `a longer timeout whose write fails stays UNLOCKED, keeps the old view and records PERSIST_FAILED for AUTO_LOCK_TIMEOUT once`() = runBlocking {
        val failures = mapOf<String, (SettingsOperationsRig) -> Unit>(
            "failed" to { r -> r.h.repository.timeoutWriteResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT) } },
            "rejected" to { r -> r.h.repository.timeoutWriteResult = { AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE) } },
            "throws" to { r -> r.h.repository.timeoutWriteFailure = IllegalStateException("secret-path /data/user/0/x") }
        )
        for ((label, arrange) in failures) {
            val rig = unlockedRig()
            val h = rig.h
            arrange(rig)

            assertEquals(label, TimeoutChangeResult.STARTED, rig.ops.requestTimeoutChange(AutoLockTimeout.M5))
            h.settle()
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

            assertEquals(label, AppLockState.Unlocked, rig.controller.state.value)
            assertEquals(label, listOf(AutoLockTimeout.M5), h.repository.timeoutWrites)
            assertEquals(label, listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), h.emitted)
            assertEquals(label, persistFailedTimeoutMetadata(), h.emittedMetadata[0].entries)
            assertTrue(label, rig.uncaught.isEmpty())
            // M1 is still the view: it locks after M1.
            assertEquals(label, AppLockState.Locked(LockCause.TIMEOUT), awayAndBack(rig, AutoLockTimeout.M1.millis))
        }
    }

    @Test
    fun `an unreadable store refuses the change with nothing written and no prompt`() = runBlocking {
        val rig = unlockedRig()
        val h = rig.h
        h.repository.loadState = AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED)

        assertEquals(TimeoutChangeResult.FAILED, rig.ops.requestTimeoutChange(AutoLockTimeout.M5))
        assertEquals(TimeoutChangeResult.FAILED, rig.ops.requestTimeoutChange(AutoLockTimeout.IMMEDIATE))

        assertTrue(h.repository.timeoutWrites.isEmpty())
        assertTrue(h.authenticator.calls.isEmpty())
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), h.emitted)
    }

    @Test
    fun `a pre-read that throws is a failure with nothing written, and a cancellation propagates`() = runBlocking {
        val rig = unlockedRig()
        val h = rig.h
        h.repository.failure = IllegalStateException("secret-binder-text")

        assertEquals(TimeoutChangeResult.FAILED, rig.ops.requestTimeoutChange(AutoLockTimeout.M5))
        assertTrue(h.repository.timeoutWrites.isEmpty())
        assertTrue(h.authenticator.calls.isEmpty())

        h.repository.failure = CancellationException("c")
        try {
            rig.ops.requestTimeoutChange(AutoLockTimeout.M5)
            fail("the cancellation was swallowed")
        } catch (e: CancellationException) {
            // propagated
        }
        assertTrue(h.repository.timeoutWrites.isEmpty())
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
    }

    // --- Token binding ---------------------------------------------------------------------------

    @Test
    fun `a wrong id or a reused id of the state move is a no-op and records nothing`() = runBlocking {
        val rig = unlockedRig(deferred = true)
        val h = rig.h
        rig.ops.requestTimeoutChange(AutoLockTimeout.M5)
        h.settle()
        val attemptId = rig.controller.inFlightAttemptId!!
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // the token is open; the apply is queued

        rig.controller.onOpenOriginSettingApplied(attemptId + 99, false)
        assertTrue("a wrong id records nothing", h.emitted.isEmpty())

        h.settle() // the apply runs: the write commits and consumes the token
        assertEquals(listOf(AutoLockTimeout.M5), h.repository.timeoutWrites)

        rig.controller.onOpenOriginSettingApplied(attemptId, false) // reused id
        rig.controller.onOpenOriginSettingApplied(attemptId, true)
        assertTrue("a reused id records nothing", h.emitted.isEmpty())
        // The view is the committed M5, not changed by the reused calls.
        assertEquals(AppLockState.Unlocked, awayAndBack(rig, AutoLockTimeout.M1.millis + 1))
    }

    @Test
    fun `a state move with no token is a no-op`() = runBlocking {
        val rig = unlockedRig()

        rig.controller.onOpenOriginSettingApplied(1L, false)

        assertTrue(rig.h.emitted.isEmpty())
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
    }

    // --- One apply at a time ---------------------------------------------------------------------

    @Test
    fun `while the apply runs another request answers ALREADY_IN_FLIGHT and after it completes requests work again`() = runBlocking {
        val rig = unlockedRig(deferred = true)
        val h = rig.h
        rig.ops.requestTimeoutChange(AutoLockTimeout.M5)
        h.settle()
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // the handler set applyRunning; the apply is queued

        assertEquals(TimeoutChangeResult.ALREADY_IN_FLIGHT, rig.ops.requestTimeoutChange(AutoLockTimeout.S30))
        assertEquals(SettingsRequestResult.ALREADY_IN_FLIGHT, rig.ops.requestDisable())
        assertTrue(h.repository.timeoutWrites.isEmpty())

        h.settle()

        assertEquals(listOf(AutoLockTimeout.M5), h.repository.timeoutWrites)
        assertEquals(TimeoutChangeResult.COMMITTED, rig.ops.requestTimeoutChange(AutoLockTimeout.S30))
    }

    // --- Final gate ISSUE-1: no direct write while a CONFIRM_CHANGE prompt or its apply is in flight -----------

    @Test
    fun `ISSUE-1 a direct request during an open CHANGE prompt is ALREADY_IN_FLIGHT, and after the prompt ends store and view agree`() = runBlocking {
        val rig = unlockedRig(AutoLockTimeout.M5)
        val h = rig.h
        assertEquals(TimeoutChangeResult.STARTED, rig.ops.requestTimeoutChange(AutoLockTimeout.M15))
        h.settle()

        assertEquals(TimeoutChangeResult.ALREADY_IN_FLIGHT, rig.ops.requestTimeoutChange(AutoLockTimeout.S30))
        assertEquals(TimeoutChangeResult.ALREADY_IN_FLIGHT, rig.ops.requestTimeoutChange(AutoLockTimeout.M5))
        assertTrue("no write while the prompt is open", h.repository.timeoutWrites.isEmpty())
        assertTrue(h.repository.conditionalTimeoutWrites.isEmpty())

        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)

        // Store and view both still hold M5: M5 away locks, 31 s does not.
        assertEquals(AutoLockTimeout.M5, (h.repository.loadState as AppLockSettingsLoadState.Loaded).settings.autoLockTimeout)
        assertEquals(AppLockState.Unlocked, awayAndBack(rig, AutoLockTimeout.S30.millis + 1))
        // The prompt ended without success: a direct request is accepted again, not stuck.
        assertEquals(TimeoutChangeResult.COMMITTED, rig.ops.requestTimeoutChange(AutoLockTimeout.S30))
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), awayAndBack(rig, AutoLockTimeout.S30.millis + 1))
    }

    @Test
    fun `ISSUE-1 a direct request during the apply is ALREADY_IN_FLIGHT, and store and view both end on the confirmed timeout`() = runBlocking {
        val rig = unlockedRig(AutoLockTimeout.M5, deferred = true)
        val h = rig.h
        rig.ops.requestTimeoutChange(AutoLockTimeout.M15)
        h.settle()
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // the prompt closed; the apply is queued

        assertEquals(TimeoutChangeResult.ALREADY_IN_FLIGHT, rig.ops.requestTimeoutChange(AutoLockTimeout.S30))
        assertTrue(h.repository.conditionalTimeoutWrites.isEmpty())
        h.settle()

        assertEquals(listOf(AutoLockTimeout.M15), h.repository.timeoutWrites)
        assertEquals(AutoLockTimeout.M15, (h.repository.loadState as AppLockSettingsLoadState.Loaded).settings.autoLockTimeout)
        // The view holds M15 too: M5 + 1 ms away would lock with a diverged M5 view.
        assertEquals(AppLockState.Unlocked, awayAndBack(rig, AutoLockTimeout.M5.millis + 1))
        assertTrue(rig.uncaught.isEmpty())
    }

    // --- Cancellation ----------------------------------------------------------------------------

    @Test
    fun `a CancellationException from the direct write propagates and the unconfirmed write is recorded once (F7)`() {
        val rig = unlockedRig(AutoLockTimeout.M5)
        rig.h.repository.timeoutWriteFailure = CancellationException("c")

        try {
            runBlocking { rig.ops.requestTimeoutChange(AutoLockTimeout.S30) }
            fail("the cancellation was swallowed")
        } catch (e: CancellationException) {
            // propagated
        }

        // S18 ii-a F7: the re-read shows the stored M5 (not the requested S30), so it is not committed and recorded once.
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), rig.h.emitted)
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
    }

    @Test
    fun `a CancellationException from the prompt-path write is not swallowed, records the unconfirmed write once (F7), and still completes the apply`() = runBlocking {
        val rig = unlockedRig()
        val h = rig.h
        h.repository.timeoutWriteFailure = CancellationException("c")
        rig.ops.requestTimeoutChange(AutoLockTimeout.M5)
        h.settle()

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals("F7: no commit on the re-read is one persist failure", listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), h.emitted)
        assertTrue("a cancellation is not reported as an uncaught failure", rig.uncaught.isEmpty())
        // The completion ran in the finally: the in-flight flag is cleared and a new request is accepted.
        h.repository.timeoutWriteFailure = null
        assertEquals(TimeoutChangeResult.COMMITTED, rig.ops.requestTimeoutChange(AutoLockTimeout.S30))
    }

    // --- Privacy review F1: the direct path is one conditional store edit under a mutex --------------

    @Test
    fun `F1 the direct path uses the conditional write and the prompt path the unconditional one`() = runBlocking {
        val rig = unlockedRig(AutoLockTimeout.M5)
        val h = rig.h

        assertEquals(TimeoutChangeResult.COMMITTED, rig.ops.requestTimeoutChange(AutoLockTimeout.S30))
        assertEquals(listOf(AutoLockTimeout.S30), h.repository.conditionalTimeoutWrites)

        assertEquals(TimeoutChangeResult.STARTED, rig.ops.requestTimeoutChange(AutoLockTimeout.M1))
        h.settle()
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals("the prompt path never uses the conditional write", listOf(AutoLockTimeout.S30), h.repository.conditionalTimeoutWrites)
        assertEquals(listOf(AutoLockTimeout.S30, AutoLockTimeout.M1), h.repository.timeoutWrites)
    }

    @Test
    fun `F1 a conditional write that finds a stale pre-read answers NOT_IN_ORIGIN, with no view change and no failure record`() = runBlocking {
        val rig = unlockedRig(AutoLockTimeout.M1)
        val h = rig.h
        h.repository.timeoutWriteResult = { AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.WOULD_WEAKEN) }

        assertEquals(TimeoutChangeResult.NOT_IN_ORIGIN, rig.ops.requestTimeoutChange(AutoLockTimeout.S30))

        assertTrue(h.emitted.isEmpty())
        assertTrue(h.authenticator.calls.isEmpty())
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        // The view still holds M1, not S30: 31 s away does not lock.
        assertEquals(AppLockState.Unlocked, awayAndBack(rig, AutoLockTimeout.S30.millis + 1))
    }

    @Test
    fun `F1 two overlapping direct requests never leave a timeout longer than either committed request`() = runBlocking {
        val rig = unlockedRig(AutoLockTimeout.M5)
        val h = rig.h
        val gate = CompletableDeferred<Unit>()
        h.repository.conditionalWriteGate = gate

        val a = async(start = CoroutineStart.UNDISPATCHED) { rig.ops.requestTimeoutChange(AutoLockTimeout.M1) }
        val b = async(start = CoroutineStart.UNDISPATCHED) { rig.ops.requestTimeoutChange(AutoLockTimeout.S30) }

        // A is inside the critical section, parked at the store; B has not read or written anything (the mutex).
        assertEquals(listOf(AutoLockTimeout.M1), h.repository.conditionalTimeoutWrites)
        gate.complete(Unit)

        assertEquals(TimeoutChangeResult.COMMITTED, a.await())
        assertEquals(TimeoutChangeResult.COMMITTED, b.await())
        val committed = h.repository.timeoutWrites
        assertEquals(listOf(AutoLockTimeout.M1, AutoLockTimeout.S30), committed)
        val finalTimeout = (h.repository.loadState as AppLockSettingsLoadState.Loaded).settings.autoLockTimeout
        assertTrue(
            "the stored timeout $finalTimeout is longer than a committed request",
            committed.all { finalTimeout.millis <= it.millis }
        )
        assertTrue(h.authenticator.calls.isEmpty())
        assertTrue(rig.uncaught.isEmpty())
    }

    @Test
    fun `F1 a request that overlaps a shortening re-reads under the lock, and its longer value takes the prompt path`() = runBlocking {
        val rig = unlockedRig(AutoLockTimeout.M5)
        val h = rig.h
        val gate = CompletableDeferred<Unit>()
        h.repository.conditionalWriteGate = gate

        val shorten = async(start = CoroutineStart.UNDISPATCHED) { rig.ops.requestTimeoutChange(AutoLockTimeout.S30) }
        // Read against the pre-shortening M5 this would be a direct write; under the lock it reads S30.
        val longer = async(start = CoroutineStart.UNDISPATCHED) { rig.ops.requestTimeoutChange(AutoLockTimeout.M1) }
        gate.complete(Unit)

        assertEquals(TimeoutChangeResult.COMMITTED, shorten.await())
        assertEquals(TimeoutChangeResult.STARTED, longer.await())
        assertEquals(listOf(AutoLockTimeout.S30), h.repository.timeoutWrites)
        assertEquals(AutoLockTimeout.S30, (h.repository.loadState as AppLockSettingsLoadState.Loaded).settings.autoLockTimeout)
        h.settle()
        assertEquals(AuthPromptCopy.CHANGE, h.authenticator.calls.last().copy)
    }

    // --- Privacy review F2: a no-prompt commit never lengthens a lock-on view ----------------------

    @Test
    fun `F2 onTimeoutWrittenWithoutPrompt with a longer timeout leaves a lock-on view unchanged`() = runBlocking {
        val rig = unlockedRig(AutoLockTimeout.M1)

        rig.controller.onTimeoutWrittenWithoutPrompt(AutoLockTimeout.M15, true)

        assertTrue(rig.h.emitted.isEmpty())
        // The view still measures with M1: M1 away locks (it would not with M15).
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), awayAndBack(rig, AutoLockTimeout.M1.millis))
    }

    @Test
    fun `F2 onTimeoutWrittenWithoutPrompt with a shorter timeout still takes it into a lock-on view`() = runBlocking {
        val rig = unlockedRig(AutoLockTimeout.M5)

        rig.controller.onTimeoutWrittenWithoutPrompt(AutoLockTimeout.S30, true)

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), awayAndBack(rig, AutoLockTimeout.S30.millis + 1))
    }

    // --- 8a: INERT -----------------------------------------------------------------------------------

    @Test
    fun `8a INERT - a longer request is NOT_IN_ORIGIN with no write and no prompt, a shorter one is COMMITTED`() = runBlocking {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1))
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppBackgrounded()
        h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        controller.onAppForegrounded()
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        val rig = SettingsOperationsRig(h, controller)
        val promptsBefore = h.authenticator.calls.size

        assertEquals(TimeoutChangeResult.NOT_IN_ORIGIN, rig.ops.requestTimeoutChange(AutoLockTimeout.M5))
        h.settle()

        assertTrue(h.repository.timeoutWrites.isEmpty())
        assertEquals(promptsBefore, h.authenticator.calls.size)

        assertEquals(TimeoutChangeResult.COMMITTED, rig.ops.requestTimeoutChange(AutoLockTimeout.S30))
        assertEquals(listOf(AutoLockTimeout.S30), h.repository.timeoutWrites)
        assertEquals(promptsBefore, h.authenticator.calls.size)
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
    }

    // --- 8c: shortening to IMMEDIATE ----------------------------------------------------------------

    @Test
    fun `8c shortening to IMMEDIATE then a background and foreground within 0 to 1 ms gives LOCKED TIMEOUT`() = runBlocking {
        for (awayMillis in listOf(0L, 1L)) {
            val (h, controller) = unlockedControllerFor(AutoLockTimeout.M5)
            val rig = SettingsOperationsRig(h, controller)

            assertEquals("$awayMillis", TimeoutChangeResult.COMMITTED, rig.ops.requestTimeoutChange(AutoLockTimeout.IMMEDIATE))
            controller.onAppBackgrounded()
            h.clock.advance(awayMillis)
            controller.onAppForegrounded()

            assertEquals("$awayMillis", AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        }
    }

    // --- 8d: AE-117 end to end, M5 variant ----------------------------------------------------------

    @Test
    fun `8d AE-117 IMMEDIATE stored, M5 requested, the credential screen stops the app - committed and UNLOCKED in both callback orders`() = runBlocking {
        for (successFirst in listOf(true, false)) {
            val (h, controller) = unlockedControllerFor(AutoLockTimeout.IMMEDIATE)
            val rig = SettingsOperationsRig(h, controller)
            h.emitted.clear()

            assertEquals("$successFirst", TimeoutChangeResult.STARTED, rig.ops.requestTimeoutChange(AutoLockTimeout.M5))
            h.settle()
            controller.onAppBackgrounded() // the system credential screen stops the host at t=0
            h.clock.advance(20_000L)
            if (successFirst) {
                h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
                controller.onAppForegrounded()
            } else {
                controller.onAppForegrounded() // no evaluation while the prompt is in flight (T32)
                h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            }

            assertEquals("$successFirst", AppLockState.Unlocked, controller.state.value)
            assertEquals("$successFirst", listOf(AutoLockTimeout.M5), h.repository.timeoutWrites)
            assertFalse("$successFirst", h.emitted.contains(PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT))
            assertNull("$successFirst", controller.pendingBackgroundAtMillis)
            assertNull("$successFirst", controller.promptBackgroundAtMillis)
            assertTrue("$successFirst", rig.uncaught.isEmpty())
            // The view holds M5: a later foreground finds no leftover background, and 61 s away does not lock.
            assertEquals("$successFirst", AppLockState.Unlocked, awayAndBack(rig, AutoLockTimeout.M1.millis + 1))
        }
    }

    // --- 8e / 8f / 8g ---------------------------------------------------------------------------------

    @Test
    fun `8e an invalid stored timeout reads as IMMEDIATE and forces the prompt for any other value`() = runBlocking {
        // The store maps an unknown token to AutoLockTimeout.FAIL_CLOSED (IMMEDIATE) before the operations see it.
        val rig = unlockedRig(AutoLockTimeout.FAIL_CLOSED)
        val h = rig.h

        assertEquals(TimeoutChangeResult.STARTED, rig.ops.requestTimeoutChange(AutoLockTimeout.S30))
        h.settle()

        assertEquals(AuthPromptCopy.CHANGE, h.authenticator.calls.last().copy)
        assertTrue("nothing is written before the prompt succeeds", h.repository.timeoutWrites.isEmpty())
    }

    @Test
    fun `8f a first-run store commits directly with no prompt`() = runBlocking {
        val h = AppLockControllerHarness(AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS))
        val controller = h.resolvedController()
        controller.onAppForegrounded()
        val rig = SettingsOperationsRig(h, controller)

        assertEquals(TimeoutChangeResult.COMMITTED, rig.ops.requestTimeoutChange(AutoLockTimeout.M5))

        assertEquals(listOf(AutoLockTimeout.M5), h.repository.timeoutWrites)
        assertTrue(h.authenticator.calls.isEmpty())
        assertEquals(AutoLockTimeout.M5, (h.repository.loadState as AppLockSettingsLoadState.Loaded).settings.autoLockTimeout)
    }

    @Test
    fun `8g the unreadable path records PERSIST_FAILED with the field AUTO_LOCK_TIMEOUT and nothing else`() = runBlocking {
        val rig = unlockedRig()
        val h = rig.h
        h.repository.loadState = AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED)

        assertEquals(TimeoutChangeResult.FAILED, rig.ops.requestTimeoutChange(AutoLockTimeout.M5))

        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), h.emitted)
        assertEquals(persistFailedTimeoutMetadata(), h.emittedMetadata[0].entries)
        assertTrue(h.repository.timeoutWrites.isEmpty())
    }
}
