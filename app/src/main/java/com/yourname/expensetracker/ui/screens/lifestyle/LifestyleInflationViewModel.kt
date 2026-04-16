package com.yourname.expensetracker.ui.screens.lifestyle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.lifestyle.LifestyleInflationDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LifestyleInflationViewModel @Inject constructor(
    private val lifestyleDetector: LifestyleInflationDetector
) : ViewModel() {

    private var analysisJob: Job? = null
    private var latestAnalysisRequestId: Long = 0
    
    private val _report = MutableStateFlow<LifestyleInflationDetector.LifestyleInflationReport?>(null)
    val report: StateFlow<LifestyleInflationDetector.LifestyleInflationReport?> = _report.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    fun analyze(months: Int = 12) {
        val requestId = ++latestAnalysisRequestId
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = lifestyleDetector.analyzeLifestyleInflation(months)
                if (requestId == latestAnalysisRequestId) {
                    _report.value = result
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                if (requestId == latestAnalysisRequestId) {
                    _report.value = null
                }
            } finally {
                if (requestId == latestAnalysisRequestId) {
                    _isLoading.value = false
                }
            }
        }
    }
}
