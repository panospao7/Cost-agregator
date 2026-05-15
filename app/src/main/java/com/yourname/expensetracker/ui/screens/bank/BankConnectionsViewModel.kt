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

    /** S12-016: true while no real repository is wired — UI shows demo/coming-soon state */
    val isDemoMode: Boolean = true
    
    init {
        // S12-017: Use bankId as stable unique key (not entity id=0)
        // S12-016: Connections are not actually connected — isDemoMode=true
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
    
    fun syncConnection(connectionId: Long) {
        // S12-016: No-op in demo mode — real sync requires BankRepository
        if (isDemoMode) return
        viewModelScope.launch { /* TODO: real sync */ }
    }
    
    fun disconnect(connectionId: Long) {
        // S12-016: No-op in demo mode — real disconnect requires BankRepository + token wipe
        if (isDemoMode) return
        viewModelScope.launch { /* TODO: real disconnect */ }
    }
    
    fun refresh() {
        // S12-016: No-op in demo mode
    }
}
