package com.yourname.expensetracker.ui.screens.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.SubscriptionCandidate
import com.yourname.expensetracker.data.database.entity.SubscriptionPriceHistory
import com.yourname.expensetracker.data.database.entity.SubscriptionUsage
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.data.repository.SubscriptionManagementRepository
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.SupportedCurrency
import kotlinx.coroutines.CancellationException
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.subscription.CreateSubscriptionRequest
import com.yourname.expensetracker.domain.subscription.SubscriptionManagerEngine
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * UI state for subscription management screen.
 */
data class SubscriptionManagementUiState(
    val subscriptions: List<SubscriptionInfo> = emptyList(),
    val detectedCandidates: List<SubscriptionCandidate> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val totalMonthlyCost: Double = 0.0,
    val totalAnnualCost: Double = 0.0,
    /** Currency of [totalMonthlyCost]/[totalAnnualCost]; null when the total is unavailable. */
    val totalCurrency: String? = null,
    /** HZ01-A: conversion state of the totals; UNAVAILABLE totals must never be rendered as amounts. */
    val totalStatus: SubscriptionTotalStatus = SubscriptionTotalStatus.AVAILABLE,
    val activeCount: Int = 0,
    val inactiveCount: Int = 0,
    val detectedCount: Int = 0,
    val selectedSubscription: SubscriptionInfo? = null,
    val referenceNowMillis: Long = 0L,
    /** S12-015: IDs of subscriptions/candidates currently being mutated */
    val mutatingIds: Set<Long> = emptySet(),
    /** S12-013: true when add-subscription succeeded — dialog should close */
    val addSubscriptionSuccess: Boolean = false
) {
    val loadableState: com.yourname.expensetracker.ui.model.LoadableUiState<List<SubscriptionInfo>>
        get() = when {
            isLoading -> com.yourname.expensetracker.ui.model.LoadableUiState.Loading
            error != null -> com.yourname.expensetracker.ui.model.LoadableUiState.Error(com.yourname.expensetracker.domain.model.UiText.DynamicString(error))
            subscriptions.isEmpty() -> com.yourname.expensetracker.ui.model.LoadableUiState.Empty(com.yourname.expensetracker.domain.model.UiText.DynamicString("No subscriptions tracked"))
            else -> com.yourname.expensetracker.ui.model.LoadableUiState.Data(subscriptions)
        }
}

enum class SubscriptionTotalStatus { AVAILABLE, PARTIAL, UNAVAILABLE }

data class SubscriptionInfo(
    val subscription: ManualRecurringExpense,
    val priceHistory: List<SubscriptionPriceHistory>,
    val monthlyUsage: Int,
    val costPerUse: Double,
    val priceChange: PriceChangeInfo?
)

data class PriceChangeInfo(
    val previousPrice: Double,
    val currentPrice: Double,
    val changeAmount: Double,
    val changePercentage: Double,
    val isIncrease: Boolean
)

@HiltViewModel
class SubscriptionManagementViewModel @Inject constructor(
    private val repository: SubscriptionManagementRepository,
    private val timeProvider: TimeProvider,
    private val subscriptionManagerEngine: SubscriptionManagerEngine,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val multiCurrencyRepository: MultiCurrencyRepository
) : ViewModel() {

    val homeCurrency: Flow<String> = currencySettingsRepository.homeCurrency()
    
    private val _uiState = MutableStateFlow(SubscriptionManagementUiState())
    val uiState: StateFlow<SubscriptionManagementUiState> = _uiState.asStateFlow()
    
    init {
        loadSubscriptions()
    }
    
    private fun loadSubscriptions() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                coroutineScope {
                    // Load active subscriptions
                    val subscriptionsDeferred = async {
                        val allSubscriptions = repository.getAllActiveSubscriptions()
                        allSubscriptions.map { subscription ->
                            async {
                                val subscriptionId = subscription.id ?: 0L
                                if (subscriptionId == 0L) return@async null
                                
                                val priceHistory = repository
                                    .getPriceHistoryForSubscription(subscriptionId)
                                    .first()
                                
                                val (usageCount, costPerUse) = calculateUsageAndCostPerUse(subscription)
                                val priceChange = calculatePriceChange(priceHistory)
                                
                                SubscriptionInfo(
                                    subscription = subscription,
                                    priceHistory = priceHistory,
                                    monthlyUsage = usageCount,
                                    costPerUse = costPerUse,
                                    priceChange = priceChange
                                )
                            }
                        }.awaitAll().filterNotNull()
                    }
                    
                    // Load detected candidates
                    val candidatesDeferred = async {
                        repository.getPendingCandidates()
                    }
                    
                    val subscriptionInfos = subscriptionsDeferred.await()
                    val candidates = candidatesDeferred.await()
                    
                    // S12-011 / HZ01-A: Normalize subscription amounts to home currency through
                    // the canonical aggregate. Rows that cannot convert are excluded (partial);
                    // when nothing converts or the home currency is unreadable the total is
                    // Unavailable. Never a raw sum labelled as home currency.
                    val monthlyTotal = computeMonthlyTotal(
                        subscriptionInfos.filter { it.subscription.isActive }
                    )
                    val totalMonthly = monthlyTotal.monthly
                    val totalAnnual = totalMonthly * 12

                    _uiState.value = SubscriptionManagementUiState(
                        subscriptions = subscriptionInfos,
                        detectedCandidates = candidates,
                        isLoading = false,
                        error = null,
                        totalMonthlyCost = totalMonthly,
                        totalAnnualCost = totalAnnual,
                        totalCurrency = monthlyTotal.currency,
                        totalStatus = monthlyTotal.status,
                        activeCount = subscriptionInfos.count { it.subscription.isActive },
                        inactiveCount = subscriptionInfos.count { !it.subscription.isActive },
                        detectedCount = candidates.size,
                        referenceNowMillis = timeProvider.now()
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("SUBSCRIPTION_LOAD_FAILED class=%s", e::class.java.simpleName)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load subscriptions"
                )
            }
        }
    }

    private data class MonthlyTotal(
        val monthly: Double,
        val currency: String?,
        val status: SubscriptionTotalStatus
    )

    private suspend fun computeMonthlyTotal(activeInfos: List<SubscriptionInfo>): MonthlyTotal {
        if (activeInfos.isEmpty()) {
            return MonthlyTotal(0.0, null, SubscriptionTotalStatus.AVAILABLE)
        }
        val targetCurrency = resolveHomeCurrencyCode()
            ?: return MonthlyTotal(0.0, null, SubscriptionTotalStatus.UNAVAILABLE)
        val amounts = activeInfos.map { info ->
            calculateMonthlyCost(info.subscription) to info.subscription.currency
        }
        return when (
            val result = multiCurrencyRepository.aggregateDisplayAmounts(
                amounts = amounts,
                transactionCounts = List(amounts.size) { 1 },
                targetCurrency = targetCurrency
            )
        ) {
            is MoneyAggregateResult.Available -> MonthlyTotal(
                monthly = result.aggregate.displayAmount,
                currency = result.aggregate.displayCurrency.code,
                status = if (result.aggregate.isPartial) {
                    SubscriptionTotalStatus.PARTIAL
                } else {
                    SubscriptionTotalStatus.AVAILABLE
                }
            )
            is MoneyAggregateResult.Unavailable ->
                MonthlyTotal(0.0, null, SubscriptionTotalStatus.UNAVAILABLE)
        }
    }

    private suspend fun resolveHomeCurrencyCode(): String? {
        val resolved = try {
            currencySettingsRepository.resolveHomeCurrency().currencyOrNull?.code
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return resolved?.let { SupportedCurrency.fromActiveCode(it)?.code }
    }

    private suspend fun calculateUsageAndCostPerUse(
        subscription: ManualRecurringExpense
    ): Pair<Int, Double> {
        val subscriptionId = subscription.id ?: return 0 to subscription.amount
        
        // Calculate appropriate lookback window based on subscription frequency
        val lookbackWindowMs = when (subscription.frequency) {
            RecurrenceFrequency.WEEKLY -> 7L * TimePeriodUtils.DAY_IN_MILLIS      // 1 week
            RecurrenceFrequency.BIWEEKLY -> 14L * TimePeriodUtils.DAY_IN_MILLIS  // 2 weeks
            RecurrenceFrequency.MONTHLY -> 30L * TimePeriodUtils.DAY_IN_MILLIS     // 1 month
            RecurrenceFrequency.QUARTERLY -> 90L * TimePeriodUtils.DAY_IN_MILLIS   // 3 months
            RecurrenceFrequency.SEMI_ANNUALLY -> 180L * TimePeriodUtils.DAY_IN_MILLIS // 6 months
            RecurrenceFrequency.ANNUALLY -> 365L * TimePeriodUtils.DAY_IN_MILLIS   // 1 year
            RecurrenceFrequency.IRREGULAR -> 30L * TimePeriodUtils.DAY_IN_MILLIS    // Default to 1 month
        }

        val windowStart = timeProvider.now() - lookbackWindowMs
        val usageCount = repository.getUsageCountSince(subscriptionId, windowStart)
        
        // Calculate cost per use with frequency context
        val periodCost = when (subscription.frequency) {
            RecurrenceFrequency.WEEKLY -> subscription.amount
            RecurrenceFrequency.BIWEEKLY -> subscription.amount
            RecurrenceFrequency.MONTHLY -> subscription.amount
            RecurrenceFrequency.QUARTERLY -> subscription.amount / 3  // Monthly equivalent
            RecurrenceFrequency.SEMI_ANNUALLY -> subscription.amount / 6
            RecurrenceFrequency.ANNUALLY -> subscription.amount / 12
            RecurrenceFrequency.IRREGULAR -> subscription.amount
        }
        
        val costPerUse = when {
            usageCount == 0 -> periodCost // No usage yet, show period cost as baseline
            subscription.frequency == RecurrenceFrequency.IRREGULAR -> periodCost / usageCount
            else -> periodCost / usageCount
        }
        
        return usageCount to costPerUse
    }
    
    private fun calculatePriceChange(history: List<SubscriptionPriceHistory>): PriceChangeInfo? {
        if (history.size < 2) return null
        
        val sorted = history.sortedBy { it.recordedAt }
        val previous = sorted[sorted.size - 2]
        val current = sorted.last()
        
        val change = current.amount - previous.amount
        val percentage = if (previous.amount > 0) (change / previous.amount) * 100 else 0.0
        
        return PriceChangeInfo(
            previousPrice = previous.amount,
            currentPrice = current.amount,
            changeAmount = change,
            changePercentage = percentage,
            isIncrease = change > 0
        )
    }
    
    /**
     * Record usage for a subscription.
     */
    fun recordUsage(subscriptionId: Long) {
        viewModelScope.launch {
            try {
                val usage = SubscriptionUsage(
                    subscriptionId = subscriptionId,
                    usedAt = timeProvider.now()
                )
                repository.insertUsage(usage)
                
                // Reload to update usage stats
                loadSubscriptions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("SUBSCRIPTION_RECORD_USAGE_FAILED class=%s", e::class.java.simpleName)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to record usage"
                )
            }
        }
    }
    
    /**
     * Toggle subscription active status.
     */
    fun toggleSubscriptionStatus(subscriptionId: Long) {
        viewModelScope.launch {
            try {
                val subscription = repository.getSubscriptionById(subscriptionId)
                subscription?.let {
                    // RP-04 A1: toggle routes through setActive → coordinator
                    // activateRule/deactivateRule (not a bare isActive update).
                    repository.setActive(subscriptionId, !it.isActive)
                    loadSubscriptions()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("SUBSCRIPTION_UPDATE_FAILED class=%s", e::class.java.simpleName)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to update subscription"
                )
            }
        }
    }
    
    /**
     * Delete a subscription.
     */
    fun deleteSubscription(subscriptionId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteSubscriptionById(subscriptionId)
                loadSubscriptions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("SUBSCRIPTION_DELETE_FAILED class=%s", e::class.java.simpleName)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to delete subscription"
                )
            }
        }
    }
    
    /**
     * Add a new subscription.
     *
     * W22-FIXED: Now routes through [SubscriptionManagerEngine.validateAndCreate]
     * which enforces:
     * - positive amount (require(amount > 0))
     * - valid 3-letter currency code
     * - non-blank merchant
     * - createdAt timestamp via timeProvider.now()
     * - baseline price history recording
     *
     * Entry points that still need validateAndCreate:
     * - Any candidate-conversion path (markCandidateAsConverted → auto-create)
     * - NotificationSubscriptionDetector auto-creation path
     *
     * RP-04 A1: no `insertSubscription` was added to SubscriptionManagementRepository —
     * rule creation already routes through SubscriptionManagerEngine →
     * RecurringExpenseRepository.insert → RecurringRuleLifecycleCoordinator.createRule,
     * and a duplicate creation entry point would widen the mutation surface.
     */
    fun addSubscription(
        merchant: String,
        amount: Double,
        frequency: RecurrenceFrequency,
        category: String?,
        nextDate: Long
    ) {
        viewModelScope.launch {
            try {
                val hc = currencySettingsRepository.homeCurrency().first()
                val request = CreateSubscriptionRequest(
                    merchant = merchant,
                    amount = amount,
                    currency = hc,
                    frequency = frequency,
                    startDate = nextDate
                )
                subscriptionManagerEngine.validateAndCreate(request).fold(
                    onSuccess = { created ->
                        if (category != null) {
                            // RP-04 A1: display-only carve-out — subscriptionCategory is
                            // a display column (only consumer is the screen label; no
                            // occurrence generator or matcher reads it), so it must not
                            // trigger a full coordinator rule update.
                            repository.updateSubscriptionCategory(created.id, category)
                        }
                        // S12-013: Signal success so dialog can close
                        _uiState.value = _uiState.value.copy(addSubscriptionSuccess = true)
                        loadSubscriptions()
                    },
                    onFailure = { error -> throw error }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // F-R18-a: bounded UI message and class-name-only log; raw exception text
                // (operation name / write-barrier mode / validation detail) never reaches the UI.
                Timber.w("SUBSCRIPTION_ADD_FAILED class=%s", e::class.java.simpleName)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to add subscription"
                )
            }
        }
    }
    
    /**
     * Refresh data.
     */
    fun refresh() {
        loadSubscriptions()
    }
    
    /**
     * Clear error.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearAddSubscriptionSuccess() {
        _uiState.value = _uiState.value.copy(addSubscriptionSuccess = false)
    }
    
    /**
     * Select subscription for detail view.
     */
    fun selectSubscription(subscription: SubscriptionInfo?) {
        _uiState.value = _uiState.value.copy(selectedSubscription = subscription)
    }
    
    /**
     * Accept a detected subscription candidate and convert it to an active subscription.
     */
    fun acceptCandidate(candidate: SubscriptionCandidate) {
        val candidateId = candidate.id ?: return
        // S12-015: Idempotency guard
        if (_uiState.value.mutatingIds.contains(candidateId)) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(mutatingIds = _uiState.value.mutatingIds + candidateId)
            try {
                val frequency = when (candidate.detectedInterval) {
                    "weekly" -> RecurrenceFrequency.WEEKLY
                    "biweekly" -> RecurrenceFrequency.BIWEEKLY
                    "monthly" -> RecurrenceFrequency.MONTHLY
                    "quarterly" -> RecurrenceFrequency.QUARTERLY
                    "semiannual" -> RecurrenceFrequency.SEMI_ANNUALLY
                    "annual" -> RecurrenceFrequency.ANNUALLY
                    else -> RecurrenceFrequency.MONTHLY
                }
                val nextDate = RecurrenceCalculator.nextOccurrence(
                    lastSeen = candidate.lastSeen,
                    frequency = frequency
                )
                subscriptionManagerEngine.acceptCandidate(candidate, frequency, nextDate)
                loadSubscriptions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("SUBSCRIPTION_ACCEPT_CANDIDATE_FAILED class=%s", e::class.java.simpleName)
                _uiState.value = _uiState.value.copy(error = "Failed to accept candidate")
            } finally {
                _uiState.value = _uiState.value.copy(mutatingIds = _uiState.value.mutatingIds - candidateId)
            }
        }
    }
    
    /**
     * Reject a detected subscription candidate.
     */
    fun rejectCandidate(candidateId: Long) {
        viewModelScope.launch {
            try {
                repository.markCandidateAsRejected(candidateId, timeProvider.now())
                loadSubscriptions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("SUBSCRIPTION_REJECT_CANDIDATE_FAILED class=%s", e::class.java.simpleName)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to reject candidate"
                )
            }
        }
    }

    /**
     * Calculate monthly cost from subscription amount and frequency.
     */
    private fun calculateMonthlyCost(subscription: ManualRecurringExpense): Double {
        return RecurrenceCalculator.toMonthlyAmount(subscription.amount, subscription.frequency)
    }
}
