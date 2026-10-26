package com.yourname.expensetracker.domain.privacy.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S16 part (i): the inert escapes (INV-32). T4 and T6 at resolution, T7b on an Unlock tap, T16 on a
 * foreground, T15 (a returning INERT session) and T14 from INERT (re-activation only at a lock trigger).
 *
 * Acceptance examples covered here: AE-48, AE-49, AE-52, AE-54. AE-50 (`AUTH_ERROR(NO_DEVICE_CREDENTIAL)`,
 * T11) is a prompt outcome and belongs to part (ii).
 */
class AppLockControllerInertTest {

    private val notAvailable = listOf(
        DeviceAuthCapability.UNSUPPORTED_API to InertReason.API_UNSUPPORTED,
        DeviceAuthCapability.NO_CREDENTIAL to InertReason.NO_CREDENTIAL,
        DeviceAuthCapability.HW_UNAVAILABLE to InertReason.HW_UNAVAILABLE,
        DeviceAuthCapability.UNKNOWN to InertReason.UNKNOWN
    )

    private fun inertCode(reason: InertReason): PrivacyDiagnosticCode = when (reason) {
        InertReason.NO_CREDENTIAL -> PrivacyDiagnosticCode.LOCK_INERT_NO_CREDENTIAL
        InertReason.HW_UNAVAILABLE -> PrivacyDiagnosticCode.LOCK_INERT_HW_UNAVAILABLE
        InertReason.UNKNOWN -> PrivacyDiagnosticCode.LOCK_INERT_UNKNOWN
        InertReason.API_UNSUPPORTED -> PrivacyDiagnosticCode.LOCK_INERT_API_UNSUPPORTED
        InertReason.SETTINGS_UNREADABLE_NO_AUTH -> PrivacyDiagnosticCode.LOCK_INERT_SETTINGS_UNREADABLE
    }

    // --- T4: lock enabled, device cannot authenticate ----------------------------------------------

    @Test
    fun `T4 lock on with a capability other than AVAILABLE resolves to INERT with the mapped reason and code`() {
        for ((capability, reason) in notAvailable) {
            val h = AppLockControllerHarness(AppLockTestSettings.enabled(), capability)
            val controller = h.resolvedController()

            assertEquals("capability $capability", AppLockState.Inert(reason), controller.state.value)
            assertEquals(listOf(inertCode(reason)), h.emitted)
            assertEquals(0, h.repository.writeAttempts)
        }
    }

    @Test
    fun `AE-54 a transferred lock flag on API 29 is INERT API_UNSUPPORTED and the forced values stay on`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.UNSUPPORTED_API)
        val controller = h.resolvedController()

        assertEquals(AppLockState.Inert(InertReason.API_UNSUPPORTED), controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_INERT_API_UNSUPPORTED), h.emitted)
        // The stored flag is unchanged, so discreet notifications and FLAG_SECURE stay forced (INV-32).
        assertTrue(EffectivePrivacyValues.effectiveLockEnabled(h.repository.loadState))
        assertTrue(EffectivePrivacyValues.effectiveDiscreet(h.repository.loadState))
        assertTrue(EffectivePrivacyValues.effectiveSecureScreen(h.repository.loadState, resolving = false))
    }

    // --- T6: unreadable settings, no way to authenticate ------------------------------------------------

    @Test
    fun `AE-52 T6 unreadable settings without capability resolve to INERT SETTINGS_UNREADABLE_NO_AUTH for every cause`() {
        for (cause in SettingsUnreadableCause.entries) {
            for ((capability, _) in notAvailable) {
                val h = AppLockControllerHarness(AppLockTestSettings.unreadable(cause), capability)
                val controller = h.resolvedController()

                assertEquals(
                    "$cause / $capability",
                    AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH),
                    controller.state.value
                )
                assertEquals(listOf(PrivacyDiagnosticCode.LOCK_INERT_SETTINGS_UNREADABLE), h.emitted)
            }
        }
    }

    @Test
    fun `T6 RESOLVE_TIMEOUT without capability resolves to INERT SETTINGS_UNREADABLE_NO_AUTH`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED, DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.newController()

        controller.onResolveTimeout()

        assertEquals(AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), controller.state.value)
    }

    @Test
    fun `T6b does not apply to an INERT fallback (fail closed)`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED, DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.newController()
        controller.onResolveTimeout()

        controller.onLateSettingsResolved(AppLockTestSettings.DISABLED)

        assertEquals(AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), controller.state.value)
    }

    // --- T7b: the capability is re-checked at the tap ---------------------------------------------------

    @Test
    fun `T7b an Unlock tap with the capability gone becomes INERT, starts no attempt and records the code`() {
        for ((capability, reason) in notAvailable) {
            val h = AppLockControllerHarness(AppLockTestSettings.enabled())
            val controller = h.resolvedController()
            assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)

            h.capabilityProvider.capability = capability
            val result = controller.onUnlockTapped()

            assertEquals("capability $capability", UnlockTapResult.BecameInert(reason), result)
            assertEquals(AppLockState.Inert(reason), controller.state.value)
            assertNull(controller.inFlightAttemptId)
            assertEquals(inertCode(reason), h.emitted.last())
        }
    }

    @Test
    fun `T7b the capability is evaluated again at every tap, never cached`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        val controller = h.resolvedController()
        val afterResolution = h.capabilityProvider.evaluations

        controller.onUnlockTapped()

        assertEquals(afterResolution + 1, h.capabilityProvider.evaluations)
    }

    // --- T16: a foreground finds the capability gone (AE-48) ----------------------------------------------

    @Test
    fun `AE-48 T16 UNLOCKED then the screen lock is removed, a foreground gives INERT and nothing is written`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        val controller = h.resolvedController()
        h.unlock(controller)

        controller.onAppBackgrounded()
        h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        h.clock.advance(10_000L)
        controller.onAppForegrounded()

        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        assertEquals(PrivacyDiagnosticCode.LOCK_INERT_NO_CREDENTIAL, h.emitted.last())
        assertEquals(0, h.repository.writeAttempts)
        // appLockEnabled is still true, notifications stay DISCREET and FLAG_SECURE stays set.
        assertTrue(EffectivePrivacyValues.effectiveLockEnabled(h.repository.loadState))
        assertTrue(EffectivePrivacyValues.effectiveDiscreet(h.repository.loadState))
        assertTrue(EffectivePrivacyValues.effectiveSecureScreen(h.repository.loadState, resolving = false))
    }

    @Test
    fun `T16 maps every non-AVAILABLE capability to its reason`() {
        for ((capability, reason) in notAvailable) {
            val h = AppLockControllerHarness(AppLockTestSettings.enabled())
            val controller = h.resolvedController()
            h.unlock(controller)
            controller.onAppBackgrounded()

            h.capabilityProvider.capability = capability
            controller.onAppForegrounded()

            assertEquals("capability $capability", AppLockState.Inert(reason), controller.state.value)
        }
    }

    @Test
    fun `T16 beats the timeout - ten minutes away with the screen lock removed is INERT, not LOCKED`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1))
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppBackgrounded()
        h.clock.advance(10 * 60_000L)

        h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        controller.onAppForegrounded()

        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        assertNull(controller.pendingBackgroundAtMillis)
    }

    @Test
    fun `T16 belongs to a foreground, a LOCK_QUERY alone does not enter INERT`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled())
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppBackgrounded()

        h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL

        assertEquals(AppLockState.Unlocked, controller.onLockQuery())
    }

    @Test
    fun `T16 does not fire while the unreadable fallback is in force with the capability available`() {
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.IO_ERROR))
        val controller = h.resolvedController()
        h.unlock(controller)

        controller.onAppBackgrounded()
        controller.onAppForegrounded() // F is IMMEDIATE and the device can authenticate: T14, not T16

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    // --- INERT while the session continues (AE-49) --------------------------------------------------------

    @Test
    fun `AE-49 INERT stays INERT through the session when the screen lock comes back in the app`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)

        h.capabilityProvider.capability = DeviceAuthCapability.AVAILABLE // re-added while in the app

        // Nothing is pending (no background happened), so neither a foreground nor a query re-activates.
        controller.onAppForegrounded()
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.onLockQuery())
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
    }

    @Test
    fun `AE-49 a background longer than the timeout then re-activates as LOCKED TIMEOUT`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()
        h.capabilityProvider.capability = DeviceAuthCapability.AVAILABLE

        controller.onAppBackgrounded()
        h.clock.advance(60_000L)
        controller.onAppForegrounded()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertEquals(PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT, h.emitted.last())
    }

    @Test
    fun `T15 a return within the timeout with the screen lock back clears the banner (INERT to UNLOCKED)`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()
        h.capabilityProvider.capability = DeviceAuthCapability.AVAILABLE

        controller.onAppBackgrounded()
        h.clock.advance(59_999L)
        controller.onAppForegrounded()

        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertNull(controller.pendingBackgroundAtMillis)
    }

    @Test
    fun `INERT with the capability still gone stays INERT across a long background`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()
        val emittedAfterResolution = h.emitted.size

        controller.onAppBackgrounded()
        assertTrue(controller.pendingBackgroundAtMillis != null) // T13 applies to INERT
        h.clock.advance(10 * 60_000L)
        controller.onAppForegrounded()

        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        assertNull(controller.pendingBackgroundAtMillis)
        assertEquals(emittedAfterResolution, h.emitted.size)
    }

    @Test
    fun `INERT after an unreadable fallback re-activates as LOCKED TIMEOUT once the device can authenticate`() {
        val h = AppLockControllerHarness(
            AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED),
            DeviceAuthCapability.NO_CREDENTIAL
        )
        val controller = h.resolvedController()
        assertEquals(AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), controller.state.value)

        h.capabilityProvider.capability = DeviceAuthCapability.AVAILABLE
        controller.onAppBackgrounded()
        h.clock.advance(1_000L) // F is IMMEDIATE
        controller.onAppForegrounded()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
    }

    @Test
    fun `a cold start re-activates the lock after INERT and never rewrites the setting`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), h.resolvedController().state.value)

        h.capabilityProvider.capability = DeviceAuthCapability.AVAILABLE
        val nextProcess = h.resolvedController()

        assertEquals(AppLockState.Locked(LockCause.COLD_START), nextProcess.state.value)
        assertEquals(AppLockTestSettings.enabled(), h.repository.loadState)
        assertEquals(0, h.repository.writeAttempts)
    }

    @Test
    fun `no prompt can start from INERT`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()

        assertEquals(UnlockTapResult.NotLocked, controller.onUnlockTapped())

        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        assertNull(controller.inFlightAttemptId)
    }
}
