package com.yourname.expensetracker.ui.applock

import android.os.Build
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.LockCause
import com.yourname.expensetracker.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Increment 1b gate G1b, app-lock lifecycle on a real window (spec AE-38, AE-51, AE-52, AE-54, AE-66, AE-69, R14, RP-5).
 *
 * Real MainActivity, real controller, real SecureWindowController, real settings DataStore (the app's own
 * singleton, seeded in the test process before launch). No Hilt test runner, no mocks. Window flags are read from the
 * live Activity window; accessibility (AE-69) is asserted on the in-process Compose merged semantics tree.
 * UiAutomation/TalkBack evidence is captured by the adb C-step (uiautomator dump).
 *
 * RUNNER CONTRACT: the controller resolves the store once per process, so each test must run in its own
 * instrumentation process. Request each as `AppLockLifecycleDeviceTest#<method>`. A whole-class request lets the first
 * test claim the process and SKIPS the rest ([G1bProcessLedger]); the runner must treat skipped > 0 as incomplete.
 *
 * Covered by adb-scripted C steps instead (evidence: screencap, dumpsys window, uiautomator dump):
 *  - AE-66 recents thumbnail and screenshot blocking (needs the system recents/screenshot path).
 *  - RP-5 first-frame ordering: FLAG_SECURE before the RESOLVING frame is drawn (screencap during resolve).
 *  - AE-51 / AE-52 corruption by overwriting the datastore file in a fresh process (the in-process DataStore caches);
 *    here the CORRUPTED sentinel, which is what the store's corruption handler writes, is seeded instead.
 */
@RunWith(AndroidJUnit4::class)
class AppLockLifecycleDeviceTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val harness = AppLockDeviceHarness()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        harness.wakeAndDismissKeyguard()
    }

    @After
    fun tearDown() {
        // Each step is independent: a failure in one must not skip the rest (the PIN must never leak onto the device).
        try {
            scenario?.close()
        } finally {
            scenario = null
            try {
                harness.clearStore()
            } finally {
                try {
                    harness.releaseCredential()
                } finally {
                    harness.restoreRotation()
                }
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    private fun present(tag: String): Boolean =
        composeRule.onAllNodesWithTagCompat(tag)

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCompat(tag: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun assumeLockCapableSdk() =
        assumeTrue(
            "Lock-on needs API ${Build.VERSION_CODES.R}+ (DEVICE_CREDENTIAL in BiometricPrompt); below that the lock is INERT (AE-54)",
            Build.VERSION.SDK_INT >= AppLockDeviceHarness.SDK_LOCK_CAPABLE
        )

    private fun lockOnColdStart(secureScreenSetting: Boolean = false): ActivityScenario<MainActivity> {
        assumeLockCapableSdk()
        assumeTrue("device could not be given a PIN for the lock", harness.ensureCredential(true))
        G1bProcessLedger.claimOrSkip(G1bColdScenario.LOCK_ON)
        harness.seedLock(enabled = true, secureScreenSetting = secureScreenSetting)
        return launch()
    }

    // --- lock on (cold start) -----------------------------------------------------------------------

    @Test
    fun g1b_AE38_coldStartLockOnShowsLockSurfaceWithFlagSecureAndNoContent() {
        val s = lockOnColdStart()
        assertTrue("lock surface never shown", waitFor(15_000) { present(AppLockUiTags.GATE_SURFACE) })
        assertFalse("MainScreen content composed behind the lock", present(AppLockUiTags.GATE_CONTENT_SLOT))
        assertFalse("neutral surface still shown after resolve", present(AppLockUiTags.GATE_NEUTRAL))
        assertTrue("FLAG_SECURE missing on the locked window", harness.isSecureFlagSet(s))
        assertEquals(AppLockState.Locked(LockCause.COLD_START), harness.controllerState(s))
    }

    @Test
    fun g1b_AE38_backgroundAndForegroundKeepsLockSurfaceFlagAndNoContent() {
        val s = lockOnColdStart()
        assertTrue(waitFor(15_000) { present(AppLockUiTags.GATE_SURFACE) })
        s.moveToState(Lifecycle.State.CREATED) // stopped: the recents snapshot is taken from this window
        assertTrue("FLAG_SECURE dropped while backgrounded", harness.isSecureFlagSet(s))
        s.moveToState(Lifecycle.State.RESUMED)
        assertTrue(waitFor(10_000) { present(AppLockUiTags.GATE_SURFACE) })
        assertFalse(present(AppLockUiTags.GATE_CONTENT_SLOT))
        assertTrue(harness.isSecureFlagSet(s))
        assertTrue(harness.controllerState(s) is AppLockState.Locked)
    }

    @Test
    fun g1b_AE66_forcedSecureScreenFlagHoldsWithLockOnAndSecureScreenSettingOff() {
        val s = lockOnColdStart(secureScreenSetting = false)
        assertTrue(waitFor(15_000) { present(AppLockUiTags.GATE_SURFACE) })
        // The stored secure-screen choice is off; the lock forces the flag. It must not clear during a settle window.
        assertFalse(
            "FLAG_SECURE cleared although the lock forces it",
            waitFor(2_500) { !harness.isSecureFlagSet(s) }
        )
        // Recents/screenshot blocking itself: C step (AE-66).
    }

    @Test
    fun g1b_AE69_lockSurfaceExposesUnlockAccessibilityNode() {
        lockOnColdStart()
        assertTrue(waitFor(15_000) { present(AppLockUiTags.GATE_SURFACE) })
        assertTrue("unlock button never composed", waitFor(10_000) { present(AppLockUiTags.UNLOCK_BUTTON) })
        val unlockText = harness.ctx.getString(R.string.applock_unlock_button)
        // Merged semantics tree (what TalkBack consumes): enabled, clickable, labelled, button role.
        composeRule.onNode(hasTestTag(AppLockUiTags.UNLOCK_BUTTON)).assertIsEnabled()
        composeRule.onNode(hasTestTag(AppLockUiTags.UNLOCK_BUTTON)).assertHasClickAction()
        composeRule.onNode(hasTestTag(AppLockUiTags.UNLOCK_BUTTON)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        )
        composeRule.onNode(hasText(unlockText) and hasClickAction(), useUnmergedTree = false).assertExists()
        composeRule.onNode(hasText(harness.ctx.getString(R.string.applock_lock_title))).assertExists()
    }

    // --- inert / unreadable ---------------------------------------------------------------------------

    @Test
    fun g1b_AE54_lockOnWithoutProtectionShowsInertBannerAndAppStaysUsable() {
        // Reachable on any SDK: with no screen lock the capability is not AVAILABLE (NO_CREDENTIAL); on API 26-29 the
        // lock is API_UNSUPPORTED regardless. The credential is cleared through the shell (emulator/userdebug only).
        assumeTrue("device still has a credential the harness cannot clear", harness.ensureCredential(false))
        G1bProcessLedger.claimOrSkip(G1bColdScenario.INERT)
        harness.seedLock(enabled = true)
        val s = launch()
        assertTrue("INERT banner never shown", waitFor(15_000) { present(AppLockUiTags.NOTICE_BANNER) })
        assertFalse("lock surface shown for an inert lock", present(AppLockUiTags.GATE_SURFACE))
        assertTrue("app not usable while inert", present(AppLockUiTags.GATE_CONTENT_SLOT))
        assertTrue(harness.controllerState(s) is AppLockState.Inert)
        // The setting is on, so the window stays secure even though the lock cannot protect.
        assertTrue(harness.isSecureFlagSet(s))
    }

    @Test
    fun g1b_AE51_corruptedSettingsOnSecureDeviceFailClosedToLockSurface() {
        assumeLockCapableSdk()
        assumeTrue("device could not be given a PIN", harness.ensureCredential(true))
        G1bProcessLedger.claimOrSkip(G1bColdScenario.UNREADABLE_SECURE)
        harness.seedCorrupted()
        val s = launch()
        assertTrue(waitFor(15_000) { present(AppLockUiTags.GATE_SURFACE) })
        assertFalse(present(AppLockUiTags.GATE_CONTENT_SLOT))
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), harness.controllerState(s))
        assertTrue(
            "bounded unreadable status line missing",
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasText(harness.ctx.getString(R.string.applock_msg_setting_unreadable))
            ).fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue("FLAG_SECURE missing for an unreadable store", harness.isSecureFlagSet(s))
    }

    @Test
    fun g1b_AE52_corruptedSettingsOnNonSecureDeviceInertWithBannerAndFlag() {
        assumeTrue("device still has a credential the harness cannot clear", harness.ensureCredential(false))
        G1bProcessLedger.claimOrSkip(G1bColdScenario.UNREADABLE_OPEN)
        harness.seedCorrupted()
        val s = launch()
        assertTrue(waitFor(15_000) { present(AppLockUiTags.NOTICE_BANNER) })
        assertFalse(present(AppLockUiTags.GATE_SURFACE))
        assertTrue(present(AppLockUiTags.GATE_CONTENT_SLOT))
        assertTrue(harness.controllerState(s) is AppLockState.Inert)
        assertTrue("FLAG_SECURE missing for an unreadable store", harness.isSecureFlagSet(s))
    }

    // --- lock off -------------------------------------------------------------------------------------

    @Test
    fun g1b_R14_lockOffShowsNoLockSurfaceAndClearsFlagSecureAfterResolve() {
        G1bProcessLedger.claimOrSkip(G1bColdScenario.LOCK_OFF)
        harness.seedLock(enabled = false)
        val s = launch()
        assertTrue("content never shown with the lock off", waitFor(15_000) { present(AppLockUiTags.GATE_CONTENT_SLOT) })
        assertFalse(present(AppLockUiTags.GATE_SURFACE))
        assertFalse(present(AppLockUiTags.GATE_NEUTRAL))
        assertTrue(
            "FLAG_SECURE still set after resolve with lock and secure screen off",
            waitFor(10_000) { !harness.isSecureFlagSet(s) }
        )
        assertEquals(AppLockState.Disabled, harness.controllerState(s))
        // RP-5: the brief RESOLVING frame with FLAG_SECURE before this point is a C step (screencap during resolve).
    }

    @Test
    fun g1b_RP5_flagFollowsCommittedLockOnThenOffWithoutSticking() {
        G1bProcessLedger.claimOrSkip(G1bColdScenario.LOCK_OFF)
        harness.seedLock(enabled = false)
        val s = launch()
        assertTrue(waitFor(15_000) { present(AppLockUiTags.GATE_CONTENT_SLOT) })
        assertTrue("flag did not clear with the lock off", waitFor(10_000) { !harness.isSecureFlagSet(s) })

        harness.seedLock(enabled = true) // a committed lock-on write: the flag must follow it
        assertTrue("flag did not follow the committed lock-on", waitFor(10_000) { harness.isSecureFlagSet(s) })

        harness.seedLock(enabled = false) // a committed lock-off write: the flag must not stay stuck
        assertTrue("FLAG_SECURE stuck after the lock was turned off", waitFor(10_000) { !harness.isSecureFlagSet(s) })
        assertTrue("content removed by a settings write", present(AppLockUiTags.GATE_CONTENT_SLOT))
    }
}
