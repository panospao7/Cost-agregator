package com.yourname.expensetracker.ui.screens.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.repository.AccountingExportRepository
import com.yourname.expensetracker.data.repository.ExportFormat
import com.yourname.expensetracker.data.repository.ExportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountingExportState(
    val isLoading: Boolean = false,
    val selectedFormat: ExportFormat = ExportFormat.XERO_CSV,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val includeReceipts: Boolean = false,
    val exportResult: ExportResult? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class AccountingExportViewModel @Inject constructor(
    private val exportRepository: AccountingExportRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AccountingExportState())
    val state: StateFlow<AccountingExportState> = _state.asStateFlow()

    fun selectFormat(format: ExportFormat) {
        _state.update { it.copy(selectedFormat = format) }
    }

    fun setDateRange(startDate: Long, endDate: Long) {
        _state.update { it.copy(startDate = startDate, endDate = endDate) }
    }

    fun toggleIncludeReceipts() {
        _state.update { it.copy(includeReceipts = !it.includeReceipts) }
    }

    fun exportExpenses(context: android.content.Context) {
        viewModelScope.launch {
            val currentState = _state.value
            
            if (currentState.startDate == null || currentState.endDate == null) {
                _state.update { it.copy(errorMessage = "Please select a date range") }
                return@launch
            }

            _state.update { it.copy(isLoading = true, errorMessage = null) }

            val result = exportRepository.exportExpenses(
                context = context,
                startDate = currentState.startDate,
                endDate = currentState.endDate,
                format = currentState.selectedFormat,
                includeReceipts = currentState.includeReceipts
            )

            _state.update { 
                it.copy(
                    isLoading = false,
                    exportResult = result,
                    errorMessage = if (!result.success) result.errorMessage else null
                )
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun reset() {
        _state.update { AccountingExportState() }
    }
}
