package com.yourname.expensetracker.ui.screens.carbon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.carbon.CarbonFootprintCalculator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private const val LOAD_ERROR_MESSAGE = "Failed to load data"

@HiltViewModel
class CarbonFootprintViewModel @Inject constructor(
    private val calculator: CarbonFootprintCalculator,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private var loadReportJob: Job? = null
    private var latestLoadRequestId: Long = 0
    
    private val _report = MutableStateFlow<CarbonFootprintCalculator.CarbonFootprintReport?>(null)
    val report: StateFlow<CarbonFootprintCalculator.CarbonFootprintReport?> = _report.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    fun loadReport(days: Int = 30) {
        val requestId = ++latestLoadRequestId
        loadReportJob?.cancel()
        loadReportJob = viewModelScope.launch {
            if (requestId == latestLoadRequestId) {
                _isLoading.value = true
                _error.value = null
            }
            try {
                val endDate = timeProvider.now()
                val startDate = endDate - (days * TimePeriodUtils.DAY_IN_MILLIS)
                
                val result = calculator.calculateCarbonFootprint(startDate, endDate)
                if (requestId == latestLoadRequestId) {
                    _report.value = result
                    _error.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to load carbon footprint report")
                if (requestId == latestLoadRequestId) {
                    _error.value = LOAD_ERROR_MESSAGE
                }
            } finally {
                if (requestId == latestLoadRequestId) {
                    _isLoading.value = false
                }
            }
        }
    }
}
