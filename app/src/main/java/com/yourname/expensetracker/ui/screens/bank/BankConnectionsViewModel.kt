package com.yourname.expensetracker.ui.screens.bank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.bank.BankConnectionLifecycleCoordinator
import com.yourname.expensetracker.domain.bank.BankConnectionSummary
import com.yourname.expensetracker.domain.bank.ConnectionDisconnectResult
import com.yourname.expensetracker.domain.bank.ConnectionSyncResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BankConnectionsViewModel @Inject constructor(
    private val coordinator: BankConnectionLifecycleCoordinator
) : ViewModel() {

    private val _connections = MutableStateFlow<List<BankConnectionSummary>>(emptyList())
    val connections: StateFlow<List<BankConnectionSummary>> = _connections.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            coordinator.observeConnections()
                .catch {
                    _connections.value = emptyList()
                }
                .collect { list ->
                    _connections.value = list
                }
        }
    }

    fun syncConnection(connectionId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                when (val result = coordinator.syncConnection(connectionId)) {
                    is ConnectionSyncResult.Success -> {}
                    is ConnectionSyncResult.NotFound -> {}
                    is ConnectionSyncResult.RetryableFailure -> {}
                }
            } catch (_: CancellationException) {
                throw CancellationException("Sync cancelled")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun disconnect(connectionId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                when (val result = coordinator.disconnectConnection(connectionId)) {
                    is ConnectionDisconnectResult.Success -> {}
                    is ConnectionDisconnectResult.NotFound -> {}
                    is ConnectionDisconnectResult.RetryableFailure -> {}
                }
            } catch (_: CancellationException) {
                throw CancellationException("Disconnect cancelled")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Re-collect triggers a fresh emission from the coordinator
                coordinator.observeConnections().collect { list ->
                    _connections.value = list
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
}
