package com.yourname.expensetracker.ui.screens.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.export.FreshBooksExporter
import com.yourname.expensetracker.domain.export.QuickBooksIIFExporter
import com.yourname.expensetracker.domain.export.XeroCSVExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

/**
 * UI state for export options screen.
 */
data class ExportOptionsUiState(
    val exportFormats: List<ExportFormat> = listOf(
        ExportFormat("csv", "CSV (Generic)", "Standard CSV format compatible with most applications"),
        ExportFormat("xero", "Xero CSV", "Xero accounting software format"),
        ExportFormat("quickbooks", "QuickBooks IIF", "QuickBooks IIF import format"),
        ExportFormat("freshbooks", "FreshBooks", "FreshBooks CSV format")
    ),
    val selectedFormat: String = "csv",
    val startDate: Long = getDefaultStartDate(),
    val endDate: Long = System.currentTimeMillis(),
    val expenseCount: Int = 0,
    val isLoading: Boolean = false,
    val exportData: String? = null,
    val error: String? = null,
    val exportSuccess: Boolean = false
)

data class ExportFormat(
    val id: String,
    val name: String,
    val description: String
)

private fun getDefaultStartDate(): Long {
    val cal = Calendar.getInstance()
    cal.add(Calendar.MONTH, -1)
    return cal.timeInMillis
}

@HiltViewModel
class ExportOptionsViewModel @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ExportOptionsUiState())
    val uiState: StateFlow<ExportOptionsUiState> = _uiState.asStateFlow()
    
    init {
        loadExpenseCount()
    }
    
    private fun loadExpenseCount() {
        viewModelScope.launch {
            try {
                val expenses = expenseDao.getExpensesBetween(
                    _uiState.value.startDate,
                    _uiState.value.endDate
                )
                _uiState.value = _uiState.value.copy(expenseCount = expenses.size)
            } catch (e: Exception) {
                // Silently fail, not critical
            }
        }
    }
    
    /**
     * Select export format.
     */
    fun selectFormat(formatId: String) {
        _uiState.value = _uiState.value.copy(selectedFormat = formatId)
    }
    
    /**
     * Set date range.
     */
    fun setDateRange(startDate: Long, endDate: Long) {
        _uiState.value = _uiState.value.copy(
            startDate = startDate,
            endDate = endDate
        )
        loadExpenseCount()
    }
    
    /**
     * Generate export data.
     */
    fun generateExport() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                exportData = null,
                exportSuccess = false
            )
            
            try {
                // Get expenses in date range
                val expenses = expenseDao.getExpensesBetween(
                    _uiState.value.startDate,
                    _uiState.value.endDate
                )
                
                // Get categories
                val categories = categoryDao.getAll().associate { it.id to it.name }
                
                // Generate export based on selected format
                val exportData = when (_uiState.value.selectedFormat) {
                    "xero" -> XeroCSVExporter().export(expenses, categories)
                    "quickbooks" -> QuickBooksIIFExporter().export(expenses, categories)
                    "freshbooks" -> FreshBooksExporter().export(expenses, categories)
                    else -> generateGenericCSV(expenses, categories)
                }
                
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    exportData = exportData,
                    exportSuccess = true,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to generate export: ${e.message}"
                )
            }
        }
    }
    
    private fun generateGenericCSV(
        expenses: List<Expense>,
        categories: Map<Long, String>
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        
        return buildString {
            // Header
            append("Date,Merchant,Amount,Category,Notes,ID\n")
            
            // Data rows
            expenses.forEach { expense ->
                val date = dateFormat.format(java.util.Date(expense.date))
                val merchant = escapeCsv(expense.merchant)
                val amount = expense.amount
                val category = escapeCsv(categories[expense.categoryId] ?: "Uncategorized")
                val notes = escapeCsv(expense.notes ?: "")
                val id = expense.id
                
                append("$date,$merchant,$amount,$category,$notes,$id\n")
            }
        }
    }
    
    private fun escapeCsv(field: String): String {
        val needsQuoting = field.contains(",") || 
                          field.contains("\"") || 
                          field.contains("\n") ||
                          field.contains("\r")
        
        return if (needsQuoting) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
    }
    
    /**
     * Clear export data.
     */
    fun clearExport() {
        _uiState.value = _uiState.value.copy(
            exportData = null,
            exportSuccess = false
        )
    }
    
    /**
     * Clear error.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}