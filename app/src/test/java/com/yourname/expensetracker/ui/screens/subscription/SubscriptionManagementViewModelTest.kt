package com.yourname.expensetracker.ui.screens.subscription

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.SubscriptionPriceHistory
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.data.repository.SubscriptionManagementRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.ConversionFailure
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.FailureReason
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import kotlinx.coroutines.CancellationException
import com.yourname.expensetracker.domain.subscription.SubscriptionManagerEngine
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
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

        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockCurrencyRepo(), multiCurrencyRepository = fakeMultiCurrencyRepository())
    }

    @Test
    fun `initial state shows subscriptions`() = runTest(testDispatcher) {
        configureRepositoryWithSubscriptions(
            listOf(
                createSubscription(id = 1L, merchant = "Netflix", amount = 15.0),
                createSubscription(id = 2L, merchant = "Spotify", amount = 10.0)
            )
        )
        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockCurrencyRepo(), multiCurrencyRepository = fakeMultiCurrencyRepository())

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
        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockCurrencyRepo(), multiCurrencyRepository = fakeMultiCurrencyRepository())
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
        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockCurrencyRepo(), multiCurrencyRepository = fakeMultiCurrencyRepository())

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(20.0, state.totalMonthlyCost, 0.0001)
        assertEquals(240.0, state.totalAnnualCost, 0.0001)
    }

    @Test
    fun `empty state when no subscriptions`() = runTest(testDispatcher) {
        configureRepositoryWithSubscriptions(emptyList())
        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockCurrencyRepo(), multiCurrencyRepository = fakeMultiCurrencyRepository())

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.subscriptions.isEmpty())
        assertEquals(0.0, state.totalMonthlyCost, 0.0001)
        assertEquals(0.0, state.totalAnnualCost, 0.0001)
        assertEquals(0, state.activeCount)
        assertFalse(state.isLoading)
    }

    @Test
    fun `deleteAndToggleUseLifecycleRepositoryPath`() = runTest(testDispatcher) {
        configureRepositoryWithSubscriptions(
            listOf(
                createSubscription(id = 1L, merchant = "Netflix", amount = 15.0)
            )
        )
        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockCurrencyRepo(), multiCurrencyRepository = fakeMultiCurrencyRepository())
        advanceUntilIdle()

        // RP-04 A1: toggle must go through setActive (coordinator activate/
        // deactivate), not a full updateSubscription with flipped isActive.
        viewModel.toggleSubscriptionStatus(1L)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setActive(1L, false) }
        coVerify(exactly = 0) { repository.updateSubscription(any()) }

        // Delete must go through deleteSubscriptionById (coordinator deleteRule).
        viewModel.deleteSubscription(1L)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.deleteSubscriptionById(1L) }
    }

    // HZ01-A: the monthly total goes through MultiCurrencyRepository.aggregateDisplayAmounts.
    // The converted total is mixed-currency safe: rows without a rate are excluded (partial),
    // and no convertible row at all is Unavailable (never 0 or a raw sum).

    @Test
    fun `total converts foreign currency rows into home currency`() = runTest(testDispatcher) {
        configureRepositoryWithSubscriptions(
            listOf(
                createSubscription(id = 1L, merchant = "Netflix", amount = 10.0, currency = "USD"),
                createSubscription(id = 2L, merchant = "Spotify", amount = 20.0, currency = "EUR")
            )
        )
        val aggregator = fakeMultiCurrencyRepository(rates = mapOf("USD" to 0.5))
        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockCurrencyRepo(), multiCurrencyRepository = aggregator)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(25.0, state.totalMonthlyCost, 0.0001)
        assertEquals(300.0, state.totalAnnualCost, 0.0001)
        assertEquals("EUR", state.totalCurrency)
        assertEquals(SubscriptionTotalStatus.AVAILABLE, state.totalStatus)
    }

    @Test
    fun `total excludes row without rate and is marked partial`() = runTest(testDispatcher) {
        configureRepositoryWithSubscriptions(
            listOf(
                createSubscription(id = 1L, merchant = "Netflix", amount = 10.0, currency = "USD"),
                createSubscription(id = 2L, merchant = "Spotify", amount = 20.0, currency = "EUR")
            )
        )
        // No USD rate: the USD row must be excluded, not added raw.
        val aggregator = fakeMultiCurrencyRepository(rates = emptyMap())
        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockCurrencyRepo(), multiCurrencyRepository = aggregator)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(20.0, state.totalMonthlyCost, 0.0001)
        assertEquals("EUR", state.totalCurrency)
        assertEquals(SubscriptionTotalStatus.PARTIAL, state.totalStatus)
    }

    @Test
    fun `total is unavailable when no row converts`() = runTest(testDispatcher) {
        configureRepositoryWithSubscriptions(
            listOf(
                createSubscription(id = 1L, merchant = "Netflix", amount = 10.0, currency = "USD"),
                createSubscription(id = 2L, merchant = "Hulu", amount = 8.0, currency = "GBP")
            )
        )
        val aggregator = fakeMultiCurrencyRepository(rates = emptyMap())
        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockCurrencyRepo(), multiCurrencyRepository = aggregator)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SubscriptionTotalStatus.UNAVAILABLE, state.totalStatus)
        assertEquals(null, state.totalCurrency)
        assertEquals(0.0, state.totalMonthlyCost, 0.0001)
        assertEquals(2, state.activeCount)
    }

    @Test
    fun `total is unavailable and does not aggregate when home currency cannot be resolved`() = runTest(testDispatcher) {
        configureRepositoryWithSubscriptions(
            listOf(createSubscription(id = 1L, merchant = "Netflix", amount = 10.0, currency = "EUR"))
        )
        val currencyRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencyRepo.homeCurrency() } returns flowOf("EUR")
        coEvery { currencyRepo.resolveHomeCurrency() } returns HomeCurrencyResolution.Failed("HOME_CURRENCY_UNAVAILABLE")
        val aggregator = fakeMultiCurrencyRepository()
        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = currencyRepo, multiCurrencyRepository = aggregator)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SubscriptionTotalStatus.UNAVAILABLE, state.totalStatus)
        assertEquals(null, state.totalCurrency)
        assertEquals(0.0, state.totalMonthlyCost, 0.0001)
        coVerify(exactly = 0) { aggregator.aggregateDisplayAmounts(any(), any(), any()) }
    }

    @Test
    fun `cancellation during aggregation propagates instead of surfacing a load error`() = runTest(testDispatcher) {
        configureRepositoryWithSubscriptions(
            listOf(createSubscription(id = 1L, merchant = "Netflix", amount = 10.0))
        )
        val aggregator = mockk<MultiCurrencyRepository>()
        coEvery { aggregator.aggregateDisplayAmounts(any(), any(), any()) } throws CancellationException("cancelled")
        viewModel = SubscriptionManagementViewModel(repository, timeProvider, subscriptionManagerEngine = mockk<SubscriptionManagerEngine>(relaxed = true), currencySettingsRepository = mockCurrencyRepo(), multiCurrencyRepository = aggregator)

        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.error)
    }

    private fun mockCurrencyRepo(): CurrencySettingsRepository {
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns flowOf("EUR")
        coEvery { repo.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        return repo
    }

    /**
     * Mirrors the canonical aggregate contract of [MultiCurrencyRepository.aggregateDisplayAmounts]:
     * same-currency rows pass through, rows with a rate are converted, rows without are recorded as
     * failures (partial), and all-failed input is Unavailable.
     */
    private fun fakeMultiCurrencyRepository(rates: Map<String, Double> = emptyMap()): MultiCurrencyRepository {
        val aggregator = mockk<MultiCurrencyRepository>()
        coEvery { aggregator.aggregateDisplayAmounts(any(), any(), any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val amounts = invocation.args[0] as List<Pair<Double, String>>
            val target = CurrencyCode(invocation.args[2] as String)
            var total = 0.0
            val buckets = mutableListOf<MoneyBucket>()
            val failures = mutableListOf<ConversionFailure>()
            amounts.forEach { (amount, currency) ->
                val code = CurrencyCode(currency)
                buckets += MoneyBucket(code, amount, 1)
                val rate = if (code == target) 1.0 else rates[currency]
                if (rate == null) {
                    failures += ConversionFailure(MoneyAmount(amount, code), target, FailureReason.MISSING_RATE, 1)
                } else {
                    total += amount * rate
                }
            }
            if (amounts.isNotEmpty() && failures.size == amounts.size) {
                MoneyAggregateResult.Unavailable(
                    reason = MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE.name,
                    requestedRateBasis = RateBasis.LATEST_AVAILABLE
                )
            } else {
                MoneyAggregateResult.Available(MoneyAggregate(total, target, buckets, failures))
            }
        }
        return aggregator
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
        // RP-04 A1: mirror the setActive contract — but ONLY the isActive flip.
        // The REAL coordinator activate/deactivate also regenerates/purges derived
        // state (occurrences, planned rows, reminders); this in-memory store stub
        // deliberately does NOT model that derived-state lifecycle, so these tests
        // verify UI routing/reload behavior only, not coordinator side effects.
        coEvery { repository.setActive(any(), any()) } coAnswers {
            val subscriptionId = invocation.args[0] as Long
            val active = invocation.args[1] as Boolean
            subscriptionsStore[subscriptionId]?.let {
                subscriptionsStore[subscriptionId] = it.copy(isActive = active)
            }
            Unit
        }
        coEvery { repository.updateSubscriptionCategory(any(), any()) } coAnswers {
            val subscriptionId = invocation.args[0] as Long
            val category = invocation.args[1] as String
            subscriptionsStore[subscriptionId]?.let {
                subscriptionsStore[subscriptionId] = it.copy(subscriptionCategory = category)
            }
            Unit
        }
    }

    private fun createSubscription(
        id: Long,
        merchant: String,
        amount: Double,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        isActive: Boolean = true,
        currency: String = "EUR"
    ): ManualRecurringExpense {
        return ManualRecurringExpense(
            id = id,
            merchant = merchant,
            amount = amount,
            currency = currency,
            frequency = frequency,
            nextDate = fixedNow,
            isSubscription = true,
            isActive = isActive
        )
    }
}