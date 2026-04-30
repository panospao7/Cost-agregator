package com.yourname.expensetracker.ui.screens.lifestyle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.lifestyle.LifestyleInflationDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private const val LOAD_ERROR_MESSAGE = "Failed to load data"

@HiltViewModel
class LifestyleInflationViewModel @Inject constructor(
    private val lifestyleDetector: LifestyleInflationDetector,
    currencySettingsRepository: CurrencySettingsRepository
) : ViewModel() {

    val homeCurrency: Flow<String> = currencySettingsRepository.homeCurrency()

    private var analysisJob: Job? = null
    private var latestAnalysisRequestId: Long = 0
    
    private val _report = MutableStateFlow<LifestyleInflationDetector.LifestyleInflationReport?>(null)
    val report: StateFlow<LifestyleInflationDetector.LifestyleInflationReport?> = _report.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    fun analyze(months: Int = 12) {
        val requestId = ++latestAnalysisRequestId
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            if (requestId == latestAnalysisRequestId) {
                _isLoading.value = true
                _error.value = null
            }
            try {
                val result = lifestyleDetector.analyzeLifestyleInflation(months)
                if (requestId == latestAnalysisRequestId) {
                    _report.value = result
                    _error.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to analyze lifestyle inflation")
                if (requestId == latestAnalysisRequestId) {
                    _error.value = LOAD_ERROR_MESSAGE
                }
            } finally {
                if (requestId == latestAnalysisRequestId) {
                    _isLoading.value = false
                }
            }
        }
    }
}
