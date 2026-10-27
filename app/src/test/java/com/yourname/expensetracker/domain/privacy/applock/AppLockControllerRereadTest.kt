package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings re-read of [AppLockController] (S18 ii-c, INV-47): T28 from `UNLOCKED` and `INERT`, T29 in `LOCKED` and
 * `AUTHENTICATING` (AE-97, AE-98), the D6 drop of a kept snapshot, the stale-sequence drop, and that a re-read never locks
 * and never writes. The re-reads are scripted through `FakeAppLockSettingsRepository.scriptRead`.
 *
 * The emission of `LOCK_SETTINGS_RECOVERED` is NOT covered: its emitter entry (D9) is not approved yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockControllerRereadTest {

    private val enabled = AppLockTestSettings.enabled()
    private val unreadableIo = AppLockTestSettings.unreadable(SettingsUnreadableCause.IO_ERROR)

    /** A resolved controller over a transient unreadable store, unlocked: `UNLOCKED` with load state `Unreadable(IO_ERROR)`. */
    private fun transientUnlocked(
        capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE,
        cause: SettingsUnreadableCause = SettingsUnreadableCause.IO_ERROR
    ): Pair<AppLockControllerHarness, AppLockController> {
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(cause), capability)
        val controller = h.resolvedController()
        h.unlock(controller)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        return h to controller
    }

    /** A resolved controller over a transient unreadable store: `LOCKED(SETTINGS_UNREADABLE)`. */
    private fun transientLocked(): Pair<AppLockControllerHarness, AppLockController> {
        val h = AppLockControllerHarness(unreadableIo)
        val controller = h.resolvedController()
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        return h to controller
    }

    /** Taps Unlock and answers success, without asserting where the state ends (T28 may apply after it). */
    private fun unlockOnce(h: AppLockControllerHarness, controller: AppLockController) {
        h.tap(controller)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
    }

    private fun assertNeverWrote(h: AppLockControllerHarness) {
        assertEquals(0, h.repository.writeAttempts)
        assertTrue(h.repository.lockWrites.isEmpty())
        assertTrue(h.repository.timeoutWrites.isEmpty())
        assertTrue(h.repository.plainWrites.isEmpty())
    }

    // --- T28 ---------------------------------------------------------------------------------------------------

    @Test
    fun `T28 from UNLOCKED applies the stored values at once without auth, and writes nothing`() {
        val firstRun = AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings())
        val rows = listOf(
            Triple(AppLockTestSettings.DISABLED, DeviceAuthCapability.AVAILABLE, AppLockState.Disabled),
            Triple(firstRun, DeviceAuthCapability.AVAILABLE, AppLockState.Disabled),
            Triple(AppLockTestSettings.DISABLED, DeviceAuthCapability.NO_CREDENTIAL, AppLockState.Disabled),
            Triple(enabled, DeviceAuthCapability.AVAILABLE, AppLockState.Unlocked),
            Triple(enabled, DeviceAuthCapability.CHECK_FAILED, AppLockState.Unlocked),
            Triple(enabled, DeviceAuthCapability.NO_CREDENTIAL, AppLockState.Inert(InertReason.NO_CREDENTIAL)),
            Triple(enabled, DeviceAuthCapability.HW_UNAVAILABLE, AppLockState.Inert(InertReason.HW_UNAVAILABLE)),
            Triple(enabled, DeviceAuthCapability.UNSUPPORTED_API, AppLockState.Inert(InertReason.API_UNSUPPORTED)),
            Triple(enabled, DeviceAuthCapability.UNKNOWN, AppLockState.Inert(InertReason.UNKNOWN))
        )
        for ((stored, capability, expected) in rows) {
            val (h, controller) = transientUnlocked()
            val authCalls = h.authenticator.calls.size
            h.capabilityProvider.capability = capability
            h.repository.scriptRead(stored)

            controller.requestSettingsReread()

            assertEquals("$stored / $capability", expected, controller.state.value)
            assertEquals("$stored / $capability", authCalls, h.authenticator.calls.size) // no auth
            assertNeverWrote(h)
        }
    }

    @Test
    fun `T28 from INERT applies the stored values at once without auth`() {
        val rows = listOf(
            Triple(AppLockTestSettings.DISABLED, DeviceAuthCapability.AVAILABLE, AppLockState.Disabled),
            Triple(enabled, DeviceAuthCapability.AVAILABLE, AppLockState.Unlocked),
            Triple(enabled, DeviceAuthCapability.NO_CREDENTIAL, AppLockState.Inert(InertReason.NO_CREDENTIAL)),
            Triple(enabled, DeviceAuthCapability.HW_UNAVAILABLE, AppLockState.Inert(InertReason.HW_UNAVAILABLE))
        )
        for ((stored, capability, expected) in rows) {
            val h = AppLockControllerHarness(unreadableIo, DeviceAuthCapability.NO_CREDENTIAL)
            val controller = h.resolvedController()
            assertEquals(AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), controller.state.value)
            h.capabilityProvider.capability = capability
            h.repository.scriptRead(stored)

            controller.requestSettingsReread()

            assertEquals("$stored / $capability", expected, controller.state.value)
            assertTrue(h.authenticator.calls.isEmpty())
            assertNeverWrote(h)
        }
    }

    @Test
    fun `T28 recomputes the effective values, so the timer afterwards measures the stored timeout`() {
        val (h, controller) = transientUnlocked()
        h.repository.scriptRead(AppLockTestSettings.enabled(AutoLockTimeout.M1))
        controller.requestSettingsReread()
        assertEquals(AppLockState.Unlocked, controller.state.value)

        controller.onAppBackgrounded()
        h.clock.advance(30_000L)
        controller.onAppForegrounded()

        // F (IMMEDIATE) would have locked here; the stored one minute does not.
        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    @Test
    fun `T28 never locks mid-session, even with a background pending past the stored timeout`() {
        val (h, controller) = transientUnlocked()
        controller.onAppBackgrounded()
        h.clock.advance(10 * 60_000L)
        h.repository.scriptRead(AppLockTestSettings.enabled(AutoLockTimeout.M1))

        controller.requestSettingsReread()

        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertNotNull(controller.pendingBackgroundAtMillis) // not consumed by the re-read
        assertNeverWrote(h)
        // Only the foreground evaluation (T14) decides the lock, with the stored values now.
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `T28 applies from the foreground re-read of an open state`() {
        val (h, controller) = transientUnlocked()
        h.repository.scriptRead(AppLockTestSettings.DISABLED)

        controller.onAppForegrounded()

        assertEquals(AppLockState.Disabled, controller.state.value)
    }

    @Test
    fun `an unreadable or failing re-read changes nothing`() {
        val (h, controller) = transientUnlocked()
        h.repository.scriptRead(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED))
        controller.requestSettingsReread()
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(null, controller.notice.value) // the view stays transient: no review notice appears

        h.repository.failure = IllegalStateException("secret-path /data/x")
        controller.requestSettingsReread()
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(emptyList<Throwable>(), h.uncaught)
    }

    @Test
    fun `no re-read is made for a readable or a persistent unreadable store`() {
        val readable = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val readableController = readable.resolvedController()
        readableController.onAppForegrounded()
        readableController.requestSettingsReread()
        assertEquals(1, readable.repository.readCount) // the resolve only

        for (cause in SettingsUnreadableCause.entries.filter { it.isPersistent }) {
            val h = AppLockControllerHarness(AppLockTestSettings.unreadable(cause))
            val controller = h.resolvedController()
            h.unlock(controller)
            controller.onAppForegrounded()
            controller.requestSettingsReread()
            assertEquals("cause $cause", 1, h.repository.readCount)
        }
    }

    // --- The foreground never waits ----------------------------------------------------------------------------

    @Test
    fun `the foreground evaluation does not wait for the re-read and uses the held state, and a held re-read is read once`() {
        val (h, controller) = transientUnlocked()
        val gate = CompletableDeferred<Unit>()
        try {
            h.repository.scriptRead(AppLockTestSettings.DISABLED, gate)
            controller.onAppBackgrounded()
            val readsBefore = h.repository.readCount

            controller.onAppForegrounded() // F is IMMEDIATE: T14 locks now, the re-read is still held

            assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
            assertEquals(readsBefore + 1, h.repository.readCount)
            controller.onAppForegrounded() // a burst of foregrounds does not start a second read while one is held
            assertEquals(readsBefore + 1, h.repository.readCount)

            gate.complete(Unit)
        } finally {
            gate.complete(Unit)
        }
        // T29: the late result is kept, LOCKED is never relaxed by it.
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `a cancelled foreground re-read frees the next foreground to read again`() {
        val (h, controller) = transientUnlocked()
        val gate = CompletableDeferred<Unit>()
        try {
            h.repository.scriptRead(AppLockTestSettings.DISABLED, gate)
            controller.onAppForegrounded()
            assertEquals(AppLockState.Unlocked, controller.state.value)

            h.cancelControllerJobs() // the held re-read is cancelled; cancellation is not swallowed
            h.settle()
        } finally {
            gate.complete(Unit)
        }
        // The held read did not apply: it was cancelled, so the state is unchanged.
        assertEquals(AppLockState.Unlocked, controller.state.value)

        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        val readsBeforeSecond = h.repository.readCount
        controller.onAppForegrounded()

        assertEquals(readsBeforeSecond + 1, h.repository.readCount)
        assertEquals(AppLockState.Disabled, controller.state.value)
    }

    // --- T29 ---------------------------------------------------------------------------------------------------

    @Test
    fun `AE-98 T29 a re-read in LOCKED never unlocks, and after the unlock T28 applies the stored values`() {
        val (h, controller) = transientLocked()
        h.repository.scriptRead(AppLockTestSettings.DISABLED)

        controller.onAppForegrounded()

        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        assertNeverWrote(h)

        unlockOnce(h, controller)

        assertEquals(AppLockState.Disabled, controller.state.value)
    }

    @Test
    fun `T29 a re-read during AUTHENTICATING UNLOCK is kept, a cancelled prompt stays LOCKED, the next unlock applies it`() {
        val (h, controller) = transientLocked()
        val attemptId = h.tap(controller)
        h.repository.scriptRead(AppLockTestSettings.DISABLED)

        controller.requestSettingsReread()

        assertEquals(AppLockState.Authenticating(attemptId, AuthPurpose.UNLOCK), controller.state.value)
        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)

        unlockOnce(h, controller)

        assertEquals(AppLockState.Disabled, controller.state.value)
    }

    @Test
    fun `T29 a re-read during an open-origin prompt is kept and applied once the prompt ends in an open state`() {
        for (outcome in listOf(DeviceAuthOutcome.Cancelled, DeviceAuthOutcome.Succeeded)) {
            val (h, controller) = transientUnlocked()
            val handler = RecordingSuccessHandler()
            val attemptId = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, handler)
            h.repository.scriptRead(AppLockTestSettings.DISABLED)

            controller.requestSettingsReread()

            assertEquals(
                "$outcome",
                AppLockState.Authenticating(attemptId, AuthPurpose.CONFIRM_CHANGE),
                controller.state.value
            )
            h.authenticator.deliver(outcome)

            assertEquals("$outcome", AppLockState.Disabled, controller.state.value)
            assertNeverWrote(h)
        }
    }

    @Test
    fun `T29 a prompt that ends LOCKED keeps the snapshot and LOCKED is not relaxed`() {
        val (h, controller) = transientUnlocked()
        h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        controller.onAppBackgrounded() // T32: recorded as the prompt background
        h.clock.advance(1_000L)
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.requestSettingsReread()

        // the foreground issues its own re-read (seq 2); script it readable so the newest result is the kept snapshot
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.onAppForegrounded()
        h.authenticator.deliver(DeviceAuthOutcome.Cancelled) // T34: F is IMMEDIATE, the hand-off locks

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)

        unlockOnce(h, controller)

        assertEquals(AppLockState.Disabled, controller.state.value)
    }

    // --- D6 and the sequence check -----------------------------------------------------------------------------

    @Test
    fun `D6 a kept snapshot is dropped if a settings write committed after it was kept`() {
        val writes: List<Pair<String, (AppLockController) -> Unit>> = listOf(
            "plain write" to { c: AppLockController ->
                c.onPlainSettingWritten(AppLockSettingField.DISCREET, true, committed = true)
            },
            "timeout write" to { c: AppLockController ->
                c.onTimeoutWrittenWithoutPrompt(AutoLockTimeout.M1, committed = true)
            }
        )
        for ((label, write) in writes) {
            val (h, controller) = transientLocked()
            h.repository.scriptRead(AppLockTestSettings.DISABLED)
            controller.onAppForegrounded() // kept
            write(controller)

            unlockOnce(h, controller)

            assertEquals(label, AppLockState.Unlocked, controller.state.value) // dropped: not DISABLED
            // The view is still transient, so the next foreground re-reads and recovers.
            h.repository.scriptRead(AppLockTestSettings.DISABLED)
            controller.onAppForegrounded()
            assertEquals(label, AppLockState.Disabled, controller.state.value)
        }
    }

    @Test
    fun `D6 a write that did not commit does not drop the snapshot`() {
        val (h, controller) = transientLocked()
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.onAppForegrounded()
        controller.onPlainSettingWritten(AppLockSettingField.DISCREET, true, committed = false)

        unlockOnce(h, controller)

        assertEquals(AppLockState.Disabled, controller.state.value)
    }

    @Test
    fun `D6 a write that committed while the re-read was in flight drops its result`() {
        val (h, controller) = transientUnlocked()
        val gate = CompletableDeferred<Unit>()
        try {
            h.repository.scriptRead(AppLockTestSettings.DISABLED, gate)
            controller.requestSettingsReread()
            controller.onPlainSettingWritten(AppLockSettingField.DISCREET, true, committed = true)

            gate.complete(Unit)
        } finally {
            gate.complete(Unit)
        }

        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    @Test
    fun `a stale re-read result is dropped, only the newest issued re-read may apply`() {
        val (h, controller) = transientUnlocked()
        val gate = CompletableDeferred<Unit>()
        try {
            h.repository.scriptRead(AppLockTestSettings.DISABLED, gate) // the first re-read, held
            h.repository.scriptRead(unreadableIo) // the second one (newest) reads nothing usable
            controller.requestSettingsReread()
            controller.requestSettingsReread()
            assertEquals(3, h.repository.readCount) // resolve + two re-reads

            gate.complete(Unit) // the first result arrives late, as a stale sequence
        } finally {
            gate.complete(Unit)
        }

        assertEquals(AppLockState.Unlocked, controller.state.value)

        // The newest re-read can still apply.
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.requestSettingsReread()
        assertEquals(AppLockState.Disabled, controller.state.value)
    }

    @Test
    fun `a re-read never locks and never opens LOCKED from any state`() {
        val (h, controller) = transientLocked()
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.requestSettingsReread()
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        assertTrue(h.authenticator.calls.isEmpty())

        val (h2, unlocked) = transientUnlocked()
        h2.repository.scriptRead(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        unlocked.requestSettingsReread()
        assertEquals(AppLockState.Unlocked, unlocked.state.value)
    }

    // --- Privacy review fixes (F3, F5, F6, F7) -----------------------------------------------------------------

    @Test
    fun `F6 INV-47 the foreground evaluates with the held state BEFORE the re-read is launched`() {
        val (h, controller) = transientUnlocked()
        controller.onAppBackgrounded()
        h.clock.advance(1_000L)
        h.repository.scriptRead(AppLockTestSettings.DISABLED) // no gate: the re-read completes inside the launch

        controller.onAppForegrounded()

        // Evaluation first: the held (transient unreadable) view measures with F = IMMEDIATE and locks; the re-read
        // then finds LOCKED and is only kept (T29). Were the re-read launched first, T28 would apply DISABLED from
        // UNLOCKED and the evaluation would see a lock that is off: no TIMEOUT lock.
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)

        unlockOnce(h, controller)

        assertEquals(AppLockState.Disabled, controller.state.value)
    }

    @Test
    fun `F5 a newer unreadable re-read result supersedes an older kept snapshot, so the unlock applies nothing`() {
        // Row 1: kept while LOCKED. Row 2: kept while AUTHENTICATING(UNLOCK).
        for (authenticating in listOf(false, true)) {
            val (h, controller) = transientLocked()
            if (authenticating) h.tap(controller)
            h.repository.scriptRead(AppLockTestSettings.DISABLED)
            controller.requestSettingsReread() // kept
            h.repository.scriptRead(unreadableIo) // the newer result: unreadable again
            controller.requestSettingsReread() // supersedes the kept snapshot

            if (authenticating) {
                h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            } else {
                unlockOnce(h, controller)
            }

            assertEquals("authenticating=$authenticating", AppLockState.Unlocked, controller.state.value) // not DISABLED
            assertNeverWrote(h)
            // The view is still transient, so the next foreground re-read can still recover it.
            h.repository.scriptRead(AppLockTestSettings.DISABLED)
            controller.onAppForegrounded()
            assertEquals("authenticating=$authenticating", AppLockState.Disabled, controller.state.value)
        }
    }

    @Test
    fun `F7 D6 a committed open-origin setting move drops a kept snapshot, even when its token does not match (F3)`() {
        // Row 1: the CHANGE token matches. Row 2: the id matches nothing (no token consumed, the write still counts).
        for (matchingToken in listOf(true, false)) {
            val (h, controller) = transientUnlocked()
            val attemptId = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // closes to UNLOCKED, records the CHANGE token
            assertEquals(AppLockState.Unlocked, controller.state.value)
            controller.onAppBackgrounded()
            h.clock.advance(1_000L)
            h.repository.scriptRead(AppLockTestSettings.DISABLED)
            controller.onAppForegrounded() // T14 locks (F is IMMEDIATE); the re-read result is kept (T29)
            assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)

            controller.onOpenOriginSettingApplied(if (matchingToken) attemptId else attemptId + 99, committed = true)

            unlockOnce(h, controller)

            assertEquals("matching=$matchingToken", AppLockState.Unlocked, controller.state.value) // dropped: not DISABLED
            assertNeverWrote(h)
        }
    }
}
