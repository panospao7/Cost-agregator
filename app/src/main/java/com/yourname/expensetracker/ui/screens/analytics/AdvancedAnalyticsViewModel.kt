package com.yourname.expensetracker.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsDashboard
import com.yourname.expensetracker.domain.analytics.AnalyticsDashboardData
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AdvancedAnalyticsViewModel @Inject constructor(
    private val analyticsDashboard: AdvancedAnalyticsDashboard,
    private val timeProvider: TimeProvider
) : ViewModel() {
    
    private val _dashboardData = MutableStateFlow<AnalyticsDashboardData?>(null)
    val dashboardData: StateFlow<AnalyticsDashboardData?> = _dashboardData.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    init {
        loadDashboardData()
    }
    
    private fun loadDashboardData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val now = timeProvider.now()
                val thirtyDaysAgo = TimePeriodUtils.addDays(now, -30)
                
                val data = analyticsDashboard.generateDashboardData(thirtyDaysAgo, now)
                _dashboardData.value = data
            } catch (e: Exception) {
                _dashboardData.value = null
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun refresh() {
        loadDashboardData()
    }
}
