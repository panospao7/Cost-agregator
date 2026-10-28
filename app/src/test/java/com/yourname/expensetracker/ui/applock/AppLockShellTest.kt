package com.yourname.expensetracker.ui.applock

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.backup.AppOperationalState
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.AuthPurpose
import com.yourname.expensetracker.domain.privacy.applock.InertReason
import com.yourname.expensetracker.domain.privacy.applock.LockCause
import com.yourname.expensetracker.domain.privacy.applock.LockNotice
import com.yourname.expensetracker.domain.privacy.applock.UnlockTapResult
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * S20-c: the Activity-side app-lock shell (CF-1..CF-4, T14, AE-113, AE-39 at the call site, S19 insets note).
 * The shell is driven by a plain [AppLockGateSource] and plain callbacks; the controller-backed overload is covered
 * with a mocked controller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockShellTest : AppLockComposeTestBase() {

    private class FakeSource(initial: AppLockState, initialNotice: LockNotice? = null) : AppLockGateSource {
        val mutableState = MutableStateFlow(initial)
        val mutableNotice = MutableStateFlow(initialNotice)
        override val state: StateFlow<AppLockState> = mutableState
        override val notice: StateFlow<LockNotice?> = mutableNotice
    }

    /**
     * A flow whose `value` is the live value but whose collection only ever delivers `Normal`: the composition keeps
     * the value it last collected, so a tap sees what the banner was drawn with while the live value has moved on.
     */
    private class StaleCollectingOperationalState(var live: AppOperationalState) : StateFlow<AppOperationalState> {
        override val replayCache: List<AppOperationalState> get() = listOf(live)
        override val value: AppOperationalState get() = live
        override suspend fun collect(collector: FlowCollector<AppOperationalState>): Nothing {
            collector.emit(AppOperationalState.Normal)
            awaitCancellation()
        }
    }

    private class CapturingTree : Timber.Tree() {
        val messages = mutableListOf<String>()
        val throwables = mutableListOf<Throwable?>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages += message
            throwables += t
        }
    }

    private val probe = ContentSlotProbe()
    private val operational = MutableStateFlow<AppOperationalState>(AppOperationalState.Normal)
    private var unlockTaps = 0
    private var securityTaps = 0
    private var settingsTaps = 0

    private fun show(
        source: AppLockGateSource,
        operationalState: StateFlow<AppOperationalState> = operational,
        content: @Composable () -> Unit = { ProbeContent(probe) }
    ) {
        composeRule.setContent {
            AppLockShell(
                source = source,
                operationalState = operationalState,
                onUnlockTapped = { unlockTaps++ },
                onOpenSecuritySettings = { securityTaps++ },
                onOpenAppLockSettings = { settingsTaps++ },
                content = content
            )
        }
    }

    private fun FakeSource.set(next: AppLockState) {
        composeRule.runOnIdle { mutableState.value = next }
        composeRule.waitForIdle()
    }

    private fun FakeSource.setNotice(next: LockNotice?) {
        composeRule.runOnIdle { mutableNotice.value = next }
        composeRule.waitForIdle()
    }

    private fun statusText(): androidx.compose.ui.test.SemanticsNodeInteraction =
        composeRule.onNodeWithTag(AppLockUiTags.STATUS_MESSAGE)

    // --- CF-1 / CF-2 ----------------------------------------------------------------------------------------

    @Test
    fun cf1_controllerGateSource_exposesTheControllersOwnFlowInstances() {
        val state = MutableStateFlow<AppLockState>(AppLockState.Resolving)
        val notice = MutableStateFlow<LockNotice?>(null)
        val controller = mockk<AppLockController>()
        every { controller.state } returns state
        every { controller.notice } returns notice

        val source = ControllerGateSource(controller)

        assertSame("state must be the controller's own StateFlow", state, source.state)
        assertSame("notice must be the controller's own StateFlow", notice, source.notice)
    }

    @Test
    fun cf1_theGateFollowsTheControllersFlowsDirectly_fromResolvingToLockedToOpenWithANotice() {
        val state = MutableStateFlow<AppLockState>(AppLockState.Resolving)
        val notice = MutableStateFlow<LockNotice?>(null)
        val controller = mockk<AppLockController>()
        every { controller.state } returns state
        every { controller.notice } returns notice

        show(ControllerGateSource(controller))
        composeRule.onAllNodesWithTag(AppLockUiTags.GATE_NEUTRAL).assertCountEquals(1)
        assertEquals(0, probe.entered)

        composeRule.runOnIdle { state.value = AppLockState.Locked(LockCause.COLD_START) }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(AppLockUiTags.GATE_SURFACE).assertCountEquals(1)
        assertEquals(0, probe.entered)

        composeRule.runOnIdle {
            notice.value = LockNotice.ReviewSettings
            state.value = AppLockState.Unlocked
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(AppLockUiTags.GATE_CONTENT_SLOT).assertCountEquals(1)
        composeRule.onAllNodesWithTag(AppLockUiTags.NOTICE_BANNER).assertCountEquals(1)
        assertEquals(1, probe.entered)
    }

    @Test
    fun publicOverload_unlockTapCallsTheControllerOnce_andTheNoticeActionNavigatesToPrivacySettings() {
        val state = MutableStateFlow<AppLockState>(AppLockState.Locked(LockCause.TIMEOUT))
        val notice = MutableStateFlow<LockNotice?>(null)
        val controller = mockk<AppLockController>()
        every { controller.state } returns state
        every { controller.notice } returns notice
        every { controller.onUnlockTapped() } returns UnlockTapResult.NotLocked
        val destinations = mutableListOf<NavigationDestination>()

        composeRule.setContent {
            AppLockShell(
                controller = controller,
                operationalState = operational,
                navigateTo = { destinations += it }
            ) { ProbeContent(probe) }
        }

        // INV-34: nothing was started by composition.
        verify(exactly = 0) { controller.onUnlockTapped() }
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).performClick()
        verify(exactly = 1) { controller.onUnlockTapped() }

        composeRule.runOnIdle {
            notice.value = LockNotice.Inert(InertReason.NO_CREDENTIAL)
            state.value = AppLockState.Inert(InertReason.NO_CREDENTIAL)
        }
        composeRule.waitForIdle()
        assertTrue(destinations.isEmpty())
        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS).performClick()
        assertEquals(listOf<NavigationDestination>(NavigationDestination.PrivacySettings), destinations)
    }

    // --- unlock is a tap only -------------------------------------------------------------------------------

    @Test
    fun inv34_unlockCallsTheCallbackFromATapOnly_neverFromCompositionOrAStateChange() {
        val source = FakeSource(AppLockState.Resolving)
        show(source)
        source.set(AppLockState.Locked(LockCause.COLD_START))
        source.set(AppLockState.Authenticating(1, AuthPurpose.UNLOCK))
        source.set(AppLockState.Locked(LockCause.COLD_START))
        assertEquals(0, unlockTaps)

        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsEnabled().performClick()
        assertEquals(1, unlockTaps)
    }

    // --- CF-3 -----------------------------------------------------------------------------------------------

    @Test
    fun cf3_securitySettingsIntent_isTheConstantActionWithNoExtrasDataComponentOrFlags() {
        val intent = SecuritySettingsLauncher.intent()

        assertEquals(Settings.ACTION_SECURITY_SETTINGS, intent.action)
        assertNull(intent.extras)
        assertNull(intent.data)
        assertNull(intent.component)
        assertNull(intent.`package`)
        assertNull(intent.categories)
        assertEquals(0, intent.flags)
    }

    @Test
    fun cf3_open_startsTheIntentOnce_andReportsTrue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val started = mutableListOf<Intent>()

        val opened = SecuritySettingsLauncher.open(context) { started += it }

        assertTrue(opened)
        assertEquals(1, started.size)
        assertEquals(Settings.ACTION_SECURITY_SETTINGS, started.single().action)
    }

    @Test
    fun cf3_activityNotFound_isHandled_returnsFalse_andLogsOnlyTheBoundedConstantReason() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tree = CapturingTree()
        Timber.plant(tree)
        try {
            val opened = SecuritySettingsLauncher.open(context) {
                throw ActivityNotFoundException("SECRET-RAW-INTENT-TEXT")
            }

            assertFalse(opened)
            assertEquals(listOf(SecuritySettingsLauncher.REASON_ACTIVITY_NOT_FOUND), tree.messages)
            assertEquals(listOf<Throwable?>(null), tree.throwables)
            assertFalse(tree.messages.single().contains("SECRET-RAW-INTENT-TEXT"))
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun cf3_aFailureThatIsNotActivityNotFound_isNotSwallowed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var thrown: Throwable? = null
        try {
            SecuritySettingsLauncher.open(context) { throw IllegalStateException("boom") }
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun cf3_theSecurityActionTap_callsTheSystemCallbackOnly_notTheInAppOnes() {
        val source = FakeSource(AppLockState.Inert(InertReason.NO_CREDENTIAL), LockNotice.Inert(InertReason.NO_CREDENTIAL))
        show(source)

        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_SECURITY_SETTINGS).performClick()

        assertEquals(1, securityTaps)
        assertEquals(0, settingsTaps)
    }

    @Test
    fun cf3_theAppLockSettingsAndReviewActionsBothOpenTheSettingsCallback_whenTheStateAllowsIt() {
        val source = FakeSource(AppLockState.Inert(InertReason.NO_CREDENTIAL), LockNotice.Inert(InertReason.NO_CREDENTIAL))
        show(source)
        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS).performClick()
        assertEquals(1, settingsTaps)

        source.set(AppLockState.Unlocked)
        source.setNotice(LockNotice.ReviewSettings)
        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_REVIEW).performClick()
        assertEquals(2, settingsTaps)
    }

    @Test
    fun cf3_tapTimeRecheck_aRestoreThatBeganAfterTheBannerWasDrawn_blocksTheInAppActions_butNotTheSystemOne() {
        val stale = StaleCollectingOperationalState(AppOperationalState.Normal)
        val source = FakeSource(AppLockState.Inert(InertReason.NO_CREDENTIAL), LockNotice.Inert(InertReason.NO_CREDENTIAL))
        show(source, operationalState = stale)
        // The banner was composed with Normal: the in-app action is on screen.
        composeRule.onAllNodesWithTag(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS).assertCountEquals(1)

        stale.live = AppOperationalState.RestartRequiredAfterRestore
        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS).performClick()
        assertEquals("the tap-time re-check must refuse the in-app action", 0, settingsTaps)

        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_SECURITY_SETTINGS).performClick()
        assertEquals(1, securityTaps)

        stale.live = AppOperationalState.Normal
        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS).performClick()
        assertEquals(1, settingsTaps)
    }

    @Test
    fun cf3_tapTimeRecheck_appliesToTheReviewAction_too() {
        val stale = StaleCollectingOperationalState(AppOperationalState.Normal)
        val source = FakeSource(AppLockState.Unlocked, LockNotice.ReviewSettings)
        show(source, operationalState = stale)

        stale.live = AppOperationalState.CriticalRecoveryRequired("x")
        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_REVIEW).performClick()

        assertEquals(0, settingsTaps)
    }

    // --- INV-52 ---------------------------------------------------------------------------------------------

    @Test
    fun inv52_inAppActionsAllowed_followsTheOperationalState_andTheSystemActionStays() {
        val source = FakeSource(AppLockState.Inert(InertReason.NO_CREDENTIAL), LockNotice.Inert(InertReason.NO_CREDENTIAL))
        show(source)
        composeRule.onAllNodesWithTag(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS).assertCountEquals(1)

        listOf(
            AppOperationalState.RestartRequiredAfterRestore,
            AppOperationalState.CriticalRecoveryRequired(),
            AppOperationalState.RestoreInProgress(RestoreMaintenanceMode.Mode.values().first())
        ).forEach { blocked ->
            composeRule.runOnIdle { operational.value = blocked }
            composeRule.waitForIdle()
            composeRule.onAllNodesWithTag(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS).assertCountEquals(0)
            composeRule.onAllNodesWithTag(AppLockUiTags.NOTICE_ACTION_SECURITY_SETTINGS).assertCountEquals(1)

            composeRule.runOnIdle { operational.value = AppOperationalState.BackupExporting }
            composeRule.waitForIdle()
            composeRule.onAllNodesWithTag(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS).assertCountEquals(1)
        }
    }

    @Test
    fun inv52_allowsInAppLockActions_coversEveryOperationalState() {
        assertTrue(AppOperationalState.Normal.allowsInAppLockActions())
        assertTrue(AppOperationalState.BackupExporting.allowsInAppLockActions())
        assertFalse(AppOperationalState.RestartRequiredAfterRestore.allowsInAppLockActions())
        assertFalse(AppOperationalState.CriticalRecoveryRequired().allowsInAppLockActions())
        RestoreMaintenanceMode.Mode.values().forEach {
            assertFalse(AppOperationalState.RestoreInProgress(it).allowsInAppLockActions())
        }
    }

    // --- CF-4 -----------------------------------------------------------------------------------------------

    @Test
    fun cf4_nextUnlockMessage_isTryAgainOnlyWhenAnUnlockPromptReturnsToLocked() {
        val unlockPrompt = AppLockState.Authenticating(3, AuthPurpose.UNLOCK)
        assertEquals(
            LockSurfaceMessage.TRY_AGAIN,
            nextUnlockMessage(unlockPrompt, AppLockState.Locked(LockCause.COLD_START), null)
        )
        assertEquals(
            LockSurfaceMessage.TRY_AGAIN,
            nextUnlockMessage(unlockPrompt, AppLockState.Locked(LockCause.TIMEOUT), null)
        )
        // The surface's own T5 line stays for an unreadable setting.
        assertNull(nextUnlockMessage(unlockPrompt, AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), null))
        // A new lock episode: entered from anywhere but an UNLOCK prompt.
        listOf(
            null,
            AppLockState.Resolving,
            AppLockState.Unlocked,
            AppLockState.Disabled,
            AppLockState.Inert(InertReason.UNKNOWN),
            AppLockState.Authenticating(4, AuthPurpose.ENABLE),
            AppLockState.Authenticating(5, AuthPurpose.CONFIRM_CHANGE)
        ).forEach { previous ->
            assertNull("previous=$previous", nextUnlockMessage(previous, AppLockState.Locked(LockCause.TIMEOUT), LockSurfaceMessage.TRY_AGAIN))
        }
        // The same episode keeps its line; another cause is another episode.
        assertEquals(
            LockSurfaceMessage.TRY_AGAIN,
            nextUnlockMessage(
                AppLockState.Locked(LockCause.COLD_START),
                AppLockState.Locked(LockCause.COLD_START),
                LockSurfaceMessage.TRY_AGAIN
            )
        )
        assertNull(
            nextUnlockMessage(
                AppLockState.Locked(LockCause.COLD_START),
                AppLockState.Locked(LockCause.TIMEOUT),
                LockSurfaceMessage.TRY_AGAIN
            )
        )
    }

    @Test
    fun cf4_nextUnlockMessage_isAlwaysClearedOutsideLocked() {
        val notLocked = listOf(
            AppLockState.Resolving,
            AppLockState.Disabled,
            AppLockState.Unlocked,
            AppLockState.Inert(InertReason.NO_CREDENTIAL),
            AppLockState.Authenticating(1, AuthPurpose.UNLOCK),
            AppLockState.Authenticating(2, AuthPurpose.ENABLE),
            AppLockState.Authenticating(3, AuthPurpose.CONFIRM_CHANGE)
        )
        notLocked.forEach { current ->
            assertNull("current=$current", nextUnlockMessage(AppLockState.Locked(LockCause.COLD_START), current, LockSurfaceMessage.TRY_AGAIN))
        }
    }

    @Test
    fun cf4_unlockMessageTracker_isIdempotentForTheSameState() {
        val tracker = UnlockMessageTracker()
        assertNull(tracker.messageFor(AppLockState.Locked(LockCause.COLD_START)))
        assertNull(tracker.messageFor(AppLockState.Authenticating(1, AuthPurpose.UNLOCK)))
        assertEquals(LockSurfaceMessage.TRY_AGAIN, tracker.messageFor(AppLockState.Locked(LockCause.COLD_START)))
        assertEquals(LockSurfaceMessage.TRY_AGAIN, tracker.messageFor(AppLockState.Locked(LockCause.COLD_START)))
        assertEquals(LockSurfaceMessage.TRY_AGAIN, tracker.messageFor(AppLockState.Locked(LockCause.COLD_START)))
    }

    @Test
    fun cf4_composed_theLineAppearsAfterAFailedPrompt_andIsClearedByANewPromptAnUnlockAndANewEpisode() {
        val source = FakeSource(AppLockState.Locked(LockCause.COLD_START))
        show(source)
        statusText().assertTextEquals("")

        source.set(AppLockState.Authenticating(1, AuthPurpose.UNLOCK))
        source.set(AppLockState.Locked(LockCause.COLD_START))
        statusText().assertTextEquals(text(R.string.applock_msg_try_again))

        // A new prompt: no line while it is in flight, and none again until it ends.
        source.set(AppLockState.Authenticating(2, AuthPurpose.UNLOCK))
        composeRule.onAllNodesWithTag(AppLockUiTags.STATUS_MESSAGE).assertCountEquals(1)
        statusText().assertTextEquals("")
        composeRule.onNodeWithTag(AppLockUiTags.UNLOCK_BUTTON).assertIsNotEnabled()

        // Success, then a re-lock for a new reason: a new episode starts with no line.
        source.set(AppLockState.Unlocked)
        source.set(AppLockState.Locked(LockCause.TIMEOUT))
        statusText().assertTextEquals("")

        // Fail again, then leave LOCKED another way (inert): the line does not survive into the next locked state.
        source.set(AppLockState.Authenticating(3, AuthPurpose.UNLOCK))
        source.set(AppLockState.Locked(LockCause.TIMEOUT))
        statusText().assertTextEquals(text(R.string.applock_msg_try_again))
        source.set(AppLockState.Inert(InertReason.NO_CREDENTIAL))
        source.set(AppLockState.Locked(LockCause.TIMEOUT))
        statusText().assertTextEquals("")
    }

    @Test
    fun cf4_composed_anUnreadableSettingKeepsTheSurfacesOwnLine_afterAFailedPrompt() {
        val source = FakeSource(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE))
        show(source)
        statusText().assertTextEquals(text(R.string.applock_msg_setting_unreadable))

        source.set(AppLockState.Authenticating(1, AuthPurpose.UNLOCK))
        source.set(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE))

        statusText().assertTextEquals(text(R.string.applock_msg_setting_unreadable))
    }

    // --- T14: SaveableStateHolder ---------------------------------------------------------------------------

    @Composable
    private fun CounterContent() {
        var count by rememberSaveable { mutableIntStateOf(0) }
        Column {
            Button(onClick = { count++ }, modifier = Modifier.testTag(COUNTER_BUTTON)) { Text("inc") }
            Text("n=$count", modifier = Modifier.testTag(COUNTER_TEXT))
        }
    }

    @Test
    fun t14_rememberSaveableInTheContentSurvivesALockedUnlockedCycle() {
        val source = FakeSource(AppLockState.Unlocked)
        show(source) { CounterContent() }
        composeRule.onNodeWithTag(COUNTER_BUTTON).performClick()
        composeRule.onNodeWithTag(COUNTER_BUTTON).performClick()
        composeRule.onNodeWithTag(COUNTER_TEXT).assertTextEquals("n=2")

        source.set(AppLockState.Locked(LockCause.TIMEOUT))
        composeRule.onAllNodesWithTag(COUNTER_TEXT).assertCountEquals(0)

        source.set(AppLockState.Authenticating(1, AuthPurpose.UNLOCK))
        source.set(AppLockState.Unlocked)
        composeRule.onNodeWithTag(COUNTER_TEXT).assertTextEquals("n=2")
    }

    @Test
    fun t14_control_withoutTheHolderTheSameCycleLosesTheState() {
        val state = androidx.compose.runtime.mutableStateOf<AppLockState>(AppLockState.Unlocked)
        composeRule.setContent {
            AppLockGate(
                state = state.value,
                notice = null,
                inAppActionsAllowed = true,
                onUnlock = {},
                onOpenSecuritySettings = {},
                onOpenAppLockSettings = {},
                onReviewSettings = {}
            ) { CounterContent() }
        }
        composeRule.onNodeWithTag(COUNTER_BUTTON).performClick()
        composeRule.onNodeWithTag(COUNTER_TEXT).assertTextEquals("n=1")

        composeRule.runOnIdle { state.value = AppLockState.Locked(LockCause.TIMEOUT) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { state.value = AppLockState.Unlocked }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(COUNTER_TEXT).assertTextEquals("n=0")
    }

    // --- AE-113, AE-114 -------------------------------------------------------------------------------------

    @Test
    fun ae113_launchedEffectInTheContent_doesNotRerunAcrossAnEnablePrompt() {
        val source = FakeSource(AppLockState.Disabled)
        show(source)
        assertEquals(1, probe.entered)
        assertEquals(1, probe.launched)

        source.set(AppLockState.Authenticating(1, AuthPurpose.ENABLE))
        composeRule.onAllNodesWithTag(AppLockUiTags.GATE_SURFACE).assertCountEquals(0)
        source.set(AppLockState.Unlocked)

        assertEquals("the content was never rebuilt", 1, probe.entered)
        assertEquals("the content was never disposed", 0, probe.disposed)
        assertEquals("LaunchedEffect(Unit) ran once", 1, probe.launched)
        assertEquals(1, probe.distinctTokens)
    }

    @Test
    fun ae113_confirmChangePrompt_keepsTheContentAndItsEffect_too() {
        val source = FakeSource(AppLockState.Unlocked)
        show(source)

        source.set(AppLockState.Authenticating(1, AuthPurpose.CONFIRM_CHANGE))
        source.set(AppLockState.Unlocked)

        assertEquals(1, probe.entered)
        assertEquals(0, probe.disposed)
        assertEquals(1, probe.launched)
    }

    @Test
    fun ae113_control_aLockedCycleDoesRebuildTheContent_soTheClaimBelowIsWhatCapsThePrompt() {
        val source = FakeSource(AppLockState.Unlocked)
        show(source)
        source.set(AppLockState.Locked(LockCause.TIMEOUT))
        source.set(AppLockState.Authenticating(1, AuthPurpose.UNLOCK))
        source.set(AppLockState.Unlocked)

        assertEquals(2, probe.entered)
        assertEquals(1, probe.disposed)
        assertEquals(2, probe.launched)
    }

    // --- AE-39 at the call site -----------------------------------------------------------------------------

    @Test
    fun ae39_theClaimAtTheContentsEffect_capsTheAutoPromptToOnePerProcess_acrossLockAndUnlock() {
        val once = NotificationPermissionPromptOnce()
        var prompts = 0
        val source = FakeSource(AppLockState.Unlocked)
        show(source) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (!once.tryClaim()) return@LaunchedEffect
                prompts++
            }
        }
        assertEquals(1, prompts)

        source.set(AppLockState.Locked(LockCause.TIMEOUT))
        source.set(AppLockState.Authenticating(1, AuthPurpose.UNLOCK))
        source.set(AppLockState.Unlocked)
        source.set(AppLockState.Locked(LockCause.TIMEOUT))
        source.set(AppLockState.Unlocked)

        assertEquals("the effect re-ran, the claim did not", 1, prompts)
    }

    // --- S20-c review #11: Back while the gate is closed moves the task to the back ---------------------------

    private var backDispatcher: OnBackPressedDispatcher? = null
    private var backCalls = 0

    private fun showWithBack(source: AppLockGateSource) {
        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            AppLockShell(
                source = source,
                operationalState = operational,
                onUnlockTapped = { unlockTaps++ },
                onOpenSecuritySettings = {},
                onOpenAppLockSettings = {},
                onBackWhileClosed = { backCalls++ }
            ) { ProbeContent(probe) }
        }
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun backHandlerEnabled(): Boolean {
        var enabled = false
        composeRule.runOnUiThread { enabled = backDispatcher!!.hasEnabledCallbacks() }
        return enabled
    }

    @Test
    fun back_whileLocked_movesTheTaskBack_andComposesNoContent() {
        val source = FakeSource(AppLockState.Locked(LockCause.COLD_START))
        showWithBack(source)
        assertTrue("the Back handler is enabled while LOCKED", backHandlerEnabled())

        pressBack()

        assertEquals(1, backCalls)
        assertEquals("no content was composed", 0, probe.entered)
        composeRule.onAllNodesWithTag(ContentSlotProbe.TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(AppLockUiTags.GATE_SURFACE).assertCountEquals(1)
    }

    @Test
    fun back_whileResolving_andWhileAnUnlockPromptIsInFlight_alsoMovesTheTaskBack() {
        val source = FakeSource(AppLockState.Resolving)
        showWithBack(source)
        assertTrue(backHandlerEnabled())
        pressBack()
        assertEquals(1, backCalls)

        source.set(AppLockState.Authenticating(1, AuthPurpose.UNLOCK))
        assertTrue(backHandlerEnabled())
        pressBack()
        assertEquals(2, backCalls)

        assertEquals("no content was ever composed", 0, probe.entered)
    }

    @Test
    fun back_whileTheGateIsOpen_theShellHandlerIsDisabled_andNeverCalled() {
        val openStates = listOf(
            AppLockState.Disabled,
            AppLockState.Unlocked,
            AppLockState.Inert(InertReason.NO_CREDENTIAL),
            AppLockState.Authenticating(1, AuthPurpose.ENABLE),
            AppLockState.Authenticating(2, AuthPurpose.CONFIRM_CHANGE)
        )
        val source = FakeSource(openStates.first())
        showWithBack(source)
        openStates.forEach { open ->
            source.set(open)
            assertFalse("handler must be disabled for $open", backHandlerEnabled())
        }
        assertEquals(0, backCalls)
    }

    @Test
    fun back_theHandlerFollowsTheGate_disabledAgainOnceUnlocked() {
        val source = FakeSource(AppLockState.Locked(LockCause.TIMEOUT))
        showWithBack(source)
        assertTrue(backHandlerEnabled())

        source.set(AppLockState.Unlocked)

        assertFalse(backHandlerEnabled())
    }

    @Test
    fun back_backLeavesTheApp_isTrueExactlyForTheClosedStates() {
        assertTrue(AppLockState.Resolving.backLeavesTheApp())
        assertTrue(AppLockState.Locked(LockCause.COLD_START).backLeavesTheApp())
        assertTrue(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE).backLeavesTheApp())
        assertTrue(AppLockState.Authenticating(1, AuthPurpose.UNLOCK).backLeavesTheApp())
        assertFalse(AppLockState.Authenticating(2, AuthPurpose.ENABLE).backLeavesTheApp())
        assertFalse(AppLockState.Authenticating(3, AuthPurpose.CONFIRM_CHANGE).backLeavesTheApp())
        assertFalse(AppLockState.Disabled.backLeavesTheApp())
        assertFalse(AppLockState.Unlocked.backLeavesTheApp())
        assertFalse(AppLockState.Inert(InertReason.UNKNOWN).backLeavesTheApp())
    }

    @Test
    fun back_moveTaskToBackIfActivity_movesTheActivitysTask_directlyAndThroughWrappers() {
        val activity = mockk<Activity>(relaxed = true)
        every { activity.moveTaskToBack(true) } returns true

        assertTrue(activity.moveTaskToBackIfActivity())
        assertTrue(ContextWrapper(activity).moveTaskToBackIfActivity())

        verify(exactly = 2) { activity.moveTaskToBack(true) }
    }

    @Test
    fun back_moveTaskToBackIfActivity_withNoActivityInTheChain_movesNothing() {
        val app = ApplicationProvider.getApplicationContext<Context>()

        assertFalse(app.moveTaskToBackIfActivity())
        assertFalse(ContextWrapper(app).moveTaskToBackIfActivity())
    }

    // --- S19 insets note ------------------------------------------------------------------------------------

    private var composeView: View? = null
    private var scaffoldTopPadding: Dp = Dp.Unspecified
    private var topInsetDp: Dp = Dp.Unspecified

    private fun showWithScaffold(source: AppLockGateSource) {
        composeRule.setContent {
            composeView = LocalView.current
            val density = LocalDensity.current
            topInsetDp = with(density) { TOP_INSET_PX.toDp() }
            AppLockShell(
                source = source,
                operationalState = operational,
                onUnlockTapped = {},
                onOpenSecuritySettings = {},
                onOpenAppLockSettings = {}
            ) {
                Scaffold { padding ->
                    scaffoldTopPadding = padding.calculateTopPadding()
                    Text("scaffold-content")
                }
            }
        }
    }

    private fun dispatchTopInset(px: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px, 0, 0))
            .build()
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(composeView!!, insets) }
        composeRule.waitForIdle()
    }

    @Test
    fun insets_withABanner_theScaffoldInTheSlotDoesNotPadTheTopTwice() {
        val source = FakeSource(AppLockState.Unlocked, LockNotice.ReviewSettings)
        showWithScaffold(source)
        dispatchTopInset(TOP_INSET_PX)

        composeRule.onAllNodesWithTag(AppLockUiTags.NOTICE_BANNER).assertCountEquals(1)
        assertEquals("the slot consumed the top inset", 0f, scaffoldTopPadding.value, 0.01f)
    }

    @Test
    fun insets_withoutABanner_theScaffoldPadsTheTopNormally() {
        val source = FakeSource(AppLockState.Unlocked)
        showWithScaffold(source)
        dispatchTopInset(TOP_INSET_PX)

        composeRule.onAllNodesWithTag(AppLockUiTags.NOTICE_BANNER).assertCountEquals(0)
        assertTrue("a real inset was dispatched", topInsetDp.value > 0f)
        assertEquals(topInsetDp.value, scaffoldTopPadding.value, 0.01f)
    }

    @Test
    fun insets_togglingTheBanner_movesTheScaffoldBetweenConsumedAndNormalPadding() {
        val source = FakeSource(AppLockState.Unlocked)
        showWithScaffold(source)
        dispatchTopInset(TOP_INSET_PX)
        val normal = scaffoldTopPadding.value
        assertTrue(normal > 0f)

        source.setNotice(LockNotice.ReviewSettings)
        assertEquals(0f, scaffoldTopPadding.value, 0.01f)

        source.setNotice(null)
        assertEquals(normal, scaffoldTopPadding.value, 0.01f)
    }

    private companion object {
        const val COUNTER_BUTTON = "shell_test_counter_button"
        const val COUNTER_TEXT = "shell_test_counter_text"
        const val TOP_INSET_PX = 48
    }
}
