package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S16 part (i): the spec 3.3 illegal transitions that apply to resolution, inert and T6b, including the
 * AMD-22 entries (T6b not while `AUTHENTICATING(UNLOCK)`, a purpose entered from the wrong origin, no
 * prompt from `INERT` or `RESOLVING`, an outcome across purposes).
 *
 * Not coverable in part (i), listed for part (ii): `AUTHENTICATING` outliving its host (T35), a skipped
 * T34, an ENABLE or CONFIRM_CHANGE success applied across purposes (no such attempt can be started yet),
 * and the isolation test (INV-36, subject to TECH-10).
 */
class AppLockControllerIllegalTransitionTest {

    private fun everyEvent(controller: AppLockController) {
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        controller.onLockQuery()
        controller.onAuthSucceeded(1L)
    }

    // --- LOCKED -> UNLOCKED only through a matching success ---------------------------------------------

    @Test
    fun `LOCKED never opens without a matching AUTH_SUCCEEDED, for every lock cause`() {
        val coldStart = AppLockControllerHarness(AppLockTestSettings.enabled()).resolvedController()
        val unreadable = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED))
            .resolvedController()
        val timeoutHarness = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val timeout = timeoutHarness.resolvedController().also {
            timeoutHarness.unlock(it)
            it.onAppBackgrounded()
            it.onAppForegrounded()
        }

        for ((expected, controller) in listOf(
            AppLockState.Locked(LockCause.COLD_START) to coldStart,
            AppLockState.Locked(LockCause.SETTINGS_UNREADABLE) to unreadable,
            AppLockState.Locked(LockCause.TIMEOUT) to timeout
        )) {
            everyEvent(controller)
            assertEquals(expected, controller.state.value)
            assertFalse(controller.onAuthSucceeded(0L))
            assertFalse(controller.onAuthSucceeded(-1L))
            assertFalse(controller.onAuthSucceeded(Long.MAX_VALUE))
            assertEquals(expected, controller.state.value)
        }
    }

    @Test
    fun `a success whose id does not match the open attempt is ignored and the attempt stays open`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        val controller = h.resolvedController()
        val started = controller.onUnlockTapped() as UnlockTapResult.Started
        val open = AppLockState.Authenticating(started.attemptId, AuthPurpose.UNLOCK)

        assertFalse(controller.onAuthSucceeded(started.attemptId + 1))
        assertFalse(controller.onAuthSucceeded(started.attemptId - 1))
        assertFalse(controller.onAuthSucceeded(0L))

        assertEquals(open, controller.state.value)
        assertEquals(started.attemptId, controller.inFlightAttemptId)
        assertTrue(controller.onAuthSucceeded(started.attemptId))
        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    @Test
    fun `T12 a repeated success for a finished attempt is stale and records no second unlock`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        val controller = h.resolvedController()
        val started = controller.onUnlockTapped() as UnlockTapResult.Started
        assertTrue(controller.onAuthSucceeded(started.attemptId))

        assertFalse(controller.onAuthSucceeded(started.attemptId))

        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(1, h.emitted.count { it == PrivacyDiagnosticCode.LOCK_UNLOCKED })
    }

    @Test
    fun `AE-47 a late success for an earlier attempt after a re-lock is ignored and the app stays LOCKED`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.resolvedController()
        val first = controller.onUnlockTapped() as UnlockTapResult.Started
        assertTrue(controller.onAuthSucceeded(first.attemptId))
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)

        assertFalse(controller.onAuthSucceeded(first.attemptId))

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        // The second attempt gets a fresh, larger id and is not confused with the first.
        val second = controller.onUnlockTapped() as UnlockTapResult.Started
        assertTrue(second.attemptId > first.attemptId)
        assertFalse(controller.onAuthSucceeded(first.attemptId))
        assertEquals(AppLockState.Authenticating(second.attemptId, AuthPurpose.UNLOCK), controller.state.value)
    }

    @Test
    fun `AE-46 a double tap leaves exactly one attempt`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        val controller = h.resolvedController()
        val evaluationsBeforeTaps = h.capabilityProvider.evaluations

        val first = controller.onUnlockTapped() as UnlockTapResult.Started
        val second = controller.onUnlockTapped()
        val third = controller.onUnlockTapped()

        assertEquals(UnlockTapResult.AlreadyInFlight, second)
        assertEquals(UnlockTapResult.AlreadyInFlight, third)
        assertEquals(first.attemptId, controller.inFlightAttemptId)
        assertEquals(AppLockState.Authenticating(first.attemptId, AuthPurpose.UNLOCK), controller.state.value)
        // Only one capability re-check and one id were spent on the winning tap.
        assertEquals(1L, first.attemptId)
        assertEquals(1, h.capabilityProvider.evaluations - evaluationsBeforeTaps)
    }

    // --- RESOLVING -> UNLOCKED, DISABLED -> LOCKED ---------------------------------------------------------

    @Test
    fun `RESOLVING never opens and never starts a prompt`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()

        everyEvent(controller)
        assertEquals(UnlockTapResult.NotLocked, controller.onUnlockTapped())
        controller.onLateSettingsResolved(AppLockTestSettings.DISABLED)

        assertEquals(AppLockState.Resolving, controller.state.value)
        assertNull(controller.inFlightAttemptId)
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
        controller.onSettingsResolved(AppLockTestSettings.enabled())
        controller.onResolveTimeout()
        controller.onLateSettingsResolved(AppLockTestSettings.enabled())

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertEquals(UnlockTapResult.NotLocked, controller.onUnlockTapped())
        assertNull(controller.pendingBackgroundAtMillis)
        assertTrue(h.emitted.isEmpty())
    }

    // --- AMD-22: a purpose is entered only from its origin ----------------------------------------------------

    @Test
    fun `AMD-22 an UNLOCK attempt starts only from LOCKED, in every other state it is refused with no change`() {
        val resolving = AppLockControllerHarness(AppLockTestSettings.enabled()).newController()

        val disabled = AppLockControllerHarness(AppLockTestSettings.DISABLED).resolvedController()

        val unlockedHarness = AppLockControllerHarness(AppLockTestSettings.enabled())
        val unlocked = unlockedHarness.resolvedController().also { unlockedHarness.unlock(it) }

        val inert = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
            .resolvedController()

        for ((controller, expected) in listOf(
            resolving to AppLockState.Resolving,
            disabled to AppLockState.Disabled,
            unlocked to AppLockState.Unlocked,
            inert to AppLockState.Inert(InertReason.NO_CREDENTIAL)
        )) {
            assertEquals(UnlockTapResult.NotLocked, controller.onUnlockTapped())
            assertEquals(expected, controller.state.value)
            assertNull(controller.inFlightAttemptId)
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
        // Every attempt part (i) can start is an UNLOCK attempt.
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
    fun `INERT never accepts an auth success`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()

        assertFalse(controller.onAuthSucceeded(1L))

        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
    }

    // --- T6b: when a late read may and may not lift the lock --------------------------------------------------

    @Test
    fun `AMD-22 T6b does not apply while AUTHENTICATING(UNLOCK), and the dropped read is not kept for later`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()
        controller.onResolveTimeout()
        val started = controller.onUnlockTapped() as UnlockTapResult.Started
        val open = AppLockState.Authenticating(started.attemptId, AuthPurpose.UNLOCK)

        controller.onLateSettingsResolved(AppLockTestSettings.DISABLED)

        assertEquals(open, controller.state.value)
        assertEquals(started.attemptId, controller.inFlightAttemptId)
        // The success unlocks into UNLOCKED, not DISABLED, and the late read did not change the timer
        // basis: the fail-closed record is still in force (IMMEDIATE).
        assertTrue(controller.onAuthSucceeded(started.attemptId))
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

            controller.onLateSettingsResolved(AppLockTestSettings.DISABLED)
            controller.onLateSettingsResolved(AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS))

            assertEquals("cause $cause", locked, controller.state.value)
        }
    }

    @Test
    fun `T6b needs a readable lock-off late read, anything else is dropped`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()
        controller.onResolveTimeout()
        val locked = AppLockState.Locked(LockCause.SETTINGS_UNREADABLE)

        controller.onLateSettingsResolved(AppLockTestSettings.enabled())
        controller.onLateSettingsResolved(AppLockTestSettings.unreadable(SettingsUnreadableCause.IO_ERROR))
        controller.onLateSettingsResolved(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED))

        assertEquals(locked, controller.state.value)
    }

    @Test
    fun `T6b applies once and a first-run default counts as lock off`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()
        controller.onResolveTimeout()

        controller.onLateSettingsResolved(AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS))
        assertEquals(AppLockState.Disabled, controller.state.value)

        controller.onLateSettingsResolved(AppLockTestSettings.enabled())
        assertEquals(AppLockState.Disabled, controller.state.value)
        assertEquals(UnlockTapResult.NotLocked, controller.onUnlockTapped())
    }

    @Test
    fun `T6b does not move an UNLOCKED session`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()
        controller.onResolveTimeout()
        h.unlock(controller)

        controller.onLateSettingsResolved(AppLockTestSettings.DISABLED)

        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    @Test
    fun `a late read before any resolution is dropped`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()

        controller.onLateSettingsResolved(AppLockTestSettings.DISABLED)

        assertEquals(AppLockState.Resolving, controller.state.value)
        // The real resolution is not pre-empted by the dropped late read.
        controller.onSettingsResolved(AppLockTestSettings.enabled())
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
    }

    // --- Ports: no operational, Room or barrier input exists (spec 3.3, INV-28, INV-36) -------------------------

    @Test
    fun `the controller is constructed from exactly the five ports, none of them operational, Room or worker`() {
        val constructor = AppLockController::class.java.declaredConstructors.single()

        assertEquals(
            listOf(
                AppLockSettingsRepository::class.java,
                DeviceAuthCapabilityProvider::class.java,
                MonotonicClock::class.java,
                PrivacyDiagnosticsEmitter::class.java,
                CoroutineScope::class.java
            ),
            constructor.parameterTypes.toList()
        )
    }

    @Test
    fun `no public method takes an operational state, a worker, a receiver or an Android type`() {
        val allowedParameterTypes = setOf(
            AppLockSettingsLoadState::class.java,
            Long::class.javaPrimitiveType
        )
        // Only compiler-generated accessors (`access$...`, first parameter = the controller) are not API.
        // Any other synthetic method on the controller is unexpected and fails the test below.
        val declared = AppLockController::class.java.declaredMethods
        val otherSynthetic = declared.filter { it.isSynthetic && !it.name.startsWith("access\$") }.map { it.name }
        assertEquals(emptyList<String>(), otherSynthetic)

        val offending = AppLockController::class.java.methods
            .filter { it.declaringClass == AppLockController::class.java && !it.name.startsWith("access\$") }
            .filter { method -> method.parameterTypes.any { it !in allowedParameterTypes && !it.name.startsWith("kotlin.coroutines") } }
            .map { it.name }

        assertEquals(emptyList<String>(), offending)
    }

    @Test
    fun `the resolution events and the auth success are test seams, only resolve and the lifecycle calls are production API`() {
        // `internal` members compile to public methods with a module suffix after a dollar sign, so the
        // exact set of unmangled public methods is the production surface (privacy F3, architecture F1).
        val production = AppLockController::class.java.methods
            .filter { it.declaringClass == AppLockController::class.java && !it.isSynthetic && '$' !in it.name }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("getState", "resolve", "onAppBackgrounded", "onAppForegrounded", "onLockQuery", "onUnlockTapped"),
            production
        )
    }
}
