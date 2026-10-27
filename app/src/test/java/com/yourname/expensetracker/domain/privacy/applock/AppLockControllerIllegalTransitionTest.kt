package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * S16: the spec 3.3 illegal transitions that apply to resolution, inert, T6b and the UNLOCK attempt,
 * including the AMD-22 entries (T6b not while `AUTHENTICATING(UNLOCK)`, a purpose entered from the wrong
 * origin, no prompt from `INERT` or `RESOLVING`, an outcome across purposes).
 *
 * Since part (ii-a) the resolution events and the auth success are private. These tests reach them the
 * way production does: `resolve()` over a fake repository on the test scheduler, and the controller-owned
 * callback handed to a fake [DeviceAuthenticator].
 *
 * Part (ii-b) adds the open-origin entries: a purpose started from the wrong origin or while any prompt is
 * in flight, a cross-purpose outcome, and the pinned public surface. The skipped-T34 entry is covered in
 * `AppLockControllerPromptOutcomeTest` (it needs the prompt machinery). The isolation test (INV-36) stays
 * subject to TECH-10.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockControllerIllegalTransitionTest {

    /** Every lifecycle event the controller accepts without an attempt. No success can be sent from here. */
    private fun everyEvent(controller: AppLockController) {
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        controller.onLockQuery()
        controller.onPromptHostLost(1L)
    }

    // --- LOCKED -> UNLOCKED only through a matching success ---------------------------------------------

    @Test
    fun `LOCKED never opens without a matching AUTH_SUCCEEDED, for every lock cause`() {
        val coldStartHarness = AppLockControllerHarness(AppLockTestSettings.enabled())
        val coldStart = coldStartHarness.resolvedController()
        val unreadableHarness = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED))
        val unreadable = unreadableHarness.resolvedController()
        val timeoutHarness = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val timeout = timeoutHarness.resolvedController().also {
            timeoutHarness.unlock(it)
            it.onAppBackgrounded()
            it.onAppForegrounded()
        }

        for ((h, controller, expected) in listOf(
            Triple(coldStartHarness, coldStart, AppLockState.Locked(LockCause.COLD_START)),
            Triple(unreadableHarness, unreadable, AppLockState.Locked(LockCause.SETTINGS_UNREADABLE)),
            Triple(timeoutHarness, timeout, AppLockState.Locked(LockCause.TIMEOUT))
        )) {
            val callsBefore = h.authenticator.calls.size
            everyEvent(controller)
            assertEquals(expected, controller.state.value)
            // Without an attempt there is no callback at all, so no success can even be sent.
            assertEquals(callsBefore, h.authenticator.calls.size)

            val attemptId = h.tap(controller)
            for (wrong in listOf(0L, -1L, Long.MAX_VALUE, attemptId + 1, attemptId - 1)) {
                h.authenticator.deliverWithId(wrong, DeviceAuthOutcome.Succeeded)
            }
            assertEquals(AppLockState.Authenticating(attemptId, AuthPurpose.UNLOCK), controller.state.value)
            h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
            assertEquals(expected, controller.state.value)
        }
    }

    @Test
    fun `a success whose id does not match the open attempt is ignored and the attempt stays open`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        val controller = h.resolvedController()
        val attemptId = h.tap(controller)
        val open = AppLockState.Authenticating(attemptId, AuthPurpose.UNLOCK)

        h.authenticator.deliverWithId(attemptId + 1, DeviceAuthOutcome.Succeeded)
        h.authenticator.deliverWithId(attemptId - 1, DeviceAuthOutcome.Succeeded)
        h.authenticator.deliverWithId(0L, DeviceAuthOutcome.Succeeded)

        assertEquals(open, controller.state.value)
        assertEquals(attemptId, controller.inFlightAttemptId)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    @Test
    fun `T12 a repeated success for a finished attempt is stale and records no second unlock`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        val controller = h.resolvedController()
        h.tap(controller)
        val call = h.authenticator.calls.last()
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded, call)
        assertEquals(AppLockState.Unlocked, controller.state.value)

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded, call)

        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(1, h.emitted.count { it == PrivacyDiagnosticCode.LOCK_UNLOCKED })
    }

    @Test
    fun `AE-47 a late success for an earlier attempt after a re-lock is ignored and the app stays LOCKED`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.resolvedController()
        val first = h.tap(controller)
        val firstCall = h.authenticator.calls.last()
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded, firstCall)
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded, firstCall)

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        // The second attempt gets a fresh, larger id and is not confused with the first.
        val second = h.tap(controller)
        assertTrue(second > first)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded, firstCall)
        assertEquals(AppLockState.Authenticating(second, AuthPurpose.UNLOCK), controller.state.value)
    }

    @Test
    fun `AE-46 a double tap leaves exactly one attempt and one prompt`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        val controller = h.resolvedController()
        val evaluationsBeforeTaps = h.capabilityProvider.evaluations

        val first = controller.onUnlockTapped() as UnlockTapResult.Started
        val second = controller.onUnlockTapped()
        val third = controller.onUnlockTapped()
        h.settle()

        assertEquals(UnlockTapResult.AlreadyInFlight, second)
        assertEquals(UnlockTapResult.AlreadyInFlight, third)
        assertEquals(first.attemptId, controller.inFlightAttemptId)
        assertEquals(AppLockState.Authenticating(first.attemptId, AuthPurpose.UNLOCK), controller.state.value)
        // Only one capability re-check, one id and one prompt were spent on the winning tap.
        assertEquals(1L, first.attemptId)
        assertEquals(1, h.capabilityProvider.evaluations - evaluationsBeforeTaps)
        assertEquals(listOf(first.attemptId), h.authenticator.calls.map { it.attemptId })
    }

    // --- RESOLVING -> UNLOCKED, DISABLED -> LOCKED ---------------------------------------------------------

    @Test
    fun `RESOLVING never opens and never starts a prompt`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()

        everyEvent(controller)
        assertEquals(UnlockTapResult.NotLocked, controller.onUnlockTapped())
        h.settle()

        assertEquals(AppLockState.Resolving, controller.state.value)
        assertNull(controller.inFlightAttemptId)
        assertTrue(h.authenticator.calls.isEmpty())
        assertTrue(h.emitted.isEmpty())
    }

    @Test
    fun `DISABLED never locks without a durable enable`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.resolvedController()

        everyEvent(controller)
        h.clock.advance(60 * 60_000L)
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        controller.onLockQuery()
        // A later resolve() (even over a store that now says enabled) is a no-op: resolution is one-shot.
        h.repository.loadState = AppLockTestSettings.enabled()
        runBlocking { controller.resolve() }

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertEquals(UnlockTapResult.NotLocked, controller.onUnlockTapped())
        assertNull(controller.pendingBackgroundAtMillis)
        assertTrue(h.emitted.isEmpty())
        assertTrue(h.authenticator.calls.isEmpty())
    }

    // --- AMD-22: a purpose is entered only from its origin ----------------------------------------------------

    @Test
    fun `AMD-22 an UNLOCK attempt starts only from LOCKED, in every other state it is refused with no change`() {
        val resolvingHarness = AppLockControllerHarness(AppLockTestSettings.enabled())
        val resolving = resolvingHarness.newController()

        val disabledHarness = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val disabled = disabledHarness.resolvedController()

        val unlockedHarness = AppLockControllerHarness(AppLockTestSettings.enabled())
        val unlocked = unlockedHarness.resolvedController().also { unlockedHarness.unlock(it) }

        val inertHarness = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
        val inert = inertHarness.resolvedController()

        for ((h, controller, expected) in listOf(
            Triple(resolvingHarness, resolving, AppLockState.Resolving),
            Triple(disabledHarness, disabled, AppLockState.Disabled),
            Triple(unlockedHarness, unlocked, AppLockState.Unlocked),
            Triple(inertHarness, inert, AppLockState.Inert(InertReason.NO_CREDENTIAL))
        )) {
            val callsBefore = h.authenticator.calls.size
            assertEquals(UnlockTapResult.NotLocked, controller.onUnlockTapped())
            h.settle()
            assertEquals(expected, controller.state.value)
            assertNull(controller.inFlightAttemptId)
            assertEquals(callsBefore, h.authenticator.calls.size)
        }
    }

    @Test
    fun `AMD-22 the purpose fixes the origin, and only the open-origin purposes are ENABLE and CONFIRM_CHANGE`() {
        assertEquals(AppLockOrigin.LOCKED, AuthPurpose.UNLOCK.origin)
        assertEquals(AppLockOrigin.DISABLED, AuthPurpose.ENABLE.origin)
        assertEquals(AppLockOrigin.UNLOCKED, AuthPurpose.CONFIRM_CHANGE.origin)
        assertFalse(AuthPurpose.UNLOCK.isOpenOrigin)
        assertTrue(AuthPurpose.ENABLE.isOpenOrigin)
        assertTrue(AuthPurpose.CONFIRM_CHANGE.isOpenOrigin)
        // The Unlock tap only ever starts an UNLOCK attempt.
        val controller = AppLockControllerHarness(AppLockTestSettings.enabled()).resolvedController()
        val started = controller.onUnlockTapped() as UnlockTapResult.Started
        assertEquals(
            AuthPurpose.UNLOCK,
            (controller.state.value as AppLockState.Authenticating).purpose
        )
        assertEquals(started.attemptId, (controller.state.value as AppLockState.Authenticating).attemptId)
    }

    @Test
    fun `AMD-22 an UNLOCK success unlocks only and applies no settings change`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M15))
        val controller = h.resolvedController()

        h.unlock(controller)

        assertEquals(0, h.repository.writeAttempts)
        assertEquals(AppLockTestSettings.enabled(AutoLockTimeout.M15), h.repository.loadState)
    }

    @Test
    fun `INERT never starts a prompt, so no success can reach it`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()

        assertEquals(UnlockTapResult.NotLocked, controller.onUnlockTapped())
        h.settle()

        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        assertTrue(h.authenticator.calls.isEmpty())
    }

    // --- AMD-22 (part ii-b): the open-origin purposes are entered only from their origin --------------------------

    @Test
    fun `AMD-22 ENABLE starts only from DISABLED and CONFIRM_CHANGE only from UNLOCKED, every other state is refused with no change`() {
        val states: List<Pair<String, () -> Pair<AppLockControllerHarness, AppLockController>>> = listOf(
            "RESOLVING" to { AppLockControllerHarness(AppLockTestSettings.enabled()).let { it to it.newController() } },
            "LOCKED" to { lockedControllerFor(LockCause.COLD_START) },
            "AUTHENTICATING(UNLOCK)" to {
                lockedControllerFor(LockCause.COLD_START).also { (h, controller) -> h.tap(controller) }
            },
            "INERT" to {
                AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
                    .let { it to it.resolvedController() }
            },
            "DISABLED" to { disabledControllerFor() },
            "UNLOCKED" to { unlockedControllerFor() }
        )

        for (prompt in OpenOriginPrompt.entries) {
            for ((name, make) in states) {
                val legal = (name == "DISABLED" && prompt.purpose == AuthPurpose.ENABLE) ||
                    (name == "UNLOCKED" && prompt.purpose == AuthPurpose.CONFIRM_CHANGE)
                if (legal) continue // the legal starts are covered in AppLockControllerPromptOutcomeTest
                val (h, controller) = make()
                val handler = RecordingSuccessHandler()
                val stateBefore = controller.state.value
                val idBefore = controller.inFlightAttemptId
                val callsBefore = h.authenticator.calls.size
                val emittedBefore = h.emitted.toList()

                val result = controller.startOpenOriginAttempt(prompt, handler)
                h.settle()

                val expected = if (name == "AUTHENTICATING(UNLOCK)") {
                    OpenOriginStartResult.AlreadyInFlight
                } else {
                    OpenOriginStartResult.NotInOrigin
                }
                assertEquals("$prompt from $name", expected, result)
                assertEquals("$prompt from $name", stateBefore, controller.state.value)
                assertEquals("$prompt from $name", idBefore, controller.inFlightAttemptId)
                assertEquals("$prompt from $name: nothing launched", callsBefore, h.authenticator.calls.size)
                assertEquals("$prompt from $name: no diagnostic", emittedBefore, h.emitted)
                assertTrue("$prompt from $name", handler.delivered.isEmpty())
            }
        }
    }

    @Test
    fun `INV-54 at most one prompt is in flight across all purposes`() {
        val (h, controller) = disabledControllerFor()
        val enable = h.startOpenOrigin(controller, OpenOriginPrompt.ENABLE, RecordingSuccessHandler())

        for (prompt in OpenOriginPrompt.entries) {
            assertEquals("$prompt", OpenOriginStartResult.AlreadyInFlight, controller.startOpenOriginAttempt(prompt, RecordingSuccessHandler()))
        }
        assertEquals(UnlockTapResult.AlreadyInFlight, controller.onUnlockTapped())
        h.settle()

        assertEquals(AppLockState.Authenticating(enable, AuthPurpose.ENABLE), controller.state.value)
        assertEquals(listOf(enable), h.authenticator.calls.map { it.attemptId })

        val (h2, unlocked) = unlockedControllerFor()
        val confirm = h2.startOpenOrigin(unlocked, OpenOriginPrompt.DISABLE, RecordingSuccessHandler())
        assertEquals(OpenOriginStartResult.AlreadyInFlight, unlocked.startOpenOriginAttempt(OpenOriginPrompt.CHANGE, RecordingSuccessHandler()))
        assertEquals(OpenOriginStartResult.AlreadyInFlight, unlocked.startOpenOriginAttempt(OpenOriginPrompt.ENABLE, RecordingSuccessHandler()))
        h2.settle()
        assertEquals(AppLockState.Authenticating(confirm, AuthPurpose.CONFIRM_CHANGE), unlocked.state.value)
        assertEquals(1, h2.authenticator.calls.count { it.attemptId == confirm })
    }

    @Test
    fun `AMD-22 an outcome never crosses purposes - a late CONFIRM_CHANGE success cannot unlock LOCKED or answer an UNLOCK attempt`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.IMMEDIATE)
        val handler = RecordingSuccessHandler()
        val confirm = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, handler)
        val confirmCall = h.authenticator.calls.last()
        controller.onPromptHostLost(confirm) // abandoned: back on UNLOCKED, nothing applied
        h.settle()
        assertEquals(AppLockState.Unlocked, controller.state.value)
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)

        // The abandoned CONFIRM_CHANGE callback cannot unlock LOCKED, and cannot answer an UNLOCK attempt.
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded, confirmCall)
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        val unlock = h.tap(controller)
        assertTrue(unlock > confirm)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded, confirmCall)
        h.authenticator.deliverWithId(confirm, DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Authenticating(unlock, AuthPurpose.UNLOCK), controller.state.value)
        assertTrue(handler.delivered.isEmpty())

        // An UNLOCK success unlocks and never reaches an open-origin handler.
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertTrue(handler.delivered.isEmpty())
    }

    // --- T6b: when a late read may and may not lift the lock --------------------------------------------------

    @Test
    fun `AMD-22 T6b does not apply while AUTHENTICATING(UNLOCK), and the dropped read is not kept for later`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED, scheduler = testScheduler)
        val controller = h.newController()
        resolveTimedOut(h, controller)
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        val attemptId = h.tap(controller)
        val open = AppLockState.Authenticating(attemptId, AuthPurpose.UNLOCK)

        // The held read now returns a readable lock-off store while the prompt is open.
        h.repository.releaseReads()
        runCurrent()

        assertEquals(open, controller.state.value)
        assertEquals(attemptId, controller.inFlightAttemptId)
        // The success unlocks into UNLOCKED, not DISABLED, and the late read did not change the timer
        // basis: the fail-closed record is still in force (IMMEDIATE).
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        controller.onAppBackgrounded()
        h.clock.advance(1_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `T6b does not apply to CORRUPTED, IO_ERROR, INVALID_VALUE or a repository-reported TIMEOUT`() {
        for (cause in SettingsUnreadableCause.entries) {
            val h = AppLockControllerHarness(AppLockTestSettings.unreadable(cause))
            val controller = h.resolvedController()
            val locked = AppLockState.Locked(LockCause.SETTINGS_UNREADABLE)
            assertEquals(locked, controller.state.value)

            // A reported unreadable cause is the first read, so there is no late read to offer at all; a
            // later resolve() over a store that now reads fine changes nothing.
            h.repository.loadState = AppLockTestSettings.DISABLED
            runBlocking { controller.resolve() }
            h.repository.loadState = AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS)
            runBlocking { controller.resolve() }

            assertEquals("cause $cause", locked, controller.state.value)
            assertEquals("cause $cause", 1, h.repository.readCount)
        }
    }

    @Test
    fun `T6b needs a readable lock-off late read, anything else is dropped`() = runTest {
        val lateReads = listOf(
            AppLockTestSettings.enabled(),
            AppLockTestSettings.unreadable(SettingsUnreadableCause.IO_ERROR),
            AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED)
        )
        for (late in lateReads) {
            val h = AppLockControllerHarness(AppLockTestSettings.DISABLED, scheduler = testScheduler)
            val controller = h.newController()
            resolveTimedOut(h, controller)
            val locked = AppLockState.Locked(LockCause.SETTINGS_UNREADABLE)

            h.repository.loadState = late
            h.repository.releaseReads()
            runCurrent()

            assertEquals("late read $late", locked, controller.state.value)
        }
    }

    @Test
    fun `T6b applies once and a first-run default counts as lock off`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED, scheduler = testScheduler)
        val controller = h.newController()
        resolveTimedOut(h, controller)

        h.repository.loadState = AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS)
        h.repository.releaseReads()
        runCurrent()
        assertEquals(AppLockState.Disabled, controller.state.value)

        // resolve() is one-shot, so a store that now says enabled cannot move DISABLED again.
        h.repository.loadState = AppLockTestSettings.enabled()
        controller.resolve()
        assertEquals(AppLockState.Disabled, controller.state.value)
        assertEquals(UnlockTapResult.NotLocked, controller.onUnlockTapped())
    }

    @Test
    fun `T6b does not move an UNLOCKED session`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED, scheduler = testScheduler)
        val controller = h.newController()
        resolveTimedOut(h, controller)
        h.unlock(controller)

        h.repository.releaseReads()
        runCurrent()

        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    @Test
    fun `a read that returns before T_resolve is the resolution itself, never a late read`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), scheduler = testScheduler)
        h.repository.holdReads()
        val controller = h.newController()
        val resolving = launch { controller.resolve() }
        runCurrent()
        advanceTimeBy(AppLockController.RESOLVE_TIMEOUT_MILLIS - 1)
        runCurrent()
        assertEquals(AppLockState.Resolving, controller.state.value)

        h.repository.releaseReads()
        runCurrent()
        resolving.join()

        // Not LOCKED(SETTINGS_UNREADABLE): the in-time read decided, so there was no fallback to supersede.
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
    }

    // --- Ports: no operational, Room or barrier input exists (spec 3.3, INV-28, INV-36) -------------------------

    @Test
    fun `the controller is constructed from exactly the seven ports, none of them operational, Room or worker`() {
        val constructor = AppLockController::class.java.declaredConstructors.single()

        assertEquals(
            listOf(
                AppLockSettingsRepository::class.java,
                DeviceAuthCapabilityProvider::class.java,
                MonotonicClock::class.java,
                PrivacyDiagnosticsEmitter::class.java,
                CoroutineScope::class.java,
                DeviceAuthenticator::class.java,
                CoroutineDispatcher::class.java
            ),
            constructor.parameterTypes.toList()
        )
    }

    @Test
    fun `no public method takes an operational state, a worker, a receiver or an Android type`() {
        // The attempt id, and (part (ii-b), exact additions) the two bounded domain types of the open-origin
        // start API: the closed prompt selector and the success handler of the owning settings operation.
        val allowedParameterTypes = setOf(
            Long::class.javaPrimitiveType,
            OpenOriginPrompt::class.java,
            OpenOriginSuccessHandler::class.java,
            // S17, exact addition: the already validated, bounded S3 link type of onLockQuery(link). No Android type.
            DeepLinkRequest::class.java,
            // S18 (i-a), exact addition: the bounded capability enum of onEnableRefusedAtRequest (T26). It carries
            // no text or platform detail. The committed flag of onOpenOriginSettingApplied (T17, T18) is a Boolean,
            // which is NOT allowed globally (privacy review F2): it is allowed for that one method only, below.
            DeviceAuthCapability::class.java,
            // S18 (i-b1), exact addition: the closed AutoLockTimeout enum of startTimeoutChangeAttempt and
            // onTimeoutWrittenWithoutPrompt. It carries no text or platform detail.
            AutoLockTimeout::class.java,
            // S18 (i-b2), exact addition: the closed field enum of onPlainSettingWritten. No text or platform detail.
            AppLockSettingField::class.java
        )
        // The committed flag is allowed for these three methods only (S18 i-a, i-b1's no-prompt timeout write, and
        // i-b2's plain-setting write, which also carries the written value as a Boolean).
        val committedFlagMethods = setOf(
            "onOpenOriginSettingApplied",
            "onTimeoutWrittenWithoutPrompt",
            "onPlainSettingWritten"
        )
        // Only compiler-generated accessors (`access$...`, first parameter = the controller) are not API.
        // Any other synthetic method on the controller is unexpected and fails the test below.
        val declared = AppLockController::class.java.declaredMethods
        val otherSynthetic = declared.filter { it.isSynthetic && !it.name.startsWith("access\$") }.map { it.name }
        assertEquals(emptyList<String>(), otherSynthetic)

        val offending = AppLockController::class.java.methods
            .filter { it.declaringClass == AppLockController::class.java && !it.name.startsWith("access\$") }
            .filter { method ->
                method.parameterTypes.any {
                    it !in allowedParameterTypes && !it.name.startsWith("kotlin.coroutines") &&
                        !(it == Boolean::class.javaPrimitiveType && method.name in committedFlagMethods)
                }
            }
            .map { it.name }

        assertEquals(emptyList<String>(), offending)
    }

    @Test
    fun `the public method set is exactly the production API, and the resolution and auth events are private`() {
        // `internal` members compile to public methods with a module suffix after a dollar sign, so the
        // exact set of unmangled public methods is the production surface (privacy F3, architecture F1).
        val production = AppLockController::class.java.methods
            .filter { it.declaringClass == AppLockController::class.java && !it.isSynthetic && '$' !in it.name }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf(
                "getState",
                "resolve",
                "onAppBackgrounded",
                "onAppForegrounded",
                "onLockQuery",
                "onUnlockTapped",
                "onPromptHostLost",
                "startOpenOriginAttempt", // part (ii-b): the one exact addition, bound by S18
                // S17, exact additions (onLockQuery gains an overload with the same name): pull release of the held
                // link, and the id-bound T33 apply completion that S18 calls.
                "releaseHeldLink",
                "onOpenOriginApplyCompleted",
                // S18 (i-a), exact additions: the T26 request-time refusal claim and the T17 / T18 state move of the
                // enable / disable apply. Both have exactly one production caller, AppLockSettingsOperations.kt.
                "onEnableRefusedAtRequest",
                "onOpenOriginSettingApplied",
                // S18 (i-b1), exact additions: the CONFIRM_CHANGE start of a longer timeout, and the no-prompt
                // timeout write's view and failure record. Both have exactly one production caller,
                // AppLockSettingsOperations.kt.
                "startTimeoutChangeAttempt",
                "onTimeoutWrittenWithoutPrompt",
                // S18 (i-b2), exact addition: the view and failure record of a forced-aware discreet or secure-screen
                // write. One production caller, AppLockSettingsOperations.kt.
                "onPlainSettingWritten"
            ),
            production
        )

        // R1: the events that used to be test seams are private now, under their plain and mangled names.
        val formerSeams = listOf("onSettingsResolved", "onResolveTimeout", "onLateSettingsResolved", "onAuthSucceeded")
        val seamMethods = AppLockController::class.java.declaredMethods
            .filter { method -> formerSeams.any { method.name == it || method.name.startsWith("$it\$") } }
        // onAuthSucceeded no longer exists at all: success arrives only through the attempt's callback.
        assertEquals(
            setOf("onSettingsResolved", "onResolveTimeout", "onLateSettingsResolved"),
            seamMethods.map { it.name }.toSet()
        )
        assertTrue(
            "a former seam is not private: ${seamMethods.map { it.name }}",
            seamMethods.all { Modifier.isPrivate(it.modifiers) }
        )
    }

    @Test
    fun `onLockQuery has exactly two forms and the S17 release and completion signatures are pinned`() {
        val own = AppLockController::class.java.methods
            .filter { it.declaringClass == AppLockController::class.java && !it.isSynthetic }

        // S17 review F3: exactly `onLockQuery()` returning the state and `onLockQuery(DeepLinkRequest)` returning the decision.
        val lockQueryForms = own.filter { it.name == "onLockQuery" }.map { it.parameterTypes.toList() to it.returnType }
        assertEquals(2, lockQueryForms.size)
        assertEquals(
            setOf<Pair<List<Class<*>>, Class<*>>>(
                emptyList<Class<*>>() to AppLockState::class.java,
                listOf<Class<*>>(DeepLinkRequest::class.java) to LinkDecision::class.java
            ),
            lockQueryForms.toSet()
        )

        val release = own.filter { it.name == "releaseHeldLink" }
        assertEquals(1, release.size)
        assertEquals(emptyList<Class<*>>(), release.single().parameterTypes.toList())
        assertEquals(HeldLinkRelease::class.java, release.single().returnType)

        val completed = own.filter { it.name == "onOpenOriginApplyCompleted" }
        assertEquals(1, completed.size)
        assertEquals(listOf<Class<*>>(Long::class.javaPrimitiveType!!), completed.single().parameterTypes.toList())
        assertEquals(Void.TYPE, completed.single().returnType)

        // S18 (i-a) privacy review F2: the state move is bound to the attempt id; the direction is not an argument.
        val applied = own.filter { it.name == "onOpenOriginSettingApplied" }
        assertEquals(1, applied.size)
        assertEquals(
            listOf<Class<*>>(Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            applied.single().parameterTypes.toList()
        )
        assertEquals(Void.TYPE, applied.single().returnType)

        // S18 (i-b1): the timeout is a closed enum argument; the timeout start returns the same bounded start result.
        val timeoutStart = own.filter { it.name == "startTimeoutChangeAttempt" }
        assertEquals(1, timeoutStart.size)
        assertEquals(
            listOf<Class<*>>(AutoLockTimeout::class.java, OpenOriginSuccessHandler::class.java),
            timeoutStart.single().parameterTypes.toList()
        )
        assertEquals(OpenOriginStartResult::class.java, timeoutStart.single().returnType)
        val timeoutDirect = own.filter { it.name == "onTimeoutWrittenWithoutPrompt" }
        assertEquals(1, timeoutDirect.size)
        assertEquals(
            listOf<Class<*>>(AutoLockTimeout::class.java, Boolean::class.javaPrimitiveType!!),
            timeoutDirect.single().parameterTypes.toList()
        )
        assertEquals(Void.TYPE, timeoutDirect.single().returnType)

        // S18 (i-b2): the plain-setting record takes the closed field enum, the written value and the committed flag.
        val plain = own.filter { it.name == "onPlainSettingWritten" }
        assertEquals(1, plain.size)
        assertEquals(
            listOf<Class<*>>(
                AppLockSettingField::class.java,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            ),
            plain.single().parameterTypes.toList()
        )
        assertEquals(Void.TYPE, plain.single().returnType)
    }

    @Test
    fun `no production file other than the controller touches the controller's internal inspection members`() {
        // pendingBackgroundAtMillis, promptBackgroundAtMillis and inFlightAttemptId stay internal for tests (R1: if anything stays
        // internal, a main file other than the controller must not reference it). Kotlin `internal` is not
        // enforced by lint here, so this scan is the enforcement.
        // Main is mandatory (fail closed if it cannot be found); the debug and release source sets are scanned
        // too when they exist, so a variant-only file cannot reach the controller's internals either (F6).
        val roots = locateProductionSourceRoots()
        val forbidden = listOf(
            "pendingBackgroundAtMillis",
            "inFlightAttemptId",
            "promptBackgroundAtMillis",
            "onSettingsResolved",
            "onResolveTimeout",
            "onLateSettingsResolved",
            "onAuthSucceeded",
            // Part (ii-b), exact addition (F3): the open-origin start API has no caller yet. S18 adds exactly its
            // own file as the one caller, and extends this pin to allow that single file and nothing else.
            "startOpenOriginAttempt",
            // S17 review F4, exact additions: the held-link release has exactly one production caller (S20) and the
            // T33 apply completion exactly one (S18). Each extends this pin to allow that single file and nothing else.
            "releaseHeldLink",
            "onOpenOriginApplyCompleted",
            // S17 final gate (Issue 3), exact addition: the link decision `onLockQuery(link)` has no production caller
            // yet. S20 allows exactly the host Activity file as its caller (with releaseHeldLink) and nothing else.
            "onLockQuery",
            // S18 (i-a), exact additions: the T26 refusal claim and the T17 / T18 state move.
            "onEnableRefusedAtRequest",
            "onOpenOriginSettingApplied",
            // S18 (i-b1), exact additions: the longer-timeout start and the no-prompt timeout write entry.
            "startTimeoutChangeAttempt",
            "onTimeoutWrittenWithoutPrompt",
            // S18 (i-b2), exact addition: the forced-aware plain-setting record.
            "onPlainSettingWritten"
        )
        // S18 (i-a, i-b1, i-b2): these seven have exactly one production caller, the settings operations file.
        val settingsOperationsOnly = setOf(
            "startOpenOriginAttempt",
            "onOpenOriginApplyCompleted",
            "onEnableRefusedAtRequest",
            "onOpenOriginSettingApplied",
            "startTimeoutChangeAttempt",
            "onTimeoutWrittenWithoutPrompt",
            "onPlainSettingWritten"
        )
        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                // Arch review F5: the exemptions are the EXACT path under the source root (not a suffix match), so a
                // same-named file in another package, or a longer path ending the same way, gets none.
                .filter { relativeToRoot(it, root) != "$APPLOCK_PACKAGE_PATH/AppLockController.kt" }
                .filter { file ->
                    val text = file.readText()
                    val allowed = if (relativeToRoot(file, root) == "$APPLOCK_PACKAGE_PATH/AppLockSettingsOperations.kt") {
                        settingsOperationsOnly
                    } else {
                        emptySet()
                    }
                    (forbidden - allowed).any { text.contains(it) }
                }
                .map { it.path }
                .toList()
        }

        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `S18 i-a dormancy - AppLockSettingsOperations, requestEnable and requestDisable have no production caller outside their own file`() {
        // DORMANT pin (arch review F2). The enable and disable requests exist and are tested, but nothing in
        // production may call them yet: no ViewModel, Activity, module or screen. There is no DI module for the class
        // (it is `@Singleton @Inject constructor`), so the single exempt file is AppLockSettingsOperations.kt itself.
        // Lifting this pin (allowing the settings UI as a caller) needs the BLOCKING list closed first: see
        // LEGAL_PATHS WRITE 3/4 and handoff S18 (i-b1) item 5.
        // The scan skips comment lines (KDoc links such as [AppLockSettingsOperations] are not calls).
        val dormant = listOf(
            "AppLockSettingsOperations",
            "requestEnable",
            "requestDisable",
            "requestTimeoutChange",
            // S18 (i-b2), exact additions.
            "requestDiscreetChange",
            "requestSecureScreenChange"
        )
        val offenders = locateProductionSourceRoots().flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .filter { relativeToRoot(it, root) != "$APPLOCK_PACKAGE_PATH/AppLockSettingsOperations.kt" }
                .filter { file ->
                    file.readLines()
                        .map { it.trim() }
                        .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
                        .any { line -> dormant.any { line.contains(it) } }
                }
                .map { it.path }
                .toList()
        }

        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `S18 i-a final gate F2 - the lock flag write has exactly one production call site, AppLockSettingsOperations`() {
        // Pins the call site `.setAppLockEnabled(` (the declaration `fun setAppLockEnabled(` has no leading dot and
        // is not a call). Exact relative path under each production root, comment lines skipped.
        assertSingleProductionCaller(".setAppLockEnabled(", "$APPLOCK_PACKAGE_PATH/AppLockSettingsOperations.kt")
    }

    @Test
    fun `S18 i-b1 - the timeout write has exactly one production call site, AppLockSettingsOperations`() {
        // Pins the call site `.setAutoLockTimeout(` (the declarations have no leading dot). Comment lines skipped.
        assertSingleProductionCaller(".setAutoLockTimeout(", "$APPLOCK_PACKAGE_PATH/AppLockSettingsOperations.kt")
    }

    @Test
    fun `S18 i-b1 privacy review F1 - the conditional timeout write has exactly one production call site, AppLockSettingsOperations`() {
        // Pins the call site `.setAutoLockTimeoutIfNotWeakening(` (the declarations have no leading dot). An exact
        // addition: the unconditional pin above is unchanged. Comment lines skipped.
        assertSingleProductionCaller(".setAutoLockTimeoutIfNotWeakening(", "$APPLOCK_PACKAGE_PATH/AppLockSettingsOperations.kt")
    }

    @Test
    fun `S18 i-b2 - the forced-aware discreet and secure-screen writes each have exactly one production call site, AppLockSettingsOperations`() {
        // Pins the call sites (the declarations have no leading dot). The 1a `.setDiscreetNotifications(` caller, the
        // use case, is a different method name and is unchanged. Comment lines skipped.
        assertSingleProductionCaller(".setDiscreetNotificationsIfNotForced(", "$APPLOCK_PACKAGE_PATH/AppLockSettingsOperations.kt")
        assertSingleProductionCaller(".setSecureScreenIfNotForced(", "$APPLOCK_PACKAGE_PATH/AppLockSettingsOperations.kt")
    }

    @Test
    fun `S18 i-b2 privacy review F3 - the unforced 1a discreet write has exactly one production call site, UpdateDiscreetNotificationsUseCase`() {
        // Pins the call site `.setDiscreetNotifications(` (the declarations and the IfNotForced variant have no match:
        // the variant's name continues after `Notifications`). The 1a use case is the only caller; the 1a secure-screen
        // path has no unforced `.setSecureScreen(` call site in production today. An exact addition. Comment lines skipped.
        assertSingleProductionCaller(".setDiscreetNotifications(", "$APPLOCK_PACKAGE_PATH/UpdateDiscreetNotificationsUseCase.kt")
    }

    @Test
    fun `S18 i-a final gate F2 - reviewSave keeps its single production caller, the 1a privacy settings ViewModel`() {
        // Pinned to the current single caller (1a review card). Moving it behind the controller (INV-45, S18-ii)
        // changes this expectation deliberately.
        assertSingleProductionCaller(
            ".reviewSave(",
            "com/yourname/expensetracker/ui/screens/privacysettings/AppLockPrivacySettingsViewModel.kt"
        )
    }

    private fun assertSingleProductionCaller(callSite: String, allowedRelativePath: String) {
        val roots = locateProductionSourceRoots()
        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .filter { relativeToRoot(it, root) != allowedRelativePath }
                .filter { file ->
                    file.readLines()
                        .map { it.trim() }
                        .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
                        .any { it.contains(callSite) }
                }
                .map { it.path }
                .toList()
        }
        assertEquals("callers of $callSite outside $allowedRelativePath", emptyList<String>(), offenders)
        // The allowed file must really contain the call, so a moved or renamed caller fails the pin instead of
        // silently leaving it vacuous.
        val allowedCalls = roots.count { root ->
            File(root, allowedRelativePath).let { f ->
                f.isFile && f.readLines().map { it.trim() }
                    .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
                    .any { it.contains(callSite) }
            }
        }
        assertEquals("the allowed caller of $callSite", 1, allowedCalls)
    }

    /** The file's path under its source root, with `/` separators on every platform. */
    private fun relativeToRoot(file: File, root: File): String = file.relativeTo(root).invariantSeparatorsPath

    private companion object {
        const val APPLOCK_PACKAGE_PATH = "com/yourname/expensetracker/domain/privacy/applock"
    }

    /**
     * The production source roots: `main` (java and kotlin dirs; at least one must exist, else the test fails
     * closed) plus `debug` and `release` (java and kotlin dirs, whichever exist; absent ones are skipped).
     */
    private fun locateProductionSourceRoots(): List<File> {
        val bases = listOf(
            File("src"),
            File("app/src"),
            File(System.getProperty("user.dir") ?: ".", "src"),
            File(System.getProperty("user.dir") ?: ".", "app/src")
        )
        val srcBase = bases.firstOrNull { File(it, "main/java").isDirectory || File(it, "main/kotlin").isDirectory }
            ?: error("Could not locate production source root. user.dir=${System.getProperty("user.dir")}")
        val mainRoots = listOf("main/java", "main/kotlin").map { File(srcBase, it) }.filter { it.isDirectory }
        check(mainRoots.isNotEmpty()) { "No main production source directory found under ${srcBase.path}" }
        val variantRoots = listOf("debug", "release").flatMap { variant ->
            listOf("java", "kotlin").map { File(srcBase, "$variant/$it") }
        }.filter { it.isDirectory }
        return mainRoots + variantRoots
    }
}
