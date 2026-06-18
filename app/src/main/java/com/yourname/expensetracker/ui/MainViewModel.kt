package com.yourname.expensetracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.backup.AppOperationalState
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.entity.Budget as BudgetEntity
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Navigation requests from MainViewModel to MainActivity.
 * All navigation now uses NavigationDestination sealed class for type-safe routing.
 */
sealed interface MainNavigationRequest {
    /**
     * Navigate to a specific tab by index (0-5).
     */
    data class Tab(val index: Int) : MainNavigationRequest
    
    /**
     * Navigate to Transactions tab with an optional filter.
     */
    data class Transactions(val filter: TransactionFilter?) : MainNavigationRequest
    
    /**
     * Navigate to a specific destination using the NavigationDestination sealed class.
     */
    data class Destination(val destination: NavigationDestination) : MainNavigationRequest
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val reviewQueueRepository: ReviewQueueRepository,
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) : ViewModel() {

    private val _navigationRequest = kotlinx.coroutines.channels.Channel<MainNavigationRequest>(
        kotlinx.coroutines.channels.Channel.BUFFERED
    )
    val navigationRequest = _navigationRequest.receiveAsFlow()

    val pendingReviewCount: StateFlow<Int> = reviewQueueRepository
        .getPendingReviewCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val operationalState: StateFlow<AppOperationalState> =
        restoreMaintenanceMode.operationalStateFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, restoreMaintenanceMode.operationalStateFlow.value)

    /**
     * Navigate to a specific tab by index (0-5).
     */
    fun navigateToTab(tabIndex: Int) {
        viewModelScope.launch {
            _navigationRequest.send(MainNavigationRequest.Tab(tabIndex))
        }
    }

    /**
     * Navigate to Transactions tab with an optional filter.
     */
    fun navigateToTransactions(filter: TransactionFilter? = null) {
        viewModelScope.launch {
            _navigationRequest.send(MainNavigationRequest.Transactions(filter))
        }
    }

    /**
     * Navigate to a specific destination using the NavigationDestination sealed class.
     */
    fun navigateTo(destination: NavigationDestination) {
        viewModelScope.launch {
            _navigationRequest.send(MainNavigationRequest.Destination(destination))
        }
    }

    /**
     * Trigger Add Expense screen via NavigationDestination.
     * Replaces the old boolean flag approach.
     */
    fun triggerAddExpense() {
        viewModelScope.launch {
            _navigationRequest.send(MainNavigationRequest.Destination(NavigationDestination.AddExpense))
        }
    }

    /**
     * Trigger Scan Receipt screen via NavigationDestination.
     */
    fun triggerScanReceipt() {
        viewModelScope.launch {
            _navigationRequest.send(MainNavigationRequest.Destination(NavigationDestination.ScanReceipt))
        }
    }

    /**
     * Trigger Budget Forecasting screen for a specific budget.
     */
    fun triggerBudgetForecasting(budget: BudgetEntity) {
        viewModelScope.launch {
            _navigationRequest.send(MainNavigationRequest.Destination(NavigationDestination.BudgetForecasting(budget)))
        }
    }
}


