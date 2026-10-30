package com.yourname.expensetracker.ui.screens.privacysettings.applockpin

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.AppLockControllerHarness
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapability
import com.yourname.expensetracker.domain.privacy.applock.FakeAppPinStore
import com.yourname.expensetracker.domain.privacy.applock.LockMode
import com.yourname.expensetracker.domain.privacy.applock.SettingsOperationsRig
import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStoreState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import com.yourname.expensetracker.ui.applock.AppLockComposeTestBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S27 b2: [AppPinSettingsScreen] over a REAL controller (harness fakes for stores and the device prompt). Back during an
 * active step cancels the step and never leaves the screen; "Back up now" only navigates to Backup & Restore (AE-104).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppPinSettingsScreenTest : AppLockComposeTestBase() {

    private var navigateBacks = 0
    private var backupOpens = 0
    private var systemBack: OnBackPressedDispatcher? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** UNLOCKED, lock on, no verifier (effective DEVICE_AUTH): the setup flow can start. */
    private fun deviceAuthViewModel(): AppPinSettingsViewModel {
        val settings = AppLockPrivacySettings(
            appLockEnabled = true, autoLockTimeout = AutoLockTimeout.IMMEDIATE, lockMode = LockMode.DEVICE_AUTH
        )
        val h = AppLockControllerHarness(
            AppLockSettingsLoadState.Loaded(settings),
            DeviceAuthCapability.AVAILABLE,
            pinStore = FakeAppPinStore(AppPinStoreState(PinVerifierState.ABSENT, false, false))
        )
        val c = h.resolvedController()
        h.unlock(c)
        c.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, c.state.value)
        return AppPinSettingsViewModel(c, SettingsOperationsRig(h, c).ops, h.capabilityProvider)
    }

    private fun show(vm: AppPinSettingsViewModel) {
        composeRule.setContent {
            systemBack = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            AppPinSettingsScreen(
                onNavigateBack = { navigateBacks++ },
                onOpenBackupRestore = { backupOpens++ },
                viewModel = vm
            )
        }
        composeRule.waitForIdle()
    }

    private fun pressSystemBack() {
        composeRule.runOnUiThread { checkNotNull(systemBack) { "no back dispatcher" }.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun `control - Back with no step open leaves the screen once`() {
        val vm = deviceAuthViewModel()
        show(vm)
        composeRule.onNodeWithText(text(R.string.applock_pin_action_setup)).assertIsDisplayed()

        pressSystemBack()

        assertEquals(1, navigateBacks)
        assertEquals(0, backupOpens)
    }

    @Test
    fun `system Back during an active step cancels the step and does not leave`() {
        val vm = deviceAuthViewModel()
        vm.startSetup()
        show(vm)
        composeRule.onNodeWithTag(PinSetupFlowTags.ACK_ROW).assertIsDisplayed()

        pressSystemBack()

        assertEquals(PinFlowStep.NONE, vm.uiState.value.step)
        assertEquals("Back must cancel the step, not leave", 0, navigateBacks)
        assertEquals(0, backupOpens)
        composeRule.onNodeWithText(text(R.string.applock_pin_action_setup)).assertIsDisplayed()
    }

    @Test
    fun `the top bar back arrow during an active step cancels the step and does not leave`() {
        val vm = deviceAuthViewModel()
        vm.startSetup()
        show(vm)

        composeRule.onNodeWithContentDescription(text(R.string.cd_back)).performClick()
        composeRule.waitForIdle()

        assertEquals(PinFlowStep.NONE, vm.uiState.value.step)
        assertEquals(0, navigateBacks)
    }

    @Test
    fun `AE-104 Back up now invokes onOpenBackupRestore only and keeps the step open`() {
        val vm = deviceAuthViewModel()
        vm.startSetup()
        show(vm)

        composeRule.onNodeWithTag(PinSetupFlowTags.BACKUP_NOW).performClick()
        composeRule.waitForIdle()

        assertEquals(1, backupOpens)
        assertEquals("navigation only: the screen is not left", 0, navigateBacks)
        assertEquals("navigation only: the step is not touched", PinFlowStep.SETUP_ACK, vm.uiState.value.step)
        assertEquals(false, vm.uiState.value.ackChecked)
    }
}
