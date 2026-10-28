package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings re-read of [AppLockController] (S18 ii-c, INV-47): T28 from `UNLOCKED` and `INERT`, T29 in `LOCKED` and
 * `AUTHENTICATING` (AE-97, AE-98), the D6 drop of a kept snapshot, the stale-sequence drop, and that a re-read never locks
 * and never writes. The re-reads are scripted through `FakeAppLockSettingsRepository.scriptRead`.
 *
 * The emission of `LOCK_SETTINGS_RECOVERED` (D9, owner-approvals item 51a) is covered at the end of this file: one
 * recovery from a transient `Unreadable(IO_ERROR | TIMEOUT)` view records it once with the bounded prior cause under the
 * `CAUSE` key, together with a new INERT diagnostic when both happen, from T28 and from the T29 kept snapshot, and
 * nothing is recorded for a readable or persistent-unreadable view, an unreadable or failing re-read, a stale or
 * D6-dropped result, or a snapshot that is superseded. The once-per-(code, cause) claim itself is not reachable twice
 * through one controller (a recovered view never becomes transient unreadable again), so only its observable
 * consequence is pinned: one record per recovery, and a different cause is a different claim.
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
    fun `D6 a discreet write committed through requestDiscreetChange after a kept snapshot drops that snapshot (S20-d WRITE 5)`() =
        runBlocking {
            // S20-d: the 1a ViewModel now reaches the controller only through the operations. The REAL
            // requestDiscreetChange (not a direct onPlainSettingWritten call) must advance the write sequence.
            val (h, controller) = transientLocked()
            val rig = SettingsOperationsRig(h, controller)
            h.repository.scriptRead(AppLockTestSettings.DISABLED)
            controller.onAppForegrounded() // the re-read result is kept (T29)
            // The store commits the discreet write (the harness view stays transient, so the snapshot is the only difference).
            h.repository.plainWriteResult = { _, value ->
                AppLockSettingsWriteResult.Committed(AppLockPrivacySettings(discreetNotifications = value))
            }

            assertEquals(PlainSettingResult.COMMITTED, rig.ops.requestDiscreetChange(true))
            unlockOnce(h, controller)

            assertEquals(AppLockState.Unlocked, controller.state.value) // dropped: not DISABLED
            // The view is still transient, so the next foreground re-reads and recovers.
            h.repository.scriptRead(AppLockTestSettings.DISABLED)
            controller.onAppForegrounded()
            assertEquals(AppLockState.Disabled, controller.state.value)
            assertTrue(rig.uncaught.isEmpty())
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

    // --- D9: LOCK_SETTINGS_RECOVERED (S18 ii-c, owner-approvals item 51a) ---------------------------------------

    private val recoveredCode = PrivacyDiagnosticCode.LOCK_SETTINGS_RECOVERED

    /** The metadata of every `LOCK_SETTINGS_RECOVERED` the harness recorded, in order (`emitted` and `emittedMetadata` are in step). */
    private fun AppLockControllerHarness.recoveryMetadata(): List<Map<PrivacyDiagnosticKey, Any>> =
        emitted.indices.filter { emitted[it] == recoveredCode }.map { emittedMetadata[it].entries }

    /** Forgets what the setup emitted, so a test sees only the diagnostics of the step under test. */
    private fun AppLockControllerHarness.forgetEmitted() {
        emitted.clear()
        emittedMetadata.clear()
    }

    /**
     * Exactly one `LOCK_SETTINGS_RECOVERED` per cause in [causes], in order, each carrying only the closed `CAUSE` key
     * with the bounded enum (no text of any kind), and no recorded diagnostic of any code carries a string value.
     */
    private fun assertRecoveries(label: String, h: AppLockControllerHarness, vararg causes: SettingsUnreadableCause) {
        val expected = causes.map { mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.CAUSE to it) }
        assertEquals(label, expected, h.recoveryMetadata())
        assertTrue(label, h.emittedMetadata.all { m -> m.entries.values.none { it is String } })
        assertEquals(label, emptyList<Throwable>(), h.uncaught)
    }

    @Test
    fun `D9 a T28 recovery from a transient IO_ERROR or TIMEOUT view records LOCK_SETTINGS_RECOVERED once with that cause`() {
        val transientCauses = SettingsUnreadableCause.entries.filter { !it.isPersistent }
        assertEquals(setOf(SettingsUnreadableCause.IO_ERROR, SettingsUnreadableCause.TIMEOUT), transientCauses.toSet())
        for (cause in transientCauses) {
            val (h, controller) = transientUnlocked(cause = cause)
            h.forgetEmitted()
            h.repository.scriptRead(AppLockTestSettings.DISABLED)

            controller.requestSettingsReread()

            assertEquals("$cause", AppLockState.Disabled, controller.state.value)
            assertEquals("$cause", listOf(recoveredCode), h.emitted)
            assertRecoveries("$cause", h, cause)
            assertNeverWrote(h)
        }
    }

    @Test
    fun `D9 a recovery that keeps the state UNLOCKED still records LOCK_SETTINGS_RECOVERED`() {
        val (h, controller) = transientUnlocked()
        h.forgetEmitted()
        h.repository.scriptRead(enabled)

        controller.requestSettingsReread()

        assertEquals(AppLockState.Unlocked, controller.state.value) // the state does not change
        assertEquals(listOf(recoveredCode), h.emitted)
        assertRecoveries("unlocked", h, SettingsUnreadableCause.IO_ERROR)
    }

    @Test
    fun `D9 a foreground re-read records LOCK_SETTINGS_RECOVERED too`() {
        val (h, controller) = transientUnlocked(cause = SettingsUnreadableCause.TIMEOUT)
        h.forgetEmitted()
        h.repository.scriptRead(AppLockTestSettings.DISABLED)

        controller.onAppForegrounded()

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertRecoveries("foreground", h, SettingsUnreadableCause.TIMEOUT)
    }

    @Test
    fun `D9 a recovery is recorded once, later re-reads and foregrounds record nothing more`() {
        val (h, controller) = transientUnlocked()
        h.forgetEmitted()
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.requestSettingsReread()
        assertRecoveries("first", h, SettingsUnreadableCause.IO_ERROR)

        // The view is readable now: no further read is made, and an unreadable result could not be applied anyway.
        val readsAfterRecovery = h.repository.readCount
        h.repository.scriptRead(unreadableIo)
        controller.requestSettingsReread()
        controller.onAppForegrounded()
        controller.onAppBackgrounded()
        controller.onAppForegrounded()

        assertEquals(readsAfterRecovery, h.repository.readCount)
        assertRecoveries("later", h, SettingsUnreadableCause.IO_ERROR)
    }

    @Test
    fun `D9 each recovery records its own prior cause (IO_ERROR then TIMEOUT)`() {
        val h = AppLockControllerHarness(unreadableIo)
        val first = h.resolvedController()
        h.unlock(first)
        h.forgetEmitted()
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        first.requestSettingsReread()
        assertRecoveries("io", h, SettingsUnreadableCause.IO_ERROR)

        // A second controller over a store that is unreadable for another transient cause.
        h.repository.loadState = AppLockTestSettings.unreadable(SettingsUnreadableCause.TIMEOUT)
        val second = h.resolvedController()
        h.unlock(second)
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        second.requestSettingsReread()

        assertEquals(AppLockState.Disabled, second.state.value)
        assertRecoveries("io then timeout", h, SettingsUnreadableCause.IO_ERROR, SettingsUnreadableCause.TIMEOUT)
    }

    @Test
    fun `D9 a stale re-read result records nothing and the newest result records exactly one recovery`() {
        val (h, controller) = transientUnlocked()
        h.forgetEmitted()
        val olderGate = CompletableDeferred<Unit>()
        val newestGate = CompletableDeferred<Unit>()
        try {
            h.repository.scriptRead(AppLockTestSettings.DISABLED, olderGate) // the first (older) re-read, held
            h.repository.scriptRead(enabled, newestGate) // the second (newest) re-read, held too
            controller.requestSettingsReread()
            controller.requestSettingsReread()
            assertRecoveries("both held", h)

            olderGate.complete(Unit) // the older result arrives first, as a stale sequence
            assertEquals(AppLockState.Unlocked, controller.state.value) // the stale DISABLED did not apply
            assertRecoveries("stale dropped", h)

            newestGate.complete(Unit) // the newest result arrives and applies
            assertEquals(AppLockState.Unlocked, controller.state.value)
            assertRecoveries("newest applied", h, SettingsUnreadableCause.IO_ERROR)
        } finally {
            olderGate.complete(Unit)
            newestGate.complete(Unit)
        }
    }

    @Test
    fun `D9 a re-read dropped by a committed write records nothing, the later recovery records it`() {
        val (h, controller) = transientUnlocked()
        h.forgetEmitted()
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
        assertRecoveries("dropped", h)

        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.onAppForegrounded()

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertRecoveries("recovered later", h, SettingsUnreadableCause.IO_ERROR)
    }

    @Test
    fun `D9 an unreadable or failing re-read is not a recovery and records nothing`() {
        val (h, controller) = transientUnlocked()
        h.forgetEmitted()
        for (cause in SettingsUnreadableCause.entries) {
            h.repository.scriptRead(AppLockTestSettings.unreadable(cause))
            controller.requestSettingsReread()
        }
        h.repository.failure = IllegalStateException("secret-path /data/x")
        controller.requestSettingsReread()

        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(emptyList<PrivacyDiagnosticCode>(), h.emitted)
        assertRecoveries("unreadable", h)
    }

    @Test
    fun `D9 a recovery to INERT records both the new INERT diagnostic and LOCK_SETTINGS_RECOVERED`() {
        // Row 1: UNLOCKED, the capability is gone. Row 2: INERT(SETTINGS_UNREADABLE_NO_AUTH), a different reason now.
        val rows = listOf(
            Triple(SettingsUnreadableCause.IO_ERROR, DeviceAuthCapability.NO_CREDENTIAL, PrivacyDiagnosticCode.LOCK_INERT_NO_CREDENTIAL),
            Triple(SettingsUnreadableCause.TIMEOUT, DeviceAuthCapability.HW_UNAVAILABLE, PrivacyDiagnosticCode.LOCK_INERT_HW_UNAVAILABLE)
        )
        for ((index, row) in rows.withIndex()) {
            val (cause, capability, inertCode) = row
            val (h, controller) = if (index == 0) {
                transientUnlocked(cause = cause)
            } else {
                val inert = AppLockControllerHarness(AppLockTestSettings.unreadable(cause), DeviceAuthCapability.NO_CREDENTIAL)
                val c = inert.resolvedController()
                assertEquals(AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), c.state.value)
                inert to c
            }
            h.forgetEmitted()
            h.capabilityProvider.capability = capability
            h.repository.scriptRead(enabled)

            controller.requestSettingsReread()

            assertEquals("$cause", true, controller.state.value is AppLockState.Inert)
            assertEquals("$cause", 2, h.emitted.size)
            assertEquals("$cause", setOf(recoveredCode, inertCode), h.emitted.toSet())
            assertRecoveries("$cause", h, cause)
            // The INERT diagnostic keeps its own shape: no metadata.
            assertTrue("$cause", h.emittedMetadata[h.emitted.indexOf(inertCode)].entries.isEmpty())
            assertNeverWrote(h)
        }
    }

    @Test
    fun `D9 a recovery from INERT to a non-INERT state records only LOCK_SETTINGS_RECOVERED`() {
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.TIMEOUT), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()
        assertEquals(AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), controller.state.value)
        h.forgetEmitted()
        h.repository.scriptRead(AppLockTestSettings.DISABLED)

        controller.requestSettingsReread()

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertEquals(listOf(recoveredCode), h.emitted)
        assertRecoveries("inert to disabled", h, SettingsUnreadableCause.TIMEOUT)
    }

    @Test
    fun `D9 T29 a snapshot kept in LOCKED is recorded when the unlock applies it, not when it is kept`() {
        val (h, controller) = transientLocked()
        h.forgetEmitted()
        h.repository.scriptRead(AppLockTestSettings.DISABLED)

        controller.requestSettingsReread() // kept: LOCKED is not relaxed

        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        assertRecoveries("kept", h)

        unlockOnce(h, controller)

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertRecoveries("applied", h, SettingsUnreadableCause.IO_ERROR)
    }

    @Test
    fun `D9 T29 a snapshot kept in AUTHENTICATING UNLOCK is recorded only when a later unlock applies it`() {
        val (h, controller) = transientLocked()
        h.forgetEmitted()
        h.tap(controller)
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.requestSettingsReread()
        h.authenticator.deliver(DeviceAuthOutcome.Cancelled) // back to LOCKED: the snapshot stays kept

        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        assertRecoveries("cancelled", h)

        unlockOnce(h, controller)

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertRecoveries("unlocked", h, SettingsUnreadableCause.IO_ERROR)
    }

    @Test
    fun `D9 T29 a snapshot kept during an open-origin prompt is recorded once the prompt ends in an open state`() {
        for (outcome in listOf(DeviceAuthOutcome.Cancelled, DeviceAuthOutcome.Succeeded)) {
            val (h, controller) = transientUnlocked(cause = SettingsUnreadableCause.TIMEOUT)
            h.forgetEmitted()
            h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
            h.repository.scriptRead(AppLockTestSettings.DISABLED)
            controller.requestSettingsReread()
            assertRecoveries("$outcome kept", h)

            h.authenticator.deliver(outcome)

            assertEquals("$outcome", AppLockState.Disabled, controller.state.value)
            assertRecoveries("$outcome applied", h, SettingsUnreadableCause.TIMEOUT)
        }
    }

    @Test
    fun `D9 T29 a kept snapshot that INERT applies records both diagnostics after the unlock`() {
        val (h, controller) = transientLocked()
        h.tap(controller) // the capability is checked at the tap, so it changes only after the prompt started
        h.forgetEmitted()
        h.repository.scriptRead(enabled)
        controller.requestSettingsReread() // kept while AUTHENTICATING(UNLOCK)
        assertRecoveries("kept", h)
        h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        assertEquals(
            setOf(PrivacyDiagnosticCode.LOCK_SETTINGS_RECOVERED, PrivacyDiagnosticCode.LOCK_INERT_NO_CREDENTIAL),
            h.emitted.filter { it == recoveredCode || it == PrivacyDiagnosticCode.LOCK_INERT_NO_CREDENTIAL }.toSet()
        )
        assertRecoveries("kept to inert", h, SettingsUnreadableCause.IO_ERROR)
    }

    @Test
    fun `D9 T29 a kept snapshot that D6 drops or a newer unreadable result supersedes records nothing`() {
        // Row 1: a committed write drops the kept snapshot. Row 2: a newer unreadable result supersedes it.
        for (dropped in listOf(true, false)) {
            val (h, controller) = transientLocked()
            h.forgetEmitted()
            h.repository.scriptRead(AppLockTestSettings.DISABLED)
            controller.requestSettingsReread() // kept
            if (dropped) {
                controller.onPlainSettingWritten(AppLockSettingField.DISCREET, true, committed = true)
            } else {
                h.repository.scriptRead(unreadableIo)
                controller.requestSettingsReread()
            }

            unlockOnce(h, controller)

            assertEquals("dropped=$dropped", AppLockState.Unlocked, controller.state.value) // not DISABLED
            assertRecoveries("dropped=$dropped", h)

            // The view is still transient, so the next foreground re-read recovers and records exactly once.
            h.repository.scriptRead(AppLockTestSettings.DISABLED)
            controller.onAppForegrounded()
            assertEquals("dropped=$dropped", AppLockState.Disabled, controller.state.value)
            assertRecoveries("dropped=$dropped later", h, SettingsUnreadableCause.IO_ERROR)
        }
    }

    @Test
    fun `D9 a readable view never records LOCK_SETTINGS_RECOVERED`() {
        val firstRun = AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings())
        for (stored in listOf(AppLockTestSettings.DISABLED, firstRun, enabled)) {
            val h = AppLockControllerHarness(stored)
            val controller = h.resolvedController()
            if (stored === enabled) h.unlock(controller)
            h.forgetEmitted()
            h.repository.scriptRead(AppLockTestSettings.DISABLED)
            val readsBefore = h.repository.readCount

            controller.requestSettingsReread()
            controller.onAppForegrounded()

            assertEquals("$stored", readsBefore, h.repository.readCount) // no re-read for a readable view
            assertRecoveries("$stored", h)
        }
    }

    @Test
    fun `D9 a persistent unreadable view never records LOCK_SETTINGS_RECOVERED, the re-read is not even made`() {
        for (cause in SettingsUnreadableCause.entries.filter { it.isPersistent }) {
            // Row 1: unlocked over the persistent view. Row 2: still locked over it.
            for (unlocked in listOf(true, false)) {
                val h = AppLockControllerHarness(AppLockTestSettings.unreadable(cause))
                val controller = h.resolvedController()
                if (unlocked) h.unlock(controller)
                val stateBefore = controller.state.value
                h.forgetEmitted()
                h.repository.scriptRead(AppLockTestSettings.DISABLED)

                controller.requestSettingsReread()
                controller.onAppForegrounded()

                val label = "$cause unlocked=$unlocked"
                assertEquals(label, 1, h.repository.readCount) // the resolve only
                assertEquals(label, stateBefore, controller.state.value)
                assertRecoveries(label, h)
            }
        }
    }

    @Test
    fun `INV-37 a failing emitter does not change the recovery and does not escape`() {
        val sinks = listOf<Pair<String, (AppLockControllerHarness) -> Unit>>(
            "failing" to { h -> h.emitFailure = IllegalStateException("secret-path /data/x merchant ACME") },
            "stuck" to { h -> h.emitSuspendsForever = true }
        )
        for ((label, breakSink) in sinks) {
            val (h, controller) = transientUnlocked()
            h.forgetEmitted()
            breakSink(h)
            h.repository.scriptRead(AppLockTestSettings.DISABLED)

            controller.requestSettingsReread()

            assertEquals(label, AppLockState.Disabled, controller.state.value) // the transition happened regardless
            assertEquals(label, listOf(recoveredCode), h.emitted) // attempted once
            assertEquals(label, emptyList<Throwable>(), h.uncaught) // and the failure did not escape
            assertNeverWrote(h)
        }
    }

    // --- S21-a, 61c-2: the controller's own RESOLVE_TIMEOUT fallback is a transient unreadable view ----------------

    /**
     * A controller resolved by the controller's own RESOLVE_TIMEOUT (view `null`, `resolvedByTimeout`): `LOCKED(SETTINGS_UNREADABLE)`
     * for an able device, `INERT(SETTINGS_UNREADABLE_NO_AUTH)` otherwise. The held first read is then released with an
     * unreadable result, so T6b drops it and the view stays the timeout fallback. Later reads return [unreadableIo]
     * unless scripted. Exactly one read has been made.
     */
    private fun TestScope.timedOut(
        capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE
    ): Pair<AppLockControllerHarness, AppLockController> {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED, capability, scheduler = testScheduler)
        val controller = h.newController()
        resolveTimedOut(h, controller)
        h.repository.loadState = unreadableIo
        h.repository.releaseReads()
        runCurrent()
        val expected = if (capability == DeviceAuthCapability.AVAILABLE) {
            AppLockState.Locked(LockCause.SETTINGS_UNREADABLE)
        } else {
            AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH)
        }
        assertEquals(expected, controller.state.value)
        assertEquals(1, h.repository.readCount)
        return h to controller
    }

    @Test
    fun `61c-2 after a resolve timeout the first APP_FOREGROUNDED issues exactly one re-read`() = runTest {
        val (h, controller) = timedOut()
        h.repository.scriptRead(unreadableIo)

        controller.onAppForegrounded()
        runCurrent()

        assertEquals(2, h.repository.readCount) // the resolve read plus exactly one re-read
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        assertNeverWrote(h)
    }

    @Test
    fun `61c-2 requestSettingsReread after a resolve timeout issues one read`() = runTest {
        val (h, controller) = timedOut()
        h.repository.scriptRead(unreadableIo)

        controller.requestSettingsReread()
        runCurrent()

        assertEquals(2, h.repository.readCount)
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
    }

    @Test
    fun `61c-2 timeout then unlock then a readable lock-off re-read gives DISABLED, with no notice before or after`() = runTest {
        val (h, controller) = timedOut()
        h.unlock(controller)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        // A missing load state (the timeout fallback) derives no notice (transient causes show none).
        assertEquals(null, controller.notice.value)
        h.repository.scriptRead(AppLockTestSettings.DISABLED)

        controller.onAppForegrounded()
        runCurrent()

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertEquals(null, controller.notice.value)
        assertEquals(2, h.repository.readCount) // the resolve read and the one foreground re-read; the unlock reads nothing
        assertNeverWrote(h)
    }

    @Test
    fun `61c-2 timeout in LOCKED keeps a readable re-read, the state stays LOCKED, and T8 applies the snapshot (T29)`() = runTest {
        val (h, controller) = timedOut()
        h.forgetEmitted()
        h.repository.scriptRead(AppLockTestSettings.DISABLED)

        controller.onAppForegrounded()
        runCurrent()

        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value) // never opened by a re-read
        assertTrue(h.authenticator.calls.isEmpty())
        assertRecoveries("kept", h)

        unlockOnce(h, controller) // T8: the unlock applies the kept snapshot through T28

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertRecoveries("applied", h, SettingsUnreadableCause.TIMEOUT)
        assertNeverWrote(h)
    }

    @Test
    fun `61c-2 timeout in LOCKED, a readable lock-on re-read is kept and the unlock lands UNLOCKED`() = runTest {
        val (h, controller) = timedOut()
        h.repository.scriptRead(enabled)

        controller.requestSettingsReread()
        runCurrent()
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)

        unlockOnce(h, controller)

        assertEquals(AppLockState.Unlocked, controller.state.value)
        // The view is readable now: the next foreground makes no further read.
        val reads = h.repository.readCount
        controller.onAppForegrounded()
        runCurrent()
        assertEquals(reads, h.repository.readCount)
    }

    @Test
    fun `61c-2 timeout then INERT(SETTINGS_UNREADABLE_NO_AUTH), a readable lock-off re-read gives DISABLED`() = runTest {
        val (h, controller) = timedOut(DeviceAuthCapability.NO_CREDENTIAL)
        h.forgetEmitted()
        h.repository.scriptRead(AppLockTestSettings.DISABLED)

        controller.requestSettingsReread()
        runCurrent()

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertTrue(h.authenticator.calls.isEmpty()) // no auth
        assertRecoveries("inert to disabled", h, SettingsUnreadableCause.TIMEOUT)
        assertNeverWrote(h)
    }

    @Test
    fun `61c-2 an unreadable re-read after a resolve timeout changes nothing, and the next foreground re-reads again`() = runTest {
        val (h, controller) = timedOut()
        h.forgetEmitted()
        h.repository.scriptRead(unreadableIo)
        controller.onAppForegrounded()
        runCurrent()
        assertEquals(2, h.repository.readCount)
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)

        h.repository.scriptRead(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED))
        controller.onAppForegrounded()
        runCurrent()
        assertEquals(3, h.repository.readCount) // the in-flight flag was released: the next foreground reads again
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)

        h.repository.failure = IllegalStateException("secret-path /data/x")
        controller.onAppForegrounded()
        runCurrent()
        assertEquals(4, h.repository.readCount)
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        assertRecoveries("none", h)

        h.repository.failure = null
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.onAppForegrounded()
        runCurrent()
        assertEquals(5, h.repository.readCount)
        unlockOnce(h, controller)
        assertEquals(AppLockState.Disabled, controller.state.value) // the view was still the timeout fallback until now
    }

    @Test
    fun `61c-2 after T6b no re-read is issued`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED, scheduler = testScheduler)
        val controller = h.newController()
        resolveTimedOut(h, controller)
        h.repository.releaseReads() // the late read is the readable lock-off store (DISABLED)
        runCurrent()
        assertEquals(AppLockState.Disabled, controller.state.value) // T6b
        assertEquals(1, h.repository.readCount)

        controller.onAppForegrounded()
        controller.requestSettingsReread()
        runCurrent()

        assertEquals(1, h.repository.readCount)
        assertEquals(AppLockState.Disabled, controller.state.value)
    }

    @Test
    fun `61c-2 a late resolve read after a re-read applied is dropped`() = runTest {
        val lateGate = CompletableDeferred<Unit>()
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), scheduler = testScheduler)
        try {
            h.repository.scriptRead(AppLockTestSettings.DISABLED, lateGate) // the resolve read: late, lock off
            val controller = h.newController()
            val resolving = launch { controller.resolve() }
            runCurrent()
            advanceTimeBy(AppLockController.RESOLVE_TIMEOUT_MILLIS + 1)
            runCurrent()
            assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
            h.unlock(controller)
            h.repository.scriptRead(enabled) // the re-read reads the store (lock on): applies, UNLOCKED stays

            controller.onAppForegrounded()
            runCurrent()
            assertEquals(AppLockState.Unlocked, controller.state.value)
            val readsAfterReread = h.repository.readCount

            lateGate.complete(Unit) // the late resolve read arrives after the re-read applied
            runCurrent()
            resolving.join()

            assertEquals(AppLockState.Unlocked, controller.state.value) // not DISABLED
            controller.onAppForegrounded()
            runCurrent()
            assertEquals(readsAfterReread, h.repository.readCount) // the view is readable: no further read
        } finally {
            lateGate.complete(Unit)
        }
    }

    @Test
    fun `61c-2 CORRUPTED and INVALID_VALUE resolutions still issue no re-read (regression)`() = runTest {
        for (cause in SettingsUnreadableCause.entries.filter { it.isPersistent }) {
            val h = AppLockControllerHarness(AppLockTestSettings.unreadable(cause), scheduler = testScheduler)
            val controller = h.resolvedController()
            assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
            h.repository.scriptRead(AppLockTestSettings.DISABLED)

            controller.onAppForegrounded()
            controller.requestSettingsReread()
            runCurrent()

            assertEquals("cause $cause", 1, h.repository.readCount) // the resolve only
            assertEquals("cause $cause", AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        }
    }

    @Test
    fun `61c-2 D6 a settings write committed after the re-read was issued drops it, the view stays the timeout fallback`() = runTest {
        val (h, controller) = timedOut()
        h.unlock(controller)
        val gate = CompletableDeferred<Unit>()
        try {
            h.repository.scriptRead(AppLockTestSettings.DISABLED, gate)
            controller.requestSettingsReread()
            controller.onPlainSettingWritten(AppLockSettingField.DISCREET, true, committed = true)

            gate.complete(Unit)
            runCurrent()
        } finally {
            gate.complete(Unit)
        }

        assertEquals(AppLockState.Unlocked, controller.state.value) // dropped: not DISABLED
        // The view is still the timeout fallback, so the next foreground re-reads and recovers.
        h.forgetEmitted()
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.onAppForegrounded()
        runCurrent()
        assertEquals(AppLockState.Disabled, controller.state.value)
        assertRecoveries("recovered later", h, SettingsUnreadableCause.TIMEOUT)
    }

    @Test
    fun `61c-2 LOCK_SETTINGS_RECOVERED(TIMEOUT) is emitted once, later re-reads and foregrounds record nothing more`() = runTest {
        val (h, controller) = timedOut()
        h.unlock(controller)
        h.forgetEmitted()
        h.repository.scriptRead(enabled)

        controller.requestSettingsReread()
        runCurrent()
        assertEquals(AppLockState.Unlocked, controller.state.value) // the state does not change
        assertEquals(listOf(recoveredCode), h.emitted)
        assertRecoveries("first", h, SettingsUnreadableCause.TIMEOUT)

        val reads = h.repository.readCount
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.requestSettingsReread()
        controller.onAppForegrounded()
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        runCurrent()

        assertEquals(reads, h.repository.readCount) // readable now: nothing more is read
        assertRecoveries("later", h, SettingsUnreadableCause.TIMEOUT)
    }

    @Test
    fun `61c-2 a re-read that hangs is bounded by RESOLVE_TIMEOUT_MILLIS, makes no change and releases the foreground flag`() = runTest {
        val (h, controller) = timedOut()
        h.unlock(controller)
        h.forgetEmitted()
        h.repository.scriptRead(AppLockTestSettings.DISABLED) // the hung read WOULD apply if it were not timed out
        h.repository.holdReads() // every later read hangs
        try {
            controller.onAppForegrounded()
            runCurrent()
            assertEquals(2, h.repository.readCount)
            controller.onAppForegrounded() // the first foreground re-read is still in flight
            runCurrent()
            assertEquals(2, h.repository.readCount)

            advanceTimeBy(AppLockController.RESOLVE_TIMEOUT_MILLIS + 1) // the bound passes: null means no change
            runCurrent()
            assertEquals(AppLockState.Unlocked, controller.state.value)
            assertEquals(emptyList<Throwable>(), h.uncaught)

            controller.onAppForegrounded() // the flag was released: this one reads again
            runCurrent()
            assertEquals(3, h.repository.readCount)
        } finally {
            h.repository.releaseReads()
        }
        runCurrent()
        // The timed-out read's readable DISABLED result is never applied, and nothing was recovered or recorded.
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertRecoveries("timed-out read applied nothing", h)

        // A later readable re-read still recovers.
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.onAppForegrounded()
        runCurrent()
        assertEquals(AppLockState.Disabled, controller.state.value)
    }

    @Test
    fun `61c-2 cancelling the controller jobs during a bounded re-read propagates and frees the next foreground`() = runTest {
        val (h, controller) = timedOut()
        h.unlock(controller)
        h.forgetEmitted()
        h.repository.scriptRead(AppLockTestSettings.DISABLED) // the held read WOULD apply if it were not cancelled
        h.repository.holdReads()
        try {
            controller.onAppForegrounded()
            runCurrent()
            assertEquals(2, h.repository.readCount)
            h.cancelControllerJobs() // a foreign cancellation is not swallowed by the bounded read
            runCurrent()

            // The flag was freed by the cancellation: the next foreground reads again, while the first read is released.
            controller.onAppForegrounded()
            runCurrent()
            assertEquals(3, h.repository.readCount)
        } finally {
            h.repository.releaseReads()
        }
        runCurrent()
        // The cancelled read's readable DISABLED result was never applied, and nothing was recovered or recorded.
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertRecoveries("cancelled read applied nothing", h)
        assertEquals(emptyList<Throwable>(), h.uncaught)

        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.onAppForegrounded()
        runCurrent()
        assertEquals(AppLockState.Disabled, controller.state.value)
    }
}
