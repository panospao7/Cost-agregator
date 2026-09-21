package com.yourname.expensetracker.ui.screens.bank

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.bank.BankConnectionLifecycleCoordinator
import com.yourname.expensetracker.domain.bank.BankConnectionSummary
import com.yourname.expensetracker.domain.bank.BankSyncOutcome
import com.yourname.expensetracker.domain.bank.ConnectionDisconnectResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * RP-17 17-B: user-visible sync result message. The ViewModel maps typed
 * [BankSyncOutcome]s one-to-one onto these; the Screen maps them to resources.
 * ReauthRequired is its own type so the UI can raise the re-auth flow.
 */
sealed interface BankSyncUserMessage {
    data class SyncResult(@StringRes val messageRes: Int) : BankSyncUserMessage
    data object ReauthRequired : BankSyncUserMessage
}

@HiltViewModel
class BankConnectionsViewModel @Inject constructor(
    private val coordinator: BankConnectionLifecycleCoordinator
) : ViewModel() {

    private val _connections = MutableStateFlow<List<BankConnectionSummary>>(emptyList())
    val connections: StateFlow<List<BankConnectionSummary>> = _connections.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * RP-17 17-B (register D12): in-flight sync state lives ONLY here in the
     * ViewModel — nothing durable is persisted for "running" syncs. The DB keeps
     * terminal statuses exclusively.
     */
    private val _syncingConnectionIds = MutableStateFlow<Set<Long>>(emptySet())
    val syncingConnectionIds: StateFlow<Set<Long>> = _syncingConnectionIds.asStateFlow()

    private val _userMessage = MutableStateFlow<BankSyncUserMessage?>(null)
    val userMessage: StateFlow<BankSyncUserMessage?> = _userMessage.asStateFlow()

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
            _syncingConnectionIds.value = _syncingConnectionIds.value + connectionId
            try {
                val outcome = coordinator.syncConnection(connectionId)
                _userMessage.value = outcome.toUserMessage()
            } catch (_: CancellationException) {
                // Cancellation propagates; it is never a sync outcome.
                throw CancellationException("Sync cancelled")
            } finally {
                _syncingConnectionIds.value = _syncingConnectionIds.value - connectionId
            }
        }
    }

    /** One-to-one outcome → message mapping (17-B integration→coordinator→VM). */
    private fun BankSyncOutcome.toUserMessage(): BankSyncUserMessage = when (this) {
        is BankSyncOutcome.Success -> BankSyncUserMessage.SyncResult(R.string.bank_sync_result_success)
        is BankSyncOutcome.Partial -> BankSyncUserMessage.SyncResult(R.string.bank_sync_result_partial)
        is BankSyncOutcome.ReauthRequired -> BankSyncUserMessage.ReauthRequired
        is BankSyncOutcome.Blocked -> BankSyncUserMessage.SyncResult(R.string.bank_sync_result_blocked)
        is BankSyncOutcome.RetryableFailure -> BankSyncUserMessage.SyncResult(R.string.bank_sync_result_retryable)
        is BankSyncOutcome.PermanentFailure -> BankSyncUserMessage.SyncResult(R.string.bank_sync_result_permanent)
        BankSyncOutcome.NotFound -> BankSyncUserMessage.SyncResult(R.string.bank_sync_result_connection_missing)
        is BankSyncOutcome.FeatureUnavailable -> BankSyncUserMessage.SyncResult(R.string.bank_feature_unavailable)
    }

    fun consumeUserMessage() {
        _userMessage.value = null
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
