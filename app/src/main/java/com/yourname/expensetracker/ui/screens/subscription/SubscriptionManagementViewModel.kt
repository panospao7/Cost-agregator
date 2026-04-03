package com.yourname.expensetracker.ui.screens.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.SubscriptionPriceHistoryDao
import com.yourname.expensetracker.data.database.dao.SubscriptionUsageDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.SubscriptionPriceHistory
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

/**
 * UI state for subscription management screen.
 */
data class SubscriptionManagementUiState(
    val subscriptions: List<SubscriptionInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val totalMonthlyCost: Double = 0.0,
    val totalAnnualCost: Double = 0.0,
    val activeCount: Int = 0,
    val inactiveCount: Int = 0,
    val selectedSubscription: SubscriptionInfo? = null
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
    private val subscriptionDao: ManualRecurringExpenseDao,
    private val priceHistoryDao: SubscriptionPriceHistoryDao,
    private val usageDao: SubscriptionUsageDao,
    private val timeProvider: TimeProvider
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(SubscriptionManagementUiState())
    val uiState: StateFlow<SubscriptionManagementUiState> = _uiState.asStateFlow()
    
    init {
        loadSubscriptions()
    }
    
    private fun loadSubscriptions() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                val allSubscriptions = subscriptionDao.getAllActiveSubscriptions()
                
                val subscriptionInfos = coroutineScope {
                    allSubscriptions.map { subscription ->
                        async {
                            val subscriptionId = subscription.id ?: 0L
                            if (subscriptionId == 0L) return@async null
                            
                            val priceHistory = priceHistoryDao
                                .getPriceHistoryForSubscription(subscriptionId)
                                .first()
                            
                            // Calculate usage and cost per use with frequency-aware window
                            val (usageCount, costPerUse) = calculateUsageAndCostPerUse(subscription)
                            
                            // Calculate price change
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
                
                // Calculate totals
                val totalMonthly = subscriptionInfos.filter { it.subscription.isActive }
                    .sumOf { calculateMonthlyCost(it.subscription) }
                val totalAnnual = totalMonthly * 12
                
                _uiState.value = SubscriptionManagementUiState(
                    subscriptions = subscriptionInfos,
                    isLoading = false,
                    error = null,
                    totalMonthlyCost = totalMonthly,
                    totalAnnualCost = totalAnnual,
                    activeCount = subscriptionInfos.count { it.subscription.isActive },
                    inactiveCount = subscriptionInfos.count { !it.subscription.isActive }
                )
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
        val usageCount = usageDao.getUsageCountSince(subscriptionId, windowStart)
        
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
                val usage = com.yourname.expensetracker.data.database.entity.SubscriptionUsage(
                    subscriptionId = subscriptionId,
                    usedAt = timeProvider.now()
                )
                usageDao.insert(usage)
                
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
                val subscription = subscriptionDao.getById(subscriptionId)
                subscription?.let {
                    val updated = it.copy(isActive = !it.isActive)
                    subscriptionDao.update(updated)
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
                subscriptionDao.deleteById(subscriptionId)
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
                
                val id = subscriptionDao.insert(subscription)
                
                // Record initial price
                val priceHistory = SubscriptionPriceHistory(
                    subscriptionId = id,
                    amount = amount,
                    changeReason = "Initial subscription"
                )
                priceHistoryDao.insert(priceHistory)
                
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
     * Calculate monthly cost from subscription amount and frequency.
     */
    private fun calculateMonthlyCost(subscription: ManualRecurringExpense): Double {
        return RecurrenceCalculator.toMonthlyAmount(subscription.amount, subscription.frequency)
    }
}
