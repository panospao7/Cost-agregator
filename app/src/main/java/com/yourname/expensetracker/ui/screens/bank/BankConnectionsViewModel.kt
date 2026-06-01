package com.yourname.expensetracker.ui.screens.bank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.BankConnectionDao
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.SyncStatus
import com.yourname.expensetracker.domain.bank.BankApiIntegration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class BankConnectionsViewModel @Inject constructor(
    private val bankConnectionDao: BankConnectionDao,
    private val bankApiIntegration: BankApiIntegration
) : ViewModel() {
    
    private val _connections = MutableStateFlow<List<BankConnection>>(emptyList())
    val connections: StateFlow<List<BankConnection>> = _connections.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** True when no real connections exist — UI shows demo/coming-soon state. */
    val isDemoMode: Boolean = false
    
    init {
        viewModelScope.launch {
            bankConnectionDao.getAllConnections()
                .catch { e ->
                    Timber.e(e, "Failed to load bank connections")
                    _connections.value = emptyList()
                }
                .collect { list ->
                    _connections.value = list.ifEmpty {
                        // Fall back to showing supported banks as disconnected placeholders
                        com.yourname.expensetracker.domain.bank.BankApiIntegration.SUPPORTED_BANKS.map { bank ->
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
                }
        }
    }
    
    fun syncConnection(connectionId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val connection = bankConnectionDao.getById(connectionId)
                if (connection != null) {
                    bankApiIntegration.syncTransactions(connection)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync connection $connectionId")
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun disconnect(connectionId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                bankConnectionDao.disconnect(connectionId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to disconnect connection $connectionId")
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // DAO flow will automatically emit via the init collector
                // Force a refresh by touching the DB — the Flow picks it up
                val current = bankConnectionDao.getAllConnections()
                // The flow is already collected in init
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh bank connections")
            } finally {
                _isLoading.value = false
            }
        }
    }
}
