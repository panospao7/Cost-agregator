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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val error: String? = null
)

@HiltViewModel
class BudgetForecastingViewModel @Inject constructor(
    private val forecastingEngine: BudgetForecastingEngine,
    private val recommendationEngine: BudgetRecommendationEngine
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(BudgetForecastUiState())
    val uiState: StateFlow<BudgetForecastUiState> = _uiState.asStateFlow()
    
    /**
     * Generate a forecast for a specific budget.
     */
    fun generateForecast(budget: Budget, forecastPeriodDays: Int = 30) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            try {
                // Generate forecast
                val forecast = forecastingEngine.generateForecast(budget, forecastPeriodDays)
                
                // Get current spending for recommendations
                val currentSpending = budget.amount - forecast.predictedRemaining
                
                // Generate recommendations
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
                
                _uiState.value = BudgetForecastUiState(
                    budget = budget,
                    forecast = forecast,
                    recommendations = recommendations,
                    isLoading = false,
                    error = null
                )
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to generate forecast: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Refresh forecast with current data.
     */
    fun refreshForecast() {
        _uiState.value.budget?.let { budget ->
            generateForecast(budget)
        }
    }
    
    /**
     * Clear any error state.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
