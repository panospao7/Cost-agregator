package com.yourname.expensetracker.ui.applock

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.AuthPurpose
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.OpenOriginStartResult
import com.yourname.expensetracker.domain.privacy.applock.UnlockTapResult
import com.yourname.expensetracker.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Increment 1b gate G1b, the UNLOCK prompt host on a real device (spec AE-44, AE-46, AE-41, AE-118; S15/S16 carry-forwards:
 * rotation during the prompt, cancel echo, library "already showing" skip, Home mid-prompt).
 *
 * Real MainActivity, AppLockPromptHost, BiometricPromptDeviceAuthenticator and controller; the device is given a PIN
 * through the shell so the capability is AVAILABLE and the real system credential prompt opens. Instrumentation cannot
 * type into the system prompt, so these tests assert the observable app state (lock surface present, content absent,
 * Unlock button state, controller state and attempt id).
 *
 * Covered by adb-scripted C steps (the prompt itself must be driven with `input text 1234` + `input keyevent 66`):
 *  - a matching success on the RECREATED Activity after a rotation unlocks (AE-118 rotation, "adopt" accepted path);
 *  - a late success for an abandoned id after host loss is ignored (AE-118, AE-47);
 *  - exactly one system prompt window in `dumpsys window` after a double tap / rotation (this class asserts one attempt);
 *  - the library's silent skip of a launch ("already showing" fragment) is only OBSERVED here through the no-strand
 *    outcome; which of the controller timeout / PROMPT_HOST_LOST paths closed it is read from the diagnostics in the C step.
 *
 * RUNNER CONTRACT: as [AppLockLifecycleDeviceTest]: one `Class#method` per instrumentation process; skipped > 0 means
 * incomplete. Needs API 30+ (below it the lock is INERT, AE-54, and no prompt exists).
 */
@RunWith(AndroidJUnit4::class)
class AppLockPromptHostDeviceTest {

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
        // Closing the scenario destroys the host: a prompt still in flight ends as host loss (T35), never as a leak.
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

    /** Null only when the process has no compose hierarchy at all (no resumed app window); any other failure propagates. */
    private fun presentOrNull(tag: String): Boolean? =
        try {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        } catch (e: IllegalStateException) {
            if (e.message?.contains("No compose hierarchies") == true) null else throw e
        }

    private fun present(tag: String): Boolean = presentOrNull(tag) == true

    private fun state(): AppLockState = harness.controllerState(scenario!!)

    private fun coldLocked(timeoutToken: String = "IMMEDIATE"): ActivityScenario<MainActivity> {
        assumeTrue(
            "needs API ${AppLockDeviceHarness.SDK_LOCK_CAPABLE}+ for a credential prompt",
            Build.VERSION.SDK_INT >= AppLockDeviceHarness.SDK_LOCK_CAPABLE
        )
        assumeTrue("device could not be given a PIN for the lock", harness.ensureCredential(true))
        G1bProcessLedger.claimOrSkip(G1bColdScenario.LOCK_ON)
        harness.seedLock(enabled = true, timeoutToken = timeoutToken)
        val s = ActivityScenario.launch(MainActivity::class.java)
        scenario = s
        assertTrue("lock surface never shown", waitFor(15_000) { present(AppLockUiTags.GATE_SURFACE) })
        assertTrue("not LOCKED at start", waitFor(10_000) { state() is AppLockState.Locked })
        assertFalse(present(AppLockUiTags.GATE_CONTENT_SLOT))
        return s
    }

    private fun tapUnlock() {
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).performClick()
    }

    private fun awaitUnlockPrompt(): AppLockState.Authenticating {
        assertTrue("UNLOCK attempt never opened", waitFor(10_000) { state() is AppLockState.Authenticating })
        val open = state() as AppLockState.Authenticating
        assertEquals(AuthPurpose.UNLOCK, open.purpose)
        SystemClock.sleep(1_500) // let the system sheet draw
        return open
    }

    /**
     * On API 35 the device-credential-only prompt is SystemUI's PIN view, which auto-opens the IME, so the first Back only
     * hides the keyboard. Press Back (max 3 times), waiting ~2 s after each for the controller to leave Authenticating.
     */
    private fun sendBack() {
        repeat(3) {
            harness.ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            if (waitFor(2_000) { state() !is AppLockState.Authenticating }) return
        }
    }

    private fun activityIdentity(s: ActivityScenario<MainActivity>): Int {
        var id = 0
        s.onActivity { id = System.identityHashCode(it) }
        return id
    }

    private fun assertLockSurfaceOnly() {
        assertTrue("lock surface missing", present(AppLockUiTags.GATE_SURFACE))
        assertFalse("app content composed behind the lock", present(AppLockUiTags.GATE_CONTENT_SLOT))
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    fun g1b_AE46_doubleTapOpensExactlyOnePrompt() {
        val s = coldLocked()
        tapUnlock()
        tapUnlock() // back to back: the second lands on a disabled button or an in-flight attempt
        val first = awaitUnlockPrompt()
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsNotEnabled()

        var again: UnlockTapResult? = null
        s.onActivity { again = it.appLockController.onUnlockTapped() }
        assertEquals(UnlockTapResult.AlreadyInFlight, again)
        // Same attempt, no second one issued (an extra attempt would carry a larger id).
        assertEquals(first, state())
        // One system prompt window is counted in the C step (dumpsys window).
    }

    @Test
    fun g1b_AE118_rotationDuringUnlockPromptKeepsTheSameAttemptInFlight() {
        val s = coldLocked()
        tapUnlock()
        val first = awaitUnlockPrompt()
        val before = activityIdentity(s)

        harness.ui.setRotation(UiAutomation.ROTATION_FREEZE_90)
        assertTrue("rotation did not recreate MainActivity", waitFor(10_000) { activityIdentity(s) != before })
        SystemClock.sleep(1_500)

        // Adopted by the recreated host: not lost (that would return to LOCKED), not re-issued (a larger id).
        assertEquals("attempt changed across the rotation", first, state())
        assertLockSurfaceOnly()
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsNotEnabled()

        harness.ui.setRotation(UiAutomation.ROTATION_FREEZE_0)
        SystemClock.sleep(1_500)
        assertEquals("attempt changed across the rotation back", first, state())
        // A matching success on the recreated Activity being accepted is the adb-scripted C step.
    }

    /**
     * AE-44, strict (G1c). Spec (ux-flows 4.4 / 4.3 CANCELLED): a user cancel returns to Locked with NO message. Every
     * UNLOCK-prompt end now returns silently to the lock surface, so none of the status texts may appear, the former
     * TRY_AGAIN line included; Locked, enabled Unlock and no auto-reprompt (INV-34) stay strict.
     */
    @Test
    fun g1b_AE44_cancelEchoReturnsToLockSurfaceWithEnabledUnlockAndNoAutoReprompt() {
        coldLocked()
        tapUnlock()
        awaitUnlockPrompt()

        sendBack() // dismisses the system sheet: the library reports a user cancel
        assertTrue(
            "cancel did not return the controller to LOCKED",
            waitFor(10_000) { state() is AppLockState.Locked }
        )
        assertLockSurfaceOnly()
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsEnabled()
        // Spec (ux-flows 4.4 / 4.3 CANCELLED): no message at all.
        SystemClock.sleep(1_000)
        val disallowed = listOf(
            R.string.applock_msg_too_many_attempts,
            R.string.applock_msg_biometric_locked,
            R.string.applock_msg_unavailable_now,
            R.string.applock_msg_setting_unreadable
        ).map { harness.ctx.getString(it) }
        disallowed.forEach { other ->
            assertTrue(
                "unexpected status text after cancel: $other",
                composeRule.onAllNodes(hasText(other)).fetchSemanticsNodes().isEmpty()
            )
        }
        val tryAgain = harness.ctx.getString(R.string.applock_msg_try_again)
        assertEquals(
            "a user cancel must return silently: the try-again line must not be shown",
            0,
            composeRule.onAllNodes(hasText(tryAgain)).fetchSemanticsNodes().size
        )
        // INV-34: no automatic re-prompt.
        SystemClock.sleep(3_000)
        assertTrue("a prompt was re-opened without a tap", state() is AppLockState.Locked)
    }

    /**
     * No-strand SMOKE test (not a proof of the library's "already showing" skip, which is only observed through its
     * outcome). After two recreations the prompt is completed end-to-end with the harness PIN, and the controller must
     * reach Unlocked with the app content visible. Needs the PIN the harness set itself; otherwise it falls back to the
     * Back-out path and asserts Locked.
     */
    @Test
    fun g1b_AE118_alreadyShowingSkipAfterRepeatedRecreationDoesNotStrandUnlock_smoke() {
        val s = coldLocked()
        tapUnlock()
        val first = awaitUnlockPrompt()

        // Two recreations in a row: the new prompt fragment may find the library's "already showing" fragment and skip
        // its launch, leaving an adapter attempt that the new prompt never answers.
        s.recreate()
        s.recreate()
        SystemClock.sleep(2_000)

        // Whatever closed the attempt (controller timeout or PROMPT_HOST_LOST), no second attempt was issued.
        val now = state()
        if (now is AppLockState.Authenticating) assertEquals("a new attempt was issued", first, now)
        assertLockSurfaceOnly()

        if (harness.harnessOwnsPin) {
            // End-to-end completion proves nothing is stranded: the PIN is typed into whatever prompt is (still) up.
            // If the controller already closed the attempt (Locked), tap Unlock again to open a fresh prompt first.
            if (state() is AppLockState.Locked) {
                tapUnlock()
                awaitUnlockPrompt()
            }
            harness.enterPinAndConfirm()
            assertTrue(
                "unlock stranded after repeated recreation: PIN entry did not unlock",
                waitFor(20_000) { state() == AppLockState.Unlocked }
            )
            assertTrue(
                "app content not visible after unlock",
                waitFor(10_000) { present(AppLockUiTags.GATE_CONTENT_SLOT) }
            )
            assertFalse("lock surface still shown after unlock", present(AppLockUiTags.GATE_SURFACE))
            return
        }

        if (now is AppLockState.Authenticating) sendBack() // the user's way out of a still-open sheet
        assertTrue(
            "unlock stranded after repeated recreation",
            waitFor(20_000) { state() is AppLockState.Locked }
        )
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsEnabled()
        assertNotEquals("lock opened without authentication", AppLockState.Disabled, state())
    }

    @Test
    fun g1b_AE41_homeMidUnlockPromptThenReturnReShowsLockSurface() {
        val s = coldLocked()
        tapUnlock()
        awaitUnlockPrompt()

        harness.ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        SystemClock.sleep(2_000)
        harness.returnToApp()
        // API 30: the DEVICE_CREDENTIAL prompt is a separate system activity (ConfirmDeviceCredentialActivity). After
        // Home + return it can stay resumed on top of MainActivity, so the process has no compose hierarchy. That is a
        // valid state as long as our content is not shown and FLAG_SECURE is still set on our window.
        assertTrue(
            "neither lock surface nor system credential UI on top after returning",
            waitFor(15_000) { present(AppLockUiTags.GATE_SURFACE) || harness.isSystemCredentialUiResumed() }
        )
        // A null read (no compose hierarchy) is "not shown"; only a positive hit is a leak.
        assertNotEquals(
            "app content visible after Home mid-prompt",
            true,
            presentOrNull(AppLockUiTags.GATE_CONTENT_SLOT)
        )
        if (!present(AppLockUiTags.GATE_SURFACE)) {
            assertTrue("no lock surface and no system credential UI resumed", harness.isSystemCredentialUiResumed())
        }
        assertTrue("FLAG_SECURE missing after Home mid-prompt", harness.isSecureFlagSet(s))

        // The library may have ended the sheet on Home (LOCKED, enabled) or left it up (AUTHENTICATING). Either way the
        // person must be able to get back to an enabled Unlock without the app ever opening.
        if (!waitFor(5_000) { state() is AppLockState.Locked }) {
            sendBack()
            if (!waitFor(5_000) { state() is AppLockState.Locked }) harness.returnToApp()
        }
        assertTrue("unlock stranded after Home mid-prompt", waitFor(15_000) { state() is AppLockState.Locked })
        // After the system UI is dismissed the app window may need to come back to the front before it is queryable.
        if (!waitFor(5_000) { present(AppLockUiTags.GATE_SURFACE) }) harness.returnToApp()
        assertTrue("lock surface not shown after recovery", waitFor(15_000) { present(AppLockUiTags.GATE_SURFACE) })
        assertLockSurfaceOnly()
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsEnabled()
    }

    /**
     * G1c AE-118: a host that finishes with a settings (CONFIRM_CHANGE) prompt showing must not leave the system prompt
     * window orphaned. The person unlocks with the harness PIN, a longer auto-lock timeout is requested (the CONFIRM_CHANGE
     * prompt opens), then the task is restarted with CLEAR_TASK | NEW_TASK, which finishes the old MainActivity. The system
     * prompt window must be gone WITHOUT pressing Back, the controller must be Unlocked again (a lost host is an abandoned
     * attempt, never a failure or a lock), and the pending change must not be applied.
     *
     * Needs the PIN the harness set itself (to reach UNLOCKED through the real prompt), so it skips on a device that
     * already carried a credential. The current timeout is M1 (not IMMEDIATE) so a restart cannot lock the app by
     * timeout and "Unlocked" can only mean the attempt was closed without a lock. One process per test (orchestrator).
     */
    @Test
    fun g1c_AE118_finishingHostCancelsOrphanPrompt() {
        val s = coldLocked(timeoutToken = "M1")
        assumeTrue("needs the harness-owned PIN to unlock through the real prompt", harness.harnessOwnsPin)
        assertEquals("seed: the committed timeout", "M1", harness.storedTimeoutToken())

        // Reach UNLOCKED through the real UNLOCK prompt.
        tapUnlock()
        awaitUnlockPrompt()
        harness.enterPinAndConfirm()
        assertTrue("PIN entry did not unlock", waitFor(20_000) { state() == AppLockState.Unlocked })
        assertTrue("app content not visible after unlock", waitFor(10_000) { present(AppLockUiTags.GATE_CONTENT_SLOT) })

        // The process-wide controller outlives the Activity: keep it to read the state after the restart.
        lateinit var controller: AppLockController
        s.onActivity { controller = it.appLockController }
        val applied = AtomicBoolean(false)
        var started: OpenOriginStartResult? = null
        s.onActivity {
            started = it.appLockController.startTimeoutChangeAttempt(AutoLockTimeout.M15) { _, _ -> applied.set(true) }
        }
        assertTrue("CONFIRM_CHANGE attempt did not start: $started", started is OpenOriginStartResult.Started)
        assertTrue(
            "CONFIRM_CHANGE prompt never opened",
            waitFor(10_000) { (controller.state.value as? AppLockState.Authenticating)?.purpose == AuthPurpose.CONFIRM_CHANGE }
        )
        SystemClock.sleep(1_500) // let the system sheet draw
        // Positive control: the detector sees the prompt that is up, so "gone" below cannot be a blind detector.
        assertTrue("control: the system prompt window is not detected while the prompt is up", harness.isSystemPromptWindowPresent())

        try {
            // Restart the task: the old MainActivity is finished (not a configuration change). No Back is pressed.
            val restart = Intent(harness.ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            harness.ctx.startActivity(restart)

            assertTrue(
                "the system prompt window was orphaned after the host finished (AE-118)",
                waitFor(15_000) { !harness.isSystemPromptWindowPresent() }
            )
            assertTrue(
                "the abandoned CONFIRM_CHANGE attempt did not return to UNLOCKED",
                waitFor(10_000) { controller.state.value == AppLockState.Unlocked }
            )
            SystemClock.sleep(2_000)
            assertEquals("state drifted after the abandon", AppLockState.Unlocked, controller.state.value)
            assertFalse("the pending change was applied after the host was lost", applied.get())
            assertEquals("the pending timeout change was written", "M1", harness.storedTimeoutToken())
        } finally {
            // The restarted Activity is not owned by the scenario: finish it so no window outlives the test.
            harness.instrumentation.runOnMainSync {
                Stage.values().forEach { stage ->
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)
                        .filterIsInstance<MainActivity>()
                        .filterNot { it.isFinishing }
                        .forEach { it.finish() }
                }
            }
        }
    }
}
