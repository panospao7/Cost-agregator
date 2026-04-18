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

sealed class AnalyticsUiState {
    data object Loading : AnalyticsUiState()
    data class Success(val data: AnalyticsDashboardData) : AnalyticsUiState()
    data class Error(val message: String) : AnalyticsUiState()
}

@HiltViewModel
class AdvancedAnalyticsViewModel @Inject constructor(
    private val analyticsDashboard: AdvancedAnalyticsDashboard,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow<AnalyticsUiState>(AnalyticsUiState.Loading)
    val uiState: StateFlow<AnalyticsUiState> = _uiState.asStateFlow()

    init {
        loadDashboardData()
    }

    private fun loadDashboardData() {
        viewModelScope.launch {
            _uiState.value = AnalyticsUiState.Loading
            try {
                val now = timeProvider.now()
                val thirtyDaysAgo = TimePeriodUtils.addDays(now, -30)

                val data = analyticsDashboard.generateDashboardData(thirtyDaysAgo, now)
                _uiState.value = AnalyticsUiState.Success(data)
            } catch (e: Exception) {
                _uiState.value = AnalyticsUiState.Error(e.message ?: "Load failed")
            }
        }
    }
    
    fun refresh() {
        loadDashboardData()
    }
}
