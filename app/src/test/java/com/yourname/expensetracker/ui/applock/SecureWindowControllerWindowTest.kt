package com.yourname.expensetracker.ui.applock

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * S20-b review fix F2: the real `Window` code of [SecureWindowController], which the plain-JVM
 * [SecureWindowControllerTest] replaces with a recording sink.
 *
 * - The `WindowSink` mapping (true to `addFlags(FLAG_SECURE)`, false to `clearFlags(FLAG_SECURE)`) against a mock
 *   `Window`.
 * - `applyInitial` / `bind` against a real Robolectric `Window`, asserting `window.attributes.flags` after the
 *   initial set, after a committed false, and after `ON_DESTROY` followed by an emission (no write).
 *
 * Setup follows [AppLockPromptHostTest] (Robolectric sdk 34, a plain `FragmentActivity` registered with the
 * package manager). Not covered here (device cases, G1b): a Compose `Dialog` window inheriting the flag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureWindowControllerWindowTest {

    private val secureFlag = WindowManager.LayoutParams.FLAG_SECURE

    private val lockOff = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings())
    private val lockOn = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = true))

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun newActivityController(): ActivityController<FragmentActivity> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, FragmentActivity::class.java))
        return Robolectric.buildActivity(FragmentActivity::class.java).create().start().resume()
    }

    private fun secure(activity: FragmentActivity): Boolean =
        (activity.window.attributes.flags and secureFlag) != 0

    private fun committedFlow(initial: AppLockSettingsLoadState): MutableSharedFlow<AppLockSettingsLoadState> =
        MutableSharedFlow<AppLockSettingsLoadState>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            .also { it.tryEmit(initial) }

    private fun controllerOver(committed: MutableSharedFlow<AppLockSettingsLoadState>): SecureWindowController {
        val repository = mockk<AppLockSettingsRepository>()
        every { repository.observeLoadState() } returns committed
        val controller = mockk<AppLockController>()
        every { controller.state } returns MutableStateFlow<AppLockState>(AppLockState.Disabled)
        return SecureWindowController(repository, controller)
    }

    // --- WindowSink mapping (mock Window)

    @Test
    fun windowSink_true_addsFlagSecure_false_clearsFlagSecure_andNothingElse() {
        val window = mockk<Window>(relaxed = true)
        val sink = SecureWindowController.WindowSink(window)

        sink.setSecure(true)
        verify(exactly = 1) { window.addFlags(secureFlag) }
        verify(exactly = 0) { window.clearFlags(any()) }

        sink.setSecure(false)
        verify(exactly = 1) { window.clearFlags(secureFlag) }
        verify(exactly = 1) { window.addFlags(secureFlag) }
    }

    @Test
    fun applyInitial_onAWindow_addsFlagSecureBeforeAnythingElse() {
        val window = mockk<Window>(relaxed = true)
        val repository = mockk<AppLockSettingsRepository>() // unstubbed: any read would throw
        val controller = mockk<AppLockController>()

        SecureWindowController(repository, controller).applyInitial(window)

        verifyOrder { window.addFlags(secureFlag) }
        verify(exactly = 0) { window.clearFlags(any()) }
    }

    // --- the real Window

    @Test
    fun realWindow_applyInitialSets_aCommittedFalseClears_aCommittedTrueSetsAgain() {
        val activityController = newActivityController()
        val activity = activityController.get()
        val committed = committedFlow(lockOff)
        val subject = controllerOver(committed)

        subject.applyInitial(activity.window)
        assertEquals("set by applyInitial", true, secure(activity))

        subject.bind(activity)
        idle()
        assertEquals("cleared by the first committed lock-off emission", false, secure(activity))

        committed.tryEmit(lockOn)
        idle()
        assertEquals("set again by a committed lock-on emission", true, secure(activity))

        committed.tryEmit(lockOff)
        idle()
        assertEquals("cleared by a committed weakening", false, secure(activity))
    }

    @Test
    fun realWindow_afterOnDestroy_aLaterEmissionWritesNothing() {
        val activityController = newActivityController()
        val activity = activityController.get()
        val committed = committedFlow(lockOff)
        val subject = controllerOver(committed)
        subject.applyInitial(activity.window)
        subject.bind(activity)
        idle()
        assertEquals(false, secure(activity))

        activityController.pause().stop().destroy()
        idle()
        committed.tryEmit(lockOn) // would set the flag if the collection were still running
        idle()

        assertEquals("no write after ON_DESTROY", false, secure(activity))
    }
}
