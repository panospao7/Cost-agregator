package com.yourname.expensetracker.ui.screens.bank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BankConnectionsViewModel @Inject constructor() : ViewModel() {
    
    private val _connections = MutableStateFlow<List<BankConnection>>(emptyList())
    val connections: StateFlow<List<BankConnection>> = _connections.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    init {
        loadConnections()
    }
    
    private fun loadConnections() {
        viewModelScope.launch {
            _isLoading.value = true
            // Would load from repository
            _connections.value = emptyList()
            _isLoading.value = false
        }
    }
    
    fun syncConnection(connectionId: Long) {
        viewModelScope.launch {
            // Would trigger sync via BankApiIntegration
        }
    }
    
    fun disconnect(connectionId: Long) {
        viewModelScope.launch {
            // Would disconnect via repository
        }
    }
    
    fun refresh() {
        loadConnections()
    }
}
