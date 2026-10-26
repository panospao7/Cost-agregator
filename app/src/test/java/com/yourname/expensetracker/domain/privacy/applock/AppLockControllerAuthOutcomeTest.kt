package com.yourname.expensetracker.domain.privacy.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S16 part (ii-a): what the controller does with each outcome of an UNLOCK attempt, reached only through
 * the controller-owned callback that a fake [DeviceAuthenticator] captured (P1, R1). T8 (success), T9
 * (cancel), T10 (error and lockout), T11 (no credential, with the R3 transient rule), T12 (stale), and
 * the `LOCK_AUTH_*` diagnostics once per process per cause (spec 10).
 *
 * Acceptance examples covered here: AE-42 and AE-117 (UNLOCK purpose, both callback orders), AE-44,
 * AE-45, AE-50. Launch results, the watchdog, host loss and publication are in
 * `AppLockControllerPromptOutcomeTest`.
 */
class AppLockControllerAuthOutcomeTest {

    private val authCodes = setOf(
        PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED,
        PrivacyDiagnosticCode.LOCK_AUTH_LOCKOUT,
        PrivacyDiagnosticCode.LOCK_AUTH_ERROR
    )

    private fun AppLockControllerHarness.authCodesEmitted(): List<PrivacyDiagnosticCode> =
        emitted.filter { it in authCodes }

    // --- Every exit from AUTHENTICATING(UNLOCK) --------------------------------------------------------

    @Test
    fun `every exit from AUTHENTICATING(UNLOCK) closes the attempt, cancels the watchdog and frees the next tap`() {
        val exits: List<Pair<DeviceAuthOutcome, AppLockState>> = listOf(
            DeviceAuthOutcome.Succeeded to AppLockState.Unlocked,
            DeviceAuthOutcome.Cancelled to AppLockState.Locked(LockCause.COLD_START),
            DeviceAuthOutcome.LockedOut(permanent = false) to AppLockState.Locked(LockCause.COLD_START),
            DeviceAuthOutcome.LockedOut(permanent = true) to AppLockState.Locked(LockCause.COLD_START),
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.HW_UNAVAILABLE) to AppLockState.Locked(LockCause.COLD_START),
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.TIMEOUT) to AppLockState.Locked(LockCause.COLD_START),
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED) to AppLockState.Locked(LockCause.COLD_START),
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER) to AppLockState.Locked(LockCause.COLD_START),
            DeviceAuthOutcome.NotAvailable to AppLockState.Inert(InertReason.NO_CREDENTIAL)
        )

        for ((outcome, expected) in exits) {
            val (h, controller) = lockedControllerFor(LockCause.COLD_START)
            h.tap(controller)
            if (outcome == DeviceAuthOutcome.NotAvailable) {
                h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
            }

            h.authenticator.deliver(outcome)

            assertEquals("$outcome", expected, controller.state.value)
            assertNull("$outcome", controller.inFlightAttemptId)
            // The watchdog and the launch coroutine are gone: nothing of the attempt is still running.
            assertEquals("$outcome", 0, h.activeJobs)
            // A closing outcome never asks the adapter to cancel (nothing is left in flight).
            assertTrue("$outcome", h.authenticator.cancelCalls.isEmpty())
            if (expected is AppLockState.Locked) {
                // The Unlock button is enabled again: a new tap starts a new attempt.
                assertTrue("$outcome", controller.onUnlockTapped() is UnlockTapResult.Started)
            }
        }
    }

    @Test
    fun `an outcome that arrives after the attempt closed is stale and changes nothing`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        h.tap(controller)
        val call = h.authenticator.calls.last()
        h.authenticator.deliver(DeviceAuthOutcome.Cancelled, call)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        val emittedBefore = h.emitted.toList()

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded, call)
        h.authenticator.deliver(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER), call)
        h.authenticator.deliver(DeviceAuthOutcome.LockedOut(permanent = true), call)
        h.authenticator.deliver(DeviceAuthOutcome.NotAvailable, call)

        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertEquals(emittedBefore, h.emitted)
    }

    // --- T8 / AE-42 / AE-117 (UNLOCK purpose): the app's own prompt never re-locks or loops -------------------

    @Test
    fun `AE-42 AE-117 success before the foreground - the OEM background during the prompt is not a timeout`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.resolvedController()
        h.tap(controller)

        // The OEM credential UI stops the host: APP_BACKGROUNDED at t=0 while AUTHENTICATING(UNLOCK).
        controller.onAppBackgrounded()
        assertNull(controller.pendingBackgroundAtMillis)
        h.clock.advance(20_000L)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // success at t=20 s
        assertEquals(AppLockState.Unlocked, controller.state.value)
        controller.onAppForegrounded() // the host restarts

        assertEquals(AppLockState.Unlocked, controller.state.value) // no LOCKED(TIMEOUT)
        assertNull(controller.pendingBackgroundAtMillis)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, controller.state.value)
        // No loop: exactly one prompt was ever launched and nothing re-prompted.
        h.settle()
        assertEquals(1, h.authenticator.calls.size)
        assertFalse(PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT in h.emitted)
    }

    @Test
    fun `AE-42 AE-117 success after the foreground - the same result in the other callback order`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.resolvedController()
        val attemptId = h.tap(controller)

        controller.onAppBackgrounded() // t=0
        h.clock.advance(20_000L)
        controller.onAppForegrounded() // the host restarts first: nothing is evaluated while the prompt is open
        assertEquals(AppLockState.Authenticating(attemptId, AuthPurpose.UNLOCK), controller.state.value)
        assertNull(controller.pendingBackgroundAtMillis)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // success at t=20 s, after the foreground

        assertEquals(AppLockState.Unlocked, controller.state.value)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertNull(controller.pendingBackgroundAtMillis)
        h.settle()
        assertEquals(1, h.authenticator.calls.size)
        assertFalse(PrivacyDiagnosticCode.LOCK_SHOWN_TIMEOUT in h.emitted)
    }

    @Test
    fun `T8 a success records LOCK_UNLOCKED once per process and applies no held link or settings change`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.IMMEDIATE))
        val controller = h.resolvedController()

        h.unlock(controller)
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        h.unlock(controller)

        assertEquals(1, h.emitted.count { it == PrivacyDiagnosticCode.LOCK_UNLOCKED })
        assertEquals(0, h.repository.writeAttempts)
    }

    // --- T9 / AE-44: cancel ----------------------------------------------------------------------------------

    @Test
    fun `AE-44 T9 cancel returns to the LOCKED cause it came from, records the code and never re-prompts`() {
        for (cause in LockCause.entries) {
            val (h, controller) = lockedControllerFor(cause)
            h.emitted.clear()
            h.emittedMetadata.clear()
            val attemptId = h.tap(controller)

            h.authenticator.deliver(DeviceAuthOutcome.Cancelled)

            assertEquals("cause $cause", AppLockState.Locked(cause), controller.state.value)
            assertEquals("cause $cause", listOf(PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED), h.emitted)
            assertSame("cause $cause", PrivacyDiagnosticMetadata.EMPTY, h.emittedMetadata.single())
            // No automatic re-prompt, however long the surface stays up.
            h.advance(AppLockController.PROMPT_WATCHDOG_MILLIS * 2)
            assertEquals("cause $cause", 1, h.authenticator.calls.count { it.attemptId == attemptId })
            assertEquals("cause $cause", attemptId, h.authenticator.calls.last().attemptId)
            // Retry is a new tap with a new, larger id.
            val retry = h.tap(controller)
            assertTrue("cause $cause", retry > attemptId)
            assertEquals("cause $cause", AppLockState.Authenticating(retry, AuthPurpose.UNLOCK), controller.state.value)
        }
    }

    // --- T10 / AE-45: lockout and errors -------------------------------------------------------------------

    @Test
    fun `AE-45 T10 a lockout returns to LOCKED, records LOCK_AUTH_LOCKOUT, and later taps retry`() {
        for (permanent in listOf(false, true)) {
            val (h, controller) = lockedControllerFor(LockCause.COLD_START)
            h.emitted.clear()
            h.emittedMetadata.clear()
            val first = h.tap(controller)

            h.authenticator.deliver(DeviceAuthOutcome.LockedOut(permanent))

            assertEquals("permanent=$permanent", AppLockState.Locked(LockCause.COLD_START), controller.state.value)
            assertEquals("permanent=$permanent", listOf(PrivacyDiagnosticCode.LOCK_AUTH_LOCKOUT), h.emitted)
            assertSame("permanent=$permanent", PrivacyDiagnosticMetadata.EMPTY, h.emittedMetadata.single())
            val second = h.tap(controller)
            assertTrue("permanent=$permanent", second > first)
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            assertEquals("permanent=$permanent", AppLockState.Unlocked, controller.state.value)
        }
    }

    @Test
    fun `T10 every error bucket returns to the origin LOCKED cause and records only its bucket`() {
        for (cause in LockCause.entries) {
            for (bucket in DeviceAuthErrorBucket.entries) {
                val (h, controller) = lockedControllerFor(cause)
                h.emitted.clear()
                h.emittedMetadata.clear()
                h.tap(controller)

                h.authenticator.deliver(DeviceAuthOutcome.Failed(bucket))

                assertEquals("$cause / $bucket", AppLockState.Locked(cause), controller.state.value)
                assertEquals("$cause / $bucket", listOf(PrivacyDiagnosticCode.LOCK_AUTH_ERROR), h.emitted)
                assertEquals(
                    "$cause / $bucket",
                    mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.CAUSE to bucket),
                    h.emittedMetadata.single().entries
                )
            }
        }
    }

    @Test
    fun `spec 10 LOCK_AUTH codes are emitted once per process per cause`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        fun attempt(outcome: DeviceAuthOutcome) {
            h.tap(controller)
            h.authenticator.deliver(outcome)
            assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        }

        attempt(DeviceAuthOutcome.Cancelled)
        attempt(DeviceAuthOutcome.Cancelled)
        attempt(DeviceAuthOutcome.LockedOut(permanent = false))
        attempt(DeviceAuthOutcome.LockedOut(permanent = true))
        attempt(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER))
        attempt(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER))
        attempt(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.TIMEOUT))
        attempt(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.TIMEOUT))

        // One cancel, one lockout, and one error per bucket seen (OTHER and TIMEOUT).
        assertEquals(
            listOf(
                PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED,
                PrivacyDiagnosticCode.LOCK_AUTH_LOCKOUT,
                PrivacyDiagnosticCode.LOCK_AUTH_ERROR,
                PrivacyDiagnosticCode.LOCK_AUTH_ERROR
            ),
            h.authCodesEmitted()
        )
    }

    @Test
    fun `the three LOCK_AUTH entries of the emitter map to their reason codes and allow only the bucket key`() {
        for (code in authCodes) {
            assertEquals(code.name, code.reasonCode.name)
        }
        assertTrue(PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED.allowedKeys.isEmpty())
        assertTrue(PrivacyDiagnosticCode.LOCK_AUTH_LOCKOUT.allowedKeys.isEmpty())
        assertEquals(setOf(PrivacyDiagnosticKey.CAUSE), PrivacyDiagnosticCode.LOCK_AUTH_ERROR.allowedKeys)
    }

    @Test
    fun `a system cancel while the app goes Home is an error bucket and returns to LOCKED`() {
        val (h, controller) = lockedControllerFor(LockCause.TIMEOUT)
        h.tap(controller)
        controller.onAppBackgrounded() // Home pressed: nothing is recorded while the prompt is open

        h.authenticator.deliver(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED))

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertNull(controller.pendingBackgroundAtMillis)
    }

    // --- T11 / AE-50: no credential ------------------------------------------------------------------------

    @Test
    fun `AE-50 T11 NotAvailable with the capability gone on re-check becomes INERT with the mapped reason and code`() {
        val reasons = listOf(
            DeviceAuthCapability.NO_CREDENTIAL to InertReason.NO_CREDENTIAL,
            DeviceAuthCapability.HW_UNAVAILABLE to InertReason.HW_UNAVAILABLE,
            DeviceAuthCapability.UNSUPPORTED_API to InertReason.API_UNSUPPORTED,
            DeviceAuthCapability.UNKNOWN to InertReason.UNKNOWN // a genuine UNKNOWN, not an exception
        )
        val codes = mapOf(
            InertReason.NO_CREDENTIAL to PrivacyDiagnosticCode.LOCK_INERT_NO_CREDENTIAL,
            InertReason.HW_UNAVAILABLE to PrivacyDiagnosticCode.LOCK_INERT_HW_UNAVAILABLE,
            InertReason.API_UNSUPPORTED to PrivacyDiagnosticCode.LOCK_INERT_API_UNSUPPORTED,
            InertReason.UNKNOWN to PrivacyDiagnosticCode.LOCK_INERT_UNKNOWN
        )
        for ((capability, reason) in reasons) {
            val (h, controller) = lockedControllerFor(LockCause.COLD_START)
            h.emitted.clear()
            h.tap(controller)
            h.capabilityProvider.capability = capability
            val evaluationsBefore = h.capabilityProvider.evaluations

            h.authenticator.deliver(DeviceAuthOutcome.NotAvailable)

            assertEquals("$capability", AppLockState.Inert(reason), controller.state.value)
            assertEquals("$capability", listOf(codes.getValue(reason)), h.emitted)
            // The capability was re-checked once, at the outcome (never cached from the tap).
            assertEquals("$capability", evaluationsBefore + 1, h.capabilityProvider.evaluations)
            assertNull("$capability", controller.inFlightAttemptId)
            assertEquals("$capability", UnlockTapResult.NotLocked, controller.onUnlockTapped())
        }
    }

    @Test
    fun `T11 does not apply when the re-check says AVAILABLE, the attempt returns to the origin as a bounded error`() {
        val (h, controller) = lockedControllerFor(LockCause.TIMEOUT)
        h.emitted.clear()
        h.emittedMetadata.clear()
        h.tap(controller)

        h.authenticator.deliver(DeviceAuthOutcome.NotAvailable) // capability still AVAILABLE

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_AUTH_ERROR), h.emitted)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.CAUSE to DeviceAuthErrorBucket.OTHER),
            h.emittedMetadata.single().entries
        )
    }

    // --- R3: an exception-derived capability is transient ------------------------------------------------------

    @Test
    fun `R3 NotAvailable with a failed capability check stays LOCKED, never INERT, and the next tap tries again`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        h.emitted.clear()
        h.tap(controller)
        h.capabilityProvider.capability = DeviceAuthCapability.CHECK_FAILED

        h.authenticator.deliver(DeviceAuthOutcome.NotAvailable)

        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertFalse(h.emitted.any { it.name.startsWith("LOCK_INERT_") })
        assertNull(controller.inFlightAttemptId)

        h.capabilityProvider.capability = DeviceAuthCapability.AVAILABLE
        val retry = h.tap(controller)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(retry, h.authenticator.calls.last().attemptId)
    }

    @Test
    fun `R3 item 32 an Unlock tap with a failed capability check starts a normal attempt, launches the prompt and success unlocks`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START, DeviceAuthCapability.CHECK_FAILED)
        h.emitted.clear()
        h.emittedMetadata.clear()

        val result = controller.onUnlockTapped()
        h.settle()

        assertTrue("expected an attempt, got $result", result is UnlockTapResult.Started)
        val id = (result as UnlockTapResult.Started).attemptId
        assertEquals(AppLockState.Authenticating(id, AuthPurpose.UNLOCK), controller.state.value)
        assertEquals(id, controller.inFlightAttemptId)
        assertEquals(id, h.authenticator.calls.single().attemptId)
        assertEquals(AuthPromptCopy.UNLOCK, h.authenticator.calls.single().copy)
        // One bounded diagnostic, with the closed CAUSE key and the enum name only.
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY), h.emitted)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.CAUSE to DeviceAuthCapability.UNKNOWN),
            h.emittedMetadata.single().entries
        )

        // Only a matching Succeeded opens the app: a wrong id does not.
        h.authenticator.deliverWithId(id + 1, DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Authenticating(id, AuthPurpose.UNLOCK), controller.state.value)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertNull(controller.inFlightAttemptId)
    }

    @Test
    fun `R3 item 32 with a failed capability check a cancel returns to LOCKED and the next tap tries again`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START, DeviceAuthCapability.CHECK_FAILED)
        val first = h.tap(controller)

        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)

        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertNull(controller.inFlightAttemptId)
        val evaluationsBefore = h.capabilityProvider.evaluations
        val second = h.tap(controller)
        assertTrue(second > first)
        assertEquals(evaluationsBefore + 1, h.capabilityProvider.evaluations) // asked again, not cached
        assertEquals(AppLockState.Authenticating(second, AuthPurpose.UNLOCK), controller.state.value)
    }

    @Test
    fun `R3 item 32 with a failed capability check a NotAvailable whose re-check still fails returns to LOCKED, never INERT`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START, DeviceAuthCapability.CHECK_FAILED)
        h.emitted.clear()
        h.emittedMetadata.clear()
        h.tap(controller)
        h.emitted.clear()
        h.emittedMetadata.clear()

        h.authenticator.deliver(DeviceAuthOutcome.NotAvailable) // the re-check still says CHECK_FAILED

        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        assertNull(controller.inFlightAttemptId)
        assertFalse(h.emitted.any { it.name.startsWith("LOCK_INERT_") })
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_AUTH_ERROR), h.emitted)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.CAUSE to DeviceAuthErrorBucket.OTHER),
            h.emittedMetadata.single().entries
        )
    }

    @Test
    fun `R3 item 32 the failed-capability diagnostic is recorded once per process however many taps`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START, DeviceAuthCapability.CHECK_FAILED)
        h.emitted.clear()

        repeat(3) {
            h.tap(controller)
            h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
        }

        assertEquals(1, h.emitted.count { it == PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY })
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
    }

    @Test
    fun `F1 the tap-time capability claim is per cause, so it does not consume the code for another cause`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START, DeviceAuthCapability.CHECK_FAILED)
        h.emitted.clear()
        h.emittedMetadata.clear()

        h.tap(controller)
        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
        h.tap(controller)

        // Exactly one emission, with cause UNKNOWN.
        assertEquals(1, h.emitted.count { it == PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY })
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.CAUSE to DeviceAuthCapability.UNKNOWN),
            // emitted and emittedMetadata are captured in parallel; the cancel also emits LOCK_AUTH_CANCELLED.
            h.emittedMetadata[h.emitted.indexOf(PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY)].entries
        )
        // The claim is recorded against the cause, never against the whole code. S18 (T26) has no public
        // entry point yet, so a second cause cannot be driven; this pins the state that lets it emit once.
        assertEquals(setOf<Any>(DeviceAuthCapability.UNKNOWN), privateSet(controller, "claimedCapabilityCauses"))
        assertFalse(
            PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY in
                privateSet(controller, "claimedDiagnosticCodes")
        )
    }

    private fun privateSet(controller: AppLockController, fieldName: String): Set<Any> {
        val field = AppLockController::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(controller) as Set<Any>).toSet()
    }

    @Test
    fun `R3 item 32 an AVAILABLE capability records no capability diagnostic`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        h.emitted.clear()

        h.tap(controller)

        assertFalse(h.emitted.contains(PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY))
    }

    @Test
    fun `R3 a genuine UNKNOWN still makes the tap INERT, only the failed check is transient`() {
        val (h, controller) = lockedControllerFor(LockCause.COLD_START)
        h.capabilityProvider.capability = DeviceAuthCapability.UNKNOWN

        assertEquals(UnlockTapResult.BecameInert(InertReason.UNKNOWN), controller.onUnlockTapped())
        h.settle()

        assertEquals(AppLockState.Inert(InertReason.UNKNOWN), controller.state.value)
        assertTrue(h.authenticator.calls.isEmpty())
    }

    @Test
    fun `R3 at resolution a failed capability check keeps the lock instead of opening to INERT`() {
        val locked = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.CHECK_FAILED)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), locked.resolvedController().state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SHOWN_COLD_START), locked.emitted)

        for (cause in SettingsUnreadableCause.entries) {
            val unreadable = AppLockControllerHarness(
                AppLockTestSettings.unreadable(cause),
                DeviceAuthCapability.CHECK_FAILED
            )
            assertEquals(
                "cause $cause",
                AppLockState.Locked(LockCause.SETTINGS_UNREADABLE),
                unreadable.resolvedController().state.value
            )
        }

        val off = AppLockControllerHarness(AppLockTestSettings.DISABLED, DeviceAuthCapability.CHECK_FAILED)
        assertEquals(AppLockState.Disabled, off.resolvedController().state.value)
    }

    @Test
    fun `R3 a foreground with a failed capability check does not open to INERT and the timer still applies`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1))
        val controller = h.resolvedController()
        h.unlock(controller)

        // Back within the timeout with the check failing: stays UNLOCKED (T16 does not fire), pending consumed.
        controller.onAppBackgrounded()
        h.clock.advance(10_000L)
        h.capabilityProvider.capability = DeviceAuthCapability.CHECK_FAILED
        controller.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertNull(controller.pendingBackgroundAtMillis)

        // Past the timeout with the check failing: the lock still engages (fail closed), it does not vanish.
        controller.onAppBackgrounded()
        h.clock.advance(120_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertFalse(h.emitted.any { it.name.startsWith("LOCK_INERT_") })
    }

    @Test
    fun `R3 contrast - the same foreground with a genuine UNKNOWN does open to INERT`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M1))
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppBackgrounded()
        h.clock.advance(10_000L)
        h.capabilityProvider.capability = DeviceAuthCapability.UNKNOWN

        controller.onAppForegrounded()

        assertEquals(AppLockState.Inert(InertReason.UNKNOWN), controller.state.value)
    }
}
