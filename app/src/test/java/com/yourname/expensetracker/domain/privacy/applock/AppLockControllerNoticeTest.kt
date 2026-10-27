package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `notice` flow of [AppLockController] (S18 ii-c, INV-51, AMD-6, AMD-22): AE-97 and AE-98 (notice half), AE-99
 * (the 1b rows; the fingerprint rows belong to Increment 2), AE-101 and the derivation half of AE-115. The pure table
 * is [LockNoticeDerivationTest]. The review-save half of AE-115 and the biometric notice (always false in
 * Increment 1, AE-100) are not covered here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockControllerNoticeTest {

    /** Records every emission of the notice flow, starting with its current value. */
    private fun TestScope.recordNotices(controller: AppLockController): MutableList<LockNotice?> {
        val seen = mutableListOf<LockNotice?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.notice.collect { seen += it } }
        return seen
    }

    private fun harness(cause: SettingsUnreadableCause, capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE) =
        AppLockControllerHarness(AppLockTestSettings.unreadable(cause), capability)

    // --- AE-97: transient unreadable, no review notice at any point -------------------------------------------

    @Test
    fun `AE-97 a transient unreadable store never shows a notice, before or after the re-read recovers it`() = runTest {
        for (cause in listOf(SettingsUnreadableCause.IO_ERROR, SettingsUnreadableCause.TIMEOUT)) {
            val h = AppLockControllerHarness(AppLockTestSettings.unreadable(cause), scheduler = testScheduler)
            val controller = h.newController()
            val seen = recordNotices(controller)
            assertNull(controller.notice.value)

            runBlocking { controller.resolve() }
            assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
            h.tap(controller)
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            assertEquals("cause $cause", AppLockState.Unlocked, controller.state.value)
            assertNull("cause $cause", controller.notice.value)

            h.repository.scriptRead(AppLockTestSettings.DISABLED)
            controller.requestSettingsReread()

            assertEquals("cause $cause", AppLockState.Disabled, controller.state.value)
            assertNull("cause $cause", controller.notice.value)
            assertTrue("cause $cause: $seen", seen.all { it == null })
        }
    }

    // --- AE-98 / AE-99: no notice while closed, the review notice after the unlock ----------------------------

    @Test
    fun `AE-99 no notice in RESOLVING, LOCKED and AUTHENTICATING UNLOCK, and only the review notice after the unlock`() {
        val h = harness(SettingsUnreadableCause.CORRUPTED)
        val controller = h.newController()
        assertEquals(AppLockState.Resolving, controller.state.value)
        assertNull(controller.notice.value)

        runBlocking { controller.resolve() }
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        assertNull(controller.notice.value)

        val attemptId = h.tap(controller)
        assertEquals(AppLockState.Authenticating(attemptId, AuthPurpose.UNLOCK), controller.state.value)
        assertNull(controller.notice.value)

        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(LockNotice.ReviewSettings, controller.notice.value)
    }

    @Test
    fun `AE-99 CORRUPTED and UNLOCKED, the screen lock removed and the app returned (T16), shows only the inert notice`() {
        val h = harness(SettingsUnreadableCause.CORRUPTED)
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppForegrounded()
        assertEquals(LockNotice.ReviewSettings, controller.notice.value)

        h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        controller.onAppBackgrounded()
        controller.onAppForegrounded()

        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        assertEquals(LockNotice.Inert(InertReason.NO_CREDENTIAL), controller.notice.value)
    }

    @Test
    fun `AE-99 an unreadable store on a device that cannot authenticate shows the inert notice, not the review notice`() {
        val h = harness(SettingsUnreadableCause.CORRUPTED, DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()

        assertEquals(AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), controller.state.value)
        assertEquals(LockNotice.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), controller.notice.value)
    }

    @Test
    fun `AE-99 an INERT start from a readable store shows the inert notice with its reason`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.HW_UNAVAILABLE)
        val controller = h.resolvedController()

        assertEquals(AppLockState.Inert(InertReason.HW_UNAVAILABLE), controller.state.value)
        assertEquals(LockNotice.Inert(InertReason.HW_UNAVAILABLE), controller.notice.value)
    }

    @Test
    fun `INV-51 a persistent cause shows the review notice and a transient cause shows none`() {
        for (cause in SettingsUnreadableCause.entries) {
            val h = harness(cause)
            val controller = h.resolvedController()
            h.unlock(controller)

            val expected = if (cause.isPersistent) LockNotice.ReviewSettings else null
            assertEquals("cause $cause", expected, controller.notice.value)
        }
    }

    // --- AE-101: the review notice persists --------------------------------------------------------------------

    @Test
    fun `AE-101 the review notice is shown after every unlock and every cold start, and cannot be dismissed`() {
        val h = harness(SettingsUnreadableCause.CORRUPTED)
        val controller = h.resolvedController()
        h.unlock(controller)
        assertEquals(LockNotice.ReviewSettings, controller.notice.value)

        // The timeout locks the app (F is IMMEDIATE): no notice while locked, then it is back after the next unlock.
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        assertNull(controller.notice.value)
        h.unlock(controller)
        assertEquals(LockNotice.ReviewSettings, controller.notice.value)

        // A cold start over the same store.
        val coldStart = h.resolvedController()
        assertNull(coldStart.notice.value)
        h.unlock(coldStart)
        assertEquals(LockNotice.ReviewSettings, coldStart.notice.value)

        // No dismiss entry exists on the controller (the only way out is a review-save, S18 ii-d).
        val dismissLike = AppLockController::class.java.methods
            .filter { it.declaringClass == AppLockController::class.java }
            .map { it.name }
            .filter { it.contains("dismiss", ignoreCase = true) }
        assertEquals(emptyList<String>(), dismissLike)
    }

    // --- F1: the state and notice pair is never torn into a closed state with a notice -------------------------

    @Test
    fun `F1 no closed state is ever observed with a non-null notice, across unlock, lock and re-lock`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED), scheduler = testScheduler)
        val controller = h.newController()
        val observed = mutableListOf<Pair<AppLockState, LockNotice?>>()
        // Publish writes a non-null notice after the state, so a collector on the state alone never sees the new
        // notice. Both flows are observed (each paired with the other's current value); a reordered publish (a
        // non-null notice written before a closed state) shows up in `torn`.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.collect { observed += it to controller.notice.value }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.notice.collect { observed += controller.state.value to it }
        }

        runBlocking { controller.resolve() }
        h.unlock(controller)
        assertEquals(LockNotice.ReviewSettings, controller.notice.value)
        // UNLOCKED with the review notice, then the timeout locks (F is IMMEDIATE): LOCKED(TIMEOUT).
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        h.unlock(controller)
        // A second lock and unlock.
        controller.onAppBackgrounded()
        controller.onAppForegrounded()
        assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        h.unlock(controller)

        assertTrue("the notice was observed at all: $observed", observed.any { it.second == LockNotice.ReviewSettings })
        assertTrue("a LOCKED(TIMEOUT) was observed: $observed", observed.any { it.first == AppLockState.Locked(LockCause.TIMEOUT) })
        assertTrue("an AUTHENTICATING(UNLOCK) was observed: $observed", observed.any { (state, _) ->
            state is AppLockState.Authenticating && state.purpose == AuthPurpose.UNLOCK
        })
        val torn = observed.filter { (state, notice) ->
            val closed = state == AppLockState.Resolving || state is AppLockState.Locked ||
                (state is AppLockState.Authenticating && state.purpose == AuthPurpose.UNLOCK)
            closed && notice != null
        }
        assertEquals("closed states observed with a notice: $torn", emptyList<Pair<AppLockState, LockNotice?>>(), torn)
    }

    // --- AE-115: the notice during a prompt --------------------------------------------------------------------

    @Test
    fun `AE-115 a CONFIRM_CHANGE prompt from UNLOCKED with CORRUPTED keeps the review notice, never removed and shown again`() = runTest {
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED), scheduler = testScheduler)
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppForegrounded()
        assertEquals(LockNotice.ReviewSettings, controller.notice.value)
        val seen = recordNotices(controller)

        // DISABLE is the CONFIRM_CHANGE prompt of T18; it is cancelled first, then answered.
        val handler = RecordingSuccessHandler()
        val first = h.startOpenOrigin(controller, OpenOriginPrompt.DISABLE, handler)
        assertEquals(AppLockState.Authenticating(first, AuthPurpose.CONFIRM_CHANGE), controller.state.value)
        assertEquals(LockNotice.ReviewSettings, controller.notice.value)

        h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(LockNotice.ReviewSettings, controller.notice.value)

        val second = h.startOpenOrigin(controller, OpenOriginPrompt.DISABLE, handler)
        assertEquals(AppLockState.Authenticating(second, AuthPurpose.CONFIRM_CHANGE), controller.state.value)
        assertEquals(LockNotice.ReviewSettings, controller.notice.value)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertEquals(LockNotice.ReviewSettings, controller.notice.value)

        // The collector saw the notice once and never a null in between.
        assertEquals(listOf<LockNotice?>(LockNotice.ReviewSettings), seen)
    }

    @Test
    fun `AE-115 an ENABLE prompt from DISABLED keeps no notice, and an UNLOCK prompt derives none`() = runTest {
        val disabled = AppLockControllerHarness(AppLockTestSettings.DISABLED, scheduler = testScheduler)
        val enableController = disabled.resolvedController()
        enableController.onAppForegrounded()
        val enableSeen = recordNotices(enableController)
        val enableId = disabled.startOpenOrigin(enableController, OpenOriginPrompt.ENABLE, RecordingSuccessHandler())
        assertEquals(AppLockState.Authenticating(enableId, AuthPurpose.ENABLE), enableController.state.value)
        assertNull(enableController.notice.value)
        disabled.authenticator.deliver(DeviceAuthOutcome.Cancelled)
        assertNull(enableController.notice.value)
        assertTrue("$enableSeen", enableSeen.all { it == null })

        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED), scheduler = testScheduler)
        val controller = h.resolvedController()
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        val seen = recordNotices(controller)
        val unlockId = h.tap(controller)
        assertEquals(AppLockState.Authenticating(unlockId, AuthPurpose.UNLOCK), controller.state.value)
        assertNull(controller.notice.value)
        assertTrue("$seen", seen.all { it == null })
    }
}
