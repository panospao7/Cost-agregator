package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.navigation.deeplink.DeepLinkHost
import com.yourname.expensetracker.domain.navigation.deeplink.DeepLinkRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import timber.log.Timber
import java.io.Serializable

/**
 * S17: the held deep link (T20, T21, T22), INV-30, INV-31, OD-7a, AE-55 to AE-60 and the link part of AE-118.
 *
 * The controller has no navigation port, so the link is pulled: `onLockQuery(link)` answers APPLY_NOW or HOLD
 * (computed under the monitor) and `releaseHeldLink()` hands a held link back at most once. The tests drive both
 * through the same fakes as the other controller tests. Time is the fake monotonic clock; the prompt's own
 * watchdog (coroutine time) is never advanced here.
 */
class AppLockControllerHeldLinkTest {

    private val home = DeepLinkRequest(DeepLinkHost.HOME)
    private val anomaly = DeepLinkRequest(DeepLinkHost.ACTIVITY, expenseId = 77L)
    private val place = DeepLinkRequest(DeepLinkHost.MAP, location = "SecretCafeLocation-42")

    private fun AppLockControllerHarness.linkEvents(): List<Pair<PrivacyDiagnosticCode, PrivacyDiagnosticMetadata>> =
        emitted.zip(emittedMetadata).filter { it.first.name.startsWith("DL_") }

    private fun AppLockControllerHarness.linkCodes(): List<PrivacyDiagnosticCode> = linkEvents().map { it.first }

    private fun released(request: DeepLinkRequest, afterUnlock: Boolean) = HeldLinkRelease.Apply(request, afterUnlock)

    /**
     * F1: `releaseHeldLink()` does nothing while the app is not in the foreground, so a release test reports the
     * foreground first, as S20 does at the first ON_START (the shared fixture leaves a cold-start lock backgrounded).
     */
    private fun foregroundLockedControllerFor(cause: LockCause): Pair<AppLockControllerHarness, AppLockController> =
        lockedControllerFor(cause).also { (_, controller) -> controller.onAppForegrounded() }

    // --- AE-55 to AE-60 -------------------------------------------------------------------------

    @Test
    fun `AE-55 a link while LOCKED is held with no side effect and applied once after unlock`() {
        val (h, controller) = foregroundLockedControllerFor(LockCause.COLD_START)

        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.DL_HELD_LOCKED), h.linkCodes())
        assertEquals(DeepLinkHost.ACTIVITY, h.linkEvents().single().second.entries[PrivacyDiagnosticKey.HOST])
        assertEquals(0, h.repository.writeAttempts)

        // A rotation or any lifecycle event while locked changes nothing; the link is not released while locked.
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())

        h.unlock(controller)
        assertEquals(released(anomaly, afterUnlock = true), controller.releaseHeldLink())
        // Exactly once.
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        assertEquals(listOf(PrivacyDiagnosticCode.DL_HELD_LOCKED), h.linkCodes())
    }

    @Test
    fun `AE-56 the newest link wins and DL_HELD_REPLACED names both hosts`() {
        val (h, controller) = foregroundLockedControllerFor(LockCause.COLD_START)

        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(home))

        assertEquals(
            listOf(PrivacyDiagnosticCode.DL_HELD_LOCKED, PrivacyDiagnosticCode.DL_HELD_REPLACED),
            h.linkCodes()
        )
        val replaced = h.linkEvents().last().second.entries
        assertEquals(DeepLinkHost.ACTIVITY, replaced[PrivacyDiagnosticKey.OLD_HOST])
        assertEquals(DeepLinkHost.HOME, replaced[PrivacyDiagnosticKey.NEW_HOST])

        h.unlock(controller)
        assertEquals(released(home, afterUnlock = true), controller.releaseHeldLink())
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
    }

    @Test
    fun `AE-57 process death drops the held link and the next start applies nothing`() {
        val (h, controller) = foregroundLockedControllerFor(LockCause.COLD_START)
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))

        // A new process: a new controller over the same stores. The link lived in memory only.
        val restarted = h.resolvedController()
        restarted.onAppForegrounded()
        assertEquals(HeldLinkRelease.None, restarted.releaseHeldLink())
        h.unlock(restarted)
        assertEquals(HeldLinkRelease.None, restarted.releaseHeldLink())
    }

    @Test
    fun `AE-58 a link after a long background is held and the lock shows first, whichever event runs first`() {
        // onNewIntent before onStart: the link query itself evaluates the pending timeout.
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        controller.onAppBackgrounded()
        h.clock.advance(10 * 60_000L)

        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        h.unlock(controller)
        assertEquals(released(anomaly, afterUnlock = true), controller.releaseHeldLink())

        // onStart before onNewIntent: the foreground already locked, the link is held.
        val (h2, controller2) = unlockedControllerFor(AutoLockTimeout.M1)
        controller2.onAppBackgrounded()
        h2.clock.advance(10 * 60_000L)
        controller2.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller2.state.value)
        assertEquals(LinkDecision.HOLD, controller2.onLockQuery(anomaly))
        h2.unlock(controller2)
        assertEquals(released(anomaly, afterUnlock = true), controller2.releaseHeldLink())
    }

    @Test
    fun `AE-59 a link within the timeout applies now and nothing is held`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        controller.onAppBackgrounded()
        h.clock.advance(10_000L)

        assertEquals(LinkDecision.APPLY_NOW, controller.onLockQuery(home))
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        assertEquals(emptyList<PrivacyDiagnosticCode>(), h.linkCodes())
    }

    @Test
    fun `AE-60 a link held for T_held or longer is dropped, DL_HELD_EXPIRED is recorded and the caller opens Home`() {
        val ttl = HeldDeepLink.HELD_LINK_TTL_MILLIS
        assertEquals(5 * 60_000L, ttl)

        // Just inside the TTL: applied.
        val (h1, c1) = foregroundLockedControllerFor(LockCause.COLD_START)
        assertEquals(LinkDecision.HOLD, c1.onLockQuery(anomaly))
        h1.clock.advance(ttl - 1)
        h1.unlock(c1)
        assertEquals(released(anomaly, afterUnlock = true), c1.releaseHeldLink())

        // At the TTL and after it (6 min): expired.
        for (heldFor in listOf(ttl, 6 * 60_000L)) {
            val (h, controller) = foregroundLockedControllerFor(LockCause.COLD_START)
            assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
            h.clock.advance(heldFor)
            h.unlock(controller)
            assertEquals("held for $heldFor", HeldLinkRelease.Expired, controller.releaseHeldLink())
            assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
            assertEquals(
                listOf(PrivacyDiagnosticCode.DL_HELD_LOCKED, PrivacyDiagnosticCode.DL_HELD_EXPIRED),
                h.linkCodes()
            )
            assertEquals(DeepLinkHost.ACTIVITY, h.linkEvents().last().second.entries[PrivacyDiagnosticKey.HOST])
        }
    }

    @Test
    fun `a backwards clock counts as expired, fail closed`() {
        val (h, controller) = foregroundLockedControllerFor(LockCause.COLD_START)
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        h.clock.nowMillis -= 10L
        h.unlock(controller)
        assertEquals(HeldLinkRelease.Expired, controller.releaseHeldLink())
    }

    // --- Hold during every state and prompt purpose ---------------------------------------------------

    @Test
    fun `a link is held while RESOLVING and released once the lock resolves open (T2)`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()
        controller.onAppForegrounded() // F1: the first ON_START is reported before any release
        assertEquals(AppLockState.Resolving, controller.state.value)

        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())

        runBlocking { controller.resolve() }
        assertEquals(AppLockState.Disabled, controller.state.value)
        assertEquals(released(anomaly, afterUnlock = false), controller.releaseHeldLink())
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
    }

    @Test
    fun `a link is held while RESOLVING and stays held when the lock resolves to LOCKED (T3)`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        val controller = h.newController()
        controller.onAppForegrounded() // F1: the first ON_START is reported before any release
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))

        runBlocking { controller.resolve() }
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        h.unlock(controller)
        assertEquals(released(anomaly, afterUnlock = true), controller.releaseHeldLink())
    }

    @Test
    fun `a link applies now when the lock is INERT and when it is DISABLED`() {
        val inert = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
        val inertController = inert.resolvedController()
        inertController.onAppForegrounded()
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), inertController.state.value)
        assertEquals(LinkDecision.APPLY_NOW, inertController.onLockQuery(anomaly))
        assertEquals(HeldLinkRelease.None, inertController.releaseHeldLink())

        val (_, disabled) = disabledControllerFor()
        assertEquals(LinkDecision.APPLY_NOW, disabled.onLockQuery(anomaly))
        assertEquals(HeldLinkRelease.None, disabled.releaseHeldLink())
    }

    @Test
    fun `T20 any purpose - a link is held while AUTHENTICATING UNLOCK and the cancel does not release it`() {
        val (h, controller) = foregroundLockedControllerFor(LockCause.COLD_START)
        val id = h.tap(controller)
        assertEquals(AppLockState.Authenticating(id, AuthPurpose.UNLOCK), controller.state.value)

        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        assertEquals(AppLockState.Authenticating(id, AuthPurpose.UNLOCK), controller.state.value)

        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())

        h.unlock(controller)
        assertEquals(released(anomaly, afterUnlock = true), controller.releaseHeldLink())
    }

    @Test
    fun `T20 any purpose - a link is held while AUTHENTICATING ENABLE, CONFIRM_CHANGE and never released by a bare query`() {
        val (hd, disabled) = disabledControllerFor()
        val enableId = hd.startOpenOrigin(disabled, OpenOriginPrompt.ENABLE, RecordingSuccessHandler())
        assertEquals(AppLockState.Authenticating(enableId, AuthPurpose.ENABLE), disabled.state.value)
        assertEquals(LinkDecision.HOLD, disabled.onLockQuery(anomaly))
        assertEquals(AppLockState.Authenticating(enableId, AuthPurpose.ENABLE), disabled.onLockQuery())
        assertEquals(HeldLinkRelease.None, disabled.releaseHeldLink())

        val (hu, unlocked) = unlockedControllerFor()
        val changeId = hu.startOpenOrigin(unlocked, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        assertEquals(AppLockState.Authenticating(changeId, AuthPurpose.CONFIRM_CHANGE), unlocked.state.value)
        assertEquals(LinkDecision.HOLD, unlocked.onLockQuery(anomaly))
        assertEquals(HeldLinkRelease.None, unlocked.releaseHeldLink())
        assertEquals(listOf(PrivacyDiagnosticCode.DL_HELD_LOCKED), hu.linkCodes())
    }

    @Test
    fun `a held link is never decided from AUTHENTICATING even after a long prompt in the foreground`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())

        h.clock.advance(10 * 60_000L) // a foreground prompt accrues nothing
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        assertEquals(AppLockState.Authenticating(id, AuthPurpose.CONFIRM_CHANGE), controller.state.value)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
    }

    // --- Release after T34 -----------------------------------------------------------------------

    @Test
    fun `release after T34 - a cancelled CONFIRM_CHANGE returns to UNLOCKED and the held link is released once`() {
        val (h, controller) = unlockedControllerFor()
        val handler = RecordingSuccessHandler()
        h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, handler)
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))

        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertTrue(handler.delivered.isEmpty())

        assertEquals(released(anomaly, afterUnlock = false), controller.releaseHeldLink())
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
    }

    @Test
    fun `release after T34 - a backgrounded prompt past the timeout locks at the foreground (F1 the release itself evaluates nothing while backgrounded) and keeps the link held`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        controller.onAppBackgrounded() // T32: promptBackgroundAt
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        h.clock.advance(2 * 60_000L)

        // The cancel arrives while backgrounded: the T34 evaluation is deferred, the state is the origin.
        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
        assertEquals(AppLockState.Unlocked, controller.state.value)

        // F1: the lock engages at the foreground (the release itself never evaluates while backgrounded), and the
        // link stays held.
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())

        h.unlock(controller)
        assertEquals(released(anomaly, afterUnlock = true), controller.releaseHeldLink())
    }

    @Test
    fun `F1 a backgrounded T34 cancel within the timeout - the release returns None, the pending background is kept and the next foreground past the timeout locks`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        controller.onAppBackgrounded() // T32: promptBackgroundAt
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        h.clock.advance(10_000L) // within the 1 minute timeout

        // The cancel arrives while backgrounded: T34 hands the background to the timer, evaluation is deferred.
        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertTrue(controller.pendingBackgroundAtMillis != null)

        // A release while not in the foreground evaluates nothing, consumes nothing and keeps the link held.
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertTrue(controller.pendingBackgroundAtMillis != null)

        // The time away goes on and passes the timeout: the next foreground locks (it was not erased).
        h.clock.advance(2 * 60_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())

        h.unlock(controller)
        assertEquals(released(anomaly, afterUnlock = true), controller.releaseHeldLink())
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
    }

    @Test
    fun `F1 a T33 success, then a background, then the apply completion - the release returns None, the pending background is kept and a foreground past the timeout locks`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)

        controller.onAppBackgrounded() // after the hand-off: a pending background of the origin state
        controller.onOpenOriginApplyCompleted(id)
        assertTrue(controller.pendingBackgroundAtMillis != null)

        // Not in the foreground: nothing is evaluated or consumed, the link stays held.
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertTrue(controller.pendingBackgroundAtMillis != null)

        h.clock.advance(2 * 60_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())

        h.unlock(controller)
        assertEquals(released(anomaly, afterUnlock = true), controller.releaseHeldLink())
    }

    @Test
    fun `release after T34 in the foreground - the hand-off locks in the close and the link waits for the unlock`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        h.clock.advance(60_000L)

        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())

        h.unlock(controller)
        assertEquals(released(anomaly, afterUnlock = true), controller.releaseHeldLink())
    }

    // --- Release after T33 and the apply completion ----------------------------------------------------

    @Test
    fun `release after T33 - the link waits for the apply completion for CONFIRM_CHANGE`() {
        val (h, controller) = unlockedControllerFor()
        val handler = RecordingSuccessHandler()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, handler)
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(listOf(id to AuthPurpose.CONFIRM_CHANGE), handler.delivered)
        assertEquals(AppLockState.Unlocked, controller.state.value)

        // The close published the origin state; the link is NOT released by that, nor by any query.
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        assertEquals(AppLockState.Unlocked, controller.onLockQuery())
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())

        // A completion for another id does not open the gate.
        controller.onOpenOriginApplyCompleted(id + 1)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())

        controller.onOpenOriginApplyCompleted(id)
        assertEquals(released(anomaly, afterUnlock = false), controller.releaseHeldLink())
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
    }

    @Test
    fun `release after T33 - ENABLE keeps the link held until the apply completes or fails`() {
        val (h, controller) = disabledControllerFor()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.ENABLE, RecordingSuccessHandler())
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Disabled, controller.state.value)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())

        // The same entry is used for a failed apply: the gate always closes.
        controller.onOpenOriginApplyCompleted(id)
        controller.onOpenOriginApplyCompleted(id) // repeated: harmless
        assertEquals(released(anomaly, afterUnlock = false), controller.releaseHeldLink())
    }

    @Test
    fun `a link arriving after the T33 hand-off but before the apply completes is held, not applied`() {
        val (h, controller) = unlockedControllerFor()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)

        assertEquals(LinkDecision.HOLD, controller.onLockQuery(home))
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        controller.onOpenOriginApplyCompleted(id)
        assertEquals(released(home, afterUnlock = false), controller.releaseHeldLink())
    }

    @Test
    fun `a gate that nobody completes lapses after T_held and the link is then dropped as expired, never held for the process`() {
        val (h, controller) = unlockedControllerFor()
        h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())

        h.clock.advance(HeldDeepLink.HELD_LINK_TTL_MILLIS)
        assertEquals(HeldLinkRelease.Expired, controller.releaseHeldLink())
        assertEquals(LinkDecision.APPLY_NOW, controller.onLockQuery(home))
    }

    @Test
    fun `F2 a link that arrives after the T33 hand-off and a gate that then lapses is dropped as expired, never applied before the apply completed`() {
        val ttl = HeldDeepLink.HELD_LINK_TTL_MILLIS
        val (h, controller) = unlockedControllerFor()
        h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // the gate opens at t0
        h.clock.advance(60_000L)
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(home)) // the link arrives after the hand-off
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())

        // The gate lapses at t0 + T_held, while the link itself is only T_held - 1 minute old.
        h.clock.advance(ttl - 60_000L)
        assertEquals(HeldLinkRelease.Expired, controller.releaseHeldLink())
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        assertEquals(
            listOf(PrivacyDiagnosticCode.DL_HELD_LOCKED, PrivacyDiagnosticCode.DL_HELD_EXPIRED),
            h.linkCodes()
        )
        assertEquals(DeepLinkHost.HOME, h.linkEvents().last().second.entries[PrivacyDiagnosticKey.HOST])
        // The gate is cleared: a new link applies now.
        assertEquals(LinkDecision.APPLY_NOW, controller.onLockQuery(anomaly))
    }

    @Test
    fun `a T33 gate that lapsed before the link arrived is cleared at hold time - the app locks, the link is held, and after the unlock the release applies it`() {
        val ttl = HeldDeepLink.HELD_LINK_TTL_MILLIS
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // the gate opens, nobody completes the apply
        h.clock.advance(ttl) // the gate lapses (still set, not yet cleared)

        controller.onAppBackgrounded()
        h.clock.advance(2 * 60_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)

        // The link arrives while LOCKED after the lapse: held, and the lapsed gate is cleared right here.
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        h.unlock(controller)
        assertEquals(released(anomaly, afterUnlock = true), controller.releaseHeldLink())
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
    }

    @Test
    fun `F5 an open-origin start is refused while the T33 gate is open and allowed once the apply completed or the gate lapsed`() {
        val (h, controller) = unlockedControllerFor()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        val callsBefore = h.authenticator.calls.size

        assertEquals(
            OpenOriginStartResult.NotInOrigin,
            controller.startOpenOriginAttempt(OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        )
        h.settle()
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertNull(controller.inFlightAttemptId)
        assertEquals(callsBefore, h.authenticator.calls.size)

        controller.onOpenOriginApplyCompleted(id)
        val next = controller.startOpenOriginAttempt(OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        assertTrue("$next", next is OpenOriginStartResult.Started && next.attemptId > id)

        // A gate nobody completes lapses after T_held and no longer blocks a start.
        val (h2, controller2) = unlockedControllerFor()
        val id2 = h2.startOpenOrigin(controller2, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        h2.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(
            OpenOriginStartResult.NotInOrigin,
            controller2.startOpenOriginAttempt(OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        )
        h2.clock.advance(HeldDeepLink.HELD_LINK_TTL_MILLIS)
        val after = controller2.startOpenOriginAttempt(OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        assertTrue("$after", after is OpenOriginStartResult.Started && after.attemptId > id2)
    }

    @Test
    fun `F8 a success handler that throws clears the gate for its attempt, so the held link is released and the next start is allowed`() {
        val (h, controller) = unlockedControllerFor()
        val handler = RecordingSuccessHandler().apply { failure = IllegalStateException("handler-failure-secret") }
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, handler)
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(listOf(id to AuthPurpose.CONFIRM_CHANGE), handler.delivered)
        assertEquals(AppLockState.Unlocked, controller.state.value)

        // No onOpenOriginApplyCompleted call is needed: the failed hand-off started no apply.
        assertEquals(released(anomaly, afterUnlock = false), controller.releaseHeldLink())
        val next = controller.startOpenOriginAttempt(OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        assertTrue("$next", next is OpenOriginStartResult.Started && next.attemptId > id)
    }

    @Test
    fun `F8 a success handler that throws CancellationException clears its gate and the cancellation is rethrown unchanged`() {
        val (h, controller) = unlockedControllerFor()
        val cancellation = CancellationException("handler cancelled")
        val handler = RecordingSuccessHandler().apply { failure = cancellation }
        h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, handler)
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))

        var thrown: Throwable? = null
        try {
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue(thrown === cancellation)
        assertEquals(released(anomaly, afterUnlock = false), controller.releaseHeldLink())
    }

    @Test
    fun `a UNLOCK success never opens a T33 gate and an open-origin success never unlocks`() {
        val (h, controller) = foregroundLockedControllerFor(LockCause.COLD_START)
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        // No open-origin attempt can start from LOCKED, and a completion call for any id changes nothing.
        assertEquals(
            OpenOriginStartResult.NotInOrigin,
            controller.startOpenOriginAttempt(OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        )
        controller.onOpenOriginApplyCompleted(1L)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        h.unlock(controller)
        assertEquals(released(anomaly, afterUnlock = true), controller.releaseHeldLink())
    }

    // --- At most once (AE-118 link part) ---------------------------------------------------------------

    @Test
    fun `AE-118 link part - a link that arrived before the host loss is applied once`() {
        val (h, controller) = unlockedControllerFor()
        val handler = RecordingSuccessHandler()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, handler)
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(home)) // the budget tap (CLEAR_TASK)

        controller.onPromptHostLost(id)
        h.settle()
        assertEquals(AppLockState.Unlocked, controller.state.value)

        // A late success for the abandoned id is stale, also for the link: nothing is handed over, no gate opens.
        h.authenticator.calls.last().callback.onOutcome(id, DeviceAuthOutcome.Succeeded)
        assertTrue(handler.delivered.isEmpty())

        assertEquals(released(home, afterUnlock = false), controller.releaseHeldLink())
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        // And a repeat of the same query does not apply it a second time.
        assertEquals(LinkDecision.APPLY_NOW, controller.onLockQuery(home)) // a NEW link event, applied by its caller
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
    }

    @Test
    fun `AE-118 link part - a link that arrived after the host loss is applied once, immediately`() {
        val (h, controller) = unlockedControllerFor()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())

        controller.onPromptHostLost(id)
        h.settle()
        assertEquals(AppLockState.Unlocked, controller.state.value)

        assertEquals(LinkDecision.APPLY_NOW, controller.onLockQuery(home))
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink()) // nothing was held, so nothing applies again
        assertEquals(emptyList<PrivacyDiagnosticCode>(), h.linkCodes())
    }

    @Test
    fun `a link that applies now supersedes an older link that was still held, which never applies after it`() {
        val (h, controller) = unlockedControllerFor()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        controller.onOpenOriginApplyCompleted(id) // the gate is closed, but the caller has not pulled the link yet

        assertEquals(LinkDecision.APPLY_NOW, controller.onLockQuery(home))
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        assertEquals(
            listOf(PrivacyDiagnosticCode.DL_HELD_LOCKED, PrivacyDiagnosticCode.DL_HELD_REPLACED),
            h.linkCodes()
        )
    }

    @Test
    fun `releasing with nothing held does not read the capability or touch the state`() {
        val (h, controller) = unlockedControllerFor()
        val reads = h.capabilityProvider.evaluations
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        assertEquals(reads, h.capabilityProvider.evaluations)
        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    // --- Privacy and fail-closed rules -------------------------------------------------------------------

    @Test
    fun `no link content reaches a diagnostic, a log line or a string form`() {
        val logged = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logged += message
            }
        }
        Timber.plant(tree)
        try {
            val (h, controller) = foregroundLockedControllerFor(LockCause.COLD_START)
            h.capabilityProvider.failure = IllegalStateException("capability-failure-secret-text")
            assertEquals(LinkDecision.HOLD, controller.onLockQuery(place))
            assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
            h.capabilityProvider.failure = null
            h.clock.advance(HeldDeepLink.HELD_LINK_TTL_MILLIS)
            h.unlock(controller)
            assertEquals(HeldLinkRelease.Expired, controller.releaseHeldLink())

            // Diagnostics carry the closed host enums only.
            val linkEvents = h.linkEvents()
            assertTrue(linkEvents.isNotEmpty())
            linkEvents.forEach { (_, metadata) ->
                metadata.entries.forEach { (key, value) ->
                    assertTrue("$key", key in setOf(PrivacyDiagnosticKey.HOST, PrivacyDiagnosticKey.OLD_HOST, PrivacyDiagnosticKey.NEW_HOST))
                    assertTrue("$key=$value", value is DeepLinkHost)
                }
            }
            // No log line carries the link, the location or the failure text.
            logged.forEach {
                assertFalse(it, it.contains("SecretCafeLocation"))
                assertFalse(it, it.contains("capability-failure-secret-text"))
                assertFalse(it, it.contains("77"))
            }
            // The string forms name the host only.
            assertFalse(place.toString().contains("SecretCafeLocation"))
            assertFalse(released(place, afterUnlock = true).toString().contains("SecretCafeLocation"))
            assertEquals("HeldDeepLink(host=MAP)", HeldDeepLink(place, 0L, 0L).toString())
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `the held link is memory only - not serializable and not parcelable`() {
        assertFalse(Serializable::class.java.isAssignableFrom(HeldDeepLink::class.java))
        assertTrue(HeldDeepLink::class.java.interfaces.isEmpty())
        assertFalse(HeldDeepLink::class.java.interfaces.any { it.name.contains("Parcelable") })
        // No Android type among its fields or constructor parameters (domain only).
        val fieldTypes = HeldDeepLink::class.java.declaredFields.map { it.type.name }
        assertTrue(fieldTypes.toString(), fieldTypes.none { it.startsWith("android.") })
    }

    @Test
    fun `a failed capability read still decides fail closed, and a cancellation propagates without holding anything`() {
        val (h, controller) = foregroundLockedControllerFor(LockCause.COLD_START)

        h.capabilityProvider.failure = IllegalStateException("boom")
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly)) // CHECK_FAILED: transient, still locked

        h.capabilityProvider.failure = CancellationException("cancelled")
        try {
            controller.onLockQuery(home)
            fail("a CancellationException must propagate")
        } catch (expected: CancellationException) {
            // never swallowed
        }
        h.capabilityProvider.failure = null
        // The earlier link is untouched by the cancelled query.
        h.unlock(controller)
        assertEquals(released(anomaly, afterUnlock = true), controller.releaseHeldLink())
    }

    @Test
    fun `the held link never starts a prompt and never writes settings`() {
        val (h, controller) = foregroundLockedControllerFor(LockCause.COLD_START)
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))
        h.settle()

        assertTrue(h.authenticator.calls.isEmpty())
        assertNull(controller.inFlightAttemptId)
        assertEquals(0, h.repository.writeAttempts)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
    }

    // --- S21-a (D-S21-1): the post-T33 release signal --------------------------------------------------------------

    @Test
    fun `S21-a releaseOpportunity bumps exactly once on a matching completion with a link held, and a repeated call does not bump`() {
        val (h, controller) = unlockedControllerFor()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(home))
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(0L, controller.releaseOpportunity.value)

        controller.onOpenOriginApplyCompleted(id)
        assertEquals(1L, controller.releaseOpportunity.value)

        controller.onOpenOriginApplyCompleted(id) // repeated: the gate is already closed
        assertEquals(1L, controller.releaseOpportunity.value)
        // The signal itself released nothing: the caller still pulls the link, once.
        assertEquals(released(home, afterUnlock = false), controller.releaseHeldLink())
        assertEquals(1L, controller.releaseOpportunity.value)
    }

    @Test
    fun `S21-a releaseOpportunity does not bump for a stale id, and a later matching completion still bumps once`() {
        val (h, controller) = unlockedControllerFor()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(home))
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        controller.onOpenOriginApplyCompleted(id + 1)
        controller.onOpenOriginApplyCompleted(id - 1)
        assertEquals(0L, controller.releaseOpportunity.value)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink()) // the gate is still open

        controller.onOpenOriginApplyCompleted(id)
        assertEquals(1L, controller.releaseOpportunity.value)
    }

    @Test
    fun `S21-a releaseOpportunity does not bump when no link is held, with or without an open gate`() {
        val (h, controller) = unlockedControllerFor()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        controller.onOpenOriginApplyCompleted(id) // matching, but nothing is held
        controller.onOpenOriginApplyCompleted(id)
        controller.onOpenOriginApplyCompleted(999L) // nothing at all matches
        assertEquals(0L, controller.releaseOpportunity.value)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
    }

    @Test
    fun `S21-a releaseOpportunity does not bump for an id whose gate was never opened, even with a link held`() {
        val (_, controller) = foregroundLockedControllerFor(LockCause.COLD_START)
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(anomaly))

        controller.onOpenOriginApplyCompleted(1L)

        assertEquals(0L, controller.releaseOpportunity.value)
    }

    @Test
    fun `S21-a releaseOpportunity carries no link data - a bare read-only counter that never changes the held link`() {
        val (h, controller) = unlockedControllerFor()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        assertEquals(LinkDecision.HOLD, controller.onLockQuery(place))
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        controller.onOpenOriginApplyCompleted(id)

        val signal = controller.releaseOpportunity
        // The public type is the read-only StateFlow (no mutable handle).
        assertFalse(signal is kotlinx.coroutines.flow.MutableStateFlow<*>)
        // Reading the signal consumed nothing: the link is still there for the one pull.
        assertEquals(released(place, afterUnlock = false), controller.releaseHeldLink())
    }
}
