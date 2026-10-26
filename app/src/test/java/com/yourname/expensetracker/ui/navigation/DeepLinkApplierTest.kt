package com.yourname.expensetracker.ui.navigation

import android.content.Intent
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.ai.usecase.MarkDashboardBriefingOpenedUseCase
import com.yourname.expensetracker.domain.bank.BankFeatureAvailability
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticMetadata
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkHost
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkParameter
import com.yourname.expensetracker.ui.MainViewModel
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * S10: [DeepLinkApplier] routes the policy outcome (INV-14, INV-16, INV-17, INV-18, INV-22;
 * AE-17..AE-20, AE-22..AE-27, AE-29, AE-31). Links go through the real [DeepLinkPolicy] so the whole
 * evaluate-then-apply path is exercised. Robolectric supplies `android.net.Uri`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeepLinkApplierTest {

    private data class Emitted(val code: PrivacyDiagnosticCode, val metadata: PrivacyDiagnosticMetadata)

    private val emitted = mutableListOf<Emitted>()
    private var emitBlocks = false

    private val diagnostics = mockk<PrivacyDiagnosticsEmitter>().also {
        coEvery { it.emit(any(), any()) } coAnswers {
            emitted += Emitted(firstArg(), secondArg())
            if (emitBlocks) awaitCancellation()
        }
    }

    private val mainViewModel = mockk<MainViewModel>(relaxed = true)
    // Real instance (spied): a relaxed mock leaves the injected TimeProvider null, and the
    // default-argument `now = timeProvider.now()` path then dereferences it.
    private val aiRuntimeDiagnostics = spyk(AiRuntimeDiagnostics(FakeTimeProvider()))

    /** Strict on purpose: any unexpected lookup fails the test. */
    private val expenseRepository = mockk<ExpenseRepository>()
    private val markOpened = mockk<MarkDashboardBriefingOpenedUseCase>()

    private fun TestScope.applier(bankSyncAvailable: Boolean = false) = DeepLinkApplier(
        expenseRepository,
        markOpened,
        diagnostics,
        BankFeatureAvailability.forDebugMode(bankSyncAvailable),
        aiRuntimeDiagnostics,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    )

    private suspend fun DeepLinkApplier.open(uri: String, action: String? = Intent.ACTION_VIEW) =
        apply(DeepLinkPolicy.evaluate(action, uri), mainViewModel)

    private fun codes(): List<PrivacyDiagnosticCode> = emitted.map { it.code }

    private fun metadataOf(code: PrivacyDiagnosticCode): Map<PrivacyDiagnosticKey, Any> =
        emitted.single { it.code == code }.metadata.entries

    private fun verifyNoNavigation() {
        verify(exactly = 0) { mainViewModel.navigateTo(any()) }
        verify(exactly = 0) { mainViewModel.navigateToTransactions(any()) }
        verify(exactly = 0) { mainViewModel.navigateToTab(any()) }
        verify(exactly = 0) { mainViewModel.triggerAddExpense() }
    }

    // ---------------------------------------------------------------- briefing key (INV-16)

    @Test
    fun `AE-17 a valid key that was not delivered goes Home with the not-delivered code`() = runTest {
        coEvery { markOpened("dashboard_home:2027-03-12") } returns
            MarkDashboardBriefingOpenedUseCase.Outcome.NOT_DELIVERED

        applier().open("expensetracker://dashboard?briefingKey=dashboard_home:2027-03-12")

        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        assertEquals(
            listOf(PrivacyDiagnosticCode.DL_BRIEFING_KEY_NOT_DELIVERED, PrivacyDiagnosticCode.DL_APPLIED),
            codes()
        )
    }

    @Test
    fun `AE-18 a delivered key is passed to the use case and records no briefing code`() = runTest {
        coEvery { markOpened("dashboard_home:2027-03-12") } returns
            MarkDashboardBriefingOpenedUseCase.Outcome.WRITTEN

        applier().open("expensetracker://home?briefingKey=dashboard_home:2027-03-12")

        coVerify(exactly = 1) { markOpened("dashboard_home:2027-03-12") }
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        assertEquals(listOf(PrivacyDiagnosticCode.DL_APPLIED), codes())
        assertEquals(DeepLinkHost.HOME, metadataOf(PrivacyDiagnosticCode.DL_APPLIED)[PrivacyDiagnosticKey.HOST])
        assertEquals(false, metadataOf(PrivacyDiagnosticCode.DL_APPLIED)[PrivacyDiagnosticKey.AFTER_UNLOCK])
    }

    @Test
    fun `AE-19 a malformed key is dropped, never reaches the use case, and the app goes Home`() = runTest {
        val longValue = "dashboard_home:" + "9".repeat(500)
        listOf(
            "dashboard_home:2027-13-40",
            longValue,
            "x%27;DROP"
        ).forEach { key ->
            emitted.clear()

            applier().open("expensetracker://dashboard?briefingKey=$key")

            assertTrue(codes().contains(PrivacyDiagnosticCode.DL_PARAM_INVALID_DROPPED))
            assertEquals(
                DeepLinkParameter.BRIEFING_KEY,
                metadataOf(PrivacyDiagnosticCode.DL_PARAM_INVALID_DROPPED)[PrivacyDiagnosticKey.PARAMETER]
            )
        }
        // The use case is a strict mock: any call would have thrown. Navigation went Home each time.
        verify(exactly = 3) { mainViewModel.navigateTo(NavigationDestination.Home) }
    }

    @Test
    fun `AE-20 an unreadable engagement store records the unreadable code and goes Home`() = runTest {
        coEvery { markOpened(any()) } returns MarkDashboardBriefingOpenedUseCase.Outcome.STATE_UNREADABLE

        applier().open("expensetracker://dashboard?briefingKey=dashboard_home:2027-03-12")

        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        assertTrue(codes().contains(PrivacyDiagnosticCode.DL_BRIEFING_STATE_UNREADABLE))
    }

    @Test
    fun `a home link without a key never touches the use case`() = runTest {
        applier().open("expensetracker://home")

        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        assertEquals(listOf(PrivacyDiagnosticCode.DL_APPLIED), codes())
    }

    @Test
    fun `INV-18 the phase4_open interaction carries a constant message for home and dashboard only`() = runTest {
        coEvery { markOpened(any()) } returns MarkDashboardBriefingOpenedUseCase.Outcome.WRITTEN

        applier().open("expensetracker://dashboard?briefingKey=dashboard_home:2027-03-12")
        applier().open("expensetracker://home")
        applier().open("expensetracker://review")
        applier().open("expensetracker://evilhost")

        verify(exactly = 2) {
            aiRuntimeDiagnostics.recordInteraction("phase4_open", "dashboard deep link opened", any())
        }
        verify(exactly = 2) { aiRuntimeDiagnostics.recordInteraction(any(), any(), any()) }
    }

    // ---------------------------------------------------------------- activity (INV-15, INV-22)

    @Test
    fun `AE-22 a valid expense id opens Transactions filtered to the expense's local day`() = runTest {
        val zone = ZoneId.systemDefault()
        val expenseMillis = LocalDateTime.of(2027, 3, 10, 14, 32).atZone(zone).toInstant().toEpochMilli()
        val expense = mockk<Expense> { io.mockk.every { date } returns expenseMillis }
        coEvery { expenseRepository.getExpenseById(77L) } returns expense

        applier().open("expensetracker://activity?expenseId=77")

        val start = LocalDateTime.of(2027, 3, 10, 0, 0).atZone(zone).toInstant().toEpochMilli()
        val end = LocalDateTime.of(2027, 3, 11, 0, 0).atZone(zone).toInstant().toEpochMilli()
        verify(exactly = 1) {
            mainViewModel.navigateToTransactions(TransactionFilter(dateRange = start to end))
        }
        assertEquals(listOf(PrivacyDiagnosticCode.DL_APPLIED), codes())
    }

    @Test
    fun `AE-23 an invalid expense id opens the Transactions list with no lookup`() = runTest {
        listOf("-5", "abc", "0", "99999999999999999999", "1&expenseId=2").forEach { value ->
            emitted.clear()

            applier().open("expensetracker://activity?expenseId=$value")

            assertTrue(value, codes().contains(PrivacyDiagnosticCode.DL_PARAM_INVALID_DROPPED))
        }
        // The repository is a strict mock: any lookup would have thrown.
        verify(exactly = 5) { mainViewModel.navigateTo(NavigationDestination.Transactions()) }
    }

    @Test
    fun `AE-24 a missing expense degrades to the Transactions list with the not-found code`() = runTest {
        coEvery { expenseRepository.getExpenseById(78L) } returns null

        applier().open("expensetracker://activity?expenseId=78")

        verify(exactly = 1) {
            mainViewModel.navigateTo(NavigationDestination.Transactions(initialExpenseId = 78L))
        }
        assertEquals(
            listOf(PrivacyDiagnosticCode.DL_TARGET_NOT_FOUND, PrivacyDiagnosticCode.DL_APPLIED),
            codes()
        )
    }

    @Test
    fun `AE-24 a blocked read degrades with the class name only`() = runTest {
        coEvery { expenseRepository.getExpenseById(79L) } throws
            IllegalStateException("secret merchant text /data/918")

        applier().open("expensetracker://activity?expenseId=79")

        verify(exactly = 1) {
            mainViewModel.navigateTo(NavigationDestination.Transactions(initialExpenseId = 79L))
        }
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.EXCEPTION_CLASS to "IllegalStateException"),
            metadataOf(PrivacyDiagnosticCode.DL_TARGET_READ_BLOCKED)
        )
    }

    @Test
    fun `INV-22 cancellation during the lookup propagates and navigates nowhere`() = runTest {
        coEvery { expenseRepository.getExpenseById(80L) } throws CancellationException("cancelled")

        try {
            applier().open("expensetracker://activity?expenseId=80")
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
        verifyNoNavigation()
        assertTrue(emitted.isEmpty())
    }

    // ---------------------------------------------------------------- other hosts

    @Test
    fun `review and plan navigate to their destinations`() = runTest {
        applier().open("expensetracker://review")
        applier().open("expensetracker://plan")

        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Review) }
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Budget) }
    }

    @Test
    fun `AE-25 analytics keeps a valid period and falls back to the default for an invalid one`() = runTest {
        applier().open("expensetracker://analytics?period=MONTH")
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Analytics(initialPeriod = "MONTH")) }
        assertEquals(listOf(PrivacyDiagnosticCode.DL_APPLIED), codes())

        emitted.clear()
        applier().open("expensetracker://analytics?period=decade")
        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Analytics(initialPeriod = null)) }
        assertTrue(codes().contains(PrivacyDiagnosticCode.DL_PARAM_INVALID_DROPPED))
    }

    @Test
    fun `AE-26 map keeps a clean location and drops control characters and over-long values`() = runTest {
        applier().open("expensetracker://map?location=Demo%20Cafe")
        verify(exactly = 1) {
            mainViewModel.navigateTo(NavigationDestination.SpendingMap(initialLocationQuery = "Demo Cafe"))
        }

        listOf("a%00b", "a%E2%80%AEb", "x".repeat(101)).forEach { value ->
            applier().open("expensetracker://map?location=$value")
        }
        verify(exactly = 3) {
            mainViewModel.navigateTo(NavigationDestination.SpendingMap(initialLocationQuery = null))
        }
    }

    @Test
    fun `AE-27 add opens an empty add sheet, drops the extras and creates nothing`() = runTest {
        applier().open("expensetracker://add?amount=50&merchant=X")

        verify(exactly = 1) { mainViewModel.triggerAddExpense() }
        val dropped = metadataOf(PrivacyDiagnosticCode.DL_PARAM_UNKNOWN_DROPPED)
        assertEquals(DeepLinkHost.ADD, dropped[PrivacyDiagnosticKey.HOST])
        assertEquals(2, dropped[PrivacyDiagnosticKey.COUNT])
        // Strict repository and use case mocks: no lookup, no write.
    }

    // ---------------------------------------------------------------- rejections (INV-14, INV-18)

    @Test
    fun `AE-29 an unknown host goes Home with the code only`() = runTest {
        applier().open("expensetracker://evilhost")

        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        assertEquals(listOf(PrivacyDiagnosticCode.DL_REJECTED_UNKNOWN_HOST), codes())
        assertTrue(emitted.single().metadata.entries.isEmpty())
    }

    @Test
    fun `AE-30 a malformed structure goes Home with the malformed code`() = runTest {
        listOf(
            "expensetracker://home/x",
            "expensetracker://home#f",
            "expensetracker://user@home",
            "expensetracker://home:80",
            "expensetracker://home?" + "a".repeat(2100)
        ).forEach { uri -> applier().open(uri) }

        verify(exactly = 5) { mainViewModel.navigateTo(NavigationDestination.Home) }
        assertEquals(List(5) { PrivacyDiagnosticCode.DL_REJECTED_MALFORMED }, codes())
    }

    @Test
    fun `AE-28 a bank host goes Home and records only the availability flag`() = runTest {
        applier(bankSyncAvailable = false).open("expensetracker://bank")

        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.BANK_SYNC_AVAILABLE to false),
            metadataOf(PrivacyDiagnosticCode.DL_REJECTED_BANK_HOST)
        )
    }

    @Test
    fun `AE-31 a non-VIEW action is ignored with no navigation at all`() = runTest {
        applier().open("expensetracker://add", action = Intent.ACTION_MAIN)

        verifyNoNavigation()
        assertEquals(listOf(PrivacyDiagnosticCode.DL_REJECTED_ACTION), codes())
    }

    @Test
    fun `data that is not an expensetracker link does nothing and records nothing`() = runTest {
        applier().apply(DeepLinkPolicy.evaluate(Intent.ACTION_MAIN, null), mainViewModel)
        applier().open("https://example.com/home")

        verifyNoNavigation()
        assertTrue(emitted.isEmpty())
    }

    // ---------------------------------------------------------------- TECH-8

    @Test
    fun `a diagnostic writer that never returns does not delay navigation`() = runTest {
        emitBlocks = true

        applier().open("expensetracker://evilhost")

        verify(exactly = 1) { mainViewModel.navigateTo(NavigationDestination.Home) }
    }
}
