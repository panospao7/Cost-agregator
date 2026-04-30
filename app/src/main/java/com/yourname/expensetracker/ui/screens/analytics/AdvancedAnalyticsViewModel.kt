package com.yourname.expensetracker.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsDashboard
import com.yourname.expensetracker.domain.analytics.AnalyticsDashboardData
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed class AnalyticsUiState {
    data object Loading : AnalyticsUiState()
    data class Success(
        val data: AnalyticsDashboardData,
        val homeCurrency: String,
        val latestRateTimestamp: Long?
    ) : AnalyticsUiState()
    data class Error(val message: String) : AnalyticsUiState()
}

@HiltViewModel
class AdvancedAnalyticsViewModel @Inject constructor(
    private val analyticsDashboard: AdvancedAnalyticsDashboard,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val refreshNonce = kotlinx.coroutines.flow.MutableStateFlow(0)

    val uiState: StateFlow<AnalyticsUiState> = combine(
        currencySettingsRepository.homeCurrency().catch { emit("EUR") },
        currencySettingsRepository.lastRateUpdate().catch { emit(0L) },
        refreshNonce
    ) { homeCurrency, latestRateTimestamp, _ ->
        homeCurrency to latestRateTimestamp
    }
        .flatMapLatest { (homeCurrency, latestRateTimestamp) ->
            flow {
                emit(AnalyticsUiState.Loading)
                try {
                    val now = timeProvider.now()
                    val thirtyDaysAgo = TimePeriodUtils.addDays(now, -30)
                    val data = analyticsDashboard.generateDashboardData(thirtyDaysAgo, now)
                    emit(
                        AnalyticsUiState.Success(
                            data = data,
                            homeCurrency = homeCurrency,
                            latestRateTimestamp = latestRateTimestamp.takeIf { it > 0L }
                        )
                    )
                } catch (e: Exception) {
                    emit(AnalyticsUiState.Error(e.message ?: "Load failed"))
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AnalyticsUiState.Loading
        )
    
    fun refresh() {
        refreshNonce.value = refreshNonce.value + 1
    }
}
