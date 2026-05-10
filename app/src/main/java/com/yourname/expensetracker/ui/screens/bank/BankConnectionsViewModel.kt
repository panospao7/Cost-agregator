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
class BankConnectionsViewModel @Inject constructor(
    // TODO: Inject real BankRepository when available
) : ViewModel() {
    
    private val _connections = MutableStateFlow<List<BankConnection>>(emptyList())
    val connections: StateFlow<List<BankConnection>> = _connections.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    init {
        // P0-02: Load connections from stub list until real repository injection is wired
        _connections.value = com.yourname.expensetracker.domain.bank.BankApiIntegration.SUPPORTED_BANKS.map { bank ->
            BankConnection(
                bankId = bank.id,
                bankName = bank.name,
                countryCode = bank.countryCode,
                isConnected = false,
                isActive = false,
                accessToken = null,
                refreshToken = null,
                tokenEncryptionVersion = 0,
                tokenExpiry = null,
                createdAt = 0L
            )
        }
    }
    
    private fun loadConnections() {
        viewModelScope.launch {
            _isLoading.value = true
            // TODO: Load from real repository once injected
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
