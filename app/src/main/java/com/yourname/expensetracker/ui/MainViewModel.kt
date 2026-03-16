package com.yourname.expensetracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface MainNavigationRequest {
    data class Tab(val index: Int) : MainNavigationRequest
    data class Transactions(val filter: TransactionFilter) : MainNavigationRequest
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val reviewQueueRepository: ReviewQueueRepository
) : ViewModel() {

    private val _navigationRequest = kotlinx.coroutines.channels.Channel<MainNavigationRequest>(
        kotlinx.coroutines.channels.Channel.BUFFERED
    )
    val navigationRequest = _navigationRequest.receiveAsFlow()

    val pendingReviewCount: StateFlow<Int> = reviewQueueRepository
        .getPendingReviewCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun navigateToTab(tabIndex: Int) {
        viewModelScope.launch {
            _navigationRequest.send(MainNavigationRequest.Tab(tabIndex))
        }
    }

    fun navigateToTransactions(filter: TransactionFilter) {
        viewModelScope.launch {
            _navigationRequest.send(MainNavigationRequest.Transactions(filter))
        }
    }
}

