package com.yourname.expensetracker.ui

import app.cash.turbine.test
import com.yourname.expensetracker.data.backup.AppOperationalState
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Loan S13-a (C1): the deep-link confirmation state of [MainViewModel]. A requested destination only waits;
 * it navigates exactly once on confirm and never on dismiss.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelDeepLinkConfirmationTest : ViewModelTestUtils() {

    private lateinit var viewModel: MainViewModel
    private val plan42 = NavigationDestination.InstallmentPlanDetail(42L)

    @Before
    override fun setup() {
        super.setup()
        val reviewQueueRepository = mockk<ReviewQueueRepository>(relaxed = true)
        every { reviewQueueRepository.getPendingReviewCount() } returns flowOf(0)
        val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        every { restoreMaintenanceMode.operationalStateFlow } returns
            MutableStateFlow<AppOperationalState>(AppOperationalState.Normal)
        viewModel = MainViewModel(reviewQueueRepository, restoreMaintenanceMode)
    }

    @Test
    fun `nothing is pending initially`() {
        assertNull(viewModel.pendingDeepLinkConfirmation.value)
    }

    @Test
    fun `a request exposes the destination as pending and emits no navigation`() = runTest(testDispatcher) {
        viewModel.navigationRequest.test {
            viewModel.requestDeepLinkConfirmation(NavigationDestination.Installments)
            runCurrent()

            assertEquals(NavigationDestination.Installments, viewModel.pendingDeepLinkConfirmation.value)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `confirm emits exactly one navigation event for the destination and clears pending`() = runTest(testDispatcher) {
        viewModel.navigationRequest.test {
            viewModel.requestDeepLinkConfirmation(plan42)
            viewModel.confirmPendingDeepLink()
            runCurrent()

            assertEquals(MainNavigationRequest.Destination(plan42), awaitItem())
            assertNull(viewModel.pendingDeepLinkConfirmation.value)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a second confirm without a new request does not navigate again`() = runTest(testDispatcher) {
        viewModel.navigationRequest.test {
            viewModel.requestDeepLinkConfirmation(plan42)
            viewModel.confirmPendingDeepLink()
            viewModel.confirmPendingDeepLink()
            runCurrent()

            assertEquals(MainNavigationRequest.Destination(plan42), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismiss clears pending and emits no navigation`() = runTest(testDispatcher) {
        viewModel.navigationRequest.test {
            viewModel.requestDeepLinkConfirmation(plan42)
            viewModel.dismissPendingDeepLink()
            runCurrent()

            assertNull(viewModel.pendingDeepLinkConfirmation.value)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `confirm after a dismiss navigates nowhere`() = runTest(testDispatcher) {
        viewModel.navigationRequest.test {
            viewModel.requestDeepLinkConfirmation(plan42)
            viewModel.dismissPendingDeepLink()
            viewModel.confirmPendingDeepLink()
            runCurrent()

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a second request replaces the first and confirm navigates only to the second`() = runTest(testDispatcher) {
        viewModel.navigationRequest.test {
            viewModel.requestDeepLinkConfirmation(NavigationDestination.Installments)
            viewModel.requestDeepLinkConfirmation(plan42)
            assertEquals(plan42, viewModel.pendingDeepLinkConfirmation.value)

            viewModel.confirmPendingDeepLink()
            runCurrent()

            assertEquals(MainNavigationRequest.Destination(plan42), awaitItem())
            assertNull(viewModel.pendingDeepLinkConfirmation.value)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
