package com.yourname.expensetracker.ui.applock

import android.app.Application
import android.content.ComponentName
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.privacy.applock.AuthLaunch
import com.yourname.expensetracker.domain.privacy.applock.AuthPromptCopy
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCallback
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthErrorBucket
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthOutcome
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * S15 strict-review fix C1 / C2: the adapter state machine, driven through the internal prompt seam.
 *
 * The library prompt is replaced by [FakeHandle]; the captured library callback is invoked directly,
 * the way the platform would. FragmentActivity is registered with the package manager so the test is
 * identical in the debug and release variants. Covers INV-34 (one prompt in flight, one outcome per
 * attempt, late and duplicate callbacks dropped) and the wait-for-echo rule after a client cancel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricPromptAdapterStateMachineTest {

    /** Shared, ordered record of what the handle and the callback saw. */
    private val events = mutableListOf<String>()

    private inner class FakeHandle : PromptHandle {
        var authenticateCalls = 0
        var cancelCalls = 0
        var authenticateFailure: RuntimeException? = null
        var cancelFailure: RuntimeException? = null
        var onCancel: (() -> Unit)? = null

        override fun authenticate(info: BiometricPrompt.PromptInfo) {
            authenticateCalls++
            events += "handle.authenticate"
            authenticateFailure?.let { throw it }
        }

        override fun cancelAuthentication() {
            cancelCalls++
            events += "handle.cancel"
            onCancel?.invoke()
            cancelFailure?.let { throw it }
        }
    }

    private inner class RecordingCallback : DeviceAuthCallback {
        val outcomes = mutableListOf<Pair<Long, DeviceAuthOutcome>>()
        var onFirstOutcome: (() -> Unit)? = null
        override fun onOutcome(attemptId: Long, outcome: DeviceAuthOutcome) {
            events += "outcome"
            outcomes += attemptId to outcome
            onFirstOutcome?.let {
                onFirstOutcome = null
                it()
            }
        }
    }

    private inner class Rig {
        val handle = FakeHandle()
        val controller: ActivityController<FragmentActivity> = newController()
        lateinit var libraryCallback: BiometricPrompt.AuthenticationCallback
        val adapter = BiometricPromptDeviceAuthenticator(controller.get()) { callback ->
            libraryCallback = callback
            handle
        }

        fun succeed() = libraryCallback.onAuthenticationSucceeded(mockk(relaxed = true))
        fun error(code: Int) = libraryCallback.onAuthenticationError(code, "ignored")
        fun launch(id: Long, callback: DeviceAuthCallback) = adapter.authenticate(id, AuthPromptCopy.UNLOCK, callback)
    }

    private fun newController(): ActivityController<FragmentActivity> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, FragmentActivity::class.java))
        return Robolectric.buildActivity(FragmentActivity::class.java).create()
    }

    private fun advance(millis: Long) = ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS)

    /**
     * S26 fix B: adopt now requires the copy of the original attempt. Every adoption scenario in this class
     * launches with the UNLOCK device-auth copy (see Rig.launch), so the legacy two-argument calls pass it here.
     */
    private fun BiometricPromptDeviceAuthenticator.adopt(attemptId: Long, callback: DeviceAuthCallback): Boolean =
        adopt(attemptId, AuthPromptCopy.UNLOCK, callback)

    @Test
    fun inv34_secondAuthenticateWhileInFlight_returnsAlreadyInFlight_andFirstStillCompletes() {
        val rig = Rig()
        val first = RecordingCallback()
        val second = RecordingCallback()

        assertEquals(AuthLaunch.LAUNCHED, rig.launch(1L, first))
        assertEquals(AuthLaunch.ALREADY_IN_FLIGHT, rig.launch(2L, second))
        assertEquals("the second request must not reach the library", 1, rig.handle.authenticateCalls)

        rig.succeed()

        assertEquals(listOf(1L to DeviceAuthOutcome.Succeeded), first.outcomes)
        assertTrue(second.outcomes.isEmpty())
    }

    @Test
    fun inv34_duplicateTerminalCallback_isDropped() {
        val rig = Rig()
        val callback = RecordingCallback()
        rig.launch(1L, callback)

        rig.succeed()
        rig.succeed()
        rig.error(BiometricPrompt.ERROR_USER_CANCELED)

        assertEquals(listOf(1L to DeviceAuthOutcome.Succeeded), callback.outcomes)
    }

    @Test
    fun inv34_libraryTerminalWithNothingInFlight_isIgnored() {
        val rig = Rig()

        rig.succeed()
        rig.error(BiometricPrompt.ERROR_CANCELED)

        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
        val callback = RecordingCallback()
        assertEquals(AuthLaunch.LAUNCHED, rig.launch(1L, callback))
        assertTrue(callback.outcomes.isEmpty())
    }

    @Test
    fun inv34_cancelWithStaleId_isANoOp() {
        val rig = Rig()
        val callback = RecordingCallback()
        rig.launch(5L, callback)

        rig.adapter.cancel(4L)

        assertEquals(0, rig.handle.cancelCalls)
        assertTrue(callback.outcomes.isEmpty())
        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
        assertEquals(AuthLaunch.ALREADY_IN_FLIGHT, rig.launch(6L, RecordingCallback()))
        rig.succeed()
        assertEquals(listOf(5L to DeviceAuthOutcome.Succeeded), callback.outcomes)
    }

    @Test
    fun inv34_cancelWithNothingInFlight_isANoOp() {
        val rig = Rig()

        rig.adapter.cancel(1L)

        assertEquals(0, rig.handle.cancelCalls)
        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
    }

    @Test
    fun inv34_cancelCurrent_deliversExactlyOneSystemCanceled_afterTheHandleCancel() {
        val rig = Rig()
        val callback = RecordingCallback()
        rig.launch(1L, callback)
        events.clear()

        rig.adapter.cancel(1L)

        assertEquals(listOf("handle.cancel", "outcome"), events)
        assertEquals(
            listOf(1L to DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED)),
            callback.outcomes
        )
        assertEquals(1, rig.handle.cancelCalls)
    }

    @Test
    fun inv34_echoAfterCancel_isDropped_andEndsTheWait() {
        val rig = Rig()
        val callback = RecordingCallback()
        rig.launch(1L, callback)
        rig.adapter.cancel(1L)
        assertTrue(rig.adapter.isAwaitingLibraryTerminal)

        rig.error(BiometricPrompt.ERROR_CANCELED)

        assertEquals("only the adapter's own cancel outcome", 1, callback.outcomes.size)
        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
    }

    @Test
    fun inv34_synchronousEchoInsideHandleCancel_isDropped_andDoesNotBlockTheNextLaunch() {
        val rig = Rig()
        val callback = RecordingCallback()
        rig.launch(1L, callback)
        rig.handle.onCancel = { rig.error(BiometricPrompt.ERROR_CANCELED) }

        rig.adapter.cancel(1L)

        assertEquals(1, callback.outcomes.size)
        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
        assertEquals(AuthLaunch.LAUNCHED, rig.launch(2L, RecordingCallback()))
    }

    @Test
    fun c1_lateEchoOfACancelledAttempt_isNeverCreditedToTheNextAttempt() {
        val rig = Rig()
        val older = RecordingCallback()
        val newer = RecordingCallback()
        rig.launch(1L, older)
        rig.adapter.cancel(1L)

        // The controller retries at once: refused, so there is no newer attempt for the echo to hit.
        assertEquals(AuthLaunch.AWAITING_PRIOR_TERMINAL, rig.launch(2L, newer))
        rig.error(BiometricPrompt.ERROR_CANCELED)
        assertEquals(AuthLaunch.LAUNCHED, rig.launch(2L, newer))

        assertTrue("the echo must not complete the newer attempt", newer.outcomes.isEmpty())
        rig.succeed()
        assertEquals(listOf(2L to DeviceAuthOutcome.Succeeded), newer.outcomes)
        assertEquals(1, older.outcomes.size)
    }

    @Test
    fun c1_awaitingFlag_blocksLaunch_andClearsOnTheNextSuccessCallbackToo() {
        val rig = Rig()
        rig.launch(1L, RecordingCallback())
        rig.adapter.cancel(1L)
        assertEquals(AuthLaunch.AWAITING_PRIOR_TERMINAL, rig.launch(2L, RecordingCallback()))
        assertEquals("a refused launch must not reach the library", 1, rig.handle.authenticateCalls)

        rig.succeed()

        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
        assertEquals(AuthLaunch.LAUNCHED, rig.launch(3L, RecordingCallback()))
    }

    @Test
    fun c1_awaitingFlag_clearsOnTimeout_whenTheLibraryNeverEchoes() {
        val rig = Rig()
        rig.launch(1L, RecordingCallback())
        rig.adapter.cancel(1L)

        advance(AWAITING_TERMINAL_TIMEOUT_MS - 100)
        assertTrue("still waiting before the timeout", rig.adapter.isAwaitingLibraryTerminal)
        assertEquals(AuthLaunch.AWAITING_PRIOR_TERMINAL, rig.launch(2L, RecordingCallback()))

        advance(200)

        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
        assertEquals(AuthLaunch.LAUNCHED, rig.launch(2L, RecordingCallback()))
    }

    @Test
    fun c1_awaitingFlag_clearsOnTheHostsNextOnStart() {
        val rig = Rig()
        rig.launch(1L, RecordingCallback())
        rig.adapter.cancel(1L)
        assertTrue(rig.adapter.isAwaitingLibraryTerminal)

        rig.controller.start()

        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
        assertEquals(AuthLaunch.LAUNCHED, rig.launch(2L, RecordingCallback()))
    }

    @Test
    fun c1_staleTimeout_doesNotClearALaterWait() {
        val rig = Rig()
        rig.launch(1L, RecordingCallback())
        rig.adapter.cancel(1L)
        advance(1000)
        rig.error(BiometricPrompt.ERROR_CANCELED)
        rig.launch(2L, RecordingCallback())
        rig.adapter.cancel(2L)

        // 600 ms after the second cancel is 1600 ms after the first: the first timeout is gone.
        advance(600)

        assertTrue(rig.adapter.isAwaitingLibraryTerminal)
    }

    @Test
    fun inv34_cancelWhoseHandleThrows_stillDeliversExactlyOnce() {
        val rig = Rig()
        val callback = RecordingCallback()
        rig.launch(1L, callback)
        rig.handle.cancelFailure = IllegalStateException("synthetic")

        rig.adapter.cancel(1L)

        assertEquals(
            listOf(1L to DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED)),
            callback.outcomes
        )
    }

    @Test
    fun cancellationFromTheHandle_isRethrown_andTheOutcomeIsStillDeliveredOnce() {
        val rig = Rig()
        val callback = RecordingCallback()
        rig.launch(1L, callback)
        rig.handle.cancelFailure = CancellationException("synthetic")

        assertThrows(CancellationException::class.java) { rig.adapter.cancel(1L) }

        assertEquals(1, callback.outcomes.size)
    }

    @Test
    fun launchThatThrows_returnsPromptUnavailable_deliversNothing_andClearsTheFlight() {
        val rig = Rig()
        val callback = RecordingCallback()
        rig.handle.authenticateFailure = IllegalStateException("synthetic")

        assertEquals(AuthLaunch.PROMPT_UNAVAILABLE, rig.launch(1L, callback))

        assertTrue(callback.outcomes.isEmpty())
        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
        rig.handle.authenticateFailure = null
        assertEquals(AuthLaunch.LAUNCHED, rig.launch(2L, callback))
    }

    @Test
    fun launchThatThrowsCancellation_isRethrown_andClearsTheFlight() {
        val rig = Rig()
        rig.handle.authenticateFailure = CancellationException("synthetic")

        assertThrows(CancellationException::class.java) { rig.launch(1L, RecordingCallback()) }

        rig.handle.authenticateFailure = null
        assertEquals(AuthLaunch.LAUNCHED, rig.launch(2L, RecordingCallback()))
    }

    @Test
    fun promptFactoryReturningNull_orThrowing_returnsPromptUnavailable() {
        val nullAdapter = BiometricPromptDeviceAuthenticator(newController().get()) { null }
        assertFalse(nullAdapter.isPromptBuilt)
        assertEquals(AuthLaunch.PROMPT_UNAVAILABLE, nullAdapter.authenticate(1L, AuthPromptCopy.UNLOCK) { _, _ -> })

        val throwing = BiometricPromptDeviceAuthenticator(newController().get()) { throw IllegalStateException("synthetic") }
        assertFalse(throwing.isPromptBuilt)
        assertEquals(AuthLaunch.PROMPT_UNAVAILABLE, throwing.authenticate(1L, AuthPromptCopy.UNLOCK) { _, _ -> })
    }

    @Test
    fun inv34_authenticateFromInsideTheOutcomeCallback_works() {
        val rig = Rig()
        val second = RecordingCallback()
        var nested: AuthLaunch? = null
        val first = RecordingCallback().apply { onFirstOutcome = { nested = rig.launch(2L, second) } }
        rig.launch(1L, first)

        rig.succeed()

        assertEquals(AuthLaunch.LAUNCHED, nested)
        rig.error(BiometricPrompt.ERROR_USER_CANCELED)
        assertEquals(listOf(2L to DeviceAuthOutcome.Cancelled), second.outcomes)
        assertEquals(1, first.outcomes.size)
    }

    @Test
    fun c1_authenticateFromInsideTheCancelOutcome_isRefusedWhileAwaiting() {
        val rig = Rig()
        var nested: AuthLaunch? = null
        val first = RecordingCallback().apply { onFirstOutcome = { nested = rig.launch(2L, RecordingCallback()) } }
        rig.launch(1L, first)

        rig.adapter.cancel(1L)

        assertEquals(AuthLaunch.AWAITING_PRIOR_TERMINAL, nested)
    }

    @Test
    fun inv34_succeeded_onlyFromOnAuthenticationSucceeded() {
        val errorCodes = listOf(
            BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
            BiometricPrompt.ERROR_TIMEOUT,
            BiometricPrompt.ERROR_NO_SPACE,
            BiometricPrompt.ERROR_CANCELED,
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_VENDOR,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            15, // SECURITY_UPDATE_REQUIRED: not a named constant in androidx.biometric 1.1.0
            0,
            -1,
            9999
        )
        for (code in errorCodes) {
            val rig = Rig()
            val callback = RecordingCallback()
            rig.launch(1L, callback)

            rig.error(code)

            assertEquals("code $code", 1, callback.outcomes.size)
            assertNotEquals("code $code", DeviceAuthOutcome.Succeeded, callback.outcomes.single().second)
        }

        val rig = Rig()
        val callback = RecordingCallback()
        rig.launch(1L, callback)
        rig.succeed()
        assertEquals(DeviceAuthOutcome.Succeeded, callback.outcomes.single().second)
    }

    // ---- G1c / AE-118: a finishing host cancels the library prompt silently ---------------------------

    /** Brings the host to RESUMED so that pause and stop dispatch their lifecycle events. */
    private fun Rig.resumed(): Rig {
        controller.start().resume()
        return this
    }

    /** Makes `isChangingConfigurations` true on the framework activity, the way a rotation relaunch does. */
    private fun markConfigurationChange(activity: FragmentActivity) {
        ReflectionHelpers.setField(activity, "mChangingConfigurations", true)
    }

    @Test
    fun g1c_finishingOnPause_cancelsTheLibraryPromptOnce_withNoCallback_clearsTheFlight_andSetsAwaiting() {
        val rig = Rig().resumed()
        val callback = RecordingCallback()
        rig.launch(1L, callback)
        events.clear()

        rig.controller.get().finish()
        rig.controller.pause()

        assertEquals("only the library cancel, never an outcome", listOf("handle.cancel"), events)
        assertEquals(1, rig.handle.cancelCalls)
        assertTrue("a lost host ends silently (Abandoned)", callback.outcomes.isEmpty())
        assertTrue(rig.adapter.isAwaitingLibraryTerminal)
        assertEquals(
            "the flight is cleared: the wait, not an attempt in flight, refuses the launch",
            AuthLaunch.AWAITING_PRIOR_TERMINAL,
            rig.launch(2L, RecordingCallback())
        )
        rig.adapter.cancel(1L)
        assertEquals("a client cancel of the abandoned attempt is a no-op", 1, rig.handle.cancelCalls)
        assertTrue(callback.outcomes.isEmpty())
    }

    @Test
    fun g1c_aSecondSignalAfterTheAbandon_doesNotCancelAgain() {
        val rig = Rig().resumed()
        val callback = RecordingCallback()
        rig.launch(1L, callback)
        rig.controller.get().finish()
        rig.controller.pause()
        assertEquals(1, rig.handle.cancelCalls)

        rig.controller.stop() // finishing ON_STOP
        rig.controller.destroy() // ON_DESTROY, not a configuration change
        rig.adapter.abandonForHostLoss()

        assertEquals("pause, stop, destroy and a direct call together cancel once", 1, rig.handle.cancelCalls)
        assertTrue(callback.outcomes.isEmpty())
    }

    @Test
    fun g1c_finishingOnStop_cancelsOnce_whenThePauseWasNotYetFinishing() {
        val rig = Rig().resumed()
        val callback = RecordingCallback()
        rig.launch(1L, callback)
        rig.controller.pause()
        assertEquals("control: a pause that is not finishing never cancels", 0, rig.handle.cancelCalls)

        rig.controller.get().finish()
        rig.controller.stop()

        assertEquals(1, rig.handle.cancelCalls)
        assertTrue(callback.outcomes.isEmpty())
        assertTrue(rig.adapter.isAwaitingLibraryTerminal)
    }

    @Test
    fun g1c_finishingOnDestroyOnly_cancelsOnce_andTheWaitSurvivesTheDestroyHandler() {
        val rig = Rig()
        val callback = RecordingCallback()
        rig.launch(1L, callback)

        rig.controller.get().finish()
        rig.controller.destroy()

        assertEquals(1, rig.handle.cancelCalls)
        assertTrue(callback.outcomes.isEmpty())
        assertTrue("the echo of the silent cancel is still owed", rig.adapter.isAwaitingLibraryTerminal)
        rig.succeed()
        assertTrue("the echo is dropped, never delivered as a success", callback.outcomes.isEmpty())
        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
    }

    @Test
    fun g1c_destroyWithoutFinishAndNotAConfigurationChange_alsoAbandons() {
        val rig = Rig()
        val callback = RecordingCallback()
        rig.launch(1L, callback)

        rig.controller.destroy()

        assertEquals(1, rig.handle.cancelCalls)
        assertTrue(callback.outcomes.isEmpty())
    }

    @Test
    fun g1c_configurationChange_neverCancels_andTheSuccessStillCommits() {
        val rig = Rig().resumed()
        val callback = RecordingCallback()
        rig.launch(1L, callback)
        markConfigurationChange(rig.controller.get())

        rig.controller.pause().stop().destroy()

        assertEquals("a rotation must not cancel the prompt", 0, rig.handle.cancelCalls)
        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
        assertTrue(callback.outcomes.isEmpty())
        rig.succeed()
        assertEquals(listOf(1L to DeviceAuthOutcome.Succeeded), callback.outcomes)
    }

    @Test
    fun g1c_configurationChange_neverCancels_forAnAdoptedAttempt() {
        val rig = Rig().resumed()
        val callback = RecordingCallback()
        assertTrue(rig.adapter.adopt(5L, callback))
        markConfigurationChange(rig.controller.get())

        rig.controller.pause().stop().destroy()

        assertEquals(0, rig.handle.cancelCalls)
        rig.succeed()
        assertEquals(listOf(5L to DeviceAuthOutcome.Succeeded), callback.outcomes)
    }

    @Test
    fun g1c_finishingTogetherWithAConfigurationChange_neverCancels_theGuardIsBoth() {
        val rig = Rig().resumed()
        val callback = RecordingCallback()
        rig.launch(1L, callback)
        rig.controller.get().finish()
        markConfigurationChange(rig.controller.get())

        rig.controller.pause().stop().destroy()

        assertEquals("isFinishing alone is not enough: the configuration-change guard wins", 0, rig.handle.cancelCalls)
        assertTrue(callback.outcomes.isEmpty())
        rig.succeed()
        assertEquals(listOf(1L to DeviceAuthOutcome.Succeeded), callback.outcomes)
    }

    @Test
    fun g1c_stoppedButNotFinishing_neverCancels_andTheAttemptStaysOpen() {
        val rig = Rig().resumed()
        val callback = RecordingCallback()
        rig.launch(1L, callback)

        rig.controller.pause().stop() // OEM credential screen or Home: not finishing

        assertEquals(0, rig.handle.cancelCalls)
        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
        assertEquals(AuthLaunch.ALREADY_IN_FLIGHT, rig.launch(2L, RecordingCallback()))
        rig.succeed()
        assertEquals(listOf(1L to DeviceAuthOutcome.Succeeded), callback.outcomes)
    }

    @Test
    fun g1c_aLibraryRuntimeExceptionOnTheAbandonCancel_isSwallowed_andTheAttemptIsStillClosedSilently() {
        val rig = Rig().resumed()
        val callback = RecordingCallback()
        rig.launch(1L, callback)
        rig.handle.cancelFailure = IllegalStateException("synthetic")

        rig.controller.get().finish()
        rig.controller.pause()

        assertEquals(1, rig.handle.cancelCalls)
        assertTrue(callback.outcomes.isEmpty())
        assertTrue(rig.adapter.isAwaitingLibraryTerminal)
    }

    @Test
    fun g1c_aLibraryEchoAfterTheAbandon_isDropped_andNeverCreditedToTheNextAdoptedAttempt() {
        val rig = Rig().resumed()
        val abandoned = RecordingCallback()
        rig.launch(1L, abandoned)
        rig.controller.get().finish()
        rig.controller.pause()

        rig.error(BiometricPrompt.ERROR_CANCELED) // the echo of the silent cancel

        assertTrue("no callback for the abandoned attempt", abandoned.outcomes.isEmpty())
        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
        val next = RecordingCallback()
        assertTrue("control: the adapter is idle again", rig.adapter.adopt(2L, next))
        assertTrue("the echo must not complete the next attempt", next.outcomes.isEmpty())
        rig.succeed()
        assertEquals(listOf(2L to DeviceAuthOutcome.Succeeded), next.outcomes)
        assertTrue(abandoned.outcomes.isEmpty())
    }

    @Test
    fun g1c_aSuccessAfterTheAbandon_isDropped_itCanNeverUnlock() {
        val rig = Rig().resumed()
        val callback = RecordingCallback()
        rig.launch(1L, callback)
        rig.controller.get().finish()
        rig.controller.pause()

        rig.succeed()

        assertTrue(callback.outcomes.isEmpty())
    }

    @Test
    fun g1c_aSynchronousEchoInsideTheAbandonCancel_isDropped() {
        val rig = Rig().resumed()
        val callback = RecordingCallback()
        rig.launch(1L, callback)
        rig.handle.onCancel = { rig.error(BiometricPrompt.ERROR_CANCELED) }

        rig.controller.get().finish()
        rig.controller.pause()

        assertTrue("the wait is set before the library cancel, so a synchronous echo is dropped", callback.outcomes.isEmpty())
        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
    }

    @Test
    fun g1c_abandonWithNothingInFlight_isANoOp() {
        val rig = Rig().resumed()

        rig.adapter.abandonForHostLoss()
        rig.controller.get().finish()
        rig.controller.pause().stop().destroy()

        assertEquals(0, rig.handle.cancelCalls)
        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
    }

    // ---- S20-a: adopt (rotation hand-over of the open attempt) ---------------------------------------

    @Test
    fun adopt_idle_returnsTrue_setsInFlight_andNeverLaunchesOrCallsBack() {
        val rig = Rig()
        val callback = RecordingCallback()

        assertTrue(rig.adapter.adopt(5L, callback))

        assertEquals("adopt never launches", 0, rig.handle.authenticateCalls)
        assertEquals(0, rig.handle.cancelCalls)
        assertTrue("adopt never reports an outcome", callback.outcomes.isEmpty())
        assertEquals(
            "the adopted attempt is in flight: a launch is refused",
            AuthLaunch.ALREADY_IN_FLIGHT,
            rig.launch(6L, RecordingCallback())
        )
        assertFalse(rig.adapter.isAwaitingLibraryTerminal)
    }

    @Test
    fun adopt_whileAnAttemptIsInFlight_returnsFalse_andChangesNothing() {
        val rig = Rig()
        val own = RecordingCallback()
        val adopted = RecordingCallback()
        rig.launch(1L, own)

        assertFalse(rig.adapter.adopt(2L, adopted))

        rig.succeed()
        assertEquals(listOf(1L to DeviceAuthOutcome.Succeeded), own.outcomes)
        assertTrue("the refused adoption never gets an outcome", adopted.outcomes.isEmpty())
    }

    @Test
    fun adopt_whileAwaitingAClientCancelTerminal_returnsFalse_andIsPossibleOnceTheEchoArrived() {
        val rig = Rig()
        val cancelledAttempt = RecordingCallback()
        rig.launch(1L, cancelledAttempt)
        rig.adapter.cancel(1L)
        assertTrue(rig.adapter.isAwaitingLibraryTerminal)
        val adopted = RecordingCallback()

        assertFalse(rig.adapter.adopt(2L, adopted))
        assertTrue("a refused adoption leaves the wait alone", rig.adapter.isAwaitingLibraryTerminal)
        rig.error(BiometricPrompt.ERROR_CANCELED) // the echo
        assertTrue("control: after the echo the adapter is idle again", rig.adapter.adopt(2L, adopted))

        assertTrue(adopted.outcomes.isEmpty())
        rig.succeed()
        assertEquals(listOf(2L to DeviceAuthOutcome.Succeeded), adopted.outcomes)
        assertEquals("the cancelled attempt got only its own cancel outcome", 1, cancelledAttempt.outcomes.size)
    }

    @Test
    fun adopt_theTerminalOfAnOldAttempt_isNeverCreditedToTheAdoptedOne() {
        val rig = Rig()
        val old = RecordingCallback()
        val adopted = RecordingCallback()
        assertTrue(rig.adapter.adopt(5L, old))
        rig.adapter.cancel(5L) // the controller closes the old attempt; the echo is still owed
        assertFalse("not adoptable while the old echo is owed", rig.adapter.adopt(6L, adopted))

        rig.error(BiometricPrompt.ERROR_CANCELED) // the late terminal of the old attempt
        assertTrue(rig.adapter.adopt(6L, adopted))

        assertTrue("the late terminal must not complete the adopted attempt", adopted.outcomes.isEmpty())
        assertEquals(
            listOf(5L to DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED)),
            old.outcomes
        )
    }

    @Test
    fun adopt_theAdoptedAttemptsTerminal_isDeliveredOnce_withTheAdoptedIdAndCallback() {
        val rig = Rig()
        val callback = RecordingCallback()
        rig.adapter.adopt(5L, callback)

        rig.succeed()
        rig.succeed()
        rig.error(BiometricPrompt.ERROR_USER_CANCELED)

        assertEquals(listOf(5L to DeviceAuthOutcome.Succeeded), callback.outcomes)
        assertEquals("control: the adapter is idle again", AuthLaunch.LAUNCHED, rig.launch(6L, RecordingCallback()))
    }

    @Test
    fun adopt_aLibraryErrorIsMappedForTheAdoptedAttempt_exactlyLikeALaunchedOne() {
        val rig = Rig()
        val callback = RecordingCallback()
        rig.adapter.adopt(5L, callback)

        rig.error(BiometricPrompt.ERROR_USER_CANCELED)

        assertEquals(listOf(5L to DeviceAuthOutcome.Cancelled), callback.outcomes)
    }

    @Test
    fun adopt_cancelOfTheAdoptedAttempt_deliversExactlyOneSystemCanceled_afterTheHandleCancel() {
        val rig = Rig()
        val callback = RecordingCallback()
        rig.adapter.adopt(5L, callback)
        events.clear()

        rig.adapter.cancel(5L)
        rig.adapter.cancel(5L)

        assertEquals(listOf("handle.cancel", "outcome"), events)
        assertEquals(
            listOf(5L to DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED)),
            callback.outcomes
        )
    }

    @Test
    fun adopt_aStaleCancelIdIsIgnored_forAnAdoptedAttempt() {
        val rig = Rig()
        val callback = RecordingCallback()
        rig.adapter.adopt(5L, callback)

        rig.adapter.cancel(4L)

        assertEquals(0, rig.handle.cancelCalls)
        assertTrue(callback.outcomes.isEmpty())
        rig.succeed()
        assertEquals(listOf(5L to DeviceAuthOutcome.Succeeded), callback.outcomes)
    }

    @Test
    fun adopt_whenThePromptWasNotBuilt_returnsFalse_andLeavesTheAdapterIdle() {
        val nullAdapter = BiometricPromptDeviceAuthenticator(newController().get()) { null }
        val throwing = BiometricPromptDeviceAuthenticator(newController().get()) { throw IllegalStateException("synthetic") }
        val callback = RecordingCallback()

        assertFalse(nullAdapter.adopt(5L, callback))
        assertFalse(throwing.adopt(5L, callback))

        assertTrue(callback.outcomes.isEmpty())
        assertEquals(
            "still idle: the request reports the missing prompt, not an attempt in flight",
            AuthLaunch.PROMPT_UNAVAILABLE,
            nullAdapter.authenticate(6L, AuthPromptCopy.UNLOCK) { _, _ -> }
        )
    }

    @Test
    @Config(sdk = [29])
    fun adopt_belowApi30_returnsFalse_andLeavesTheAdapterIdle() {
        val rig = Rig()
        val callback = RecordingCallback()

        assertFalse(rig.adapter.adopt(5L, callback))

        assertTrue(callback.outcomes.isEmpty())
        rig.succeed()
        assertTrue("nothing was adopted, so the terminal has no attempt", callback.outcomes.isEmpty())
        assertEquals(
            "below API 30 the adapter never launches",
            AuthLaunch.HOST_UNAVAILABLE,
            rig.launch(6L, RecordingCallback())
        )
    }

    @Test
    @Config(sdk = [30])
    fun adopt_atApi30_isAccepted_theBoundary() {
        val rig = Rig()

        assertTrue(rig.adapter.adopt(5L, RecordingCallback()))
    }
}
