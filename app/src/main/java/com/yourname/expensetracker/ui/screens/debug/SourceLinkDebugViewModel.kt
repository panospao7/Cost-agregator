package com.yourname.expensetracker.ui.screens.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.EntitySourceLink
import com.yourname.expensetracker.domain.provenance.SourceLinkQueryService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PR9: ViewModel for the SourceLinkDebugScreen.
 */
@HiltViewModel
class SourceLinkDebugViewModel @Inject constructor(
    private val queryService: SourceLinkQueryService
) : ViewModel() {

    private val _sourceLinks = MutableStateFlow<List<EntitySourceLink>?>(null)
    val sourceLinks: StateFlow<List<EntitySourceLink>?> = _sourceLinks

    private val _querySummary = MutableStateFlow<String?>(null)
    val querySummary: StateFlow<String?> = _querySummary

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    fun queryByExpenseId(expenseId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _sourceLinks.value = null
            _querySummary.value = null
            try {
                _sourceLinks.value = queryService.getLinksForExpense(expenseId)
                _querySummary.value = queryService.getExpenseSourceSummary(expenseId)
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
