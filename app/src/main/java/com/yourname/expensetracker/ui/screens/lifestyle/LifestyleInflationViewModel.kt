package com.yourname.expensetracker.ui.screens.lifestyle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.lifestyle.LifestyleInflationDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LifestyleInflationViewModel @Inject constructor(
    private val lifestyleDetector: LifestyleInflationDetector
) : ViewModel() {
    
    private val _report = MutableStateFlow<LifestyleInflationDetector.LifestyleInflationReport?>(null)
    val report: StateFlow<LifestyleInflationDetector.LifestyleInflationReport?> = _report.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    fun analyze(months: Int = 12) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = lifestyleDetector.analyzeLifestyleInflation(months)
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
