package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S18 part (i-b2): TECH-13 (weakening change while `INERT`) over [AppLockSettingsOperations] and one
 * [AppLockController]. In INERT nothing can authenticate, so every weakening is refused with no write, no prompt, no
 * state change and no diagnostic; a strengthening still commits. The refusal maps the spec's
 * `Rejected(AUTH_UNAVAILABLE)` to the existing bounded [TimeoutChangeResult.NOT_IN_ORIGIN].
 * Not covered here (S18-ii): the INERT review-save (AE-87).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockControllerInertWeakeningTest {

    private fun inertRig(timeout: AutoLockTimeout = AutoLockTimeout.M1): SettingsOperationsRig {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(timeout))
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppBackgrounded()
        h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        controller.onAppForegrounded()
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        h.emitted.clear()
        h.emittedMetadata.clear()
        h.authenticator.calls.clear()
        return SettingsOperationsRig(h, controller)
    }

    private fun assertNothingHappened(rig: SettingsOperationsRig) {
        val h = rig.h
        assertTrue("no timeout write", h.repository.timeoutWrites.isEmpty())
        assertTrue("no lock write", h.repository.lockWrites.isEmpty())
        assertTrue("no plain write", h.repository.plainWrites.isEmpty())
        assertTrue("no prompt", h.authenticator.calls.isEmpty())
        assertTrue("no diagnostic", h.emitted.isEmpty())
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)
        assertTrue(rig.uncaught.isEmpty())
    }

    @Test
    fun `TECH-13 every longer timeout is refused in INERT with no write, no prompt and no state change`() = runBlocking {
        for ((stored, longer) in listOf(
            AutoLockTimeout.S30 to AutoLockTimeout.M1,
            AutoLockTimeout.M1 to AutoLockTimeout.M5,
            AutoLockTimeout.M1 to AutoLockTimeout.M15,
            AutoLockTimeout.M5 to AutoLockTimeout.M15
        )) {
            val rig = inertRig(stored)

            assertEquals("$stored->$longer", TimeoutChangeResult.NOT_IN_ORIGIN, rig.ops.requestTimeoutChange(longer))
            rig.h.settle()

            assertNothingHappened(rig)
            assertEquals(
                "$stored->$longer the store keeps its value",
                stored,
                (rig.h.repository.loadState as AppLockSettingsLoadState.Loaded).settings.autoLockTimeout
            )
        }
    }

    @Test
    fun `TECH-13 an equal or shorter timeout still commits in INERT with no prompt`() = runBlocking {
        val rig = inertRig(AutoLockTimeout.M5)

        assertEquals(TimeoutChangeResult.COMMITTED, rig.ops.requestTimeoutChange(AutoLockTimeout.M5))
        assertEquals(TimeoutChangeResult.COMMITTED, rig.ops.requestTimeoutChange(AutoLockTimeout.S30))

        assertEquals(listOf(AutoLockTimeout.M5, AutoLockTimeout.S30), rig.h.repository.timeoutWrites)
        assertTrue(rig.h.authenticator.calls.isEmpty())
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)
        assertTrue(rig.h.emitted.isEmpty())
    }

    @Test
    fun `TECH-13 a refused request leaves a later strengthening free to commit, and a retry is refused again`() = runBlocking {
        val rig = inertRig(AutoLockTimeout.M1)

        assertEquals(TimeoutChangeResult.NOT_IN_ORIGIN, rig.ops.requestTimeoutChange(AutoLockTimeout.M15))
        assertEquals(TimeoutChangeResult.NOT_IN_ORIGIN, rig.ops.requestTimeoutChange(AutoLockTimeout.M15))
        assertEquals(TimeoutChangeResult.COMMITTED, rig.ops.requestTimeoutChange(AutoLockTimeout.IMMEDIATE))

        assertEquals("only the strengthening was written", listOf(AutoLockTimeout.IMMEDIATE), rig.h.repository.timeoutWrites)
        assertEquals(
            AutoLockTimeout.IMMEDIATE,
            (rig.h.repository.loadState as AppLockSettingsLoadState.Loaded).settings.autoLockTimeout
        )
        assertTrue(rig.h.authenticator.calls.isEmpty())
    }

    @Test
    fun `TECH-13 disabling the lock is refused in INERT with no write and no prompt`() = runBlocking {
        val rig = inertRig()

        assertEquals(SettingsRequestResult.NOT_IN_ORIGIN, rig.ops.requestDisable())
        rig.h.settle()

        assertNothingHappened(rig)
        assertEquals(true, (rig.h.repository.loadState as AppLockSettingsLoadState.Loaded).settings.appLockEnabled)
    }

    @Test
    fun `TECH-13 in INERT the discreet and secure values stay forced and read-only`() = runBlocking {
        val rig = inertRig()

        for (value in listOf(true, false)) {
            assertEquals(PlainSettingResult.REJECTED_FORCED, rig.ops.requestDiscreetChange(value))
            assertEquals(PlainSettingResult.REJECTED_FORCED, rig.ops.requestSecureScreenChange(value))
        }

        val stored = (rig.h.repository.loadState as AppLockSettingsLoadState.Loaded).settings
        assertEquals(false, stored.discreetNotifications)
        assertEquals(false, stored.secureScreen)
        assertTrue(rig.h.authenticator.calls.isEmpty())
        assertTrue(rig.h.emitted.isEmpty())
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)
    }

    @Test
    fun `an unreadable store in INERT is a failure with nothing written, fail closed`() = runBlocking {
        val rig = inertRig()
        rig.h.repository.loadState = AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED)

        assertEquals(TimeoutChangeResult.FAILED, rig.ops.requestTimeoutChange(AutoLockTimeout.M15))

        assertTrue(rig.h.repository.timeoutWrites.isEmpty())
        assertTrue(rig.h.authenticator.calls.isEmpty())
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)
    }

    @Test
    fun `a CancellationException from the pre-read in INERT propagates and nothing is written`() {
        val rig = inertRig()
        rig.h.repository.failure = CancellationException("c")

        try {
            runBlocking { rig.ops.requestTimeoutChange(AutoLockTimeout.M15) }
            fail("the cancellation was swallowed")
        } catch (e: CancellationException) {
            // propagated
        }

        assertTrue(rig.h.repository.timeoutWrites.isEmpty())
        assertTrue(rig.h.emitted.isEmpty())
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)
    }
}
