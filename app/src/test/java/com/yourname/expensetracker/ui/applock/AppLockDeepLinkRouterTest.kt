package com.yourname.expensetracker.ui.applock

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.yourname.expensetracker.architecture.SourceTextSanitizer
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.ai.usecase.MarkDashboardBriefingOpenedUseCase
import com.yourname.expensetracker.domain.bank.BankFeatureAvailability
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockControllerHarness
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.AppLockTestSettings
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapability
import com.yourname.expensetracker.domain.privacy.applock.HeldDeepLink
import com.yourname.expensetracker.domain.privacy.applock.HeldLinkRelease
import com.yourname.expensetracker.domain.privacy.applock.LockCause
import com.yourname.expensetracker.domain.privacy.applock.OpenOriginPrompt
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticMetadata
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.applock.RecordingSuccessHandler
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthOutcome
import com.yourname.expensetracker.domain.privacy.applock.disabledControllerFor
import com.yourname.expensetracker.domain.privacy.applock.lockedControllerFor
import com.yourname.expensetracker.domain.privacy.applock.resolveTimedOut
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import com.yourname.expensetracker.domain.privacy.applock.unlockedControllerFor
import com.yourname.expensetracker.domain.navigation.deeplink.DeepLinkHost
import com.yourname.expensetracker.domain.navigation.deeplink.DeepLinkRequest
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.ui.MainViewModel
import com.yourname.expensetracker.ui.navigation.DeepLinkApplier
import com.yourname.expensetracker.ui.navigation.DeepLinkPolicyResult
import com.yourname.expensetracker.ui.navigation.DeepLinkRejection
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * S21-b (PRV-16; closes 61c #9): the deep-link router in front of the REAL [AppLockController] (fixtures from the
 * controller tests) and the real [DeepLinkApplier] over strict mocks. The main looper is paused, so a task posted to
 * `Dispatchers.Main` runs only on [idle]; the lifecycle owner is a bare registry. AE-55 to AE-60, the link part of
 * AE-118, the RESOLVING and RESOLVE_TIMEOUT holds, the T33 signal, rejected-while-closed, F1 and S21-a F3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class AppLockDeepLinkRouterTest {

    private class TestOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun start() {
            registry.currentState = Lifecycle.State.STARTED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.CREATED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private class Started(val router: AppLockDeepLinkRouter, val owner: TestOwner)

    private val applierEvents = mutableListOf<Pair<PrivacyDiagnosticCode, PrivacyDiagnosticMetadata>>()
    private val diagnostics = mockk<PrivacyDiagnosticsEmitter>().also {
        coEvery { it.emit(any(), any()) } coAnswers {
            applierEvents += firstArg<PrivacyDiagnosticCode>() to secondArg<PrivacyDiagnosticMetadata>()
        }
    }

    private val mainViewModel = mockk<MainViewModel>(relaxed = true)

    /** Strict on purpose: only expense 77 can be looked up, so any other lookup fails the test. */
    private val expenseRepository = mockk<ExpenseRepository>()
    private val markOpened = mockk<MarkDashboardBriefingOpenedUseCase>()
    private val aiRuntimeDiagnostics = spyk(AiRuntimeDiagnostics(FakeTimeProvider()))
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private val owners = mutableListOf<TestOwner>()

    private val applier = DeepLinkApplier(
        expenseRepository,
        markOpened,
        diagnostics,
        BankFeatureAvailability.forDebugMode(false),
        aiRuntimeDiagnostics,
        CoroutineScope(Dispatchers.Unconfined)
    )

    private val expenseDate = 1_700_000_000_000L
    private val activity77 = DeepLinkPolicyResult.Accepted(DeepLinkRequest(DeepLinkHost.ACTIVITY, expenseId = 77L))
    private val home = DeepLinkPolicyResult.Accepted(DeepLinkRequest(DeepLinkHost.HOME))

    @Before
    fun stubTheKnownExpense() {
        val expense = mockk<Expense> { every { date } returns expenseDate }
        coEvery { expenseRepository.getExpenseById(77L) } returns expense
    }

    @After
    fun tearDown() {
        owners.forEach { it.destroy() }
        scope.cancel()
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun router(controller: AppLockController) = AppLockDeepLinkRouter(controller, applier)

    private fun started(controller: AppLockController): Started {
        val owner = TestOwner().also { owners += it }
        val router = router(controller)
        router.startReleasing(owner, mainViewModel)
        owner.start()
        idle()
        return Started(router, owner)
    }

    private fun AppLockDeepLinkRouter.route(result: DeepLinkPolicyResult) = route(result, scope, mainViewModel)

    private fun foregroundLocked(): Pair<AppLockControllerHarness, AppLockController> =
        lockedControllerFor(LockCause.COLD_START).also { (_, controller) -> controller.onAppForegrounded() }

    private fun AppLockControllerHarness.linkCodes(): List<PrivacyDiagnosticCode> =
        emitted.filter { it.name.startsWith("DL_") }

    private fun applierCodes(): List<PrivacyDiagnosticCode> = applierEvents.map { it.first }

    private fun expectedDayFilter(): TransactionFilter {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(expenseDate).atZone(zone).toLocalDate()
        return TransactionFilter(
            dateRange = day.atStartOfDay(zone).toInstant().toEpochMilli() to
                day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        )
    }

    private fun verifyNoNavigation() {
        verify(exactly = 0) { mainViewModel.navigateTo(any()) }
        verify(exactly = 0) { mainViewModel.navigateToTransactions(any()) }
        verify(exactly = 0) { mainViewModel.navigateToTab(any()) }
        verify(exactly = 0) { mainViewModel.triggerAddExpense() }
    }

    private fun verifyNoLookup() {
        coVerify(exactly = 0) { expenseRepository.getExpenseById(any()) }
    }

    private fun verifyDayNavigationOnce() {
        coVerify(exactly = 1) { expenseRepository.getExpenseById(77L) }
        verify(exactly = 1) { mainViewModel.navigateToTransactions(expectedDayFilter()) }
        verify(exactly = 0) { mainViewModel.navigateTo(any()) }
    }

    private fun appliedAfterUnlock(): Any? =
        applierEvents.single { it.first == PrivacyDiagnosticCode.DL_APPLIED }.second.entries[PrivacyDiagnosticKey.AFTER_UNLOCK]

    // --- AE-55 to AE-60 -----------------------------------------------------------------------------------------

    @Test
    fun `AE-55 a link while LOCKED is held with no lookup and no navigation, survives a rotation and applies once after unlock`() {
        val (h, controller) = foregroundLocked()
        val first = started(controller)

        first.router.route(activity77)
        idle()

        verifyNoLookup()
        verifyNoNavigation()
        assertEquals(listOf(PrivacyDiagnosticCode.DL_HELD_LOCKED), h.linkCodes())
        assertEquals(emptyList<PrivacyDiagnosticCode>(), applierCodes())

        // Rotation: the old Activity (and its router) are gone; a new router over the same controller takes over.
        first.owner.destroy()
        started(controller)
        idle()
        verifyNoLookup()
        verifyNoNavigation()

        h.unlock(controller)
        idle()

        verifyDayNavigationOnce()
        assertEquals(true, appliedAfterUnlock())
        // A second release gives nothing, also through another idle pass.
        idle()
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        verifyDayNavigationOnce()
    }

    @Test
    fun `AE-56 two links while LOCKED apply only the newest, and DL_HELD_REPLACED is recorded`() {
        val (h, controller) = foregroundLocked()
        val s = started(controller)

        s.router.route(activity77)
        s.router.route(home)
        h.unlock(controller)
        idle()

        assertEquals(listOf(PrivacyDiagnosticCode.DL_HELD_LOCKED, PrivacyDiagnosticCode.DL_HELD_REPLACED), h.linkCodes())
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        verify(exactly = 0) { mainViewModel.navigateToTransactions(any()) }
        verifyNoLookup()
    }

    @Test
    fun `AE-57 process death drops the held link and the next start applies nothing`() {
        val (h, old) = foregroundLocked()
        val oldStarted = started(old)
        oldStarted.router.route(activity77)
        oldStarted.owner.destroy()

        val restarted = h.resolvedController()
        restarted.onAppForegrounded()
        started(restarted)
        h.unlock(restarted)
        idle()

        verifyNoLookup()
        verifyNoNavigation()
        assertEquals(HeldLinkRelease.None, restarted.releaseHeldLink())
    }

    @Test
    fun `AE-58 a link after a long background is held and applies once after unlock, in both event orders`() {
        // onNewIntent before onStart: the link query evaluates the pending timeout itself.
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        started(controller)
        controller.onAppBackgrounded()
        h.clock.advance(10 * 60_000L)
        router(controller).route(activity77)
        controller.onAppForegrounded()
        idle()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        verifyNoLookup()
        verifyNoNavigation()
        h.unlock(controller)
        idle()
        verifyDayNavigationOnce()

        // onStart before onNewIntent: the foreground already locked.
        val (h2, controller2) = unlockedControllerFor(AutoLockTimeout.M1)
        val s2 = started(controller2)
        controller2.onAppBackgrounded()
        h2.clock.advance(10 * 60_000L)
        controller2.onAppForegrounded()
        s2.router.route(home)
        idle()
        verify(exactly = 0) { mainViewModel.navigateTo(any()) }
        h2.unlock(controller2)
        idle()
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
    }

    @Test
    fun `AE-59 a link within the timeout applies now and nothing is held`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        val s = started(controller)
        controller.onAppBackgrounded()
        h.clock.advance(10_000L)

        s.router.route(home)

        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        assertEquals(listOf(PrivacyDiagnosticCode.DL_APPLIED), applierCodes())
        assertEquals(false, appliedAfterUnlock())
        assertEquals(emptyList<PrivacyDiagnosticCode>(), h.linkCodes())
        idle()
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
    }

    @Test
    fun `AE-60 a link held for six minutes opens Home, records DL_HELD_EXPIRED once and looks nothing up`() {
        val (h, controller) = foregroundLocked()
        val s = started(controller)
        s.router.route(activity77)
        h.clock.advance(6 * 60_000L)

        h.unlock(controller)
        idle()

        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        verify(exactly = 0) { mainViewModel.navigateToTransactions(any()) }
        verifyNoLookup()
        assertEquals(listOf(PrivacyDiagnosticCode.DL_HELD_LOCKED, PrivacyDiagnosticCode.DL_HELD_EXPIRED), h.linkCodes())
        assertEquals(emptyList<PrivacyDiagnosticCode>(), applierCodes())
        idle()
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
    }

    // --- RESOLVING and RESOLVE_TIMEOUT --------------------------------------------------------------------------

    @Test
    fun `a link while RESOLVING is held and applies after the lock resolves to DISABLED with afterUnlock false`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()
        controller.onAppForegrounded()
        val s = started(controller)
        assertEquals(AppLockState.Resolving, controller.state.value)

        s.router.route(activity77)
        idle()
        verifyNoLookup()
        verifyNoNavigation()

        runBlocking { controller.resolve() }
        idle()

        assertEquals(AppLockState.Disabled, controller.state.value)
        verifyDayNavigationOnce()
        assertEquals(false, appliedAfterUnlock())
    }

    @Test
    fun `61c 9 a link while the resolve timeout leaves the app LOCKED is held and applies after the unlock`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(AutoLockTimeout.M15), scheduler = testScheduler)
        val controller = h.newController()
        controller.onAppForegrounded()
        val s = started(controller)
        resolveTimedOut(h, controller)
        idle()
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)

        s.router.route(activity77)
        idle()
        verifyNoLookup()
        verifyNoNavigation()
        assertEquals(listOf(PrivacyDiagnosticCode.DL_HELD_LOCKED), h.linkCodes())

        h.unlock(controller)
        idle()

        verifyDayNavigationOnce()
        assertEquals(true, appliedAfterUnlock())
    }

    // --- T33, AE-118 --------------------------------------------------------------------------------------------

    @Test
    fun `T33 signal - a link held during CONFIRM_CHANGE stays held after the success until the apply completes, then releases once`() {
        val (h, controller) = unlockedControllerFor()
        val s = started(controller)
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        s.router.route(activity77)
        idle()
        verifyNoLookup()

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        idle()
        assertEquals(AppLockState.Unlocked, controller.state.value)
        // The origin state was published, but the T33 gate is still open: nothing is released.
        verifyNoLookup()
        verifyNoNavigation()

        controller.onOpenOriginApplyCompleted(id)
        idle()

        verifyDayNavigationOnce()
        assertEquals(false, appliedAfterUnlock())
        idle()
        verifyDayNavigationOnce()
    }

    @Test
    fun `AE-118 a link held during CONFIRM_CHANGE opens Home exactly once after the prompt host is lost`() {
        val (h, controller) = unlockedControllerFor()
        val s = started(controller)
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        s.router.route(home)
        idle()
        verifyNoNavigation()

        controller.onPromptHostLost(id)
        h.settle()
        idle()

        assertEquals(AppLockState.Unlocked, controller.state.value)
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        idle()
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
    }

    // --- Rejected links -----------------------------------------------------------------------------------------

    @Test
    fun `a rejected link while LOCKED records one bounded code, never navigates and leaves the held link intact`() {
        val (h, controller) = foregroundLocked()
        val s = started(controller)
        s.router.route(activity77)

        s.router.route(DeepLinkPolicyResult.Rejected(DeepLinkRejection.UNKNOWN_HOST))
        idle()

        assertEquals(listOf(PrivacyDiagnosticCode.DL_REJECTED_UNKNOWN_HOST), applierCodes())
        verifyNoNavigation()
        assertEquals(listOf(PrivacyDiagnosticCode.DL_HELD_LOCKED), h.linkCodes())

        h.unlock(controller)
        idle()

        verifyDayNavigationOnce()
    }

    @Test
    fun `a rejected link while the app is open goes Home, and a non-VIEW action is only reported`() {
        val (_, disabled) = disabledControllerFor()
        val s = started(disabled)

        s.router.route(DeepLinkPolicyResult.Rejected(DeepLinkRejection.MALFORMED))
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        assertEquals(listOf(PrivacyDiagnosticCode.DL_REJECTED_MALFORMED), applierCodes())

        s.router.route(DeepLinkPolicyResult.Rejected(DeepLinkRejection.ACTION))
        verify(exactly = 1) { mainViewModel.navigateTo(any()) }
        assertEquals(
            listOf(PrivacyDiagnosticCode.DL_REJECTED_MALFORMED, PrivacyDiagnosticCode.DL_REJECTED_ACTION),
            applierCodes()
        )

        val (_, unlocked) = unlockedControllerFor()
        started(unlocked).router.route(DeepLinkPolicyResult.Rejected(DeepLinkRejection.UNKNOWN_HOST))
        verify(exactly = 2) { mainViewModel.navigateTo(NavigationDestination.Home) }
    }

    // --- F1, S21-a F3, posted never inline -----------------------------------------------------------------------

    @Test
    fun `F1 a release in the background gives nothing and the link stays held until the next start`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()
        controller.onAppForegrounded()
        val s = started(controller)
        s.router.route(activity77)

        controller.onAppBackgrounded()
        runBlocking { controller.resolve() }
        idle()

        assertEquals(AppLockState.Disabled, controller.state.value)
        verifyNoLookup()
        verifyNoNavigation()

        // The foreground returns and the owner starts again: the same link is released, once.
        controller.onAppForegrounded()
        s.owner.stop()
        s.owner.start()
        idle()

        verifyDayNavigationOnce()
        idle()
        verifyDayNavigationOnce()
    }

    @Test
    fun `C2 a release task posted before a stop runs after it, gets nothing in the background and the link is released once at the next start`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()
        controller.onAppForegrounded()
        val s = started(controller)
        assertEquals(AppLockState.Resolving, controller.state.value)
        s.router.route(activity77)

        // The resolve publishes DISABLED, so a release task is posted; the app then leaves the foreground and the
        // owner stops before the paused main looper runs that task.
        runBlocking { controller.resolve() }
        assertEquals(AppLockState.Disabled, controller.state.value)
        controller.onAppBackgrounded()
        s.owner.stop()

        idle()

        verifyNoLookup()
        verifyNoNavigation()

        // The foreground returns and the owner starts: the same link is released, exactly once.
        controller.onAppForegrounded()
        s.owner.start()
        idle()

        verifyDayNavigationOnce()
        idle()
        verifyDayNavigationOnce()
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
    }

    @Test
    fun `C2 a released lookup that completes only after the owner stops still applies and is not lost`() {
        val gate = CompletableDeferred<Unit>()
        val expense = mockk<Expense> { every { date } returns expenseDate }
        coEvery { expenseRepository.getExpenseById(77L) } coAnswers {
            gate.await()
            expense
        }
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()
        controller.onAppForegrounded()
        val s = started(controller)
        s.router.route(activity77)
        runBlocking { controller.resolve() }

        idle()

        // The release ran while the owner was started: the lookup began and is suspended, nothing navigated yet.
        coVerify(exactly = 1) { expenseRepository.getExpenseById(77L) }
        verifyNoNavigation()

        s.owner.stop()
        gate.complete(Unit)
        idle()

        verifyDayNavigationOnce()
        idle()
        verifyDayNavigationOnce()
    }

    @Test
    fun `a link while AUTHENTICATING for the unlock is held and applies once after the success`() {
        val (h, controller) = foregroundLocked()
        val s = started(controller)
        h.tap(controller)
        assertTrue(controller.state.value is AppLockState.Authenticating)

        s.router.route(activity77)
        idle()

        verifyNoLookup()
        verifyNoNavigation()

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        idle()

        assertEquals(AppLockState.Unlocked, controller.state.value)
        verifyDayNavigationOnce()
        assertEquals(true, appliedAfterUnlock())
        idle()
        verifyDayNavigationOnce()
    }

    @Test
    fun `a rejected link while RESOLVING records one bounded code, never navigates and leaves the held slot untouched`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()
        controller.onAppForegrounded()
        val s = started(controller)
        assertEquals(AppLockState.Resolving, controller.state.value)

        s.router.route(DeepLinkPolicyResult.Rejected(DeepLinkRejection.UNKNOWN_HOST))
        idle()

        assertEquals(listOf(PrivacyDiagnosticCode.DL_REJECTED_UNKNOWN_HOST), applierCodes())
        verifyNoNavigation()
        verifyNoLookup()

        // A link held afterwards is still released normally once the lock resolves.
        s.router.route(activity77)
        runBlocking { controller.resolve() }
        idle()

        verifyDayNavigationOnce()
    }

    @Test
    fun `S21-a F3 a release signal while LOCKED gives nothing, the link stays held and is released after the unlock`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        val s = started(controller)
        val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
        s.router.route(home)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        idle()
        verifyNoNavigation()

        // The app locks (a 2 min background under M1) before the apply completes.
        controller.onAppBackgrounded()
        h.clock.advance(2 * 60_000L)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)

        controller.onOpenOriginApplyCompleted(id)
        idle()

        assertEquals(1L, controller.releaseOpportunity.value)
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
        verifyNoNavigation()

        h.unlock(controller)
        idle()

        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        assertEquals(HeldLinkRelease.None, controller.releaseHeldLink())
    }

    @Test
    fun `the release is posted to the main looper and never runs inline with the publication`() {
        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()
        controller.onAppForegrounded()
        val s = started(controller)
        s.router.route(activity77)

        runBlocking { controller.resolve() }
        assertEquals(AppLockState.Disabled, controller.state.value)

        // The publication happened, but the paused looper has not run the posted task: zero release effects.
        assertFalse("a task must be pending on the main looper", shadowOf(Looper.getMainLooper()).isIdle)
        verifyNoLookup()
        verifyNoNavigation()

        idle()

        verifyDayNavigationOnce()
    }

    @Test
    fun `an INERT app applies a link now and holds nothing`() {
        val inert = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = inert.resolvedController()
        controller.onAppForegrounded()
        started(controller).router.route(home)
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        assertEquals(emptyList<PrivacyDiagnosticCode>(), inert.linkCodes())
    }

    @Test
    fun `an unreadable store keeps a link held until the unlock`() {
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED))
        val controller = h.resolvedController()
        controller.onAppForegrounded()
        val s = started(controller)
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)

        s.router.route(home)
        idle()
        verifyNoNavigation()

        h.unlock(controller)
        idle()
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
    }

    // --- Feature-scoped source scan (not a registered guard) ------------------------------------------------------

    private val routerFile = "com/yourname/expensetracker/ui/applock/AppLockDeepLinkRouter.kt"

    private fun routerSource(): String {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        val root = roots.firstOrNull { it.isDirectory } ?: error("Could not locate the production source root")
        val file = File(root, routerFile)
        assertTrue("missing $routerFile", file.isFile)
        return SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
    }

    private fun matching(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    @Test
    fun `the router never uses an immediate or unconfined dispatcher and asks for the release only inside a Dispatchers Main launch`() {
        val text = routerSource()
        assertFalse(text.contains("immediate", ignoreCase = true))
        assertFalse(text.contains("Unconfined"))
        assertFalse(text.contains("runBlocking"))

        val mainLaunches = Regex("""launch\s*\(\s*Dispatchers\.Main\s*\)\s*\{""").findAll(text)
            .map { it.range.last..matching(text, it.range.last) }.toList()
        assertEquals("exactly one posted release task", 1, mainLaunches.size)
        val calls = Regex("""\.releaseHeldLink\s*\(""").findAll(text).map { it.range.first }.toList()
        assertEquals("exactly one release call", 1, calls.size)
        assertTrue("the release call is inside the Dispatchers.Main launch", calls.single() in mainLaunches.single())
        assertEquals("the release call is not made from a non-suspending helper", 1, Regex("""releaseHeldLink""").findAll(text).count())
    }

    @Test
    fun `the router names nothing bind, install or attach, and declares no fun interface or composable`() {
        val text = routerSource()
        assertFalse(Regex("""\b(bind|install|attach)\w*\s*\(""").containsMatchIn(text))
        assertFalse(Regex("""\bfun\s+interface\b""").containsMatchIn(text))
        assertFalse(Regex("""@Compos""" + """able\b""").containsMatchIn(text))
        assertFalse("the router must not touch the release-time controller internals", text.contains("onOpenOriginApplyCompleted"))
    }

    @Test
    fun `a held link expires only after T_held (sanity for the AE-60 timing used here)`() {
        assertEquals(5 * 60_000L, HeldDeepLink.HELD_LINK_TTL_MILLIS)
    }
}
