package com.yourname.expensetracker.ui.applock

import android.app.Application
import android.content.ComponentName
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AuthLaunch
import com.yourname.expensetracker.domain.privacy.applock.AuthPromptCopy
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCallback
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthErrorBucket
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthOutcome
import com.yourname.expensetracker.domain.privacy.applock.pin.BiometricKeyResult
import com.yourname.expensetracker.domain.privacy.applock.pin.BiometricPinKeyManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import javax.crypto.Cipher

/**
 * A FragmentActivity whose [isChangingConfigurations] is driven by the test, so a rotation and a real finish
 * are two deterministic scenarios instead of depending on Robolectric's relaunch machinery.
 */
class AppLockPromptHostTestActivity : FragmentActivity() {
    var simulateConfigurationChange: Boolean = false

    override fun isChangingConfigurations(): Boolean = simulateConfigurationChange
}

/**
 * S20-a: the per-Activity prompt host with the real relay, the real prompt adapter
 * ([BiometricPromptDeviceAuthenticator], its library prompt replaced by a fake handle) and a mock controller.
 *
 * Covers AE-118 (a finish with an open attempt reports the host lost exactly once, T35), the rotation adoption
 * rule (no loss report, the new adapter accepts the matching success), exactly-once emission, the constant
 * copy pass-through, a double attach, and a destroyed activity.
 *
 * Activity recreation is driven two ways: the deterministic subclass above (primary) and one test through
 * Robolectric's `recreate()` with a plain FragmentActivity. The real androidx.biometric fragment re-attachment
 * and the system prompt surviving a rotation are device cases (G1b), not covered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockPromptHostTest {

    private class FakeHandle : PromptHandle {
        val infos = mutableListOf<BiometricPrompt.PromptInfo>()
        var cancelCalls = 0
        var cryptoLaunches = 0
        override fun authenticate(info: BiometricPrompt.PromptInfo) {
            infos += info
        }

        // S26: the PIN-mode fingerprint prompt is launched with a CryptoObject.
        override fun authenticate(info: BiometricPrompt.PromptInfo, crypto: BiometricPrompt.CryptoObject) {
            infos += info
            cryptoLaunches++
        }

        override fun cancelAuthentication() {
            cancelCalls++
        }
    }

    private class RecordingCallback : DeviceAuthCallback {
        val received = mutableListOf<Pair<Long, DeviceAuthOutcome>>()
        override fun onOutcome(attemptId: Long, outcome: DeviceAuthOutcome) {
            received += attemptId to outcome
        }
    }

    // The relay's orphan check is queued here, never run by Robolectric's main looper: the activity controller
    // idles the looper after each lifecycle call, which would run the check between the destroy and the
    // attach of a simulated rotation (in production both happen inside one main-thread message).
    private val postedToMain = mutableListOf<Runnable>()
    private val relay = AppLockAuthenticatorRelay({ true }, { postedToMain += it })
    private val controller = mockk<AppLockController>(relaxed = true)

    /** Everything the adapter factory built, in attach order. */
    private val handles = mutableListOf<FakeHandle>()
    private val libraryCallbacks = mutableListOf<BiometricPrompt.AuthenticationCallback>()
    private var buildPromptsForNextAdapters = true

    private val factory: (FragmentActivity) -> BiometricPromptDeviceAuthenticator = { activity ->
        val handle = FakeHandle()
        handles += handle
        BiometricPromptDeviceAuthenticator(activity) { callback ->
            libraryCallbacks += callback
            if (buildPromptsForNextAdapters) handle else null
        }
    }

    private fun newHost() = AppLockPromptHost(relay, controller, factory)

    private fun registerActivityClasses() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val packageManager = shadowOf(app.packageManager)
        packageManager.addActivityIfNotPresent(ComponentName(app, AppLockPromptHostTestActivity::class.java))
        packageManager.addActivityIfNotPresent(ComponentName(app, FragmentActivity::class.java))
    }

    private fun newController(): ActivityController<AppLockPromptHostTestActivity> {
        registerActivityClasses()
        return Robolectric.buildActivity(AppLockPromptHostTestActivity::class.java).create().start().resume()
    }

    private fun finish(activityController: ActivityController<AppLockPromptHostTestActivity>) {
        activityController.get().simulateConfigurationChange = false
        activityController.get().finish()
        activityController.pause().stop().destroy()
    }

    private fun rotateAway(activityController: ActivityController<AppLockPromptHostTestActivity>) {
        activityController.get().simulateConfigurationChange = true
        activityController.pause().stop().destroy()
    }

    private fun succeedThrough(adapterIndex: Int) =
        libraryCallbacks[adapterIndex].onAuthenticationSucceeded(mockk(relaxed = true))

    // ---- attach ------------------------------------------------------------------------------------------

    @Test
    fun attach_registersTheAdapterWithTheRelay_soAPromptCanBeLaunched() {
        val ctrl = newController()
        val host = newHost()
        assertFalse(host.isAttached)
        assertEquals("nothing registered yet", AuthLaunch.HOST_UNAVAILABLE, relay.authenticate(1L, AuthPromptCopy.UNLOCK, RecordingCallback()))

        host.attach(ctrl.get())

        assertTrue(host.isAttached)
        assertTrue(relay.hasRegistration)
        assertEquals(AuthLaunch.LAUNCHED, relay.authenticate(1L, AuthPromptCopy.UNLOCK, RecordingCallback()))
        assertEquals(1, handles.single().infos.size)
        assertEquals(1L, relay.openAttemptId)
    }

    @Test
    fun attach_twice_throws_andLeavesTheFirstAttachmentIntact() {
        val ctrl = newController()
        val other = newController()
        val host = newHost()
        host.attach(ctrl.get())

        assertThrows(IllegalStateException::class.java) { host.attach(other.get()) }

        assertEquals("the adapter was built once", 1, handles.size)
        assertTrue(host.isAttached)
        assertTrue(relay.hasRegistration)
        assertEquals(AuthLaunch.LAUNCHED, relay.authenticate(1L, AuthPromptCopy.UNLOCK, RecordingCallback()))
        assertEquals(1, handles.single().infos.size)
    }

    @Test
    fun attach_toADestroyedActivity_getsNoRegistration() {
        val ctrl = newController()
        ctrl.pause().stop().destroy()
        val host = newHost()

        host.attach(ctrl.get())

        assertFalse(host.isAttached)
        assertFalse(relay.hasRegistration)
        assertTrue("no adapter is built for a destroyed activity", handles.isEmpty())
        assertEquals(AuthLaunch.HOST_UNAVAILABLE, relay.authenticate(1L, AuthPromptCopy.UNLOCK, RecordingCallback()))
        verify(exactly = 0) { controller.onPromptHostLost(any()) }
    }

    // ---- AE-118: a finish with an open attempt reports the host lost exactly once ------------------------

    @Test
    fun ae118_finishWithAnOpenAttempt_reportsTheHostLostExactlyOnce() {
        val ctrl = newController()
        val host = newHost()
        host.attach(ctrl.get())
        val callback = RecordingCallback()
        assertEquals(AuthLaunch.LAUNCHED, relay.authenticate(7L, AuthPromptCopy.UNLOCK, callback))

        finish(ctrl)

        verify(exactly = 1) { controller.onPromptHostLost(7L) }
        verify(exactly = 1) { controller.onPromptHostLost(any()) }
        assertNull(relay.openAttemptId)
        assertFalse(relay.hasRegistration)
        assertFalse(host.isAttached)
        assertTrue("no outcome is owed to the callback", callback.received.isEmpty())
    }

    @Test
    fun g1c_ae118_aFinishingDestroy_reportsTheLossOnceWithTheRelayId_andCancelsTheLibraryPromptOnce() {
        val ctrl = newController()
        newHost().attach(ctrl.get())
        val callback = RecordingCallback()
        relay.authenticate(7L, AuthPromptCopy.CHANGE, callback)

        finish(ctrl)

        verify(exactly = 1) { controller.onPromptHostLost(7L) }
        verify(exactly = 1) { controller.onPromptHostLost(any()) }
        assertEquals("the orphaned system prompt is cancelled", 1, handles.single().cancelCalls)
        assertTrue("a lost host ends silently: no outcome for the attempt", callback.received.isEmpty())
        assertNull(relay.openAttemptId)
    }

    @Test
    fun g1c_ae118_aConfigurationChangeDestroy_reportsNoLoss_andDoesNotCancelThePrompt() {
        val ctrl = newController()
        newHost().attach(ctrl.get())
        val callback = RecordingCallback()
        relay.authenticate(7L, AuthPromptCopy.CHANGE, callback)

        rotateAway(ctrl)

        verify(exactly = 0) { controller.onPromptHostLost(any()) }
        assertEquals("a rotation must not cancel the prompt", 0, handles.single().cancelCalls)
        assertEquals("the attempt survives for adoption", 7L, relay.openAttemptId)
        assertTrue(callback.received.isEmpty())
    }

    @Test
    fun ae118_aLateLibraryTerminalAfterTheLoss_isDropped_andReportsNothingMore() {
        val ctrl = newController()
        val host = newHost()
        host.attach(ctrl.get())
        val callback = RecordingCallback()
        relay.authenticate(7L, AuthPromptCopy.UNLOCK, callback)
        finish(ctrl)

        succeedThrough(0)
        succeedThrough(0)

        assertTrue("a late success after the loss must not unlock", callback.received.isEmpty())
        verify(exactly = 1) { controller.onPromptHostLost(any()) }
    }

    @Test
    fun ae118_control_finishWithNoOpenAttempt_reportsNothing() {
        val ctrl = newController()
        val host = newHost()
        host.attach(ctrl.get())

        finish(ctrl)

        verify(exactly = 0) { controller.onPromptHostLost(any()) }
        assertFalse(relay.hasRegistration)
    }

    @Test
    fun ae118_control_finishAfterTheOutcomeWasDelivered_reportsNothing() {
        val ctrl = newController()
        val host = newHost()
        host.attach(ctrl.get())
        val callback = RecordingCallback()
        relay.authenticate(7L, AuthPromptCopy.UNLOCK, callback)
        succeedThrough(0)
        assertEquals(listOf(7L to DeviceAuthOutcome.Succeeded), callback.received)

        finish(ctrl)

        verify(exactly = 0) { controller.onPromptHostLost(any()) }
    }

    @Test
    fun ae118_aHostThatNeverHeldTheAttempt_doesNotReportIt_butTheHoldingHostDoes() {
        val first = newController()
        val second = newController()
        newHost().attach(first.get())
        relay.authenticate(7L, AuthPromptCopy.UNLOCK, RecordingCallback())
        newHost().attach(second.get()) // a newer activity created while the first is alive (CLEAR_TASK style)
        assertEquals("a live holder is not adopted from", 0, handles[1].infos.size)

        finish(first)
        finish(second)

        verify(exactly = 1) { controller.onPromptHostLost(7L) }
        verify(exactly = 1) { controller.onPromptHostLost(any()) }
    }

    // ---- rotation: no loss report, adoption, the matching success is accepted ----------------------------

    @Test
    fun rotation_reportsNoLoss_theNewAdapterAdoptsTheAttempt_andAMatchingSuccessIsAccepted() {
        val before = newController()
        newHost().attach(before.get())
        val callback = RecordingCallback()
        relay.authenticate(7L, AuthPromptCopy.UNLOCK, callback)

        rotateAway(before)
        verify(exactly = 0) { controller.onPromptHostLost(any()) }
        assertEquals("the attempt survives the rotation", 7L, relay.openAttemptId)
        assertTrue(relay.isOpenAttemptOrphaned)
        val after = newController()
        val hostAfter = newHost()
        hostAfter.attach(after.get())

        assertFalse("adopted by the new host's adapter", relay.isOpenAttemptOrphaned)
        assertEquals(7L, relay.openAttemptId)
        assertEquals("adopt never launches a second prompt", 0, handles[1].infos.size)
        succeedThrough(1)

        assertEquals(listOf(7L to DeviceAuthOutcome.Succeeded), callback.received)
        assertNull(relay.openAttemptId)
        verify(exactly = 0) { controller.onPromptHostLost(any()) }
    }

    @Test
    fun rotation_aSuccessThroughTheOldAdapterIsDropped_andOnlyTheNewAdapterCanAcceptIt() {
        val before = newController()
        newHost().attach(before.get())
        val callback = RecordingCallback()
        relay.authenticate(7L, AuthPromptCopy.UNLOCK, callback)
        rotateAway(before)
        val after = newController()
        newHost().attach(after.get())

        succeedThrough(0) // the old adapter's library callback
        assertTrue("an adapter that lost the attempt must not unlock", callback.received.isEmpty())
        assertEquals(7L, relay.openAttemptId)
        succeedThrough(1)

        assertEquals(listOf(7L to DeviceAuthOutcome.Succeeded), callback.received)
    }

    @Test
    fun rotation_thenARealFinishOfTheNewHost_reportsTheAdoptedAttemptLostExactlyOnce() {
        val before = newController()
        newHost().attach(before.get())
        relay.authenticate(7L, AuthPromptCopy.UNLOCK, RecordingCallback())
        rotateAway(before)
        val after = newController()
        newHost().attach(after.get())

        finish(after)

        verify(exactly = 1) { controller.onPromptHostLost(7L) }
        verify(exactly = 1) { controller.onPromptHostLost(any()) }
        assertNull(relay.openAttemptId)
    }

    @Test
    fun rotation_twoRotationsInARow_keepTheAttemptAndStillAcceptTheSuccess() {
        val first = newController()
        newHost().attach(first.get())
        val callback = RecordingCallback()
        relay.authenticate(7L, AuthPromptCopy.UNLOCK, callback)
        rotateAway(first)
        val second = newController()
        newHost().attach(second.get())
        rotateAway(second)
        val third = newController()
        newHost().attach(third.get())

        succeedThrough(2)

        assertEquals(listOf(7L to DeviceAuthOutcome.Succeeded), callback.received)
        verify(exactly = 0) { controller.onPromptHostLost(any()) }
    }

    @Test
    fun rotation_aRefusedAdoption_closesTheAttemptWithOneSystemCanceled_andReportsNoLoss() {
        val before = newController()
        newHost().attach(before.get())
        val callback = RecordingCallback()
        relay.authenticate(7L, AuthPromptCopy.UNLOCK, callback)
        rotateAway(before)
        buildPromptsForNextAdapters = false // the new adapter has no prompt, so adopt returns false
        val after = newController()

        newHost().attach(after.get())

        assertEquals(
            listOf(7L to DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED)),
            callback.received
        )
        assertNull(relay.openAttemptId)
        verify(exactly = 0) { controller.onPromptHostLost(any()) }
    }

    @Test
    fun rotation_throughRobolectricRecreate_reportsNoLoss_andTheNewAdapterAcceptsTheSuccess() {
        registerActivityClasses()
        val ctrl = Robolectric.buildActivity(FragmentActivity::class.java).create().start().resume()
        newHost().attach(ctrl.get())
        val callback = RecordingCallback()
        relay.authenticate(7L, AuthPromptCopy.UNLOCK, callback)

        ctrl.recreate()

        verify(exactly = 0) { controller.onPromptHostLost(any()) }
        assertEquals(7L, relay.openAttemptId)
        newHost().attach(ctrl.get())
        assertFalse(relay.isOpenAttemptOrphaned)
        succeedThrough(1)

        assertEquals(listOf(7L to DeviceAuthOutcome.Succeeded), callback.received)
    }

    @Test
    fun rotation_theQueuedOrphanCheckRunsAfterTheAdoption_andClosesNothing() {
        val before = newController()
        newHost().attach(before.get())
        val callback = RecordingCallback()
        relay.authenticate(7L, AuthPromptCopy.UNLOCK, callback)
        rotateAway(before)
        val after = newController()
        newHost().attach(after.get())

        postedToMain.forEach { it.run() }

        assertTrue("a real adoption always wins", callback.received.isEmpty())
        assertEquals(7L, relay.openAttemptId)
        succeedThrough(1)
        assertEquals(listOf(7L to DeviceAuthOutcome.Succeeded), callback.received)
        verify(exactly = 0) { controller.onPromptHostLost(any()) }
    }

    @Test
    fun rotation_theRecreatedHostNeverAttaches_theQueuedOrphanCheckClosesTheAttemptOnce_withoutALossReport() {
        val before = newController()
        newHost().attach(before.get())
        val callback = RecordingCallback()
        relay.authenticate(7L, AuthPromptCopy.UNLOCK, callback)
        rotateAway(before)
        assertTrue("nothing is closed before the check runs", callback.received.isEmpty())

        postedToMain.forEach { it.run() }
        postedToMain.forEach { it.run() }

        assertEquals(
            listOf(7L to DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED)),
            callback.received
        )
        assertNull(relay.openAttemptId)
        verify(exactly = 0) { controller.onPromptHostLost(any()) }
    }

    // ---- ON_START re-asserts the current registration (two hosts) -----------------------------------------

    @Test
    fun twoHosts_theOlderOneRestarted_becomesCurrent_andANewerOnesDestroyDoesNotStrandIt() {
        val first = newController()
        newHost().attach(first.get())
        val second = newController()
        newHost().attach(second.get())
        // The newer host is current. The older one is stopped, then comes back to the front.
        first.pause().stop()
        first.restart().resume() // onRestart and onStart: the host observes ON_START

        assertEquals(AuthLaunch.LAUNCHED, relay.authenticate(1L, AuthPromptCopy.UNLOCK, RecordingCallback()))
        assertEquals("the restarted host got the prompt", 1, handles[0].infos.size)
        assertEquals("the newer host did not", 0, handles[1].infos.size)
        succeedThrough(0)

        finish(second) // the newer instance goes away: the older one must stay usable

        assertTrue(relay.hasRegistration)
        assertEquals(AuthLaunch.LAUNCHED, relay.authenticate(2L, AuthPromptCopy.UNLOCK, RecordingCallback()))
        assertEquals(2, handles[0].infos.size)
        verify(exactly = 0) { controller.onPromptHostLost(any()) }
    }

    @Test
    fun twoHosts_control_withoutARestartTheNewestHostIsCurrent() {
        val first = newController()
        newHost().attach(first.get())
        val second = newController()
        newHost().attach(second.get())

        assertEquals(AuthLaunch.LAUNCHED, relay.authenticate(1L, AuthPromptCopy.UNLOCK, RecordingCallback()))

        assertEquals(0, handles[0].infos.size)
        assertEquals(1, handles[1].infos.size)
    }

    // ---- exactly-once emission ---------------------------------------------------------------------------

    @Test
    fun exactlyOnce_aSecondDestroyOrALateOutcomeCannotReportAgain() {
        val ctrl = newController()
        val host = newHost()
        host.attach(ctrl.get())
        relay.authenticate(7L, AuthPromptCopy.UNLOCK, RecordingCallback())

        finish(ctrl)
        succeedThrough(0)
        // The host is detached: a fresh activity and host start clean and report nothing for the old attempt.
        val next = newController()
        val nextHost = newHost()
        nextHost.attach(next.get())
        finish(next)

        verify(exactly = 1) { controller.onPromptHostLost(7L) }
        verify(exactly = 1) { controller.onPromptHostLost(any()) }
    }

    @Test
    fun exactlyOnce_oneOutcomeOnly_forTwoLibraryTerminals() {
        val ctrl = newController()
        newHost().attach(ctrl.get())
        val callback = RecordingCallback()
        relay.authenticate(7L, AuthPromptCopy.UNLOCK, callback)

        succeedThrough(0)
        succeedThrough(0)
        libraryCallbacks[0].onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED, "ignored")

        assertEquals(listOf(7L to DeviceAuthOutcome.Succeeded), callback.received)
    }

    @Test
    fun exactlyOnce_aCancelThroughTheRelay_reachesTheHandleAndDeliversOneSystemCanceled() {
        val ctrl = newController()
        newHost().attach(ctrl.get())
        val callback = RecordingCallback()
        relay.authenticate(7L, AuthPromptCopy.UNLOCK, callback)

        relay.cancel(7L)
        relay.cancel(7L)

        assertEquals(1, handles.single().cancelCalls)
        assertEquals(
            listOf(7L to DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED)),
            callback.received
        )
    }

    // ---- copy pass-through -------------------------------------------------------------------------------

    @Test
    fun copy_everyValueReachesThePromptWithItsConstantResourceText() {
        val expectedTitles = mapOf(
            AuthPromptCopy.UNLOCK to R.string.applock_prompt_title_unlock,
            AuthPromptCopy.ENABLE to R.string.applock_prompt_title_enable,
            AuthPromptCopy.DISABLE to R.string.applock_prompt_title_disable,
            AuthPromptCopy.CHANGE to R.string.applock_prompt_title_change,
            AuthPromptCopy.EXPORT to R.string.applock_prompt_title_export,
            // S26: the PIN-mode fingerprint prompt (strings_applock_pin_prompt.xml).
            AuthPromptCopy.PIN_FINGERPRINT to R.string.applock_pin_prompt_title
        )
        assertEquals("every copy value must have an expectation", AuthPromptCopy.entries.toSet(), expectedTitles.keys)
        for ((copy, titleRes) in expectedTitles) {
            val localRelay = AppLockAuthenticatorRelay { true }
            val ctrl = newController()
            val handle = FakeHandle()
            // A fake domain key manager that returns a ready cipher, so PIN_FINGERPRINT can reach the prompt.
            val keyManager = object : BiometricPinKeyManager {
                override fun createKey(): Boolean = true
                override fun cipherForAuthentication(): BiometricKeyResult =
                    BiometricKeyResult.Ready(Cipher.getInstance("AES/CBC/PKCS5Padding"))
                override fun verifyAuthenticatedCrypto(cipher: Cipher?): Boolean = false
                override fun deleteKey() = Unit
            }
            val host = AppLockPromptHost(localRelay, controller) { activity ->
                BiometricPromptDeviceAuthenticator(activity, keyManager) { handle }
            }
            host.attach(ctrl.get())

            assertEquals(AuthLaunch.LAUNCHED, localRelay.authenticate(1L, copy, RecordingCallback()))

            val info = handle.infos.single()
            assertEquals("title for $copy", ctrl.get().getString(titleRes), info.title.toString())
            if (copy == AuthPromptCopy.ENABLE) {
                assertEquals(ctrl.get().getString(R.string.applock_prompt_subtitle_enable), info.subtitle.toString())
            } else {
                assertNull("subtitle for $copy", info.subtitle)
            }
            assertNull("description for $copy", info.description)
            if (copy == AuthPromptCopy.PIN_FINGERPRINT) {
                assertEquals("crypto launch for $copy", 1, handle.cryptoLaunches)
                assertEquals(ctrl.get().getString(R.string.applock_pin_prompt_negative), info.negativeButtonText.toString())
                assertEquals("authenticators for $copy", 15, info.allowedAuthenticators)
            } else {
                assertEquals("no crypto launch for $copy", 0, handle.cryptoLaunches)
                assertTrue("negative button for $copy", info.negativeButtonText.isNullOrEmpty())
                assertEquals("authenticators for $copy", 32783, info.allowedAuthenticators)
            }
        }
    }
}
