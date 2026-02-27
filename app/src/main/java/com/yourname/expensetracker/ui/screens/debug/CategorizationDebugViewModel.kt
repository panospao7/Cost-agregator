package com.yourname.expensetracker.ui.screens.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.categorization.CategorizationDebugTrace
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategorizationDebugViewModel @Inject constructor(
    private val categorizationEngine: CategorizationEngine
) : ViewModel() {

    private val _debugTrace = MutableStateFlow<CategorizationDebugTrace?>(null)
    val debugTrace: StateFlow<CategorizationDebugTrace?> = _debugTrace

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    fun testCategorization(merchant: String, amount: Double, timestamp: Long) {
        if (merchant.isBlank()) return
        
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                _debugTrace.value = categorizationEngine.debugCategorize(merchant, amount, timestamp)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun clearTrace() {
        _debugTrace.value = null
    }
}
