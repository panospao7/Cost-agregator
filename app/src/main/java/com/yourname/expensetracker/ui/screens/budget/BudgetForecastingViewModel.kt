package com.yourname.expensetracker.ui.screens.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import com.yourname.expensetracker.domain.budget.BudgetForecastingEngine
import com.yourname.expensetracker.domain.budget.BudgetRecommendationEngine
import com.yourname.expensetracker.domain.budget.BudgetRecommendation
import com.yourname.expensetracker.domain.budget.BudgetRecommendationBudget
import com.yourname.expensetracker.domain.budget.BudgetRecommendationForecast
import com.yourname.expensetracker.domain.budget.BudgetRecommendationRiskLevel
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for budget forecasting screen.
 */
data class BudgetForecastUiState(
    val budget: Budget? = null,
    val forecast: BudgetForecast? = null,
    val recommendations: List<BudgetRecommendation> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** S8-013: null until loaded — never empty string */
    val homeCurrency: String? = null
)

@HiltViewModel
class BudgetForecastingViewModel @Inject constructor(
    private val forecastingEngine: BudgetForecastingEngine,
    private val recommendationEngine: BudgetRecommendationEngine,
    private val currencySettingsRepository: CurrencySettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BudgetForecastUiState())
    val uiState: StateFlow<BudgetForecastUiState> = _uiState.asStateFlow()
    private var lastForecastPeriodDays: Int = 30

    /** S8-014: Race guard — cancel prior forecast job */
    private var forecastJob: kotlinx.coroutines.Job? = null
    private var forecastRequestId = 0L

    init {
        viewModelScope.launch {
            currencySettingsRepository.homeCurrency().collect { hc ->
                _uiState.update { it.copy(homeCurrency = hc) }
            }
        }
    }

    fun generateForecast(budget: Budget, forecastPeriodDays: Int = 30) {
        // S8-014: Cancel prior job and increment request ID
        val requestId = ++forecastRequestId
        forecastJob?.cancel()

        lastForecastPeriodDays = forecastPeriodDays
        _uiState.update { it.copy(budget = budget, isLoading = true, error = null) }

        forecastJob = viewModelScope.launch {
            try {
                val forecast = forecastingEngine.generateForecast(budget, forecastPeriodDays)

                // S8-014: Discard stale result
                if (requestId != forecastRequestId) return@launch

                // S8-003: Use engine-provided spentToDate — correct for all currencies
                val currentSpending = forecast.spentToDate.coerceAtLeast(0.0)

                val recommendations = recommendationEngine.generateRecommendations(
                    budget = BudgetRecommendationBudget(amount = budget.amount),
                    forecast = BudgetRecommendationForecast(
                        predictedSpending = forecast.predictedSpending,
                        predictedRemaining = forecast.predictedRemaining,
                        confidenceScore = forecast.confidenceScore,
                        riskLevel = when (forecast.riskLevel) {
                            ForecastRiskLevel.LOW -> BudgetRecommendationRiskLevel.LOW
                            ForecastRiskLevel.MEDIUM -> BudgetRecommendationRiskLevel.MEDIUM
                            ForecastRiskLevel.HIGH -> BudgetRecommendationRiskLevel.HIGH
                            ForecastRiskLevel.CRITICAL -> BudgetRecommendationRiskLevel.CRITICAL
                        },
                        overspendProbability = forecast.overspendProbability
                    ),
                    currentSpending
                )

                _uiState.update {
                    it.copy(
                        budget = budget,
                        forecast = forecast,
                        recommendations = recommendations,
                        isLoading = false,
                        error = null
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestId != forecastRequestId) return@launch
                _uiState.update { it.copy(isLoading = false, error = "Failed to generate forecast: ${e.message}") }
            }
        }
    }

    fun refreshForecast() {
        _uiState.value.budget?.let { generateForecast(it, lastForecastPeriodDays) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
