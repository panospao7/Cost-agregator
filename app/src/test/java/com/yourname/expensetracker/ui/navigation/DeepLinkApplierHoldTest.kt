package com.yourname.expensetracker.ui.navigation

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.ai.usecase.MarkDashboardBriefingOpenedUseCase
import com.yourname.expensetracker.domain.bank.BankFeatureAvailability
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.privacy.applock.HeldLinkRelease
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticMetadata
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkHost
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkParameter
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkRequest
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.ui.MainViewModel
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/**
 * S21-b (PRV-16, D-S21-3, D-S21-4): the held-link entries of [DeepLinkApplier] ([DeepLinkApplier.onHeld],
 * [DeepLinkApplier.onRejectedWhileClosed], [DeepLinkApplier.applyReleased], [DeepLinkApplier.applyExpired]).
 * The repository and the briefing use case are strict mocks: any lookup or write that is not stubbed fails the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeepLinkApplierHoldTest {

    private data class Emitted(val code: PrivacyDiagnosticCode, val metadata: PrivacyDiagnosticMetadata)

    private val emitted = mutableListOf<Emitted>()
    private val diagnostics = mockk<PrivacyDiagnosticsEmitter>().also {
        coEvery { it.emit(any(), any()) } coAnswers { emitted += Emitted(firstArg(), secondArg()) }
    }
    private val mainViewModel = mockk<MainViewModel>(relaxed = true)
    private val aiRuntimeDiagnostics = spyk(AiRuntimeDiagnostics(FakeTimeProvider()))
    private val expenseRepository = mockk<ExpenseRepository>()
    private val markOpened = mockk<MarkDashboardBriefingOpenedUseCase>()
    private val expenseDate = 1_700_000_000_000L

    private fun TestScope.applier() = DeepLinkApplier(
        expenseRepository,
        markOpened,
        diagnostics,
        BankFeatureAvailability.forDebugMode(false),
        aiRuntimeDiagnostics,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    )

    private fun codes(): List<PrivacyDiagnosticCode> = emitted.map { it.code }

    private fun verifyNoNavigation() {
        verify(exactly = 0) { mainViewModel.navigateTo(any()) }
        verify(exactly = 0) { mainViewModel.navigateToTransactions(any()) }
        verify(exactly = 0) { mainViewModel.navigateToTab(any()) }
        verify(exactly = 0) { mainViewModel.triggerAddExpense() }
    }

    private fun stubExpense77() {
        val expense = mockk<Expense> { every { date } returns expenseDate }
        coEvery { expenseRepository.getExpenseById(77L) } returns expense
    }

    private fun expectedDayFilter(): TransactionFilter {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(expenseDate).atZone(zone).toLocalDate()
        return TransactionFilter(
            dateRange = day.atStartOfDay(zone).toInstant().toEpochMilli() to
                day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        )
    }

    private val activity = DeepLinkRequest(DeepLinkHost.ACTIVITY, expenseId = 77L)
    private val keyedHome = DeepLinkRequest(DeepLinkHost.HOME, briefingKey = "dashboard_home:2027-03-12")
    private val drops = DeepLinkDrops(unknownCount = 2, invalid = setOf(DeepLinkParameter.BRIEFING_KEY))

    // --- applyReleased ---------------------------------------------------------------------------------------

    @Test
    fun `applyReleased records DL_APPLIED with AFTER_UNLOCK true for a link released after an unlock`() = runTest {
        applier().applyReleased(HeldLinkRelease.Apply(DeepLinkRequest(DeepLinkHost.HOME), afterUnlock = true), mainViewModel)

        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        assertEquals(listOf(PrivacyDiagnosticCode.DL_APPLIED), codes())
        assertEquals(DeepLinkHost.HOME, emitted.single().metadata.entries[PrivacyDiagnosticKey.HOST])
        assertEquals(true, emitted.single().metadata.entries[PrivacyDiagnosticKey.AFTER_UNLOCK])
    }

    @Test
    fun `applyReleased records AFTER_UNLOCK false for a link released without an unlock`() = runTest {
        applier().applyReleased(HeldLinkRelease.Apply(DeepLinkRequest(DeepLinkHost.HOME), afterUnlock = false), mainViewModel)

        assertEquals(listOf(PrivacyDiagnosticCode.DL_APPLIED), codes())
        assertEquals(false, emitted.single().metadata.entries[PrivacyDiagnosticKey.AFTER_UNLOCK])
    }

    @Test
    fun `a held link reports its dropped parameters once at receipt and the release does not report them again`() = runTest {
        val applier = applier()

        applier.onHeld(DeepLinkPolicyResult.Accepted(DeepLinkRequest(DeepLinkHost.MAP), drops))
        applier.applyReleased(HeldLinkRelease.Apply(DeepLinkRequest(DeepLinkHost.MAP), afterUnlock = true), mainViewModel)

        assertEquals(
            listOf(
                PrivacyDiagnosticCode.DL_PARAM_UNKNOWN_DROPPED,
                PrivacyDiagnosticCode.DL_PARAM_INVALID_DROPPED,
                PrivacyDiagnosticCode.DL_APPLIED
            ),
            codes()
        )
        assertEquals(2, emitted[0].metadata.entries[PrivacyDiagnosticKey.COUNT])
        assertEquals(DeepLinkParameter.BRIEFING_KEY, emitted[1].metadata.entries[PrivacyDiagnosticKey.PARAMETER])
    }

    @Test
    fun `apply still reports the dropped parameters itself and records AFTER_UNLOCK false`() = runTest {
        applier().apply(DeepLinkPolicyResult.Accepted(DeepLinkRequest(DeepLinkHost.MAP), drops), mainViewModel)

        assertEquals(
            listOf(
                PrivacyDiagnosticCode.DL_PARAM_UNKNOWN_DROPPED,
                PrivacyDiagnosticCode.DL_PARAM_INVALID_DROPPED,
                PrivacyDiagnosticCode.DL_APPLIED
            ),
            codes()
        )
        assertEquals(false, emitted.last().metadata.entries[PrivacyDiagnosticKey.AFTER_UNLOCK])
    }

    // --- onHeld ---------------------------------------------------------------------------------------------

    @Test
    fun `onHeld reports drops only - no lookup, no briefing write, no navigation, no applied code`() = runTest {
        val applier = applier()

        applier.onHeld(DeepLinkPolicyResult.Accepted(activity, drops))
        applier.onHeld(DeepLinkPolicyResult.Accepted(keyedHome))
        applier.onHeld(DeepLinkPolicyResult.Accepted(DeepLinkRequest(DeepLinkHost.ADD)))

        // The strict mocks would have thrown on a call; the verification makes the absence explicit.
        coVerify(exactly = 0) { expenseRepository.getExpenseById(any()) }
        coVerify(exactly = 0) { markOpened(any()) }
        verifyNoNavigation()
        assertEquals(
            listOf(PrivacyDiagnosticCode.DL_PARAM_UNKNOWN_DROPPED, PrivacyDiagnosticCode.DL_PARAM_INVALID_DROPPED),
            codes()
        )
    }

    @Test
    fun `the lookup happens only on the release`() = runTest {
        stubExpense77()
        val applier = applier()

        applier.onHeld(DeepLinkPolicyResult.Accepted(activity))
        coVerify(exactly = 0) { expenseRepository.getExpenseById(any()) }

        applier.applyReleased(HeldLinkRelease.Apply(activity, afterUnlock = true), mainViewModel)

        coVerify(exactly = 1) { expenseRepository.getExpenseById(77L) }
        verify(exactly = 1) { mainViewModel.navigateToTransactions(expectedDayFilter()) }
        assertEquals(listOf(PrivacyDiagnosticCode.DL_APPLIED), codes())
    }

    @Test
    fun `the briefing write happens only on the release`() = runTest {
        coEvery { markOpened("dashboard_home:2027-03-12") } returns MarkDashboardBriefingOpenedUseCase.Outcome.WRITTEN
        val applier = applier()

        applier.onHeld(DeepLinkPolicyResult.Accepted(keyedHome))
        coVerify(exactly = 0) { markOpened(any()) }

        applier.applyReleased(HeldLinkRelease.Apply(keyedHome, afterUnlock = true), mainViewModel)

        coVerify(exactly = 1) { markOpened("dashboard_home:2027-03-12") }
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
    }

    // --- onRejectedWhileClosed -------------------------------------------------------------------------------

    @Test
    fun `onRejectedWhileClosed reports one bounded code per reason and never navigates`() = runTest {
        val applier = applier()

        applier.onRejectedWhileClosed(DeepLinkPolicyResult.Rejected(DeepLinkRejection.ACTION))
        applier.onRejectedWhileClosed(DeepLinkPolicyResult.Rejected(DeepLinkRejection.MALFORMED))
        applier.onRejectedWhileClosed(DeepLinkPolicyResult.Rejected(DeepLinkRejection.UNKNOWN_HOST))
        applier.onRejectedWhileClosed(DeepLinkPolicyResult.Rejected(DeepLinkRejection.BANK_HOST))

        assertEquals(
            listOf(
                PrivacyDiagnosticCode.DL_REJECTED_ACTION,
                PrivacyDiagnosticCode.DL_REJECTED_MALFORMED,
                PrivacyDiagnosticCode.DL_REJECTED_UNKNOWN_HOST,
                PrivacyDiagnosticCode.DL_REJECTED_BANK_HOST
            ),
            codes()
        )
        assertEquals(false, emitted.last().metadata.entries[PrivacyDiagnosticKey.BANK_SYNC_AVAILABLE])
        verifyNoNavigation()
    }

    @Test
    fun `apply of a rejected link still reports the same code and goes Home`() = runTest {
        applier().apply(DeepLinkPolicyResult.Rejected(DeepLinkRejection.UNKNOWN_HOST), mainViewModel)

        assertEquals(listOf(PrivacyDiagnosticCode.DL_REJECTED_UNKNOWN_HOST), codes())
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
    }

    // --- applyExpired ----------------------------------------------------------------------------------------

    @Test
    fun `applyExpired opens Home only and records nothing`() = runTest {
        applier().applyExpired(mainViewModel)

        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        verify(exactly = 0) { mainViewModel.navigateToTransactions(any()) }
        coVerify(exactly = 0) { expenseRepository.getExpenseById(any()) }
        coVerify(exactly = 0) { markOpened(any()) }
        assertEquals(emptyList<PrivacyDiagnosticCode>(), codes())
    }
}
