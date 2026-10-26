package com.yourname.expensetracker.domain.privacy.applock

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppLockSettingsOperations] wired to one [AppLockController] over the shared fakes (S18 part (i-a)).
 * With [deferred] false the apply runs inside the hand-off call (an unconfined scope); with true it is queued on
 * the test scheduler and runs on `h.settle()`, so a test can see that the handler itself does not wait for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SettingsOperationsRig(
    val h: AppLockControllerHarness,
    val controller: AppLockController,
    deferred: Boolean = false
) {
    /** Throwables that reached the operations scope's exception handler. */
    val uncaught = mutableListOf<Throwable>()

    /** Every `DiscreetRepostTrigger.onCommittedWrite(was, is)` call. */
    val triggerCalls = mutableListOf<Pair<Boolean, Boolean>>()

    /** When set, the trigger throws it. */
    var triggerFailure: Throwable? = null

    val job = SupervisorJob()
    private val dispatcher: CoroutineDispatcher =
        if (deferred) StandardTestDispatcher(h.scheduler) else UnconfinedTestDispatcher(h.scheduler)
    val scope = CoroutineScope(job + dispatcher + CoroutineExceptionHandler { _, t -> uncaught += t })

    private val trigger = mockk<DiscreetRepostTrigger>().also { mock ->
        coEvery { mock.onCommittedWrite(any(), any()) } coAnswers {
            triggerCalls += firstArg<Boolean>() to secondArg<Boolean>()
            triggerFailure?.let { throw it }
            DiscreetRepostOutcome.SWEPT
        }
    }

    val ops = AppLockSettingsOperations(controller, h.repository, h.capabilityProvider, trigger, scope)
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppLockControllerEnableDisableTest {

    private fun metadataOf(h: AppLockControllerHarness, index: Int): Map<PrivacyDiagnosticKey, Any> =
        h.emittedMetadata[index].entries

    private fun disabledRig(
        capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE,
        deferred: Boolean = false
    ): SettingsOperationsRig {
        val (h, controller) = disabledControllerFor(capability)
        h.emitted.clear()
        h.emittedMetadata.clear()
        return SettingsOperationsRig(h, controller, deferred)
    }

    private fun unlockedRig(deferred: Boolean = false): SettingsOperationsRig {
        val (h, controller) = unlockedControllerFor()
        h.emitted.clear()
        h.emittedMetadata.clear()
        return SettingsOperationsRig(h, controller, deferred)
    }

    // --- T26: the request-time refusal ------------------------------------------------------------

    @Test
    fun `T26 each unavailable capability refuses with its own code and CAUSE, once per process per cause, with no prompt and no write`() {
        val expected = mapOf(
            DeviceAuthCapability.NO_CREDENTIAL to (PrivacyDiagnosticCode.LOCK_UNAVAILABLE_NOT_SECURE to DeviceAuthCapability.NO_CREDENTIAL),
            DeviceAuthCapability.UNSUPPORTED_API to (PrivacyDiagnosticCode.LOCK_UNAVAILABLE_API to DeviceAuthCapability.UNSUPPORTED_API),
            DeviceAuthCapability.HW_UNAVAILABLE to (PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY to DeviceAuthCapability.HW_UNAVAILABLE),
            DeviceAuthCapability.UNKNOWN to (PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY to DeviceAuthCapability.UNKNOWN),
            DeviceAuthCapability.CHECK_FAILED to (PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY to DeviceAuthCapability.UNKNOWN)
        )
        for ((capability, codeAndCause) in expected) {
            val rig = disabledRig(capability)
            val h = rig.h

            assertEquals("$capability", SettingsRequestResult.REFUSED_UNAVAILABLE, rig.ops.requestEnable())

            assertEquals("$capability", AppLockState.Disabled, rig.controller.state.value)
            assertNull("$capability", rig.controller.inFlightAttemptId)
            assertTrue("$capability", h.authenticator.calls.isEmpty())
            assertEquals("$capability", listOf(codeAndCause.first), h.emitted)
            assertEquals("$capability", mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.CAUSE to codeAndCause.second), metadataOf(h, 0))
            assertTrue("$capability", h.repository.lockWrites.isEmpty())

            // The claim is per (code, cause): a repeat in the same process stays silent.
            assertEquals("$capability", SettingsRequestResult.REFUSED_UNAVAILABLE, rig.ops.requestEnable())
            assertEquals("$capability", 1, h.emitted.size)
        }
    }

    @Test
    fun `T26 a capability read that throws refuses as UNKNOWN and a cancellation propagates`() {
        val rig = disabledRig()
        rig.h.capabilityProvider.failure = IllegalStateException("secret-binder-text")

        assertEquals(SettingsRequestResult.REFUSED_UNAVAILABLE, rig.ops.requestEnable())
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY), rig.h.emitted)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.CAUSE to DeviceAuthCapability.UNKNOWN),
            metadataOf(rig.h, 0)
        )

        val cancelled = kotlinx.coroutines.CancellationException("c")
        rig.h.capabilityProvider.failure = cancelled
        try {
            rig.ops.requestEnable()
            org.junit.Assert.fail("the cancellation was swallowed")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertTrue(e === cancelled)
        }
        assertEquals(AppLockState.Disabled, rig.controller.state.value)
    }

    @Test
    fun `T26 the refusal claims nothing and changes nothing outside DISABLED`() {
        val rig = unlockedRig()
        rig.h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL

        rig.ops.requestEnable()

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue(rig.h.emitted.isEmpty())
    }

    // --- T17: enable (AE-36, AE-113) ----------------------------------------------------------------

    @Test
    fun `AE-113 enable success writes true, moves DISABLED to UNLOCKED, records LOCK_ENABLED and fires the repost trigger`() {
        val rig = disabledRig()
        val h = rig.h

        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestEnable())
        h.settle()
        assertEquals(AuthPromptCopy.ENABLE, h.authenticator.calls.last().copy)
        assertTrue(rig.controller.state.value is AppLockState.Authenticating)
        assertTrue(h.repository.lockWrites.isEmpty())

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(listOf(true), h.repository.lockWrites)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_ENABLED), h.emitted)
        // INV-13: the lock term turns effective discreet on; before was false (stored discreet off).
        assertEquals(listOf(false to true), rig.triggerCalls)
        assertEquals(0, h.repository.writeAttempts)
        assertTrue(rig.uncaught.isEmpty())
    }

    @Test
    fun `AE-113 the controller's settings view takes the committed flag, so the lock then evaluates like an enabled lock`() {
        val rig = disabledRig()
        val h = rig.h
        rig.ops.requestEnable()
        h.settle()
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        rig.controller.onAppForegrounded()

        rig.controller.onAppBackgrounded()
        h.clock.advance(AutoLockTimeout.DEFAULT.millis + 1)
        rig.controller.onAppForegrounded()

        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), rig.controller.state.value)
    }

    @Test
    fun `AE-113 enable cancel returns to DISABLED, writes nothing, fires no trigger and records no LOCK_ENABLED`() {
        val rig = disabledRig()
        val h = rig.h
        rig.ops.requestEnable()
        h.settle()

        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)

        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertTrue(h.repository.lockWrites.isEmpty())
        assertTrue(rig.triggerCalls.isEmpty())
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED), h.emitted)
    }

    @Test
    fun `enable write failure stays DISABLED and records LOCK_SETTING_PERSIST_FAILED for APP_LOCK_ENABLED, with no trigger`() {
        val failures = mapOf<String, (SettingsOperationsRig) -> Unit>(
            "failed" to { r -> r.h.repository.lockWriteResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED) } },
            "rejected" to { r -> r.h.repository.lockWriteResult = { AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE) } },
            "throws" to { r -> r.h.repository.lockWriteFailure = IllegalStateException("secret-path /data/user/0/x") }
        )
        for ((label, arrange) in failures) {
            val rig = disabledRig()
            val h = rig.h
            arrange(rig)
            rig.ops.requestEnable()
            h.settle()

            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

            assertEquals(label, AppLockState.Disabled, rig.controller.state.value)
            assertEquals(label, listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), h.emitted)
            assertEquals(
                label,
                mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.FIELD to AppLockSettingField.APP_LOCK_ENABLED),
                metadataOf(h, 0)
            )
            assertTrue(label, rig.triggerCalls.isEmpty())
            assertTrue(label, rig.uncaught.isEmpty())
            // The apply ended, so the gate is closed and a new request may start again.
            assertEquals(label, SettingsRequestResult.STARTED, rig.ops.requestEnable())
        }
    }

    @Test
    fun `a sweep failure after the commit does not undo the enable`() {
        val rig = disabledRig()
        rig.triggerFailure = IllegalStateException("sweep")
        rig.ops.requestEnable()
        rig.h.settle()

        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(listOf(true), rig.h.repository.lockWrites)
        // Final gate finding 6: the sweep failure reached the scope's handler as the thrown type (not swallowed)...
        assertTrue(rig.uncaught.single() is IllegalStateException)
        // ...and the finally still ran: the completion and the flag clear, so the next request is not blocked.
        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
    }

    // --- T18: disable (AE-114) -----------------------------------------------------------------------

    @Test
    fun `AE-114 disable success uses the DISABLE copy, writes false, moves UNLOCKED to DISABLED and never re-posts`() {
        val rig = unlockedRig()
        val h = rig.h

        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
        h.settle()
        assertEquals(AuthPromptCopy.DISABLE, h.authenticator.calls.last().copy)
        assertTrue(rig.controller.state.value is AppLockState.Authenticating)

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertEquals(listOf(false), h.repository.lockWrites)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_DISABLED), h.emitted)
        assertTrue(rig.triggerCalls.isEmpty())
        assertEquals(0, h.repository.writeAttempts)
    }

    @Test
    fun `AE-114 disable write failure stays UNLOCKED with LOCK_SETTING_PERSIST_FAILED for APP_LOCK_ENABLED`() {
        val rig = unlockedRig()
        val h = rig.h
        h.repository.lockWriteResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED) }
        rig.ops.requestDisable()
        h.settle()

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), h.emitted)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.FIELD to AppLockSettingField.APP_LOCK_ENABLED),
            metadataOf(h, 0)
        )
        assertFalse(h.emitted.contains(PrivacyDiagnosticCode.LOCK_DISABLED))
        // The lock is still enabled for the controller: a timeout still locks.
        rig.controller.onAppBackgrounded()
        h.clock.advance(AutoLockTimeout.M1.millis)
        rig.controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), rig.controller.state.value)
    }

    @Test
    fun `AE-114 disable cancel, error and lockout keep UNLOCKED with nothing written`() {
        val outcomes = listOf(
            DeviceAuthOutcome.Cancelled,
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER),
            DeviceAuthOutcome.LockedOut(permanent = false)
        )
        for (outcome in outcomes) {
            val rig = unlockedRig()
            rig.ops.requestDisable()
            rig.h.settle()

            rig.h.authenticator.deliver(outcome)

            assertEquals("$outcome", AppLockState.Unlocked, rig.controller.state.value)
            assertTrue("$outcome", rig.h.repository.lockWrites.isEmpty())
            assertFalse("$outcome", rig.h.emitted.contains(PrivacyDiagnosticCode.LOCK_DISABLED))
        }
    }

    @Test
    fun `the T14 gap - a lock that lands between the hand-off and the write is never turned into DISABLED`() {
        val rig = unlockedRig(deferred = true)
        val h = rig.h
        rig.ops.requestDisable()
        h.settle()
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        // The apply is queued, not run: the state is the closed origin and the write has not happened.
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue(h.repository.lockWrites.isEmpty())

        rig.controller.onAppBackgrounded()
        h.clock.advance(AutoLockTimeout.M1.millis + 1)
        rig.controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), rig.controller.state.value)

        h.settle()

        assertEquals(listOf(false), h.repository.lockWrites)
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), rig.controller.state.value)
        assertTrue(h.emitted.contains(PrivacyDiagnosticCode.LOCK_DISABLED))
    }

    // --- Request results -----------------------------------------------------------------------------

    @Test
    fun `requests from the wrong state or while a prompt is in flight start nothing`() {
        val onRig = unlockedRig()
        assertEquals(SettingsRequestResult.NOT_IN_ORIGIN, onRig.ops.requestEnable())

        val offRig = disabledRig()
        assertEquals(SettingsRequestResult.NOT_IN_ORIGIN, offRig.ops.requestDisable())

        assertEquals(SettingsRequestResult.STARTED, offRig.ops.requestEnable())
        assertEquals(SettingsRequestResult.ALREADY_IN_FLIGHT, offRig.ops.requestEnable())
        offRig.h.settle()
        assertEquals(1, offRig.h.authenticator.calls.size)
    }

    @Test
    fun `no diagnostic of the enable and disable paths carries anything but a closed enum`() {
        val rig = disabledRig()
        rig.h.repository.lockWriteFailure = IllegalStateException("secret-path /data/user/0/x password=hunter2")
        rig.ops.requestEnable()
        rig.h.settle()
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        rig.h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        rig.ops.requestEnable()

        assertTrue(rig.h.emittedMetadata.isNotEmpty())
        for (metadata in rig.h.emittedMetadata) {
            for ((key, value) in metadata.entries) {
                assertTrue("$key", key == PrivacyDiagnosticKey.FIELD || key == PrivacyDiagnosticKey.CAUSE)
                assertTrue("$key", value is Enum<*>)
                assertFalse("$key", value.toString().contains("secret") || value.toString().contains("hunter2"))
            }
        }
    }

    // --- S18 (i-a) privacy review fixes (F1, F2, F5, F6, F8) -------------------------------------------

    @Test
    fun `F1 an unreadable pre-read counts as not discreet, so a committed enable fires the trigger with false to true`() {
        val rig = disabledRig()
        val h = rig.h
        rig.ops.requestEnable()
        h.settle()
        // The store turns unreadable after the controller resolved, and the lock write still commits.
        h.repository.loadState = AppLockTestSettings.unreadable(SettingsUnreadableCause.IO_ERROR)
        h.repository.lockWriteResult = { enabled ->
            AppLockSettingsWriteResult.Committed(AppLockPrivacySettings(appLockEnabled = enabled))
        }

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(listOf(true), h.repository.lockWrites)
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        // INV-13: Unreadable is not evidence of "already discreet" (same mapping as UpdateDiscreetNotificationsUseCase).
        assertEquals(listOf(false to true), rig.triggerCalls)
        assertTrue(rig.uncaught.isEmpty())
    }

    @Test
    fun `F2 the setting move is a no-op without the matching unconsumed token - no prior success, a wrong id, a reused id`() {
        val rig = disabledRig(deferred = true)
        val h = rig.h
        val controller = rig.controller

        // No prior success at all: nothing moves and nothing is recorded, committed or not.
        controller.onOpenOriginSettingApplied(1L, true)
        controller.onOpenOriginSettingApplied(1L, false)
        assertEquals(AppLockState.Disabled, controller.state.value)
        assertTrue(h.emitted.isEmpty())

        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestEnable())
        h.settle()
        val id = h.authenticator.calls.last().attemptId
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // the apply is queued, the token is recorded

        // A wrong id does not consume the token and moves nothing.
        controller.onOpenOriginSettingApplied(id + 1, true)
        controller.onOpenOriginSettingApplied(0L, false)
        assertEquals(AppLockState.Disabled, controller.state.value)
        assertTrue(h.emitted.isEmpty())

        h.settle() // the real apply, with the right id
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_ENABLED), h.emitted)

        // A reused id is a no-op: the token was consumed.
        controller.onOpenOriginSettingApplied(id, false)
        controller.onOpenOriginSettingApplied(id, true)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_ENABLED), h.emitted)
    }

    @Test
    fun `F5 while an apply is running both requests answer ALREADY_IN_FLIGHT, and they work again after it`() {
        val rig = disabledRig(deferred = true)
        val h = rig.h
        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestEnable())
        h.settle()
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // the apply is started but has not run

        assertEquals(SettingsRequestResult.ALREADY_IN_FLIGHT, rig.ops.requestEnable())
        assertEquals(SettingsRequestResult.ALREADY_IN_FLIGHT, rig.ops.requestDisable())
        assertEquals(1, h.authenticator.calls.size)

        h.settle()

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
    }

    @Test
    fun `F6 a background during the durable write is measured from the move, and the next foreground locks`() {
        val rig = disabledRig(deferred = true)
        val h = rig.h
        rig.ops.requestEnable()
        h.settle()
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // closed in the foreground
        rig.controller.onAppBackgrounded() // DISABLED records nothing by itself
        assertNull(rig.controller.pendingBackgroundAtMillis)

        h.settle() // the write and the move to UNLOCKED, while the app is in the background

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(h.clock.elapsedRealtimeMillis(), rig.controller.pendingBackgroundAtMillis)
        h.clock.advance(AutoLockTimeout.DEFAULT.millis + 1)
        rig.controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), rig.controller.state.value)
    }

    @Test
    fun `F6 a background during the prompt, or no background, sets no pending background at the move (AE-117)`() {
        val promptBackground = disabledRig(deferred = true)
        promptBackground.ops.requestEnable()
        promptBackground.h.settle()
        promptBackground.controller.onAppBackgrounded() // during the prompt: unlock-equivalent, discarded
        promptBackground.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        promptBackground.h.settle()
        assertEquals(AppLockState.Unlocked, promptBackground.controller.state.value)
        assertNull(promptBackground.controller.pendingBackgroundAtMillis)

        val foreground = disabledRig(deferred = true)
        foreground.ops.requestEnable()
        foreground.h.settle()
        foreground.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        foreground.h.settle()
        assertEquals(AppLockState.Unlocked, foreground.controller.state.value)
        assertNull(foreground.controller.pendingBackgroundAtMillis)
    }

    @Test
    fun `arch F3 a success that closed in the background, then a foreground and a background during the write, still records the pending background at the move`() {
        val rig = disabledRig(deferred = true)
        val h = rig.h
        rig.ops.requestEnable()
        h.settle()
        rig.controller.onAppBackgrounded() // the OEM credential UI stops the host during the prompt
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // closes in the background; the apply is queued
        assertEquals(AppLockState.Disabled, rig.controller.state.value)

        rig.controller.onAppForegrounded() // back during the write ...
        rig.controller.onAppBackgrounded() // ... and gone again before the move
        assertNull(rig.controller.pendingBackgroundAtMillis) // DISABLED records nothing by itself

        h.settle() // the write and the move land while the app is in the background

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(h.clock.elapsedRealtimeMillis(), rig.controller.pendingBackgroundAtMillis)
        h.clock.advance(AutoLockTimeout.DEFAULT.millis + 1)
        rig.controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), rig.controller.state.value)
    }

    @Test
    fun `arch F6 after onOpenOriginApplyCompleted a late onOpenOriginSettingApplied for the same id is a no-op`() {
        val (h, controller) = disabledControllerFor()
        h.emitted.clear()
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.ENABLE, RecordingSuccessHandler())
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // the handler only records; the token is open

        controller.onOpenOriginApplyCompleted(id) // the apply ended without a move: the token is dropped
        controller.onOpenOriginSettingApplied(id, true)
        controller.onOpenOriginSettingApplied(id, false)

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertTrue(h.emitted.isEmpty())
    }

    @Test
    fun `arch F6 after a throwing handler cleared the gate and the token a late onOpenOriginSettingApplied is a no-op`() {
        val (h, controller) = disabledControllerFor()
        h.emitted.clear()
        val handler = RecordingSuccessHandler().also { it.failure = IllegalStateException("synthetic") }
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.ENABLE, handler)

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // the handler throws; clearApplyGate drops gate and token

        assertEquals(listOf(id to AuthPurpose.ENABLE), handler.delivered)
        controller.onOpenOriginSettingApplied(id, true)
        controller.onOpenOriginSettingApplied(id, false)

        assertEquals(AppLockState.Disabled, controller.state.value)
        assertTrue(h.emitted.isEmpty())
    }

    @Test
    fun `F8 LOCK_ENABLED, LOCK_DISABLED and LOCK_SETTING_PERSIST_FAILED for a field are recorded once per process per cause`() {
        val rig = disabledRig()
        val h = rig.h
        fun apply(request: () -> SettingsRequestResult) {
            assertEquals(SettingsRequestResult.STARTED, request())
            h.settle()
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        }

        apply { rig.ops.requestEnable() }
        apply { rig.ops.requestDisable() }
        apply { rig.ops.requestEnable() } // the same codes again in the same process: silent
        apply { rig.ops.requestDisable() }

        assertEquals(listOf(true, false, true, false), h.repository.lockWrites)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_ENABLED, PrivacyDiagnosticCode.LOCK_DISABLED), h.emitted)

        h.repository.lockWriteResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED) }
        apply { rig.ops.requestEnable() }
        apply { rig.ops.requestEnable() }

        assertEquals(
            listOf(
                PrivacyDiagnosticCode.LOCK_ENABLED,
                PrivacyDiagnosticCode.LOCK_DISABLED,
                PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED
            ),
            h.emitted
        )
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.FIELD to AppLockSettingField.APP_LOCK_ENABLED),
            metadataOf(h, 2)
        )
        assertEquals(AppLockState.Disabled, rig.controller.state.value)
    }
}
