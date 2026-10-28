package com.yourname.expensetracker.ui.applock

import android.app.Application
import android.content.ComponentName
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.privacy.applock.AuthLaunch
import com.yourname.expensetracker.domain.privacy.applock.AuthPromptCopy
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCallback
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * S15, INV-23 (no prompt below API 30), INV-34 (no outcome for a request that never launched).
 *
 * The pure gate [canLaunchPrompt] is tested exhaustively. The adapter itself is exercised on API 26,
 * 28 and 29 (the library must never be reached, so no callback follows) and on API 34 with a
 * finishing host. FragmentActivity is registered with the package manager so the test is identical
 * in the debug and release variants.
 */
@RunWith(RobolectricTestRunner::class)
class BiometricPromptHostGatingTest {

    private class RecordingCallback : DeviceAuthCallback {
        val outcomes = mutableListOf<Pair<Long, DeviceAuthOutcome>>()
        override fun onOutcome(attemptId: Long, outcome: DeviceAuthOutcome) {
            outcomes += attemptId to outcome
        }
    }

    private fun newActivity(): FragmentActivity {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, FragmentActivity::class.java))
        return Robolectric.buildActivity(FragmentActivity::class.java).create().get()
    }

    private fun assertNotLaunched() {
        val activity = newActivity()
        val callback = RecordingCallback()
        val launched = BiometricPromptDeviceAuthenticator(activity)
            .authenticate(1L, AuthPromptCopy.UNLOCK, callback)
        assertEquals(AuthLaunch.HOST_UNAVAILABLE, launched)
        assertTrue(callback.outcomes.isEmpty())
    }

    @Test
    @Config(sdk = [26])
    fun inv23_api26_neverLaunches_noCallback() = assertNotLaunched()

    @Test
    @Config(sdk = [28])
    fun inv23_api28_neverLaunches_noCallback() = assertNotLaunched()

    @Test
    @Config(sdk = [29])
    fun inv23_api29_neverLaunches_noCallback() = assertNotLaunched()

    @Test
    @Config(sdk = [34])
    fun inv34_finishingHost_neverLaunches_noCallback() {
        val activity = newActivity()
        val adapter = BiometricPromptDeviceAuthenticator(activity)
        // The prompt must exist, otherwise the gate under test would never be reached.
        assertTrue("prompt must be built for this test to be meaningful", adapter.isPromptBuilt)
        activity.finish()
        assertTrue(activity.isFinishing)
        val callback = RecordingCallback()
        val launched = adapter.authenticate(2L, AuthPromptCopy.UNLOCK, callback)
        assertEquals(AuthLaunch.HOST_UNAVAILABLE, launched)
        assertTrue(callback.outcomes.isEmpty())
        assertFalse(adapter.isAwaitingLibraryTerminal)
    }

    @Test
    @Config(sdk = [34])
    fun inv34_cancelWithNothingInFlight_isANoOp() {
        // Nothing launched: cancel must not throw and has no sink to deliver to.
        BiometricPromptDeviceAuthenticator(newActivity()).cancel(3L)
    }

    @Test
    fun canLaunchPrompt_truthTable() {
        for (sdk in listOf(26, 28, 29, 30, 31, 34)) {
            for (stateSaved in listOf(false, true)) {
                for (finishing in listOf(false, true)) {
                    for (destroyed in listOf(false, true)) {
                        val expected = sdk >= 30 && !stateSaved && !finishing && !destroyed
                        assertEquals(
                            "sdk=$sdk saved=$stateSaved finishing=$finishing destroyed=$destroyed",
                            expected,
                            canLaunchPrompt(sdk, stateSaved, finishing, destroyed)
                        )
                    }
                }
            }
        }
    }
}
