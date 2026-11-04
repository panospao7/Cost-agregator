package com.yourname.expensetracker.ui.screens.transactions

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.ReadHomeCurrencyState
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Tab badges of [TransactionsViewModel] come from `ExpenseRepository.countExpensesBetween`, the
 * ownership-only count that also counts flagged (marked) installment rows, because the tab list shows
 * them. They must not come from the spending-only `getCountForPeriod`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTabBadgeTest : ViewModelTestUtils() {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var viewModel: TransactionsViewModel

    @Before
    override fun setup() {
        super.setup()
        expenseRepository = mockk(relaxed = true)
        val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)

        every { timeProvider.now() } returns 1_700_000_000_000L
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { currencySettingsRepository.observeReadHomeCurrency() } returns
            flowOf(ReadHomeCurrencyState.Available(CurrencyCode.EUR, firstRunDefault = false))

        viewModel = TransactionsViewModel(
            mockk<NotificationRepository>(relaxed = true),
            expenseRepository,
            categoryRepository,
            mockk<RecurringExpenseRepository>(relaxed = true),
            mockk<MerchantLocationRepository>(relaxed = true),
            timeProvider,
            mockk<GeocodingService>(relaxed = true),
            currencySettingsRepository,
            sourceLinkQueryService = mockk(relaxed = true),
            transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true),
        )
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `tab badges reflect countExpensesBetween including marked rows and not the spending-only count`() =
        runTest(testDispatcher) {
            // 7 is what the ownership-only count returns when a flagged row is present; the
            // spending-only count would be lower and is deliberately given a different value.
            coEvery { expenseRepository.countExpensesBetween(any(), any()) } returns 7
            coEvery { expenseRepository.getCountForPeriod(any(), any()) } returns 6

            val collector = launch { viewModel.tabTransactionCounts.collect { } }
            advanceUntilIdle()

            val counts = viewModel.tabTransactionCounts.value
            val badgeTabs = TransactionsViewModel.TransactionTab.values().filter {
                it != TransactionsViewModel.TransactionTab.ALL
            }
            assertEquals(badgeTabs.toSet(), counts.keys)
            badgeTabs.forEach { tab -> assertEquals("badge for $tab", 7, counts[tab]) }
            assertFalse(TransactionsViewModel.TransactionTab.ALL in counts)
            coVerify(atLeast = badgeTabs.size) { expenseRepository.countExpensesBetween(any(), any()) }
            coVerify(exactly = 0) { expenseRepository.getCountForPeriod(any(), any()) }
            collector.cancel()
        }
}
