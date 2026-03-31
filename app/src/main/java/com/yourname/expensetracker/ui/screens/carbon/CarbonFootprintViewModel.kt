package com.yourname.expensetracker.ui.screens.carbon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.carbon.CarbonFootprintCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CarbonFootprintViewModel @Inject constructor(
    private val calculator: CarbonFootprintCalculator
) : ViewModel() {
    
    private val _report = MutableStateFlow<CarbonFootprintCalculator.CarbonFootprintReport?>(null)
    val report: StateFlow<CarbonFootprintCalculator.CarbonFootprintReport?> = _report.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    fun loadReport(days: Int = 30) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val endDate = System.currentTimeMillis()
                val startDate = endDate - (days * 24L * 60 * 60 * 1000)
                
                val result = calculator.calculateCarbonFootprint(startDate, endDate)
                _report.value = result
            } catch (e: Exception) {
                e.printStackTrace()
                _report.value = null
            } finally {
                _isLoading.value = false
            }
        }
    }
}
