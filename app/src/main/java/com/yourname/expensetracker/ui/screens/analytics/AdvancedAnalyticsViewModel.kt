package com.yourname.expensetracker.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.analytics.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import javax.inject.Inject

@HiltViewModel
class AdvancedAnalyticsViewModel @Inject constructor(
    private val analyticsEngine: AdvancedAnalyticsEngine
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(AnalyticsUiState())
    val uiState: StateFlow<AnalyticsUiState> = _uiState.asStateFlow()
    
    init {
        // Initial load
        loadData(AnalyticsPeriod.MONTH)
    }
    
    fun setPeriod(period: AnalyticsPeriod) {
        if (_uiState.value.selectedPeriod == period) return
        loadData(period)
    }
    
    fun refresh() {
        loadData(uiState.value.selectedPeriod, isRefresh = true)
    }

    private fun loadData(period: AnalyticsPeriod, isRefresh: Boolean = false) {
        viewModelScope.launch {
            // 1. Start loading, keep old data
            _uiState.update { 
                it.copy(
                    isLoading = !isRefresh, 
                    isRefreshing = isRefresh,
                    selectedPeriod = period, 
                    error = null
                ) 
            }
            
            try {
                // 2. Resolve PeriodRange (fast)
                val range = analyticsEngine.getPeriodRange(period)
                
                // 3. Fetch all analytics in parallel (Async)
                // We use async to avoid sequential blocking
                val categoryDeferred = async { analyticsEngine.getCategoryAnalytics(range) }
                val merchantDeferred = async { analyticsEngine.getMerchantAnalytics(range, limit = 20) }
                val patternsDeferred = async { analyticsEngine.getSpendingPatterns(range) }
                val statsDeferred = async { analyticsEngine.getStatisticalInsights(range) }
                
                val categoryData = categoryDeferred.await()
                val merchantData = merchantDeferred.await()
                val patternsData = patternsDeferred.await()
                val statsData = statsDeferred.await()
                
                // 4. Update state with new data
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        categoryAnalytics = categoryData,
                        merchantAnalytics = merchantData,
                        spendingPatterns = patternsData,
                        statisticalInsights = statsData
                    )
                }
                
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false, 
                        isRefreshing = false,
                        error = "Failed to load data: ${e.message}"
                    ) 
                }
            }
        }
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    fun getCurrentDateRange(): Pair<Long, Long> {
        val range = analyticsEngine.getPeriodRange(uiState.value.selectedPeriod, computeComparison = false)
        return Pair(range.startMs, range.endMs)
    }
}

data class AnalyticsUiState(
    val selectedPeriod: AnalyticsPeriod = AnalyticsPeriod.MONTH,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val categoryAnalytics: List<EnhancedCategoryAnalytics> = emptyList(),
    val merchantAnalytics: List<EnhancedMerchantAnalytics> = emptyList(),
    val spendingPatterns: SpendingPatternAnalysis? = null,
    val statisticalInsights: StatisticalInsights? = null
)