package com.yourname.expensetracker.ui.screens.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.SubscriptionCandidate
import com.yourname.expensetracker.data.database.entity.SubscriptionPriceHistory
import com.yourname.expensetracker.data.database.entity.SubscriptionUsage
import com.yourname.expensetracker.data.repository.SubscriptionManagementRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
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
    val activeCount: Int = 0,
    val inactiveCount: Int = 0,
    val detectedCount: Int = 0,
    val selectedSubscription: SubscriptionInfo? = null,
    val referenceNowMillis: Long = 0L
)

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
    currencySettingsRepository: CurrencySettingsRepository
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
                    
                    // Calculate totals
                    val totalMonthly = subscriptionInfos.filter { it.subscription.isActive }
                        .sumOf { calculateMonthlyCost(it.subscription) }
                    val totalAnnual = totalMonthly * 12
                    
                    _uiState.value = SubscriptionManagementUiState(
                        subscriptions = subscriptionInfos,
                        detectedCandidates = candidates,
                        isLoading = false,
                        error = null,
                        totalMonthlyCost = totalMonthly,
                        totalAnnualCost = totalAnnual,
                        activeCount = subscriptionInfos.count { it.subscription.isActive },
                        inactiveCount = subscriptionInfos.count { !it.subscription.isActive },
                        detectedCount = candidates.size,
                        referenceNowMillis = timeProvider.now()
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load subscriptions: ${e.message}"
                )
            }
        }
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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to record usage: ${e.message}"
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
                    val updated = it.copy(isActive = !it.isActive)
                    repository.updateSubscription(updated)
                    loadSubscriptions()
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to update subscription: ${e.message}"
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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to delete subscription: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Add a new subscription.
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
                val subscription = ManualRecurringExpense(
                    merchant = merchant,
                    amount = amount,
                    frequency = frequency,
                    subscriptionCategory = category,
                    nextDate = nextDate,
                    isSubscription = true,
                    isActive = true
                )
                
                val id = repository.insertSubscription(subscription)
                
                // REC-8: Record initial baseline price entry
                // W04: Set recordedAt to timeProvider.now() to avoid the 0L sentinel
                val priceHistory = SubscriptionPriceHistory(
                    subscriptionId = id,
                    amount = amount,
                    recordedAt = timeProvider.now(),
                    changeReason = "BASELINE: Initial subscription"
                )
                repository.insertPriceHistory(priceHistory)
                
                loadSubscriptions()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to add subscription: ${e.message}"
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
        viewModelScope.launch {
            try {
                // Map detected interval to RecurrenceFrequency
                val frequency = when (candidate.detectedInterval) {
                    "weekly" -> RecurrenceFrequency.WEEKLY
                    "biweekly" -> RecurrenceFrequency.BIWEEKLY
                    "monthly" -> RecurrenceFrequency.MONTHLY
                    "quarterly" -> RecurrenceFrequency.QUARTERLY
                    "semiannual" -> RecurrenceFrequency.SEMI_ANNUALLY
                    "annual" -> RecurrenceFrequency.ANNUALLY
                    else -> RecurrenceFrequency.MONTHLY
                }
                
                // TODO (W23): Use RecurrenceCalculator.nextOccurrence() instead of adding fixed day offsets.
                // Fixed offsets (30 days, 90 days, 365 days) don't account for variable month lengths.
                // Create subscription from candidate
                val subscription = ManualRecurringExpense(
                    merchant = candidate.merchant,
                    amount = candidate.averageAmount,
                    currency = candidate.currency,
                    frequency = frequency,
                    nextDate = candidate.lastSeen + when (frequency) {
                        RecurrenceFrequency.WEEKLY -> 7L * TimePeriodUtils.DAY_IN_MILLIS
                        RecurrenceFrequency.BIWEEKLY -> 14L * TimePeriodUtils.DAY_IN_MILLIS
                        RecurrenceFrequency.MONTHLY -> 30L * TimePeriodUtils.DAY_IN_MILLIS
                        RecurrenceFrequency.QUARTERLY -> 90L * TimePeriodUtils.DAY_IN_MILLIS
                        RecurrenceFrequency.SEMI_ANNUALLY -> 180L * TimePeriodUtils.DAY_IN_MILLIS
                        RecurrenceFrequency.ANNUALLY -> 365L * TimePeriodUtils.DAY_IN_MILLIS
                        RecurrenceFrequency.IRREGULAR -> 30L * TimePeriodUtils.DAY_IN_MILLIS
                    },
                    isSubscription = true,
                    isActive = true
                )
                
                val subscriptionId = repository.insertSubscription(subscription)
                
                // REC-8: Record initial baseline price entry
                // W04: Set recordedAt to timeProvider.now() to avoid the 0L sentinel
                val priceHistory = SubscriptionPriceHistory(
                    subscriptionId = subscriptionId,
                    amount = candidate.averageAmount,
                    recordedAt = timeProvider.now(),
                    changeReason = "BASELINE: Auto-detected from notifications"
                )
                repository.insertPriceHistory(priceHistory)
                
                // Mark candidate as converted
                repository.markCandidateAsConverted(candidate.id, subscriptionId, timeProvider.now())
                
                loadSubscriptions()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to accept candidate: ${e.message}"
                )
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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to reject candidate: ${e.message}"
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
