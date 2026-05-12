package com.yourname.expensetracker.ui.screens.subscription

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.SubscriptionPriceHistory
import com.yourname.expensetracker.data.repository.SubscriptionManagementRepository
import com.yourname.expensetracker.domain.subscription.SubscriptionManagerEngine
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionManagementViewModelTest : ViewModelTestUtils() {

    private val repository = mockk<SubscriptionManagementRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    private val fixedNow = 1_735_689_600_000L
    private lateinit var subscriptionsStore: MutableMap<Long, ManualRecurringExpense>
    private lateinit var viewModel: SubscriptionManagementViewModel

    @Before
    override fun setup() {
        super.setup()

        every { timeProvider.now() } returns fixedNow
        configureRepositoryWithSubscriptions(emptyList())

        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockk())
    }

    @Test
    fun `initial state shows subscriptions`() = runTest(testDispatcher) {
        configureRepositoryWithSubscriptions(
            listOf(
                createSubscription(id = 1L, merchant = "Netflix", amount = 15.0),
                createSubscription(id = 2L, merchant = "Spotify", amount = 10.0)
            )
        )
        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockk())

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.subscriptions.size)
        assertEquals(2, state.activeCount)
        assertFalse(state.isLoading)
    }

    @Test
    fun `cancel subscription updates state`() = runTest(testDispatcher) {
        configureRepositoryWithSubscriptions(
            listOf(
                createSubscription(id = 1L, merchant = "Netflix", amount = 15.0)
            )
        )
        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockk())
        advanceUntilIdle()

        viewModel.toggleSubscriptionStatus(1L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.subscriptions.isEmpty())
        assertEquals(0, state.activeCount)
        assertEquals(0, state.inactiveCount)
    }

    @Test
    fun `cost calculation correct`() = runTest(testDispatcher) {
        configureRepositoryWithSubscriptions(
            listOf(
                createSubscription(
                    id = 1L,
                    merchant = "Music App",
                    amount = 10.0,
                    frequency = RecurrenceFrequency.MONTHLY
                ),
                createSubscription(
                    id = 2L,
                    merchant = "Cloud Storage",
                    amount = 120.0,
                    frequency = RecurrenceFrequency.ANNUALLY
                )
            )
        )
        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockk())

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(20.0, state.totalMonthlyCost, 0.0001)
        assertEquals(240.0, state.totalAnnualCost, 0.0001)
    }

    @Test
    fun `empty state when no subscriptions`() = runTest(testDispatcher) {
        configureRepositoryWithSubscriptions(emptyList())
        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockk())

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.subscriptions.isEmpty())
        assertEquals(0.0, state.totalMonthlyCost, 0.0001)
        assertEquals(0.0, state.totalAnnualCost, 0.0001)
        assertEquals(0, state.activeCount)
        assertFalse(state.isLoading)
    }

    private fun configureRepositoryWithSubscriptions(items: List<ManualRecurringExpense>) {
        subscriptionsStore = items.associateBy { it.id }.toMutableMap()

        coEvery { repository.getAllActiveSubscriptions() } coAnswers {
            subscriptionsStore.values
                .filter { it.isSubscription && it.isActive }
                .sortedBy { it.id }
        }
        coEvery { repository.getPendingCandidates() } returns emptyList()

        coEvery { repository.getPriceHistoryForSubscription(any()) } coAnswers {
            val subscriptionId = invocation.args[0] as Long
            flowOf(
                listOf(
                    SubscriptionPriceHistory(
                        id = 1L,
                        subscriptionId = subscriptionId,
                        amount = subscriptionsStore[subscriptionId]?.amount ?: 0.0,
                        recordedAt = fixedNow
                    )
                )
            )
        }
        coEvery { repository.getUsageCountSince(any(), any()) } returns 0

        coEvery { repository.getSubscriptionById(any()) } coAnswers {
            val subscriptionId = invocation.args[0] as Long
            subscriptionsStore[subscriptionId]
        }
        coEvery { repository.updateSubscription(any()) } coAnswers {
            val updated = invocation.args[0] as ManualRecurringExpense
            subscriptionsStore[updated.id] = updated
            Unit
        }
    }

    private fun createSubscription(
        id: Long,
        merchant: String,
        amount: Double,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        isActive: Boolean = true
    ): ManualRecurringExpense {
        return ManualRecurringExpense(
            id = id,
            merchant = merchant,
            amount = amount,
            frequency = frequency,
            nextDate = fixedNow,
            isSubscription = true,
            isActive = isActive
        )
    }
}